package com.shibi.orbis.torrent

import com.shibi.orbis.downloads.DownloadFileNames
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where torrent payloads live and how much room is left. Private payloads get their own folder so leftovers are easy to find. */
class TorrentDirs(
    /** getExternalFilesDir("torrents"), or filesDir/torrents/data when there is no external storage. */
    val payloadRoot: () -> File,
    /**
     * Where a finished private torrent is kept: the private folder (".s") of Orbis's own downloads folder
     * (DownloadManager.privateDir, the one private file downloads use). Same volume as the payload, so keeping is a
     * rename. Only folders here are torrents'; the files belong to the file downloads.
     */
    val keepRoot: () -> File,
    val freeBytes: (File) -> Long = { it.usableSpace },
) {
    fun saveDir(id: String, isPrivate: Boolean): File = File(if (isPrivate) privateRoot() else payloadRoot(), id)

    fun privateRoot(): File = File(payloadRoot(), PRIVATE_DIR)

    companion object {
        const val PRIVATE_DIR = "private"
    }
}

/**
 * Copies finished files into the public Download folder (MediaStore in the app). Blocking. A handle is the copy's
 * content URI as a string, so the manager stays Android-free.
 */
interface TorrentPublisher {
    /**
     * A copy published to [handle]. [reused]: a finished copy this app had already made of the same file (an earlier
     * publish cut short) was taken as it is; it was there before this attempt, so a rollback must leave it.
     */
    class Published(val handle: String, val reused: Boolean) {
        override fun toString(): String = "Published(reused=$reused)"
    }

    /** The published copy, or null on any failure (a half-written copy is already gone). */
    fun publish(file: File, relativeDir: String, displayName: String): Published?

    /** Deletes a copy [publish] made. Never throws. */
    fun unpublish(handle: String)
}

/**
 * Owns the torrent list: ids, persistence, the engine's lifetime, Wi-Fi gating, the active-slot limit and publishing
 * finished torrents to Download/Orbis/<name>/. Android-free; every change runs under one lock on [scope].
 *
 * The engine runs only while some torrent wants it: the first add or resume starts it, and it is stopped (resume data
 * saved first) as soon as nothing is downloading. Engine ids are per session; entry ids are 16 random bytes.
 *
 * Private entries (from a private tab) never reach the disk except as payload: no index line, no resume data, no
 * metadata file. Their payload sits under payloadRoot/private/. A finished one is never published on its own: its
 * files are moved into [TorrentDirs.keepRoot] ("kept inside Orbis", like a private file download) and reach
 * Download/Orbis only when the user taps Save to Downloads ([saveKept]) or Save and remove. Whatever was not saved,
 * payload or kept copy, is deleted by [forgetPrivate] (OD-3) or, after the process died, by the next [restore].
 *
 * Logs carry enum names and numbers only: never a magnet, hash, tracker, name or path.
 */
