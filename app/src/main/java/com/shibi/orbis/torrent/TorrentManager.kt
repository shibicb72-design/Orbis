package com.shibi.orbis.torrent

import com.shibi.orbis.downloads.DownloadFileNames
import java.io.File
import java.io.IOException
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
 * metadata file. Their payload sits under payloadRoot/private/ and is deleted by [forgetPrivate] or, after the
 * process died, by the next [restore].
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
                val restored = loaded.entries.take(TorrentLimits.MAX_ENTRIES).filterNot { r -> _entries.value.any { it.id == r.id } }
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
    fun add(source: TorrentSource, isPrivate: Boolean): String? {
        if (_entries.value.size >= TorrentLimits.MAX_ENTRIES) {
            log("add refused, list full")
            return null
        }
        val id = newId()
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
     * ("Save and remove") and leaves the list when the copy is done.
     */
    fun remove(id: String, deleteFiles: Boolean) = locked {
        val e = find(id) ?: return@locked
        if (finishedUnsaved(e) && !deleteFiles) {
            removeAfterPublish += id
            finishAndPublish(id)
            log("removed ${e.status.name}, publishing")
            persist()
            reconcile()
            return@locked
        }
        detach(id, deleteFiles)
        _entries.update { list -> list.filterNot { it.id == id } }
        forgetMemory(id)
        // A private payload is never kept: nothing could ever reach it again.
        val deletePayload = deleteFiles || e.isPrivate
        withContext(io) {
            store.deleteState(id)
            if (deletePayload && e.status != TorrentStatus.PUBLISHING) deletePayload(File(e.saveDir))
        }
        log("removed ${e.status.name} files=$deleteFiles")
        persist()
        reconcile()
    }

    /**
     * Tries a failed torrent again. PUBLISH_FAILED copies the finished files again; NO_SPACE, FILE_ERROR and
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
                launchPublish(id)
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

    /** "Stop seeding" on a seeding row: it leaves the session and is saved to Download/Orbis now. */
    fun stopSeeding(id: String) = locked {
        if (!finishSeeding(id)) return@locked
        log("stop seeding USER")
        persist()
        reconcile()
    }

    /**
     * Private browsing ended: every private torrent leaves the list and its partial files are deleted. A seeding one
     * is finished: it is saved to Download/Orbis like a private file download, then its payload is deleted. Returns
     * the ids that leave, so their notifications can go too.
     */
    fun forgetPrivate(): List<String> {
        val ids = _entries.value.filter { it.isPrivate }.map { it.id }
        if (ids.isNotEmpty()) dropPrivate()
        return ids
    }

    private fun dropPrivate() = locked {
        val gone = _entries.value.filter { it.isPrivate }
        if (gone.isEmpty()) return@locked
        // Seeding, or finished and paused / waiting for Wi-Fi / stopped by Android: the files are complete.
        val seeding = gone.filter { finishedUnsaved(it) }
        val saved = seeding.map { it.id }.toSet()
        for (e in gone) {
            val seeds = e.id in saved
            // A finished torrent's files are complete: flushed, kept for the copy, published after it left the list.
            if (seeds) attached[e.id]?.let { checkpoint(e.id, it) }
            detach(e.id, deleteFiles = !seeds)
            if (seeds) launchPublish(e.id, captured = e.copy(status = TorrentStatus.PUBLISHING), capturedPlan = plans[e.id])
            forgetMemory(e.id)
        }
        _entries.update { list -> list.filterNot { it.isPrivate } }
        withContext(io) {
            // One still publishing (or seeding) finishes its copy first; the publish job deletes its payload afterwards.
            gone.filter { it.status != TorrentStatus.PUBLISHING && it.id !in saved }.forEach { deletePayload(File(it.saveDir)) }
        }
        if (seeding.isNotEmpty()) log("seeding dropped, publishing ${seeding.size}")
        log("private torrents dropped ${gone.size}")
        reconcile()
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
        finishAndPublish(entry.id)
        log("finished")
        persist()
        reconcile()
    }

    /**
     * The finish path: resume data is flushed (so the copy reads complete files), the torrent leaves the session with
     * its files kept, and they are copied to Download/Orbis. The caller persists and reconciles.
     */
    private suspend fun finishAndPublish(id: String) {
        attached[id]?.let { checkpoint(id, it) }
        detach(id, deleteFiles = false)
        seeded.remove(id)
        set(id) { it.copy(status = TorrentStatus.PUBLISHING, doneBytes = it.totalBytes.coerceAtLeast(it.doneBytes), downBps = 0, upBps = 0, peers = 0, seeds = 0, etaSeconds = null) }
        launchPublish(id)
    }

    /** Finished but not saved yet; [seeded] also knows one whose live byte count lags. */
    private fun finishedUnsaved(e: TorrentEntry): Boolean =
        e.finishedUnsaved || (e.id in seeded && e.status != TorrentStatus.PUBLISHING && e.status != TorrentStatus.COMPLETED && e.status != TorrentStatus.FAILED)

    /** A SEEDING torrent takes the finish path. False when [id] is not seeding (gone, paused, already saving). */
    private suspend fun finishSeeding(id: String): Boolean {
        if (find(id)?.status != TorrentStatus.SEEDING) return false
        finishAndPublish(id)
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
                seeding.forEach { finishAndPublish(it) }
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
     * Copies [id]'s files to Download/Orbis. [captured]: the entry as it was when it left the list in the same locked
     * step (a seeding private torrent at the end of private browsing), with its [capturedPlan].
     */
    private fun launchPublish(id: String, captured: TorrentEntry? = null, capturedPlan: TorrentPaths.Plan? = null) {
        scope.launch {
            val entry = captured ?: lock.withLock { find(id) } ?: return@launch
            val plan = capturedPlan ?: lock.withLock { plans[id] } ?: storedPlan(entry)
            val saveDir = File(entry.saveDir)
            val ok = withContext(io) { publishFiles(saveDir, entry.displayName, plan) }
            lock.withLock {
                val still = find(id)
                if (still == null) {
                    // Dropped meanwhile (private browsing ended): the copy is the user's, the payload goes.
                    withContext(io) { if (ok) deletePayload(saveDir) }
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

    /** After a restart the plan is gone: it is rebuilt from the stored .torrent when there is one (never for a private entry). */
    private suspend fun storedPlan(entry: TorrentEntry): TorrentPaths.Plan? {
        if (entry.isPrivate) return null
        val bytes = withContext(io) { runCatching { store.readTorrent(entry.id) }.getOrNull() } ?: return null
        val files = engine.readFiles(bytes) ?: return null
        return (TorrentPaths.plan(files) as? TorrentPaths.Result.Ok)?.plan
    }

    /**
     * Blocking. Every file is checked to be inside [saveDir], a regular file and (when the plan knows it) exactly its
     * declared size before it is copied: a missing or short file fails the whole publish, and every copy this attempt
     * made is deleted again, so Download/Orbis never holds half a torrent and a retry starts clean. A copy that was reused
     * (it existed before this attempt) is never deleted by the rollback.
     */
    private fun publishFiles(saveDir: File, displayName: String, plan: TorrentPaths.Plan?): Boolean {
        val made = ArrayList<TorrentPublisher.Published>()
        val ok = publishAll(saveDir, displayName, plan, made)
        val own = made.filterNot { it.reused }
        if (!ok && own.isNotEmpty()) {
            for (p in own) {
                runCatching { publisher.unpublish(p.handle) }.onFailure { log("unpublish failed: ${it.javaClass.simpleName}") }
            }
            log("publish rolled back ${own.size}")
        }
        return ok
    }

    private fun publishAll(saveDir: File, displayName: String, plan: TorrentPaths.Plan?, made: MutableList<TorrentPublisher.Published>): Boolean {
        val files: List<Triple<File, String, Long>> =
            if (plan != null) {
                plan.relativePaths.indices.mapNotNull { i ->
                    val rel = plan.relativePaths[i] ?: return@mapNotNull null
                    val pub = plan.publishPaths[i] ?: return@mapNotNull null
                    Triple(File(saveDir, rel), pub, plan.sizes.getOrElse(i) { -1L })
                }
            } else {
                // Restored mid-publish: the plan is gone, the folder is the truth.
                val found = saveDir.walkTopDown().filter { it.isFile && !it.name.endsWith(".parts") }.toList()
                val rels = found.map { it.relativeTo(saveDir).invariantSeparatorsPath }
                val top = rels.firstOrNull()?.substringBefore('/', "")
                val stripTop = !top.isNullOrEmpty() && rels.all { it.startsWith("$top/") }
                found.zip(rels.map { if (stripTop) it.substringAfter('/') else it }).map { (f, p) -> Triple(f, p, -1L) }
            }
        if (files.isEmpty()) return false
        for ((file, pub, size) in files) {
            if (!DownloadFileNames.isInside(saveDir, file)) return false
            if (Files.isSymbolicLink(file.toPath()) || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                // Only an empty file may be absent (libtorrent writes nothing for it): it is created empty, so the
                // folder and the file exist in Downloads too. Anything else is missing data.
                if (size != 0L || file.exists() || !createEmpty(saveDir, file)) return false
            }
            if (size >= 0 && file.length() != size) return false
            val (dir, name) = TorrentPaths.publishTarget(displayName, pub)
            made += publisher.publish(file, dir, name) ?: return false
        }
        return true
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
    }
}
