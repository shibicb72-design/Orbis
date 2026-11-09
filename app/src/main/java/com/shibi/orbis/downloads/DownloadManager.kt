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
    /** Fetches the rest of a paused download (see BrowserEngine.fetchFrom); null turns pause and resume off. */
    private val fetchFrom: (suspend (url: String, fromByte: Long, isPrivate: Boolean) -> IncomingDownload?)? = null,
) {
    private val _entries = MutableStateFlow<List<DownloadEntry>>(emptyList())

    /** Newest first. */
    val entries: StateFlow<List<DownloadEntry>> = _entries.asStateFlow()

    private val nextId = AtomicLong(now())
    private val active = ConcurrentHashMap<Long, Active>()
    private val reserveLock = Any()
    private val reservedKeys = HashSet<String>()
    private val saveLock = Any()

    /** Set by [clearIndex]; makes [restore] drop the stored entries instead of merging them back in. */
    private val indexCleared = AtomicBoolean(false)

    private class Active(
        val id: Long,
        val download: IncomingDownload,
        val baseName: String,
        val total: Long,
        val isPrivate: Boolean = false,
        /** Set for a resumed download: its file and how much of it is already written. */
        val resumeFrom: Paused? = null,
    ) {
        @Volatile var paused = false

        /** Set by whoever takes responsibility for the body (copy job) or for discarding it (cancel/scope death). */
        val claimed = AtomicBoolean(false)

        @Volatile var cancelled = false

        @Volatile var stream: InputStream? = null

        @Volatile var job: Job? = null
    }

    private class Reservation(val name: String, val finalFile: File, val partFile: File, val keys: List<String>)

    /** A paused download: its address (memory only), its part file and the bytes in it. */
    private class Paused(val url: String, val reservation: Reservation, val received: Long, val total: Long, val isPrivate: Boolean)

    private val pausedDownloads = ConcurrentHashMap<Long, Paused>()

    /** Pause and resume are offered (the engine can fetch from an offset). */
    val canPause: Boolean get() = fetchFrom != null

    /** Anything still IN_PROGRESS from a previous process is dead: mark FAILED and delete its part file. */
    fun restore() {
        scope.launch(io) {
            val loaded = try { store.load() } catch (e: Exception) { emptyList() }
            val fixed =
                loaded.map { e ->
                    // A paused download cannot continue after a restart: its address was never stored.
                    if (e.status == DownloadStatus.IN_PROGRESS || e.status == DownloadStatus.PAUSED) {
                        e.filePath?.let { deletePartFor(it) }
                        e.copy(status = DownloadStatus.FAILED, filePath = null)
                    } else {
                        e
                    }
                }
            fixed.maxOfOrNull { it.id }?.let { max -> nextId.updateAndGet { maxOf(it, max) } }
            // A private download is not in the index, so a crash during one could leave a .part file nobody names:
            // remove part files that no stored entry owns (only when nothing is running yet, to stay clear of new ones).
            if (active.isEmpty()) {
                val owned = loaded.mapNotNull { it.filePath?.let { p -> "$p.part" } }.toSet()
                runCatching {
                    dir().listFiles { f -> f.isFile && f.name.endsWith(".part") }?.forEach { f ->
                        if (f.absolutePath !in owned) deleteOwned(f)
                    }
                }
            }
            // Merge rather than replace: a download may already have started while the index was loading. Everything
            // stored is settled by now, so a list cleared in the meantime must not get it back. The flag is read inside
            // the update: clearIndex sets it before it edits the list, so one of the two always sees the other.
            _entries.update { current ->
                if (indexCleared.get()) current else sorted(current + fixed.filter { f -> current.none { it.id == f.id } })
            }
            saveNow()
            log("downloads restored")
        }
    }

    /**
     * Starts [download]. A private tab's download ([isPrivate]) carries no source host, is never written to the index
     * file, and is dropped from the list by [forgetPrivate]; its file is kept like any other.
     */
    fun start(download: IncomingDownload, isPrivate: Boolean = false): Long {
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
                sourceHost = if (isPrivate) null else DownloadFileNames.sourceHost(download.url),
                totalBytes = total,
                receivedBytes = 0L,
                status = DownloadStatus.IN_PROGRESS,
                filePath = null,
                startedAt = now(),
                isPrivate = isPrivate,
            )
        val a = Active(id, download, name, total, isPrivate)
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

    /** Stops a running download but keeps what it received; [resume] continues it. */
    fun pause(id: Long) {
        if (fetchFrom == null) return
        val a = active[id] ?: return
        if (a.paused || a.cancelled) return
        a.paused = true
        a.job?.cancel()
        a.stream?.let { s -> Thread { closeQuietly(s) }.apply { isDaemon = true }.start() }
    }

    /** Continues a paused download from where it stopped (from the start when the server cannot). */
    fun resume(id: Long) {
        val fetch = fetchFrom ?: return
        val p = pausedDownloads.remove(id) ?: return
        _entries.update { list -> list.map { if (it.id == id) it.copy(status = DownloadStatus.IN_PROGRESS) else it } }
        scope.launch(io) {
            val body = runCatching { fetch(p.url, p.received, p.isPrivate) }.getOrNull()
            if (body == null) {
                p.reservation.partFile.delete()
                release(p.reservation)
                _entries.update { list -> list.map { if (it.id == id) it.copy(status = DownloadStatus.FAILED, filePath = null) else it } }
                saveNow()
                log("download resume failed")
                return@launch
            }
            val a = Active(id, body, p.reservation.name, p.total, p.isPrivate, resumeFrom = p)
            active[id] = a
            val job = scope.launch(io) { execute(a) }
            a.job = job
            job.invokeOnCompletion { cause ->
                if (cause != null && a.claimed.compareAndSet(false, true)) {
                    runCatching { a.download.discard() }
                    finish(a, DownloadStatus.CANCELLED, null, 0L)
                }
            }
            log("download resumed")
        }
    }

    fun cancel(id: Long) {
        pausedDownloads.remove(id)?.let { p ->
            p.reservation.partFile.delete()
            release(p.reservation)
            _entries.update { list -> list.map { if (it.id == id) it.copy(status = DownloadStatus.CANCELLED, filePath = null) else it } }
            scope.launch(io) { saveNow() }
            return
        }
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

    /**
     * Empties the list but for downloads still running, which stay listed and carry on. No file is deleted and nothing
     * is cancelled: only the index forgets the finished, failed and cancelled entries. Returns the ids removed.
     * A clear made before [restore] has read the stored index also drops what it then reads.
     */
    fun clearIndex(): List<Long> {
        dropOnFinish.clear()
        indexCleared.set(true)
        var removed = emptyList<Long>()
        _entries.update { list ->
            val (running, settled) = list.partition { it.status == DownloadStatus.IN_PROGRESS || active.containsKey(it.id) }
            removed = settled.map { it.id }
            running
        }
        scope.launch(io) { saveNow() }
        log("download list cleared")
        return removed
    }

    /** Private ids still running when private browsing ended: they leave the list as soon as they settle. */
    private val dropOnFinish: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /**
     * Private browsing ended: every private entry leaves the list (so no private file name stays visible). Files are
     * kept. A private download still running finishes first and then leaves. Returns the ids removed now.
     */
    fun forgetPrivate(): List<Long> {
        var removed = emptyList<Long>()
        _entries.update { list ->
            val (gone, keep) = list.partition { it.isPrivate && it.status != DownloadStatus.IN_PROGRESS && !active.containsKey(it.id) }
            list.filter { it.isPrivate && (it.status == DownloadStatus.IN_PROGRESS || active.containsKey(it.id)) }.forEach { dropOnFinish += it.id }
            removed = gone.map { it.id }
            keep
        }
        if (removed.isNotEmpty()) log("private downloads left the list")
        return removed
    }

    // ---- copy job ----

    private suspend fun execute(a: Active) {
        if (!a.claimed.compareAndSet(false, true)) return // cancel() already settled this one
        var reservation: Reservation? = null
        var stream: InputStream? = null
        var bodyOpened = false
        var status = DownloadStatus.FAILED
        var received = 0L
        var append = false
        try {
            val d = dir()
            if (!d.isDirectory && !d.mkdirs() && !d.isDirectory) throw IOException("downloads directory unavailable")
            val resumed = a.resumeFrom
            if (a.total > 0) {
                val usable = d.usableSpace
                if (usable in 1 until a.total - (resumed?.received ?: 0L)) throw IOException("insufficient space")
            }
            val r = resumed?.reservation ?: reserve(d, a.baseName)
            reservation = r
            if (resumed == null) publishTarget(a.id, r)
            // Continue the part file only when the server sent exactly the missing bytes; otherwise start over.
            if (resumed != null && resumed.received > 0 && a.download.startOffset == resumed.received) {
                append = true
                received = resumed.received
            }
            checkActive(a)

            bodyOpened = true
            val input = a.download.openBody() ?: throw IOException("no body")
            stream = input
            a.stream = input
            if (!DownloadFileNames.isInside(d, r.partFile)) throw IOException("path escapes downloads directory")
            FileOutputStream(r.partFile, append).use { out ->
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
            status = if (a.paused) DownloadStatus.PAUSED else DownloadStatus.CANCELLED
            throw e
        } catch (e: Exception) {
            // Closing the stream to cancel (or pause) surfaces as an IOException here: not a failure.
            status =
                when {
                    a.paused -> DownloadStatus.PAUSED
                    a.cancelled || !currentCoroutineContext().isActive -> DownloadStatus.CANCELLED
                    else -> DownloadStatus.FAILED
                }
            if (status == DownloadStatus.FAILED) log("download failed: ${e.javaClass.simpleName}")
        } finally {
            closeQuietly(stream)
            a.stream = null
            if (!bodyOpened) runCatching { a.download.discard() }
            val r = reservation
            if (status == DownloadStatus.PAUSED && r != null) {
                // Kept: the part file and its reserved name wait for resume.
                pausedDownloads[a.id] = Paused(a.download.url, r, received, a.total, a.isPrivate)
            } else {
                if (status != DownloadStatus.COMPLETED) r?.partFile?.delete()
                r?.let { release(it) }
            }
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
                        filePath = if (status == DownloadStatus.COMPLETED || status == DownloadStatus.PAUSED) r?.finalFile?.absolutePath else null,
                        receivedBytes = if (status == DownloadStatus.COMPLETED || status == DownloadStatus.PAUSED) received else e.receivedBytes,
                        totalBytes = if (status == DownloadStatus.COMPLETED && e.totalBytes < 0) received else e.totalBytes,
                    )
                }
            }
        }
        // Deleted while it was finishing: do not leave an orphan behind.
        if (!stillListed && status == DownloadStatus.COMPLETED) r?.finalFile?.delete()
        // Private browsing ended while it ran: it leaves the list now, its file stays.
        if (dropOnFinish.remove(a.id)) _entries.update { list -> list.filterNot { it.id == a.id } }
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
                // Private downloads are never written: the index file names nothing from a private session.
                store.save(_entries.value.filterNot { it.isPrivate })
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