class TorrentManager(
    private val engine: TorrentEngine,
    private val store: TorrentStore,
    private val dirs: TorrentDirs,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val log: (String) -> Unit,
    /** Settings > Torrents; every change is applied at once (limits, discovery, Wi-Fi only, seeding). */
    val settings: StateFlow<TorrentSettings>,
    /** The per-install listen port ([TorrentSettingsStore.listenPort]), read at each engine start. */
    private val listenPort: () -> Int,
    private val network: TorrentNetwork,
    private val now: () -> Long,
    private val publisher: TorrentPublisher,
) {
    private val _entries = MutableStateFlow<List<TorrentEntry>>(emptyList())

    /** Newest first. */
    val entries: StateFlow<List<TorrentEntry>> = _entries.asStateFlow()

    private val lock = Mutex()
    private val random = SecureRandom()

    // ---- under [lock] ----
    private class Attached(val eid: EngineId, val expectsMetadata: Boolean) {
        /** The file list passed the checks and the renames were applied: it may download. */
        var metadataOk = false

        /** We asked the engine to run it (or it fetches metadata on its own). */
        var running = false
        var savedDone = -1L
    }

    private val attached = HashMap<String, Attached>()
    private val byEngine = HashMap<EngineId, String>()

    /** Sources not yet backed by resume data (memory only, so a private magnet is never written). */
    private val sources = HashMap<String, TorrentSource>()

    /** Resume data of private entries: memory only. */
    private val privateResume = HashMap<String, ByteArray>()
    private val plans = HashMap<String, TorrentPaths.Plan>()

    /** Finished in this process with "stop seeding" off: they run as SEEDING again after a pause. */
    private val seeded = HashSet<String>()

    /** "Save and remove" on a seeding torrent: the row leaves the list once its copy is in Download/Orbis. */
    private val removeAfterPublish = HashSet<String>()
    private var engineRunning = false
    private var appliedSettings: TorrentSettings? = null
    private var lastCheckpoint = 0L

    /** [restore] has read the index. Until then a [clearSettled] also applies to what it reads. */
    private var restored = false
    private var clearedBeforeRestore = false

    /** [clearSettled] ran while the engine was running: the next stop drops session.dat if the list is empty then. */
    private var forgetSessionOnStop = false

    /**
     * Kept folders of private rows [clearSettled] took off the list: the files stay, like a private file download's,
     * and go when private browsing ends ([forgetPrivate], OD-3) or at the next start.
     */
    private val clearedKept = HashSet<String>()

    init {
        // Undispatched so the subscriptions exist before the first add can produce an event.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { e -> lock.withLock { onEvent(e) } } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { engine.snapshots.collect { m -> lock.withLock { onSnapshots(m) } } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(settings, network.kind) { s, k -> s to k }.collect { lock.withLock { reconcile() } }
        }
    }

    /** Reads the index only. The engine starts here only when a restored torrent was downloading. */
    fun restore() {
        scope.launch {
            lock.withLock {
                val loaded = withContext(io) { store.load() }
                if (loaded.corrupt) log("index set aside")
                // A list cleared before the index was read does not get its settled rows back; their state files and
                // payloads are leftovers now, which deleteLeftovers removes.
                val restored =
                    loaded.entries.take(TorrentLimits.MAX_ENTRIES).filterNot { r ->
                        _entries.value.any { it.id == r.id } || clearedBeforeRestore && leavesOnClear(r)
                    }
                this@TorrentManager.restored = true
                _entries.update { sorted(it + restored) }
                withContext(io) { deleteLeftovers() }
                persist()
                log("restored ${restored.size}")
                reconcile()
                restored.filter { it.status == TorrentStatus.PUBLISHING }.forEach { launchPublish(it.id) }
            }
        }
    }

    /**
     * Adds a torrent. Returns its id, or null when the list is full. A source that breaks a limit becomes a FAILED
     * entry right away. [isPrivate]: nothing about it is written to disk but its payload, and [forgetPrivate] drops it.
     */
    fun add(requested: TorrentSource, isPrivate: Boolean): String? {
        if (_entries.value.size >= TorrentLimits.MAX_ENTRIES) {
            log("add refused, list full")
            return null
        }
        val id = newId()
        // Only http, https and udp trackers get in (TorrentTrackers); a .torrent's are checked by the engine, which reads them.
        val stripped = (requested as? TorrentSource.Magnet)?.let { TorrentTrackers.stripMagnet(it.uri) }
        val source = if (stripped != null && stripped.dropped > 0) TorrentSource.Magnet(stripped.uri) else requested
        if (stripped != null && stripped.dropped > 0) log("announce urls dropped ${stripped.dropped}")
        // Resume data alone is only ever used for a restore, never as something new to add.
        val error = if (source == TorrentSource.FromResumeData) TorrentErrorKind.INVALID_SOURCE else TorrentPaths.checkSource(source)
        val name = (source as? TorrentSource.Magnet)?.let { TorrentPaths.magnetDisplayName(it.uri) } ?: TorrentPaths.UNNAMED
        val entry =
            TorrentEntry(
                id = id,
                displayName = name,
                totalBytes = -1L,
                doneBytes = 0L,
                status = if (error == null) TorrentStatus.FETCHING_METADATA else TorrentStatus.FAILED,
                addedAt = now(),
                saveDir = dirs.saveDir(id, isPrivate).absolutePath,
                isPrivate = isPrivate,
                errorKind = error,
            )
        if (error == null) sources[id] = source
        _entries.update { sorted(listOf(entry) + it) }
        val kind = if (source is TorrentSource.TorrentFile) "file" else "link"
        log("add $kind ${entry.status.name}${error?.let { " ${it.name}" } ?: ""}")
        scope.launch {
            lock.withLock {
                persist()
                reconcile()
            }
        }
        return id
    }

    fun pause(id: String) = locked {
        val e = find(id) ?: return@locked
        if (!(e.status.wantsToRun || e.status == TorrentStatus.WAITING_FOR_WIFI || e.status == TorrentStatus.PAUSED)) return@locked
        set(id) { it.copy(status = TorrentStatus.PAUSED, pauseReason = PauseReason.USER) }
        log("pause")
        reconcile()
        persist()
    }

    fun resume(id: String) = locked {
        val e = find(id) ?: return@locked
        if (e.status != TorrentStatus.PAUSED) return@locked
        set(id) { it.copy(status = runningStatus(it), pauseReason = null) }
        log("resume")
        reconcile()
        persist()
    }

    /**
     * Takes the torrent off the list. [deleteFiles]: its payload goes too; otherwise it stays in the torrents folder.
     * A finished torrent that is not saved yet (seeding, or finished and paused or waiting for Wi-Fi): without
     * [deleteFiles] it is saved to Download/Orbis first
     * ("Save and remove", private ones too: the user asked for it) and leaves the list when the copy is done.
     * A private torrent kept inside Orbis loses its kept files.
     */
    fun remove(id: String, deleteFiles: Boolean) = locked {
        val e = find(id) ?: return@locked
        if (finishedUnsaved(e) && !deleteFiles) {
            removeAfterPublish += id
            finish(id, publish = true)
            log("removed ${e.status.name}, publishing")
            persist()
            reconcile()
            return@locked
        }
        detach(id, deleteFiles)
        _entries.update { list -> list.filterNot { it.id == id } }
        forgetMemory(id)
        // A private payload or kept copy is never left: nothing could ever reach it again. One being saved right now
        // is deleted by its job when the copy is done.
        val deletePayload = deleteFiles || e.isPrivate
        withContext(io) {
            store.deleteState(id)
            if (e.status != TorrentStatus.PUBLISHING) {
                if (deletePayload) deletePayload(File(e.saveDir))
                e.keptDir?.let { deleteKept(File(it)) }
            }
        }
        log("removed ${e.status.name} files=$deleteFiles")
        persist()
        reconcile()
    }

    /**
     * Tries a failed torrent again. PUBLISH_FAILED copies the finished files again (a private one is kept inside
     * Orbis again); NO_SPACE, FILE_ERROR and
     * ENGINE_ERROR continue the download from its resume data. Any other failure has nothing left to retry.
     */
    fun retry(id: String) = locked {
        val e = find(id) ?: return@locked
        if (e.status != TorrentStatus.FAILED) return@locked
        when (val kind = e.errorKind) {
            TorrentErrorKind.PUBLISH_FAILED -> {
                set(id) { it.copy(status = TorrentStatus.PUBLISHING, errorKind = null) }
                log("retry ${kind.name}")
                persist()
                if (e.isPrivate) launchKeep(id) else launchPublish(id)
            }
            TorrentErrorKind.NO_SPACE, TorrentErrorKind.FILE_ERROR, TorrentErrorKind.ENGINE_ERROR -> {
                log("retry ${kind.name}")
                // Paused by the user, then the normal resume path (resume data on disk, or in memory when private).
                set(id) { it.copy(status = TorrentStatus.PAUSED, pauseReason = PauseReason.USER, errorKind = null) }
                set(id) { it.copy(status = runningStatus(it), pauseReason = null) }
                reconcile()
                persist()
            }
            else -> Unit
        }
    }

    /**
     * "Stop seeding" on a seeding row: it leaves the session and is saved to Download/Orbis now (a private one is
     * kept inside Orbis).
     */
    fun stopSeeding(id: String) = locked {
        if (!finishSeeding(id)) return@locked
        log("stop seeding USER")
        persist()
        reconcile()
    }

    /**
     * "Save to Downloads" on a private torrent kept inside Orbis: its files are copied to Download/Orbis/<name>, with
     * the same paths a normal torrent's get, and the kept copy is deleted. A failed copy leaves the kept files as they
     * were (the row offers the button again).
     */
    fun saveKept(id: String) = locked {
        val e = find(id) ?: return@locked
        val keptPath = e.keptDir
        if (!e.isPrivate || e.status != TorrentStatus.COMPLETED || keptPath == null) return@locked
        set(id) { it.copy(status = TorrentStatus.PUBLISHING) }
        val kept = File(keptPath)
        val plan = plans[id]
        scope.launch {
            val ok = withContext(io) { publishFiles(kept, keptFiles(kept, plan), e.displayName) }
            lock.withLock {
                if (find(id) == null) {
                    // Left the list meanwhile (removed, or private browsing ended): a finished copy is the user's, the
                    // kept files go.
                    withContext(io) { deleteKept(kept) }
                    return@withLock
                }
                if (ok) {
                    withContext(io) { deleteKept(kept) }
                    plans.remove(id)
                    set(id) { it.copy(status = TorrentStatus.COMPLETED, keptDir = null, publishedPath = "Download/Orbis/${it.displayName}") }
                    log("kept saved")
                } else {
                    set(id) { it.copy(status = TorrentStatus.COMPLETED) }
                    log("kept save failed")
                }
            }
        }
    }

    /**
     * Private browsing ended: every private torrent leaves the list and every file of it that was not saved to
     * Download/Orbis is deleted (OD-3): partial payloads, finished ones still seeding, paused or waiting, and the
     * files kept inside Orbis. One being copied to Download/Orbis right now finishes its copy (the user asked for
     * it), then what is left of it goes. Kept files whose row [clearSettled] took off the list go too. Returns the ids
     * that leave, so their notifications can go too.
     */
    fun forgetPrivate(): List<String> {
        val ids = _entries.value.filter { it.isPrivate }.map { it.id }
        dropPrivate()
        return ids
    }

    private fun dropPrivate() = locked {
        if (clearedKept.isNotEmpty()) {
            val orphans = clearedKept.toList()
            clearedKept.clear()
            withContext(io) { orphans.forEach { deleteKept(File(it)) } }
        }
        val gone = _entries.value.filter { it.isPrivate }
        if (gone.isEmpty()) return@locked
        // Seeding, or finished and paused / waiting for Wi-Fi / stopped by Android: complete, but never saved.
        val finished = gone.count { finishedUnsaved(it) }
        for (e in gone) {
            detach(e.id, deleteFiles = true)
            forgetMemory(e.id)
        }
        _entries.update { list -> list.filterNot { it.isPrivate } }
        withContext(io) {
            // One being kept or copied right now: its job deletes what is left of it when it is done.
            gone.filter { it.status != TorrentStatus.PUBLISHING }.forEach { e ->
                deletePayload(File(e.saveDir))
                e.keptDir?.let { deleteKept(File(it)) }
            }
        }
        if (finished > 0) log("seeding dropped, deleted $finished")
        log("private torrents dropped ${gone.size}")
        reconcile()
    }

    /**
     * Clear browsing data > Downloads list. Finished rows and failed ones leave the list with their resume data and
     * metadata; a failed one's partial payload is deleted too (nothing else can reach it). Files in Download/Orbis
     * and kept private folders are not touched, as with file downloads; a kept folder still goes when private
     * browsing ends. Anything running, paused, queued, waiting, seeding or being saved stays, and so does a
     * PUBLISH_FAILED one: its finished files exist only inside Orbis until Retry saves them. session.dat goes once the
     * list is empty and the engine is stopped; a set-aside index goes at once.
     * Returns the ids that leave (from the list as it is now), so their notifications can go too.
     */
    fun clearSettled(): List<String> {
        val ids = _entries.value.filter { leavesOnClear(it) }.map { it.id }
        locked { dropSettled() }
        return ids
    }

    private suspend fun dropSettled() {
        if (!restored) clearedBeforeRestore = true
        val gone = _entries.value.filter { leavesOnClear(it) }
        for (e in gone) {
            detach(e.id, deleteFiles = false)
            forgetMemory(e.id)
        }
        val goneIds = gone.map { it.id }.toSet()
        _entries.update { list -> list.filterNot { it.id in goneIds } }
        gone.filter { it.isPrivate }.mapNotNullTo(clearedKept) { it.keptDir }
        withContext(io) {
            for (e in gone) {
                store.deleteState(e.id)
                if (e.status == TorrentStatus.FAILED) deletePayload(File(e.saveDir))
            }
            store.deleteSetAside()
        }
        log("torrent list cleared ${gone.size}")
        persist()
        when {
            engineRunning -> forgetSessionOnStop = true
            _entries.value.isEmpty() -> forgetSessionState()
        }
    }

    /** What [clearSettled] takes off the list: every finished row, and every failed one but PUBLISH_FAILED. */
    private fun leavesOnClear(e: TorrentEntry): Boolean =
        e.status == TorrentStatus.COMPLETED || e.status == TorrentStatus.FAILED && e.errorKind != TorrentErrorKind.PUBLISH_FAILED

    private fun forgetSessionState() {
        engine.forgetSessionState()
        log("session state dropped")
    }

    /** The foreground service is going away (or the app went to the background): write every resume file now. */
    fun onServiceStopping() = locked {
        if (attached.isEmpty()) return@locked
        checkpointAll()
        persist()
    }

    /**
     * Android 15+ ended the dataSync time budget: save resume data, pause everything that runs (reason TIMEOUT, the
     * user resumes), which also stops the engine.
     */
    fun onTimeout() = locked {
        checkpointAll()
        engine.pauseAll()
        _entries.update { list ->
            list.map { if (it.status.wantsToRun || it.status == TorrentStatus.WAITING_FOR_WIFI) it.copy(status = TorrentStatus.PAUSED, pauseReason = PauseReason.TIMEOUT) else it }
        }
        attached.values.forEach { it.running = false }
        log("timeout, paused")
        reconcile()
        persist()
    }

    // ---- engine events ----

    private suspend fun onEvent(event: TorrentEvent) {
        val id = byEngine[event.id] ?: return
        val entry = find(id) ?: return
        val a = attached[id] ?: return
        when (event) {
            is TorrentEvent.MetadataReceived -> onMetadata(entry, a, event)
            // Only a torrent whose file list was checked can be finished; anything else is not ours to publish.
            // With nothing wanted yet libtorrent may call a torrent finished: only an approved one can be (the engine
            // filters that alert too). A paused one may finish: its last piece landed while it was being paused.
            is TorrentEvent.Finished -> if (a.metadataOk) onFinished(entry, a) else log("finished before approval, ignored")
            is TorrentEvent.Error -> fail(id, event.kind)
        }
    }

    private suspend fun onMetadata(entry: TorrentEntry, a: Attached, event: TorrentEvent.MetadataReceived) {
        val plan =
            when (val r = TorrentPaths.plan(event.files)) {
                is TorrentPaths.Result.Rejected -> {
                    log("metadata rejected ${r.reason.name}")
                    fail(entry.id, TorrentErrorKind.UNSAFE_METADATA)
                    return
                }
                is TorrentPaths.Result.Ok -> r.plan
            }
        val saveDir = File(entry.saveDir)
        val inside =
            withContext(io) {
                saveDir.mkdirs()
                TorrentPaths.allInside(saveDir, plan)
            }
        if (!inside) {
            log("metadata rejected OUTSIDE")
            fail(entry.id, TorrentErrorKind.UNSAFE_METADATA)
            return
        }
        val remaining = (event.files.totalBytes - entry.doneBytes).coerceAtLeast(0L)
        val free = withContext(io) { runCatching { dirs.freeBytes(saveDir) }.getOrDefault(0L) }
        if (remaining > free - TorrentLimits.FREE_SPACE_RESERVE_BYTES) {
            log("no space")
            fail(entry.id, TorrentErrorKind.NO_SPACE)
            return
        }
        engine.approveFiles(a.eid, plan.renames)
        plans[entry.id] = plan
        a.metadataOk = true
        a.running = false
        sources.remove(entry.id)
        set(entry.id) {
            it.copy(
                displayName = plan.displayName,
                totalBytes = event.files.totalBytes,
                hasMetadata = true,
                status = if (it.status == TorrentStatus.FETCHING_METADATA) TorrentStatus.DOWNLOADING else it.status,
            )
        }
        log("metadata ok files=${plan.relativePaths.size} renamed=${plan.renames.size}")
        if (!entry.isPrivate) {
            val bytes = event.torrentFile
            if (bytes != null) {
                withContext(io) {
                    runCatching { store.writeTorrent(entry.id, bytes) }.onFailure { log("metadata not saved: ${it.javaClass.simpleName}") }
                }
            }
        }
        checkpoint(entry.id, a)
        persist()
        reconcile()
    }

    private suspend fun onFinished(entry: TorrentEntry, a: Attached) {
        if (!settings.value.stopSeeding) {
            // Seeds from its private copy until the ratio is reached or the user stops it; nothing is published yet.
            seeded += entry.id
            // One paused as its last piece landed stays paused; it seeds when resumed.
            set(entry.id) {
                it.copy(
                    status = if (it.status == TorrentStatus.PAUSED) TorrentStatus.PAUSED else TorrentStatus.SEEDING,
                    doneBytes = it.totalBytes.coerceAtLeast(it.doneBytes),
                    etaSeconds = null,
                )
            }
            checkpoint(entry.id, a)
            log("finished, seeding")
            persist()
            reconcile()
            return
        }
        finish(entry.id)
        log("finished")
        persist()
        reconcile()
    }

    /**
     * The finish path: resume data is flushed (so the copy reads complete files), the torrent leaves the session with
     * its files kept, and they are copied to Download/Orbis, or for a private torrent moved inside Orbis
     * ([launchKeep]) unless [publish] (the user's "Save and remove"). The caller persists and reconciles.
     */
    private suspend fun finish(id: String, publish: Boolean = false) {
        attached[id]?.let { checkpoint(id, it) }
        detach(id, deleteFiles = false)
        seeded.remove(id)
        set(id) { it.copy(status = TorrentStatus.PUBLISHING, doneBytes = it.totalBytes.coerceAtLeast(it.doneBytes), downBps = 0, upBps = 0, peers = 0, seeds = 0, etaSeconds = null) }
        if (find(id)?.isPrivate == true && !publish) launchKeep(id) else launchPublish(id)
    }

    /** Finished but not saved yet; [seeded] also knows one whose live byte count lags. */
    private fun finishedUnsaved(e: TorrentEntry): Boolean =
        e.finishedUnsaved || (e.id in seeded && e.status != TorrentStatus.PUBLISHING && e.status != TorrentStatus.COMPLETED && e.status != TorrentStatus.FAILED)

    /** A SEEDING torrent takes the finish path. False when [id] is not seeding (gone, paused, already saving). */
    private suspend fun finishSeeding(id: String): Boolean {
        if (find(id)?.status != TorrentStatus.SEEDING) return false
        finish(id)
        return true
    }

    private suspend fun fail(id: String, kind: TorrentErrorKind) {
        val e = find(id) ?: return
        // Only an untrustworthy or unusable torrent loses its files. A full disk or a file error keeps the partial
        // download and its resume data, so a later retry continues from there.
        val discard = kind == TorrentErrorKind.UNSAFE_METADATA || kind == TorrentErrorKind.INVALID_SOURCE || kind == TorrentErrorKind.MISSING_DATA
        val a = attached[id]
        if (a != null && !discard) checkpoint(id, a)
        detach(id, deleteFiles = discard)
        if (discard) forgetMemory(id) else sources.remove(id)
        set(id) { it.copy(status = TorrentStatus.FAILED, errorKind = kind, downBps = 0, upBps = 0, peers = 0, seeds = 0, etaSeconds = null) }
        if (discard) {
            withContext(io) {
                store.deleteState(id)
                deletePayload(File(e.saveDir))
            }
        }
        log("failed ${kind.name}")
        persist()
        reconcile()
    }

    private fun onSnapshots(map: Map<EngineId, TorrentSnapshot>) {
        if (map.isEmpty()) return
        val updates = map.mapNotNull { (eid, snap) -> byEngine[eid]?.let { it to snap } }.toMap()
        if (updates.isEmpty()) return
        _entries.update { list ->
            list.map { e ->
                val snap = updates[e.id] ?: return@map e
                val a = attached[e.id]
                val status =
                    when {
                        e.status != TorrentStatus.FETCHING_METADATA && e.status != TorrentStatus.DOWNLOADING && e.status != TorrentStatus.CHECKING -> e.status
                        snap.state == EngineState.CHECKING -> TorrentStatus.CHECKING
                        snap.state == EngineState.FETCHING_METADATA && !e.hasMetadata -> TorrentStatus.FETCHING_METADATA
                        snap.state == EngineState.DOWNLOADING && a?.metadataOk == true -> TorrentStatus.DOWNLOADING
                        e.status == TorrentStatus.CHECKING && a?.metadataOk == true -> TorrentStatus.DOWNLOADING
                        else -> e.status
                    }
                e.copy(
                    status = status,
                    totalBytes = if (snap.totalBytes > 0) snap.totalBytes else e.totalBytes,
                    doneBytes = if (snap.hasMetadata) snap.doneBytes else e.doneBytes,
                    downBps = snap.downBps,
                    upBps = snap.upBps,
                    peers = snap.peers,
                    seeds = snap.seeds,
                    etaSeconds = snap.etaSeconds,
                    ratio = snap.ratio,
                )
            }
        }
        val s = settings.value
        val limit = s.seedRatioLimit
        if (!s.stopSeeding && limit != null) {
            val reached = _entries.value.filter { it.id in updates && it.status == TorrentStatus.SEEDING && it.ratio >= limit }.map { it.id }
            if (reached.isNotEmpty()) {
                scope.launch {
                    lock.withLock {
                        var n = 0
                        for (id in reached) if (finishSeeding(id)) n++
                        if (n == 0) return@withLock
                        repeat(n) { log("stop seeding RATIO") }
                        persist()
                        reconcile()
                    }
                }
            }
        }
        val t = now()
        if (t - lastCheckpoint >= TorrentLimits.CHECKPOINT_INTERVAL_MS) {
            lastCheckpoint = t
            scope.launch {
                lock.withLock {
                    for ((id, a) in attached.toList()) {
                        val e = find(id) ?: continue
                        if (a.running && a.metadataOk && e.doneBytes != a.savedDone) checkpoint(id, a)
                    }
                    persist()
                }
            }
        }
    }

    // ---- engine lifetime ----

    /**
     * Brings the engine in line with the list: Wi-Fi gate, at most [TorrentLimits.MAX_ACTIVE] running (the rest
     * PAUSED, QUEUED), the session started for the first runner and stopped after the last one.
     */
    private suspend fun reconcile() {
        val s = settings.value
        if (s.stopSeeding) {
            // "Stop seeding when finished" is on (just turned on, or a seeding torrent came back from a pause or a
            // restart): every seeding torrent is saved now, so the setting means what it says.
            val seeding = _entries.value.filter { it.status == TorrentStatus.SEEDING }.map { it.id }
            if (seeding.isNotEmpty()) {
                seeding.forEach { finish(it) }
                log("stop seeding SETTING ${seeding.size}")
                persist()
            }
        }
        val netOk = !s.wifiOnly || network.kind.value == NetworkKind.WIFI_OR_ETHERNET
        _entries.update { list ->
            list.map { e ->
                when {
                    !netOk && e.status.wantsToRun -> e.copy(status = TorrentStatus.WAITING_FOR_WIFI, downBps = 0, upBps = 0)
                    netOk && e.status == TorrentStatus.WAITING_FOR_WIFI -> e.copy(status = runningStatus(e))
                    else -> e
                }
            }
        }
        // Oldest first keep their slot; newer ones wait (and come back by themselves when a slot frees up).
        val byAge = _entries.value.sortedWith(compareBy<TorrentEntry> { it.addedAt }.thenBy { it.id })
        val runners = byAge.filter { it.status.wantsToRun }
        if (runners.size > TorrentLimits.MAX_ACTIVE) {
            val over = runners.drop(TorrentLimits.MAX_ACTIVE).map { it.id }.toSet()
            _entries.update { list -> list.map { if (it.id in over) it.copy(status = TorrentStatus.PAUSED, pauseReason = PauseReason.QUEUED) else it } }
        } else if (netOk) {
            val free = TorrentLimits.MAX_ACTIVE - runners.size
            val back = byAge.filter { it.status == TorrentStatus.PAUSED && it.pauseReason == PauseReason.QUEUED }.take(free).map { it.id }.toSet()
            if (back.isNotEmpty()) _entries.update { list -> list.map { if (it.id in back) it.copy(status = runningStatus(it), pauseReason = null) else it } }
        }

        val run = _entries.value.filter { it.status.wantsToRun }.sortedWith(compareBy<TorrentEntry> { it.addedAt }.thenBy { it.id })
        val runIds = run.map { it.id }.toSet()
        // Stop what must not run (resume data saved on the way).
        for ((id, a) in attached.toList()) {
            if (id in runIds || !a.running) continue
            engine.pause(a.eid)
            a.running = false
            checkpoint(id, a)
        }
        if (run.isEmpty()) {
            if (engineRunning) stopEngine()
            return
        }
        if (!engineRunning) {
            engine.start(s.engine(withContext(io) { listenPort() }))
            engineRunning = true
            appliedSettings = s
        } else if (appliedSettings != s) {
            engine.setLimits(s.downBps, s.upBps)
            engine.setDiscovery(s.dht, s.pex, s.lsd)
            appliedSettings = s
            log("settings applied")
        }
        for (e in run) {
            val a = attached[e.id] ?: attach(e) ?: continue
            // A torrent whose file list is still unchecked waits; a magnet fetching metadata runs on its own.
            if (!a.running && (a.metadataOk || !a.expectsMetadata && !e.hasMetadata)) {
                engine.resume(a.eid)
                a.running = true
            }
        }
    }

    private suspend fun attach(e: TorrentEntry): Attached? {
        val resume = if (e.isPrivate) privateResume[e.id] else withContext(io) { store.readResume(e.id) }
        val torrent = if (e.isPrivate) null else withContext(io) { store.readTorrent(e.id) }
        val source = torrent?.let { TorrentSource.TorrentFile(it) } ?: sources[e.id] ?: TorrentSource.FromResumeData
        if (source == TorrentSource.FromResumeData && resume == null) {
            log("restore impossible, no resume data")
            fail(e.id, TorrentErrorKind.MISSING_DATA)
            return null
        }
        val saveDir = File(e.saveDir)
        withContext(io) { saveDir.mkdirs() }
        val eid = engine.add(source, saveDir, resume, e.isPrivate)
        val expectsMetadata = source is TorrentSource.TorrentFile || e.hasMetadata
        val a = Attached(eid, expectsMetadata)
        // A magnet without metadata starts fetching by itself.
        a.running = !expectsMetadata
        attached[e.id] = a
        byEngine[eid] = e.id
        // A fresh magnet: write resume data at once, so a restart can continue without the magnet itself.
        if (!e.isPrivate && resume == null && source is TorrentSource.Magnet) checkpoint(e.id, a)
        return a
    }

    private fun detach(id: String, deleteFiles: Boolean) {
        val a = attached.remove(id) ?: return
        byEngine.remove(a.eid)
        engine.remove(a.eid, deleteFiles)
    }

    private suspend fun stopEngine() {
        checkpointAll()
        attached.clear()
        byEngine.clear()
        engine.stop()
        engineRunning = false
        appliedSettings = null
        _entries.update { list -> list.map { it.copy(downBps = 0, upBps = 0, peers = 0, seeds = 0, etaSeconds = null) } }
        log("engine idle")
        // Asked for by a clear while the engine ran; applied after the stop above has written the state.
        if (forgetSessionOnStop) {
            forgetSessionOnStop = false
            if (_entries.value.isEmpty()) forgetSessionState()
        }
    }

    private suspend fun checkpointAll() {
        for ((id, a) in attached.toList()) checkpoint(id, a)
    }

    /** Resume data for one torrent: on disk for a normal entry, in memory for a private one. */
    private suspend fun checkpoint(id: String, a: Attached) {
        val e = find(id) ?: return
        val bytes = engine.saveResumeData(a.eid) ?: return
        if (e.isPrivate) {
            privateResume[id] = bytes
        } else {
            withContext(io) {
                runCatching { store.writeResume(id, bytes) }.onFailure { log("resume not saved: ${it.javaClass.simpleName}") }
            }
        }
        a.savedDone = e.doneBytes
    }

    // ---- publishing ----

    /**
     * Copies [id]'s files to Download/Orbis. Called under the lock: the entry and its plan are taken now, so the copy
     * goes on even if the entry leaves the list meanwhile.
     */
    private fun launchPublish(id: String) {
        val entry = find(id) ?: return
        val known = plans[id]
        scope.launch {
            val plan = known ?: storedPlan(entry)
            val saveDir = File(entry.saveDir)
            val ok = withContext(io) { publishFiles(saveDir, payloadFiles(saveDir, plan), entry.displayName) }
            lock.withLock {
                val still = find(id)
                if (still == null) {
                    // Left the list meanwhile (removed, or private browsing ended): a finished copy is the user's, the
                    // payload goes. A private payload always goes (OD-3): nothing could reach it again.
                    withContext(io) { if (ok || entry.isPrivate) deletePayload(saveDir) }
                    return@withLock
                }
                val thenRemove = removeAfterPublish.remove(id)
                if (ok) {
                    withContext(io) {
                        deletePayload(saveDir)
                        store.deleteState(id)
                    }
                    plans.remove(id)
                    if (thenRemove) {
                        _entries.update { list -> list.filterNot { it.id == id } }
                        log("published, removed")
                    } else {
                        set(id) { it.copy(status = TorrentStatus.COMPLETED, publishedPath = "Download/Orbis/${it.displayName}") }
                        log("published")
                    }
                } else {
                    // The payload stays so nothing finished is lost; Retry copies it again (the row stays for that).
                    set(id) { it.copy(status = TorrentStatus.FAILED, errorKind = TorrentErrorKind.PUBLISH_FAILED) }
                    log("publish failed")
                }
                persist()
            }
        }
    }

    /**
     * A finished private torrent: its files are moved from the payload into a new folder under
     * [TorrentDirs.keepRoot] ([keepFiles]) and the row reads "Kept inside Orbis". Nothing goes to MediaStore. Called
     * under the lock, like [launchPublish]. Its plan stays in memory for [saveKept].
     */
    private fun launchKeep(id: String) {
        val entry = find(id) ?: return
        val plan = plans[id]
        scope.launch {
            val saveDir = File(entry.saveDir)
            val kept = withContext(io) { keepFiles(saveDir, entry.displayName, plan) }
            lock.withLock {
                if (find(id) == null) {
                    // Left the list meanwhile (removed, or private browsing ended): nothing of it stays (OD-3).
                    withContext(io) {
                        kept?.let { deleteKept(it) }
                        deletePayload(saveDir)
                    }
                    return@withLock
                }
                if (kept != null) {
                    withContext(io) { deletePayload(saveDir) }
                    set(id) { it.copy(status = TorrentStatus.COMPLETED, keptDir = kept.absolutePath, publishedPath = null) }
                    log("kept")
                } else {
                    // The payload is as it was (a partial move was moved back); Retry keeps it again.
                    set(id) { it.copy(status = TorrentStatus.FAILED, errorKind = TorrentErrorKind.PUBLISH_FAILED) }
                    log("keep failed")
                }
                persist()
            }
        }
    }

    /** After a restart the plan is gone: it is rebuilt from the stored .torrent when there is one (never for a private entry). */
    private suspend fun storedPlan(entry: TorrentEntry): TorrentPaths.Plan? {
        if (entry.isPrivate) return null
        val bytes = withContext(io) { runCatching { store.readTorrent(entry.id) }.getOrNull() } ?: return null
        val files = engine.readFiles(bytes) ?: return null
        return (TorrentPaths.plan(files) as? TorrentPaths.Result.Ok)?.plan
    }

    /** One finished file: where it is, its path below Download/Orbis/<name>/, its declared size (-1 unknown). */
    private class FinishedFile(val file: File, val publishPath: String, val size: Long)

    /** The payload's files as the plan places them; without a plan (restored mid-publish) the folder is the truth. */
    private fun payloadFiles(saveDir: File, plan: TorrentPaths.Plan?): List<FinishedFile> =
        if (plan != null) {
            plan.relativePaths.indices.mapNotNull { i ->
                val rel = plan.relativePaths[i] ?: return@mapNotNull null
                val pub = plan.publishPaths[i] ?: return@mapNotNull null
                FinishedFile(File(saveDir, rel), pub, plan.sizes.getOrElse(i) { -1L })
            }
        } else {
            val found = saveDir.walkTopDown().filter { it.isFile && !it.name.endsWith(".parts") }.toList()
            val rels = found.map { it.relativeTo(saveDir).invariantSeparatorsPath }
            val top = rels.firstOrNull()?.substringBefore('/', "")
            val stripTop = !top.isNullOrEmpty() && rels.all { it.startsWith("$top/") }
            found.zip(rels.map { if (stripTop) it.substringAfter('/') else it }).map { (f, p) -> FinishedFile(f, p, -1L) }
        }

    /**
     * A kept folder's files: it already has the publish layout (no top folder of the torrent's own), so each file's
     * path in it is its publish path. Without a plan the folder is walked as it is, nothing stripped.
     */
    private fun keptFiles(kept: File, plan: TorrentPaths.Plan?): List<FinishedFile> =
        if (plan != null) {
            plan.publishPaths.indices.mapNotNull { i ->
                val pub = plan.publishPaths[i] ?: return@mapNotNull null
                FinishedFile(File(kept, pub), pub, plan.sizes.getOrElse(i) { -1L })
            }
        } else {
            kept.walkTopDown().filter { it.isFile }.map { FinishedFile(it, it.relativeTo(kept).invariantSeparatorsPath, -1L) }.toList()
        }

    /**
     * Blocking. Every file is checked ([checkFinished]) before it is copied: a missing or short file fails the whole
     * publish, and every copy this attempt made is deleted again, so Download/Orbis never holds half a torrent and a
     * retry starts clean. A copy that was reused (it existed before this attempt) is never deleted by the rollback.
     */
    private fun publishFiles(root: File, files: List<FinishedFile>, displayName: String): Boolean {
        val made = ArrayList<TorrentPublisher.Published>()
        val ok = publishAll(root, files, displayName, made)
        val own = made.filterNot { it.reused }
        if (!ok && own.isNotEmpty()) {
            for (p in own) {
                runCatching { publisher.unpublish(p.handle) }.onFailure { log("unpublish failed: ${it.javaClass.simpleName}") }
            }
            log("publish rolled back ${own.size}")
        }
        return ok
    }

    private fun publishAll(root: File, files: List<FinishedFile>, displayName: String, made: MutableList<TorrentPublisher.Published>): Boolean {
        if (files.isEmpty()) return false
        for (f in files) {
            if (!checkFinished(root, f)) return false
            val (dir, name) = TorrentPaths.publishTarget(displayName, f.publishPath)
            made += publisher.publish(f.file, dir, name) ?: return false
        }
        return true
    }

    /**
     * [f] is inside [root], a regular file (not a symlink) and, when the size is known, exactly that size. Only an
     * empty file may be absent (libtorrent writes nothing for it): it is created empty, so the folder and the file
     * exist in Downloads too. Anything else is missing data.
     */
    private fun checkFinished(root: File, f: FinishedFile): Boolean {
        val file = f.file
        if (!DownloadFileNames.isInside(root, file)) return false
        if (Files.isSymbolicLink(file.toPath()) || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (f.size != 0L || file.exists() || !createEmpty(root, file)) return false
        }
        return f.size < 0 || file.length() == f.size
    }

    /**
     * Blocking. Moves a finished private torrent's files from [saveDir] into a new folder under
     * [TorrentDirs.keepRoot] named [displayName] (already safe), " (1)", " (2)" ... when taken, each to its publish
     * path. Every file passes the publish checks first; every target must stay inside the new folder. A move is a
     * rename on the same volume, else a copy and a delete. On any failure the files already moved go back and the new
     * folder is deleted, so nothing is left under the keep root. Returns the folder, or null.
     */
    private fun keepFiles(saveDir: File, displayName: String, plan: TorrentPaths.Plan?): File? {
        // A private entry never outlives its process, so its plan is always in memory; without it nothing is moved.
        if (plan == null) return null
        val files = payloadFiles(saveDir, plan)
        if (files.isEmpty() || !files.all { checkFinished(saveDir, it) }) return null
        val folder = runCatching { newKeepFolder(displayName) }.getOrNull() ?: return null
        val moved = ArrayList<Pair<File, File>>()
        try {
            for (f in files) {
                val target = File(folder, f.publishPath)
                if (!DownloadFileNames.isInside(folder, target)) throw IOException("outside")
                target.parentFile?.mkdirs()
                if (!DownloadFileNames.isInside(folder, target)) throw IOException("outside")
                moveFile(f.file, target)
                moved += f.file to target
            }
            return folder
        } catch (e: Exception) {
            for ((from, to) in moved.asReversed()) runCatching { moveFile(to, from) }
            deleteKept(folder)
            return null
        }
    }

    /** A new, empty folder below the keep root, made unique like a file download's name. Null when none can be made. */
    private fun newKeepFolder(displayName: String): File? {
        val root = dirs.keepRoot()
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) return null
        for (n in 0 until MAX_KEEP_NAME_ATTEMPTS) {
            val folder = File(root, if (n == 0) displayName else "$displayName ($n)")
            if (!DownloadFileNames.isInside(root, folder) || folder.canonicalFile.parentFile != root.canonicalFile) return null
            // mkdir fails when anything of that name exists (a kept folder or a private file download).
            if (folder.mkdir()) return folder
        }
        return null
    }

    /** Never replaces anything: a rename where it can, else a copy then a delete. */
    private fun moveFile(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), LinkOption.NOFOLLOW_LINKS)
        } catch (e: FileAlreadyExistsException) {
            throw e
        } catch (e: IOException) {
            // AtomicMoveNotSupportedException included. No REPLACE_EXISTING: an existing target fails the copy too.
            Files.copy(from.toPath(), to.toPath(), LinkOption.NOFOLLOW_LINKS)
            Files.delete(from.toPath())
        }
    }

    // ---- files ----

    /** An empty file for a declared size of 0, below [saveDir] only. */
    private fun createEmpty(saveDir: File, file: File): Boolean =
        runCatching {
            if (!DownloadFileNames.isInside(saveDir, file)) return@runCatching false
            file.parentFile?.mkdirs()
            file.createNewFile()
            DownloadFileNames.isInside(saveDir, file) && Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        }.getOrDefault(false)

    /** Deletes a kept folder, but only one strictly below the keep root, without following symlinks. Never throws. */
    private fun deleteKept(dir: File) {
        try {
            if (DownloadFileNames.isInside(dirs.keepRoot(), dir)) deleteTree(dir) else log("kept outside root, kept")
        } catch (e: Exception) {
            log("kept delete failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Leftovers of a process that died: private payloads (never in the index), payload folders no entry owns (no code
     * path keeps a payload without its entry) and state files no entry owns. Only folders named like an id are
     * touched; anything else under the payload root stays and is counted in the log.
     */
    private fun deleteLeftovers() {
        val known = _entries.value.map { it.id }.toSet()
        runCatching {
            val privateRoot = dirs.privateRoot()
            privateRoot.listFiles()?.forEach { d -> if (d.name !in known) deletePayload(d) }
        }
        runCatching {
            var foreign = 0
            var reaped = 0
            dirs.payloadRoot().listFiles()?.forEach { d ->
                when {
                    d.name == TorrentDirs.PRIVATE_DIR -> Unit
                    !ID_PATTERN.matches(d.name) -> foreign++
                    d.name !in known -> {
                        deletePayload(d)
                        reaped++
                    }
                }
            }
            if (reaped > 0) log("orphan payloads reaped $reaped")
            if (foreign > 0) log("payload root kept $foreign foreign")
        }
        runCatching { store.storedIds().filter { it !in known }.forEach { store.deleteState(it) } }
        // Kept private torrents whose private browsing ended with the process (OD-3). Only folders: the files there
        // are private file downloads, which the download manager cleans.
        runCatching {
            val owned = _entries.value.mapNotNull { it.keptDir }.toSet()
            var n = 0
            dirs.keepRoot().listFiles()?.forEach { d ->
                if (Files.isDirectory(d.toPath(), LinkOption.NOFOLLOW_LINKS) && d.absolutePath !in owned) {
                    deleteKept(d)
                    n++
                }
            }
            if (n > 0) log("kept leftovers deleted $n")
        }
    }

    /** Deletes a payload folder, but only one inside the payload root. Never throws. */
    private fun deletePayload(dir: File) {
        try {
            if (DownloadFileNames.isInside(dirs.payloadRoot(), dir)) deleteTree(dir) else log("payload outside root, kept")
        } catch (e: Exception) {
            log("payload delete failed: ${e.javaClass.simpleName}")
        }
    }

    /** Deletes [f] and what is below it without ever following a symlink (a link is removed, not its target). */
    private fun deleteTree(f: File) {
        val path = f.toPath()
        if (!Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            f.listFiles()?.forEach { deleteTree(it) }
        }
        Files.deleteIfExists(path)
    }

    private suspend fun persist() {
        val snapshot = _entries.value
        withContext(io) {
            try {
                store.save(snapshot)
            } catch (e: IOException) {
                log("index save failed: ${e.javaClass.simpleName}")
            }
        }
    }

    // ---- helpers ----

    private fun locked(block: suspend () -> Unit) {
        scope.launch { lock.withLock { block() } }
    }

    private fun forgetMemory(id: String) {
        sources.remove(id)
        privateResume.remove(id)
        plans.remove(id)
        seeded.remove(id)
        removeAfterPublish.remove(id)
    }

    private fun find(id: String): TorrentEntry? = _entries.value.firstOrNull { it.id == id }

    private fun set(id: String, change: (TorrentEntry) -> TorrentEntry) {
        _entries.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private fun runningStatus(e: TorrentEntry): TorrentStatus =
        when {
            e.id in seeded -> TorrentStatus.SEEDING
            e.hasMetadata -> TorrentStatus.DOWNLOADING
            else -> TorrentStatus.FETCHING_METADATA
        }

    private fun newId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun sorted(list: List<TorrentEntry>): List<TorrentEntry> =
        list.sortedWith(compareByDescending<TorrentEntry> { it.addedAt }.thenByDescending { it.id })

    private companion object {
        /** What [newId] makes: 16 bytes in lowercase hex. */
        val ID_PATTERN = Regex("[0-9a-f]{32}")

        /** Like a file download's name counter. */
        const val MAX_KEEP_NAME_ATTEMPTS = 1000
    }
}
