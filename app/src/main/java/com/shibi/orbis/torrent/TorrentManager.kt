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

/** Copies one finished file into the public Download folder (MediaStore in the app). Blocking; false on any failure. */
fun interface TorrentPublisher {
    fun publish(file: File, relativeDir: String, displayName: String): Boolean
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
    private val settings: StateFlow<TorrentSettings>,
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

    /** Takes the torrent off the list. [deleteFiles]: its payload goes too; otherwise it stays in the torrents folder. */
    fun remove(id: String, deleteFiles: Boolean) = locked {
        val e = find(id) ?: return@locked
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

    /** Private browsing ended: every private torrent leaves the list and its partial files are deleted. */
    fun forgetPrivate() = locked {
        val gone = _entries.value.filter { it.isPrivate }
        if (gone.isEmpty()) return@locked
        for (e in gone) {
            detach(e.id, deleteFiles = true)
            forgetMemory(e.id)
        }
        _entries.update { list -> list.filterNot { it.isPrivate } }
        withContext(io) {
            // One still publishing finishes its copy first; the publish job deletes its payload afterwards.
            gone.filter { it.status != TorrentStatus.PUBLISHING }.forEach { deletePayload(File(it.saveDir)) }
        }
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
            is TorrentEvent.Finished -> if (a.metadataOk) onFinished(entry, a) else log("finished before metadata check, ignored")
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
        if (plan.renames.isNotEmpty()) engine.renameFiles(a.eid, plan.renames)
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
            set(entry.id) { it.copy(status = TorrentStatus.SEEDING, doneBytes = it.totalBytes.coerceAtLeast(it.doneBytes)) }
            checkpoint(entry.id, a)
            persist()
            return
        }
        // Flushes what libtorrent still holds, so the copy reads complete files.
        checkpoint(entry.id, a)
        detach(entry.id, deleteFiles = false)
        set(entry.id) { it.copy(status = TorrentStatus.PUBLISHING, doneBytes = it.totalBytes.coerceAtLeast(it.doneBytes), downBps = 0, upBps = 0, peers = 0, seeds = 0, etaSeconds = null) }
        log("finished")
        persist()
        reconcile()
        launchPublish(entry.id)
    }

    private suspend fun fail(id: String, kind: TorrentErrorKind) {
        val e = find(id) ?: return
        detach(id, deleteFiles = true)
        forgetMemory(id)
        set(id) { it.copy(status = TorrentStatus.FAILED, errorKind = kind, downBps = 0, upBps = 0, peers = 0, seeds = 0, etaSeconds = null) }
        withContext(io) {
            store.deleteState(id)
            deletePayload(File(e.saveDir))
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
                )
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
            engine.start(s.engine())
            engineRunning = true
            appliedSettings = s
        } else if (appliedSettings != s) {
            engine.setLimits(s.downBps, s.upBps)
            engine.setDiscovery(s.dht, s.pex, s.lsd)
            appliedSettings = s
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
        val eid = engine.add(source, saveDir, resume)
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

    private fun launchPublish(id: String) {
        scope.launch {
            val entry = lock.withLock { find(id) } ?: return@launch
            val plan = lock.withLock { plans[id] }
            val saveDir = File(entry.saveDir)
            val ok = withContext(io) { publishFiles(saveDir, entry.displayName, plan) }
            lock.withLock {
                val still = find(id)
                if (still == null) {
                    // Dropped meanwhile (private browsing ended): the copy is the user's, the payload goes.
                    withContext(io) { if (ok) deletePayload(saveDir) }
                    return@withLock
                }
                if (ok) {
                    withContext(io) {
                        deletePayload(saveDir)
                        store.deleteState(id)
                    }
                    plans.remove(id)
                    set(id) { it.copy(status = TorrentStatus.COMPLETED, publishedPath = "Download/Orbis/${it.displayName}") }
                    log("published")
                } else {
                    // The payload stays so nothing finished is lost; a later version can retry.
                    set(id) { it.copy(status = TorrentStatus.FAILED, errorKind = TorrentErrorKind.PUBLISH_FAILED) }
                    log("publish failed")
                }
                persist()
            }
        }
    }

    /** Blocking. Every file is checked to be inside [saveDir] and a regular file before it is copied. */
    private fun publishFiles(saveDir: File, displayName: String, plan: TorrentPaths.Plan?): Boolean {
        val files: List<Pair<File, String>> =
            if (plan != null) {
                plan.relativePaths.indices.mapNotNull { i ->
                    val rel = plan.relativePaths[i] ?: return@mapNotNull null
                    val pub = plan.publishPaths[i] ?: return@mapNotNull null
                    File(saveDir, rel) to pub
                }
            } else {
                // Restored mid-publish: the plan is gone, the folder is the truth.
                val found = saveDir.walkTopDown().filter { it.isFile && !it.name.endsWith(".parts") }.toList()
                val rels = found.map { it.relativeTo(saveDir).invariantSeparatorsPath }
                val top = rels.firstOrNull()?.substringBefore('/', "")
                val stripTop = !top.isNullOrEmpty() && rels.all { it.startsWith("$top/") }
                found.zip(rels.map { if (stripTop) it.substringAfter('/') else it })
            }
        if (files.isEmpty()) return false
        for ((file, pub) in files) {
            if (!DownloadFileNames.isInside(saveDir, file)) return false
            if (Files.isSymbolicLink(file.toPath()) || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                // A zero-byte file libtorrent never had to write: nothing to copy.
                if (!file.exists()) continue
                return false
            }
            val (dir, name) = TorrentPaths.publishTarget(displayName, pub)
            if (!publisher.publish(file, dir, name)) return false
        }
        return true
    }

    // ---- files ----

    /** Leftovers of a process that died: private payloads (never in the index) and state files no entry owns. */
    private fun deleteLeftovers() {
        val known = _entries.value.map { it.id }.toSet()
        runCatching {
            val privateRoot = dirs.privateRoot()
            privateRoot.listFiles()?.forEach { d -> if (d.name !in known) deletePayload(d) }
        }
        runCatching { store.storedIds().filter { it !in known }.forEach { store.deleteState(it) } }
    }

    /** Deletes a payload folder, but only one inside the payload root. Never throws. */
    private fun deletePayload(dir: File) {
        try {
            if (DownloadFileNames.isInside(dirs.payloadRoot(), dir)) dir.deleteRecursively() else log("payload outside root, kept")
        } catch (e: Exception) {
            log("payload delete failed: ${e.javaClass.simpleName}")
        }
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
    }

    private fun find(id: String): TorrentEntry? = _entries.value.firstOrNull { it.id == id }

    private fun set(id: String, change: (TorrentEntry) -> TorrentEntry) {
        _entries.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private fun runningStatus(e: TorrentEntry): TorrentStatus = if (e.hasMetadata) TorrentStatus.DOWNLOADING else TorrentStatus.FETCHING_METADATA

    private fun newId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun sorted(list: List<TorrentEntry>): List<TorrentEntry> =
        list.sortedWith(compareByDescending<TorrentEntry> { it.addedAt }.thenByDescending { it.id })
}
