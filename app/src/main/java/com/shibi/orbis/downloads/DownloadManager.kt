package com.shibi.orbis.downloads

import com.shibi.orbis.engine.IncomingDownload
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the download list and the copy jobs. Android-free: [extensionForMime] is injected by the caller
 * (MimeTypeMap in the app, a fake in tests).
 *
 * Every [IncomingDownload] ends in exactly one of openBody() or discard(): the first of the copy job, [cancel]
 * and the job-completion handler to win [Active.claimed] decides. Logs carry status only: never names, hosts or URLs.
 */
class DownloadManager(
    private val dir: () -> File,
    private val store: DownloadStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val log: (String) -> Unit,
    private val now: () -> Long,
    private val extensionForMime: (String) -> String? = { null },
) {
    private val _entries = MutableStateFlow<List<DownloadEntry>>(emptyList())

    /** Newest first. */
    val entries: StateFlow<List<DownloadEntry>> = _entries.asStateFlow()

    private val nextId = AtomicLong(now())
    private val active = ConcurrentHashMap<Long, Active>()
    private val reserveLock = Any()
    private val reservedKeys = HashSet<String>()
    private val saveLock = Any()

    private class Active(val id: Long, val download: IncomingDownload, val baseName: String, val total: Long) {
        /** Set by whoever takes responsibility for the body (copy job) or for discarding it (cancel/scope death). */
        val claimed = AtomicBoolean(false)

        @Volatile var cancelled = false

        @Volatile var stream: InputStream? = null

        @Volatile var job: Job? = null
    }

    private class Reservation(val name: String, val finalFile: File, val partFile: File, val keys: List<String>)

    /** Anything still IN_PROGRESS from a previous process is dead: mark FAILED and delete its part file. */
    fun restore() {
        scope.launch(io) {
            val loaded = try { store.load() } catch (e: Exception) { emptyList() }
            val fixed =
                loaded.map { e ->
                    if (e.status == DownloadStatus.IN_PROGRESS) {
                        e.filePath?.let { deletePartFor(it) }
                        e.copy(status = DownloadStatus.FAILED, filePath = null)
                    } else {
                        e
                    }
                }
            fixed.maxOfOrNull { it.id }?.let { max -> nextId.updateAndGet { maxOf(it, max) } }
            // Merge rather than replace: a download may already have started while the index was loading.
            _entries.update { current -> sorted(current + fixed.filter { f -> current.none { it.id == f.id } }) }
            saveNow()
            log("downloads restored")
        }
    }

    /**
     * Starts [download]. With [recordSource] false (a private tab's download) the entry carries no source host, so
     * the index file names no site from a private session. The file and its listing are otherwise the same.
     */
    fun start(download: IncomingDownload, recordSource: Boolean = true): Long {
        val id = nextId.incrementAndGet()
        val name =
            try {
                DownloadFileNames.resolve(download.contentDisposition, download.url, download.contentType, extensionForMime)
            } catch (e: Exception) {
                DownloadFileNames.FALLBACK_NAME
            }
        val total = download.contentLength.coerceAtLeast(-1L)
        val entry =
            DownloadEntry(
                id = id,
                fileName = name,
                mimeType = DownloadFileNames.cleanMime(download.contentType),
                sourceHost = if (recordSource) DownloadFileNames.sourceHost(download.url) else null,
                totalBytes = total,
                receivedBytes = 0L,
                status = DownloadStatus.IN_PROGRESS,
                filePath = null,
                startedAt = now(),
            )
        val a = Active(id, download, name, total)
        active[id] = a
        _entries.update { sorted(listOf(entry) + it) }
        scope.launch(io) { saveNow() }
        val job = scope.launch(io) { execute(a) }
        a.job = job
        // The job can die before its body ever runs (scope cancelled): the body must still be discarded.
        job.invokeOnCompletion { cause ->
            if (cause != null && a.claimed.compareAndSet(false, true)) {
                runCatching { a.download.discard() }
                finish(a, DownloadStatus.CANCELLED, null, 0L)
            }
        }
        log("download started")
        return id
    }

    fun cancel(id: Long) {
        val a = active[id] ?: return
        a.cancelled = true
        if (a.claimed.compareAndSet(false, true)) {
            // The copy job never took the body.
            a.job?.cancel()
            runCatching { a.download.discard() }
            finish(a, DownloadStatus.CANCELLED, null, 0L)
        } else {
            a.job?.cancel()
            // A read blocked on the network ignores job cancellation; closing the stream is what unblocks it.
            // Close off-thread: the caller is usually the UI thread and close() may itself block.
            a.stream?.let { s ->
                Thread { closeQuietly(s) }.apply { isDaemon = true }.start()
            }
        }
    }

    /** Removes the entry; a completed file is deleted too, a running download is cancelled first. */
    fun delete(id: Long) {
        cancel(id)
        val entry = _entries.value.firstOrNull { it.id == id } ?: return
        _entries.update { list -> list.filterNot { it.id == id } }
        scope.launch(io) {
            if (entry.status == DownloadStatus.COMPLETED) entry.filePath?.let { deleteOwned(File(it)) }
            saveNow()
        }
    }

    // ---- copy job ----

    private suspend fun execute(a: Active) {
        if (!a.claimed.compareAndSet(false, true)) return // cancel() already settled this one
        var reservation: Reservation? = null
        var stream: InputStream? = null
        var bodyOpened = false
        var status = DownloadStatus.FAILED
        var received = 0L
        try {
            val d = dir()
            if (!d.isDirectory && !d.mkdirs() && !d.isDirectory) throw IOException("downloads directory unavailable")
            if (a.total > 0) {
                val usable = d.usableSpace
                if (usable in 1 until a.total) throw IOException("insufficient space")
            }
            val r = reserve(d, a.baseName)
            reservation = r
            publishTarget(a.id, r)
            checkActive(a)

            bodyOpened = true
            val input = a.download.openBody() ?: throw IOException("no body")
            stream = input
            a.stream = input
            if (!DownloadFileNames.isInside(d, r.partFile)) throw IOException("path escapes downloads directory")
            FileOutputStream(r.partFile).use { out ->
                val buf = ByteArray(BUFFER_SIZE)
                var lastEmit = now()
                while (true) {
                    checkActive(a)
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    out.write(buf, 0, n)
                    received += n
                    val t = now()
                    if (t - lastEmit >= PROGRESS_INTERVAL_MS) {
                        lastEmit = t
                        setProgress(a.id, received)
                    }
                }
                out.flush()
                out.fd.sync()
            }
            // A body shorter (or longer) than the announced length is a truncated or tampered download.
            if (a.total >= 0 && received != a.total) throw IOException("length mismatch")
            moveIntoPlace(r.partFile, r.finalFile)
            status = DownloadStatus.COMPLETED
        } catch (e: CancellationException) {
            status = DownloadStatus.CANCELLED
            throw e
        } catch (e: Exception) {
            // Closing the stream to cancel surfaces as an IOException here: that is a cancel, not a failure.
            status = if (a.cancelled || !currentCoroutineContext().isActive) DownloadStatus.CANCELLED else DownloadStatus.FAILED
            if (status == DownloadStatus.FAILED) log("download failed: ${e.javaClass.simpleName}")
        } finally {
            closeQuietly(stream)
            a.stream = null
            if (!bodyOpened) runCatching { a.download.discard() }
            if (status != DownloadStatus.COMPLETED) reservation?.partFile?.delete()
            reservation?.let { release(it) }
            finish(a, status, reservation, received)
        }
    }

    private suspend fun checkActive(a: Active) {
        if (a.cancelled) throw CancellationException("cancelled")
        currentCoroutineContext().ensureActive()
    }

    private fun finish(a: Active, status: DownloadStatus, r: Reservation?, received: Long) {
        active.remove(a.id)
        val stillListed = _entries.value.any { it.id == a.id }
        _entries.update { list ->
            list.map { e ->
                if (e.id != a.id) {
                    e
                } else {
                    e.copy(
                        status = status,
                        fileName = if (status == DownloadStatus.COMPLETED) r?.name ?: e.fileName else e.fileName,
                        filePath = if (status == DownloadStatus.COMPLETED) r?.finalFile?.absolutePath else null,
                        receivedBytes = if (status == DownloadStatus.COMPLETED) received else e.receivedBytes,
                        totalBytes = if (status == DownloadStatus.COMPLETED && e.totalBytes < 0) received else e.totalBytes,
                    )
                }
            }
        }
        // Deleted while it was finishing: do not leave an orphan behind.
        if (!stillListed && status == DownloadStatus.COMPLETED) r?.finalFile?.delete()
        saveNow()
        log("download ${status.name.lowercase()}")
    }

    private fun setProgress(id: Long, received: Long) {
        _entries.update { list -> list.map { if (it.id == id) it.copy(receivedBytes = received) else it } }
    }

    private fun publishTarget(id: Long, r: Reservation) {
        _entries.update { list ->
            list.map { if (it.id == id) it.copy(fileName = r.name, filePath = r.finalFile.absolutePath) else it }
        }
        // Persisted so a crash can still find and delete the part file on the next start.
        saveNow()
    }

    // ---- files ----

    /**
     * Picks a free name and creates its `.part` file under one lock, so two starts with the same name can never
     * share a file. Both the final name and the part name are reserved in memory until the download settles,
     * which also keeps "x" and a download literally named "x.part" from colliding.
     */
    private fun reserve(d: File, base: String): Reservation =
        synchronized(reserveLock) {
            for (n in 0 until MAX_NAME_ATTEMPTS) {
                val name = if (n == 0) base else DownloadFileNames.withCounter(base, n)
                val finalFile = File(d, name)
                val partFile = File(d, "$name.part")
                val keys = listOf(name.lowercase(), "$name.part".lowercase())
                if (keys.any { it in reservedKeys } || finalFile.exists() || partFile.exists()) continue
                if (!DownloadFileNames.isInside(d, finalFile) || !DownloadFileNames.isInside(d, partFile)) {
                    throw IOException("path escapes downloads directory")
                }
                if (partFile.createNewFile()) {
                    reservedKeys += keys
                    return@synchronized Reservation(name, finalFile, partFile, keys)
                }
            }
            throw IOException("no free file name")
        }

    private fun release(r: Reservation) {
        synchronized(reserveLock) { reservedKeys -= r.keys.toSet() }
    }

    private fun moveIntoPlace(part: File, target: File) {
        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), target.toPath())
        }
    }

    private fun deletePartFor(finalPath: String) {
        try {
            val part = File("$finalPath.part")
            if (DownloadFileNames.isInside(dir(), part)) part.delete()
        } catch (e: Exception) {
            // Best effort: a stray part file is harmless.
        }
    }

    private fun deleteOwned(f: File) {
        try {
            if (DownloadFileNames.isInside(dir(), f)) f.delete() else log("download file outside directory, kept")
        } catch (e: Exception) {
            log("download file delete failed")
        }
    }

    private fun saveNow() {
        synchronized(saveLock) {
            try {
                store.save(_entries.value)
            } catch (e: Exception) {
                log("download index save failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun closeQuietly(s: InputStream?) {
        try {
            s?.close()
        } catch (e: Exception) {
            // Nothing useful to do.
        }
    }

    private fun sorted(list: List<DownloadEntry>): List<DownloadEntry> =
        list.sortedWith(compareByDescending<DownloadEntry> { it.startedAt }.thenByDescending { it.id })

    private companion object {
        const val BUFFER_SIZE = 32 * 1024
        const val PROGRESS_INTERVAL_MS = 250L
        const val MAX_NAME_ATTEMPTS = 1000
    }
}
