package com.shibi.orbis.torrent

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.libtorrent4j.AlertListener
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.FileStorage
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.Vectors
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.FileErrorAlert
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.alerts.StateUpdateAlert
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import org.libtorrent4j.swig.add_torrent_params
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.int_vector
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.session_handle
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.string_vector
import org.libtorrent4j.swig.torrent_flags_t

/**
 * [TorrentEngine] on libtorrent (libtorrent4j). The only class in the app that may import org.libtorrent4j.
 *
 * Every session call runs on one thread ("torrent-engine"); callers never wait on native code. libtorrent's alert
 * thread only copies what it needs out of an alert (an alert dies at the next pop) and posts the rest here.
 * The native library is loaded by the first [add], never before: with no torrent the .so stays unloaded.
 *
 * Logs carry class names, enum names and numbers only: never a magnet, hash, tracker, name or address.
 */
class Libtorrent4jEngine(
    /** session.dat: the DHT table and settings, written on [stop] and read back on the next [start]. */
    private val sessionFile: File,
    private val log: (String) -> Unit,
) : TorrentEngine {
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "torrent-engine").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val nextId = AtomicLong(0)

    private val _snapshots = MutableStateFlow<Map<EngineId, TorrentSnapshot>>(emptyMap())
    override val snapshots: StateFlow<Map<EngineId, TorrentSnapshot>> = _snapshots.asStateFlow()

    private val _events = MutableSharedFlow<TorrentEvent>(extraBufferCapacity = 256)
    override val events: SharedFlow<TorrentEvent> = _events.asSharedFlow()

    // ---- engine thread only ----
    private var session: SessionManager? = null
    private var settings = EngineSettings(listenPort = 0) // replaced by the first start, before any torrent is added
    private var ticker: Job? = null
    private val handles = HashMap<Long, TorrentHandle>()

    /** Engine ids of torrents from a private tab: trackers only, whatever the settings say. */
    private val privateIds = HashSet<Long>()

    /**
     * Engine ids of BEP 27 private torrents (private=1 in the info dictionary): trackers only as well. libtorrent
     * already keeps them off the DHT and local peers and gives them no peer exchange plugin, but that plugin is made
     * when a torrent is added, so a magnet that turns out private would keep peer exchange; the flags close that.
     */
    private val bep27Ids = HashSet<Long>()

    /** What the session runs now (see [SessionDiscovery.of]); null before the first session. */
    private var appliedSession: SessionDiscovery? = null
    private val byHandle = HashMap<Long, EngineId>()
    private val pendingResume = HashMap<Long, CompletableDeferred<ByteArray?>>()
    private val latest = HashMap<EngineId, TorrentSnapshot>()

    // Lazy: nothing of libtorrent4j is touched before the first start (its classes load the native library).
    private val listener by lazy {
        object : AlertListener {
            override fun types(): IntArray =
                intArrayOf(
                    AlertType.STATE_UPDATE.swig(),
                    AlertType.METADATA_RECEIVED.swig(),
                    AlertType.TORRENT_FINISHED.swig(),
                    AlertType.SAVE_RESUME_DATA.swig(),
                    AlertType.SAVE_RESUME_DATA_FAILED.swig(),
                    AlertType.TORRENT_ERROR.swig(),
                    AlertType.FILE_ERROR.swig(),
                )

            // libtorrent's alert thread: copy out, then post.
            override fun alert(alert: Alert<*>) {
                runCatching { onAlert(alert) }.onFailure { log("alert ${alert.type().name} failed: ${it.javaClass.simpleName}") }
            }
        }
    }

    /**
     * Takes the settings. The session itself is built by the first [add] (the manager adds right after starting),
     * so it starts knowing whether that torrent is from a private tab: a session built for a private-tab torrent
     * alone never starts the DHT node or local peer discovery, not even for a moment.
     */
    override fun start(settings: EngineSettings) {
        post {
            this.settings = settings
            session?.applySettings(pack(settings))
        }
    }

    /** Builds the session for its first torrent ([firstIsPrivate]). Throws when the native library cannot load. */
    private fun open(firstIsPrivate: Boolean): SessionManager {
        val started = SessionManager(false)
        val params =
            runCatching { if (sessionFile.isFile) SessionParams(sessionFile.readBytes()) else null }.getOrNull()
                ?: SessionParams(SettingsPack())
        // Our settings go in before the session exists: libtorrent's defaults would start UPnP and NAT-PMP and
        // listen on 6881 the moment the session is built. This replaces any settings in session.dat (an older
        // one may still carry the "Orbis/<version>" user agent; it is not set any more, libtorrent's own goes out).
        params.setSettings(pack(settings, SessionDiscovery.of(settings.dht, settings.lsd, privateTorrents = if (firstIsPrivate) 1 else 0, torrents = 1)))
        // Files go through plain read/write: mmap on Android's external storage is not worth the risk.
        params.setPosixDiskIO()
        started.addListener(listener)
        started.start(params)
        session = started
        ticker =
            scope.launch {
                while (isActive) {
                    runCatching { session?.postTorrentUpdates() }
                    delay(1000)
                }
            }
        log("engine started")
        return started
    }

    override fun stop() {
        post {
            val s = session ?: return@post
            ticker?.cancel()
            ticker = null
            runCatching {
                val state = s.saveState()
                if (state != null) {
                    val tmp = File(sessionFile.parentFile, sessionFile.name + ".tmp")
                    sessionFile.parentFile?.mkdirs()
                    tmp.writeBytes(state)
                    if (!tmp.renameTo(sessionFile)) {
                        sessionFile.delete()
                        tmp.renameTo(sessionFile)
                    }
                }
            }.onFailure { log("session state not saved: ${it.javaClass.simpleName}") }
            s.removeListener(listener)
            runCatching { s.stop() }
            session = null
            handles.clear()
            byHandle.clear()
            privateIds.clear()
            bep27Ids.clear()
            appliedSession = null
            pendingResume.values.forEach { it.complete(null) }
            pendingResume.clear()
            latest.clear()
            _snapshots.value = emptyMap()
            log("engine stopped")
        }
    }

    override fun add(source: TorrentSource, saveDir: File, resumeData: ByteArray?, isPrivate: Boolean): EngineId {
        val id = EngineId(nextId.incrementAndGet())
        post { doAdd(id, source, saveDir, resumeData, isPrivate) }
        return id
    }

    private suspend fun doAdd(id: EngineId, source: TorrentSource, saveDir: File, resumeData: ByteArray?, isPrivate: Boolean) {
        // UnsatisfiedLinkError (no native library for this ABI) is an Error: caught here so the torrent fails visibly.
        val s =
            session
                ?: try {
                    open(firstIsPrivate = isPrivate)
                } catch (e: Throwable) {
                    if (e is VirtualMachineError && e !is OutOfMemoryError) throw e
                    log("engine start failed: ${e.javaClass.simpleName}")
                    null
                }
        if (s == null) {
            emit(TorrentEvent.Error(id, TorrentErrorKind.ENGINE_ERROR))
            return
        }
        val ec = error_code()
        var p: add_torrent_params? = null
        if (resumeData != null) {
            p = libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(resumeData), ec).takeIf { !ec.failed() }
            if (p == null) log("resume data unreadable")
        }
        if (p == null) {
            p =
                when (source) {
                    is TorrentSource.Magnet -> {
                        ec.clear()
                        libtorrent.parse_magnet_uri(source.uri, ec).takeIf { !ec.failed() }
                    }
                    is TorrentSource.TorrentFile ->
                        runCatching { TorrentInfo.bdecode(source.bytes) }.getOrNull()?.let { ti ->
                            add_torrent_params().apply { set_ti(ti.swig()) }
                        }
                    TorrentSource.FromResumeData -> null
                }
        } else if (source is TorrentSource.TorrentFile && !hasInfo(p)) {
            runCatching { TorrentInfo.bdecode(source.bytes) }.getOrNull()?.let { p.set_ti(it.swig()) }
        }
        if (p == null) {
            emit(TorrentEvent.Error(id, if (source == TorrentSource.FromResumeData) TorrentErrorKind.MISSING_DATA else TorrentErrorKind.INVALID_SOURCE))
            return
        }
        // Trackers: a .torrent's own (TorrentInfo.bdecode reads only its info dictionary), then no WebSocket one from
        // any source (a magnet's tr=, resume data on a restore, the file).
        var wsDropped = 0
        if (source is TorrentSource.TorrentFile) {
            val announce = TorrentTrackers.fromTorrentFile(source.bytes)
            wsDropped += announce.dropped
            addTrackers(p, announce)
        }
        wsDropped += dropWebSocketTrackers(p)
        if (wsDropped > 0) log("websocket urls dropped $wsDropped")
        saveDir.mkdirs()
        p.setSave_path(saveDir.absolutePath)
        val withMetadata = hasInfo(p)
        val bep27 = withMetadata && runCatching { TorrentInfo(p.ti_ptr()).isPrivate }.getOrDefault(false)
        // Never auto-managed: the manager decides what runs. With metadata it waits paused for the file check; a
        // magnet runs to fetch metadata and is paused the moment it arrives (see METADATA_RECEIVED). Not
        // stop_when_ready: libtorrent counts downloading_metadata as a transferring state, so a magnet with that flag
        // pauses itself straight out of checking_resume_data and never fetches anything.
        var flags = p.getFlags().and_(TorrentFlags.AUTO_MANAGED.inv()).and_(TorrentFlags.SEED_MODE.inv())
        flags =
            if (withMetadata) {
                flags.or_(TorrentFlags.PAUSED)
            } else {
                // default_dont_download: files past the priority list below (a torrent with more files than any we
                // accept) are not wanted either, so nothing at all is created before the list is rejected.
                flags.and_(TorrentFlags.PAUSED.inv()).and_(TorrentFlags.STOP_WHEN_READY.inv()).or_(TorrentFlags.DEFAULT_DONT_DOWNLOAD)
            }
        flags = discoveryFlags(flags, trackersOnly = isPrivate || bep27)
        p.setFlags(flags)
        // No file is wanted until the manager approved the list: libtorrent then creates no empty file, folder or
        // symlink under the torrent's own names. For a magnet the list is longer than any accepted torrent; libtorrent
        // cuts it to the real file count when the metadata arrives.
        val fileCount =
            if (withMetadata) runCatching { TorrentInfo(p.ti_ptr()).numFiles() }.getOrDefault(TorrentLimits.MAX_FILES) else TorrentLimits.MAX_FILES
        AddTorrentParams(p).filePriorities(Priority.array(Priority.IGNORE, fileCount))
        // A private-tab torrent joins a session whose local peer discovery is already off: libtorrent answers an LSD
        // announce for any torrent that is not BEP 27 private, whatever its own DISABLE_LSD flag.
        applySessionDiscovery(addingPrivate = isPrivate)
        ec.clear()
        val h = s.swig().add_torrent(p, ec)
        if (ec.failed() || h == null || !h.is_valid()) {
            log("add refused: ${ec.value()}")
            applySessionDiscovery()
            emit(TorrentEvent.Error(id, TorrentErrorKind.INVALID_SOURCE))
            return
        }
        val handle = TorrentHandle(h)
        handles[id.value] = handle
        byHandle[h.id()] = id
        if (isPrivate) privateIds += id.value
        if (bep27) bep27Ids += id.value
        applySessionDiscovery()
        log("added ${id.value} metadata=$withMetadata bep27=$bep27")
        if (withMetadata) announceMetadata(id, handle)
    }

    private fun hasInfo(p: add_torrent_params): Boolean = runCatching { p.ti_ptr()?.is_valid() == true }.getOrDefault(false)

    private fun addTrackers(p: add_torrent_params, announce: TorrentTrackers.Announce) {
        if (announce.urls.isEmpty()) return
        val urls = p.getTrackers()
        val tiers = p.getTracker_tiers()
        // Tiers line up with the URLs only when every existing URL has one; libtorrent carries the last tier on.
        while (tiers.size < urls.size) tiers.add(if (tiers.size == 0) 0 else tiers[tiers.size - 1])
        announce.urls.forEachIndexed { i, url ->
            urls.add(url)
            tiers.add(announce.tiers[i])
        }
        p.setTrackers(urls)
        p.setTracker_tiers(tiers)
    }

    /** Removes every ws:// and wss:// tracker from [p] (tiers kept in line). Returns how many went. */
    private fun dropWebSocketTrackers(p: add_torrent_params): Int {
        val urls = p.getTrackers()
        val tiers = p.getTracker_tiers()
        var dropped = 0
        val keptUrls = string_vector()
        val keptTiers = int_vector()
        var tier = 0
        for (i in 0 until urls.size) {
            if (i < tiers.size) tier = tiers[i]
            val url = urls[i]
            if (TorrentTrackers.isWebSocket(url)) {
                dropped++
                continue
            }
            keptUrls.add(url)
            keptTiers.add(tier)
        }
        if (dropped > 0) {
            p.setTrackers(keptUrls)
            p.setTracker_tiers(keptTiers)
        }
        return dropped
    }

    private fun sessionDiscovery(addingPrivate: Boolean? = null): SessionDiscovery {
        val adding = if (addingPrivate == null) 0 else 1
        val addingPriv = if (addingPrivate == true) 1 else 0
        return SessionDiscovery.of(settings.dht, settings.lsd, privateTorrents = privateIds.size + addingPriv, torrents = handles.size + adding)
    }

    /**
     * Session-wide DHT and local peer discovery follow the torrents in the session ([SessionDiscovery.of]).
     * [addingPrivate]: counts one torrent about to be added. Only a change is applied.
     */
    private fun applySessionDiscovery(addingPrivate: Boolean? = null) {
        val s = session ?: return
        val d = sessionDiscovery(addingPrivate)
        if (d == appliedSession) return
        val p = SettingsPack()
        p.setEnableDht(d.dht)
        p.setEnableLsd(d.lsd)
        s.applySettings(p)
        appliedSession = d
        log("session discovery dht=${d.dht} lsd=${d.lsd}")
    }

    private fun trackersOnly(key: Long): Boolean = key in privateIds || key in bep27Ids

    private fun discoveryFlags(f: torrent_flags_t, trackersOnly: Boolean): torrent_flags_t {
        val d = TorrentDiscovery.of(settings.dht, settings.pex, settings.lsd, trackersOnly)
        var flags = f
        flags = if (d.dht) flags.and_(TorrentFlags.DISABLE_DHT.inv()) else flags.or_(TorrentFlags.DISABLE_DHT)
        flags = if (d.pex) flags.and_(TorrentFlags.DISABLE_PEX.inv()) else flags.or_(TorrentFlags.DISABLE_PEX)
        flags = if (d.lsd) flags.and_(TorrentFlags.DISABLE_LSD.inv()) else flags.or_(TorrentFlags.DISABLE_LSD)
        return flags
    }

    /** A magnet whose metadata says private=1 (BEP 27): trackers only from now on. */
    private fun markIfBep27(id: EngineId, h: TorrentHandle) {
        if (id.value in bep27Ids) return
        if (runCatching { h.torrentFile()?.isPrivate == true }.getOrDefault(false)) {
            bep27Ids += id.value
            h.setFlags(TorrentFlags.DISABLE_DHT.or_(TorrentFlags.DISABLE_PEX).or_(TorrentFlags.DISABLE_LSD))
            log("torrent ${id.value} bep27=true")
        }
    }

    private suspend fun announceMetadata(id: EngineId, handle: TorrentHandle) {
        val ti = runCatching { handle.torrentFile() }.getOrNull()
        if (ti == null || !ti.isValid) {
            emit(TorrentEvent.Error(id, TorrentErrorKind.INVALID_SOURCE))
            return
        }
        val bytes =
            runCatching {
                val p = add_torrent_params().apply { set_ti(ti.swig()) }
                Vectors.byte_vector2bytes(libtorrent.write_torrent_file_buf_ex(p))
            }.getOrNull()
        emit(TorrentEvent.MetadataReceived(id, filesOf(ti), bytes))
    }

    private fun filesOf(ti: TorrentInfo): TorrentFiles {
        val fs: FileStorage = ti.files()
        val files =
            (0 until fs.numFiles()).map { i ->
                val flags = fs.fileFlags(i)
                TorrentFileEntry(
                    pathSegments = fs.filePath(i).split('/'),
                    size = fs.fileSize(i),
                    isSymlink = flags.and_(FileStorage.FLAG_SYMLINK).non_zero(),
                    isPadFile = fs.padFileAt(i),
                )
            }
        return TorrentFiles(ti.name() ?: "", ti.totalSize(), ti.numPieces(), files)
    }

    override suspend fun readFiles(torrentFile: ByteArray): TorrentFiles? =
        withContext(dispatcher) {
            runCatching { TorrentInfo.bdecode(torrentFile).takeIf { it.isValid }?.let { filesOf(it) } }
                .onFailure { log("stored torrent unreadable: ${it.javaClass.simpleName}") }
                .getOrNull()
        }

    override fun pause(id: EngineId) {
        post { handles[id.value]?.pause() }
    }

    override fun resume(id: EngineId) {
        post {
            val h = handles[id.value] ?: return@post
            h.unsetFlags(TorrentFlags.AUTO_MANAGED)
            h.resume()
        }
    }

    override fun remove(id: EngineId, deleteFiles: Boolean) {
        post {
            val h = handles.remove(id.value) ?: return@post
            privateIds -= id.value
            bep27Ids -= id.value
            runCatching { byHandle.remove(h.swig().id()) }
            pendingResume.remove(h.swig().id())?.complete(null)
            latest.remove(id)
            _snapshots.value = latest.toMap()
            val s = session ?: return@post
            if (deleteFiles) s.remove(h, session_handle.delete_files) else s.remove(h)
            // Local peers come back only once the last private-tab torrent is out of the session.
            applySessionDiscovery()
        }
    }

    override fun approveFiles(id: EngineId, renames: Map<Int, String>) {
        post {
            val h = handles[id.value] ?: return@post
            renames.forEach { (index, path) -> h.renameFile(index, path) }
            val count = h.torrentFile()?.numFiles() ?: return@post
            h.prioritizeFiles(Priority.array(Priority.DEFAULT, count))
            // libtorrent posts "finished" only on a not-finished -> finished change, and a torrent with nothing wanted
            // already counts as finished. One that is complete on disk (restored after its last piece) would never
            // get the alert, so it is reported here.
            if (isComplete(h)) {
                log("complete at approval ${id.value}")
                emit(TorrentEvent.Finished(id))
            }
        }
    }

    /**
     * Every wanted byte is on disk. A torrent made only of empty files wants nothing at all (totalWanted 0) and is
     * complete as soon as it has metadata; for any other torrent 0 wanted means "not approved yet", never done.
     */
    private fun isComplete(h: TorrentHandle): Boolean {
        val st = h.status()
        if (!st.hasMetadata()) return false
        if (st.totalWanted() > 0) return st.totalWantedDone() >= st.totalWanted()
        return runCatching { h.torrentFile()?.totalSize() == 0L }.getOrDefault(false)
    }

    override suspend fun saveResumeData(id: EngineId): ByteArray? {
        val wait =
            withContext(dispatcher) {
                val h = handles[id.value] ?: return@withContext null
                val key = h.swig().id()
                val d = CompletableDeferred<ByteArray?>()
                pendingResume.put(key, d)?.complete(null)
                h.saveResumeData(TorrentHandle.FLUSH_DISK_CACHE.or_(TorrentHandle.SAVE_INFO_DICT))
                d
            } ?: return null
        return withTimeoutOrNull(RESUME_TIMEOUT_MS) { wait.await() }
    }

    override fun setLimits(downBps: Int, upBps: Int) {
        post {
            settings = settings.copy(downBps = downBps, upBps = upBps)
            session?.applySettings(pack(settings))
        }
    }

    override fun setDiscovery(dht: Boolean, pex: Boolean, lsd: Boolean) {
        post {
            settings = settings.copy(dht = dht, pex = pex, lsd = lsd)
            session?.applySettings(pack(settings))
            handles.forEach { (key, h) ->
                val d = TorrentDiscovery.of(dht, pex, lsd, trackersOnly = trackersOnly(key))
                if (d.pex) h.unsetFlags(TorrentFlags.DISABLE_PEX) else h.setFlags(TorrentFlags.DISABLE_PEX)
                if (d.dht) h.unsetFlags(TorrentFlags.DISABLE_DHT) else h.setFlags(TorrentFlags.DISABLE_DHT)
                if (d.lsd) h.unsetFlags(TorrentFlags.DISABLE_LSD) else h.setFlags(TorrentFlags.DISABLE_LSD)
            }
        }
    }

    override fun pauseAll() {
        post { handles.values.forEach { it.pause() } }
    }

    override fun resumeAll() {
        post {
            handles.values.forEach { it.resume() }
        }
    }

    /** Every setting Orbis makes. [d]: session-wide DHT and local peers, recorded as applied (the pack is applied at once). */
    private fun pack(s: EngineSettings, d: SessionDiscovery = sessionDiscovery()): SettingsPack {
        appliedSession = d
        val p = SettingsPack()
        // No user_agent, peer_fingerprint or handshake_client_version: libtorrent's own defaults go out, so Orbis
        // looks like any libtorrent client.
        p.listenInterfaces("0.0.0.0:${s.listenPort},[::]:${s.listenPort}")
        p.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false)
        p.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false)
        // libtorrent's default, stated so it cannot drift: a torrent may not point tracker or web seed requests at
        // this phone's own loopback services (only /announce paths there, no web seed query strings).
        p.setBoolean(settings_pack.bool_types.ssrf_mitigation.swigValue(), true)
        p.setEnableDht(d.dht)
        p.setEnableLsd(d.lsd)
        p.setMaxMetadataSize(TorrentLimits.MAX_METADATA_BYTES)
        p.downloadRateLimit(s.downBps.coerceAtLeast(0))
        p.uploadRateLimit(s.upBps.coerceAtLeast(0))
        p.activeDownloads(s.maxActive)
        if (s.stopSeeding) p.activeSeeds(0)
        p.stopTrackerTimeout(STOP_TRACKER_TIMEOUT_S)
        return p
    }

    // ---- alerts (libtorrent's alert thread) ----

    private fun onAlert(alert: Alert<*>) {
        when (alert.type()) {
            AlertType.STATE_UPDATE -> {
                val updates = (alert as StateUpdateAlert).status().map { st -> st.swig().getHandle().id() to snapshotOf(st) }
                post {
                    for ((key, snap) in updates) {
                        val id = byHandle[key] ?: continue
                        val before = latest[id]
                        // Status only: the engine id, the state and counts.
                        if (before == null || before.state != snap.state || before.hasMetadata != snap.hasMetadata) {
                            log("torrent ${id.value} ${snap.state.name} metadata=${snap.hasMetadata} peers=${snap.peers}")
                        }
                        latest[id] = snap
                    }
                    _snapshots.value = latest.toMap()
                }
            }
            AlertType.METADATA_RECEIVED -> {
                val alertHandle = (alert as TorrentAlert<*>).handle()
                val key = alertHandle.swig().id()
                // Pause right here, before the post: torrent_handle calls are thread-safe and the alert's handle is
                // valid during this callback. The fewer pieces requested under unchecked names, the better.
                alertHandle.pause()
                post {
                    val id = byHandle[key] ?: return@post
                    // The alert's handle dies with the alert: use the one add_torrent returned.
                    val h = handles[id.value] ?: return@post
                    // Nothing may be written under the torrent's own names: stop until the manager checked the list.
                    h.pause()
                    markIfBep27(id, h)
                    announceMetadata(id, h)
                }
            }
            AlertType.TORRENT_FINISHED -> {
                val key = (alert as TorrentAlert<*>).handle().swig().id()
                post {
                    val id = byHandle[key] ?: return@post
                    val h = handles[id.value] ?: return@post
                    // libtorrent also calls a torrent "finished" while no file is wanted yet (before approveFiles), and
                    // that alert can arrive after the approval. Only a torrent with every file wanted and every wanted
                    // byte on disk is really done.
                    val allWanted = h.filePriorities().none { it == Priority.IGNORE }
                    if (allWanted && isComplete(h)) {
                        emit(TorrentEvent.Finished(id))
                    } else {
                        log("finished alert ignored ${id.value}")
                    }
                }
            }
            AlertType.SAVE_RESUME_DATA -> {
                val a = alert as SaveResumeDataAlert
                val key = a.handle().swig().id()
                val bytes = runCatching { AddTorrentParams.writeResumeDataBuf(a.params()) }.getOrNull()
                post { pendingResume.remove(key)?.complete(bytes) }
            }
            AlertType.SAVE_RESUME_DATA_FAILED -> {
                val key = (alert as TorrentAlert<*>).handle().swig().id()
                post { pendingResume.remove(key)?.complete(null) }
            }
            AlertType.TORRENT_ERROR -> {
                val a = alert as TorrentErrorAlert
                val key = a.handle().swig().id()
                val kind = errorKind(a.error().value, a.filename().isNotEmpty())
                log("torrent error ${kind.name} ${a.error().value}")
                post { byHandle[key]?.let { emit(TorrentEvent.Error(it, kind)) } }
            }
            AlertType.FILE_ERROR -> {
                val a = alert as FileErrorAlert
                val key = a.handle().swig().id()
                val kind = errorKind(a.error().value, true)
                log("file error ${kind.name} ${a.error().value} ${a.operation().name}")
                post { byHandle[key]?.let { emit(TorrentEvent.Error(it, kind)) } }
            }
            else -> Unit
        }
    }

    private fun errorKind(code: Int, isFile: Boolean): TorrentErrorKind =
        when {
            code == ENOSPC -> TorrentErrorKind.NO_SPACE
            isFile -> TorrentErrorKind.FILE_ERROR
            else -> TorrentErrorKind.ENGINE_ERROR
        }

    private fun snapshotOf(st: TorrentStatus): TorrentSnapshot {
        val paused = st.flags().and_(TorrentFlags.PAUSED).non_zero()
        val state =
            when {
                paused -> EngineState.PAUSED
                else ->
                    when (st.state()) {
                        TorrentStatus.State.CHECKING_FILES, TorrentStatus.State.CHECKING_RESUME_DATA -> EngineState.CHECKING
                        TorrentStatus.State.DOWNLOADING_METADATA -> EngineState.FETCHING_METADATA
                        TorrentStatus.State.FINISHED -> EngineState.FINISHED
                        TorrentStatus.State.SEEDING -> EngineState.SEEDING
                        else -> EngineState.DOWNLOADING
                    }
            }
        val total = st.totalWanted()
        val done = st.totalWantedDone()
        val rate = st.downloadPayloadRate().toLong()
        val downloaded = st.allTimeDownload()
        return TorrentSnapshot(
            state = state,
            totalBytes = if (st.hasMetadata()) total else -1L,
            doneBytes = done,
            downBps = rate,
            upBps = st.uploadPayloadRate().toLong(),
            peers = st.numPeers(),
            seeds = st.numSeeds(),
            etaSeconds = if (rate > 0 && total > done) (total - done) / rate else null,
            ratio = if (downloaded > 0) st.allTimeUpload().toFloat() / downloaded.toFloat() else 0f,
            hasMetadata = st.hasMetadata(),
        )
    }

    /** Suspends (on the engine thread) rather than drop an event when the manager falls behind. */
    private suspend fun emit(e: TorrentEvent) {
        _events.emit(e)
    }

    private fun post(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Throwable) {
                // UnsatisfiedLinkError (no native library for this ABI) lands here too: the app keeps running.
                if (e is VirtualMachineError && e !is OutOfMemoryError) throw e
                log("engine call failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        const val RESUME_TIMEOUT_MS = 15_000L
        const val STOP_TRACKER_TIMEOUT_S = 2
        const val ENOSPC = 28
    }
}
