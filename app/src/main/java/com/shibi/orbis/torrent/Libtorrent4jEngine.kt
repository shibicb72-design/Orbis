package com.shibi.orbis.torrent

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KClass
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
import org.libtorrent4j.Operation
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
import org.libtorrent4j.alerts.DhtReplyAlert
import org.libtorrent4j.alerts.DhtStatsAlert
import org.libtorrent4j.alerts.ExternalIpAlert
import org.libtorrent4j.alerts.FileErrorAlert
import org.libtorrent4j.alerts.ListenFailedAlert
import org.libtorrent4j.alerts.ListenSucceededAlert
import org.libtorrent4j.alerts.PeerConnectAlert
import org.libtorrent4j.alerts.PeerDisconnectedAlert
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.alerts.StateUpdateAlert
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import org.libtorrent4j.alerts.TrackerErrorAlert
import org.libtorrent4j.alerts.TrackerReplyAlert
import org.libtorrent4j.swig.add_torrent_params
import org.libtorrent4j.swig.address
import org.libtorrent4j.swig.alert as SwigAlert
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.int_vector
import org.libtorrent4j.swig.ip_filter
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.peer_info
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

    /**
     * The session for libtorrent's alert thread, which may only make thread-safe session calls with it (a torrent
     * found to be v2 is removed there). Set and cleared on the engine thread with [session].
     */
    @Volatile private var alertSession: SessionManager? = null

    /** The two address filters ([blockedRanges]): by "local peers on". Built before the session starts. */
    private var addressFilters: Map<Boolean, ip_filter>? = null

    /** The "local peers" value of the address filter the session holds; null: none set yet, or the last set failed. */
    private var filterLsd: Boolean? = null
    private var settings = EngineSettings(listenPort = 0) // replaced by the first start, before any torrent is added
    private var ticker: Job? = null
    private val handles = HashMap<Long, TorrentHandle>()

    /**
     * Engine ids of torrents from a private tab: never local peer discovery, theirs or the session's, whatever the
     * settings say (their DHT and peer exchange follow the settings).
     */
    private val privateIds = HashSet<Long>()

    /**
     * Engine ids of BEP 27 private torrents (private=1 in the info dictionary): trackers only, in any tab. libtorrent
     * already keeps them off the DHT and local peers and gives them no peer exchange plugin, but that plugin is made
     * when a torrent is added, so a magnet that turns out private would keep peer exchange; the flags close that.
     */
    private val bep27Ids = HashSet<Long>()

    /** What the session runs now (see [SessionDiscovery.of]); null before the first session. */
    private var appliedSession: SessionDiscovery? = null
    private val byHandle = HashMap<Long, EngineId>()
    private val pendingResume = HashMap<Long, CompletableDeferred<ByteArray?>>()
    private val latest = HashMap<EngineId, TorrentSnapshot>()

    /** When each running torrent started (or resumed) its search for peers, on [clock]; by engine id. */
    private val searchStart = HashMap<Long, Long>()

    /** The connection timeline in the error log: counts and times only (keyed by engine id). */
    private val timeline = TorrentTimeline(log)

    /** Asks the DHT and the trackers again while a torrent knows no peer (keyed by engine id). */
    private val peerSearch = PeerSearchRetry()

    /**
     * Engine ids whose details were handled: v2 checked, then announced ([onDetails]). A torrent with details that is
     * not in here is handled by the STATE_UPDATE backstop (its metadata_received alert was dropped or is late).
     */
    private val detailsHandled = HashSet<Long>()

    /** Approved torrents and the ones reported finished: each is reported once per time in the session (tor17-1). */
    private val finishes = FinishReports()

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
                    // The timeline (TorrentTimeline). SessionManager's mask (every category but the logs) already
                    // carries all of them: no setting changes.
                    AlertType.DHT_BOOTSTRAP.swig(),
                    AlertType.DHT_STATS.swig(),
                    AlertType.DHT_REPLY.swig(),
                    AlertType.TRACKER_ANNOUNCE.swig(),
                    AlertType.TRACKER_REPLY.swig(),
                    AlertType.TRACKER_ERROR.swig(),
                    AlertType.LSD_PEER.swig(),
                    AlertType.PEER_CONNECT.swig(),
                    AlertType.PEER_DISCONNECTED.swig(),
                    AlertType.LISTEN_SUCCEEDED.swig(),
                    AlertType.LISTEN_FAILED.swig(),
                    AlertType.EXTERNAL_IP.swig(),
                )

            // libtorrent's alert thread: copy out, then post.
            override fun alert(alert: Alert<*>) {
                runCatching { onAlert(alert) }.onFailure { log("alert ${alert.type().name} failed: ${it.javaClass.simpleName}") }
            }
        }
    }

    /**
     * Takes the settings. The session itself is built by the first [add] (the manager adds right after starting),
     * so it starts knowing what that torrent is: a session built for a private-tab torrent never starts local peer
     * discovery, and one built for a BEP 27 torrent never starts the DHT node, not even for a moment.
     */
    override fun start(settings: EngineSettings) {
        post {
            this.settings = settings
            session?.let {
                it.applySettings(pack(settings))
                applyAddressFilter(it)
            }
        }
    }

    /**
     * Builds the session for its first torrent ([first]). Throws when the native library cannot load, and when the
     * address filter cannot be built or set: never a session without it.
     */
    private fun open(first: Kind): SessionManager {
        val started = SessionManager(false)
        val filters = mapOf(false to addressFilter(lsd = false), true to addressFilter(lsd = true))
        val params =
            runCatching { if (sessionFile.isFile) SessionParams(sessionFile.readBytes()) else null }.getOrNull()
                ?: SessionParams(SettingsPack())
        // Our settings go in before the session exists: libtorrent's defaults would start UPnP and NAT-PMP and
        // listen on 6881 the moment the session is built. This replaces any settings in session.dat (an older
        // one may still carry the "Orbis/<version>" user agent; it is not set any more, libtorrent's own goes out).
        params.setSettings(pack(settings, sessionDiscovery(adding = first)))
        val lsd = appliedSession?.lsd == true
        // The address filter goes in the same way (and replaces any filter saved in session.dat): the session holds it
        // from its first moment. It is set again right after the start below, with the call every later change uses.
        params.swig().setIp_filter(filters.getValue(lsd))
        // Files go through plain read/write: mmap on Android's external storage is not worth the risk.
        params.setPosixDiskIO()
        started.addListener(listener)
        started.start(params)
        try {
            // Before the first torrent is added: libtorrent runs session calls in the order they are made.
            started.swig().set_ip_filter(filters.getValue(lsd))
            // start() forces its own alert mask (every category but the logs, ip_block included) over the params, so
            // it is replaced here, before the first torrent: with the filter every blocked address would post a
            // peer_blocked_alert, and one tracker answer full of blocked peers would fill the alert queue and push out
            // metadata_received ([alertMask]).
            val mask =
                alertMask(
                    all = SwigAlert.all_categories.to_int(),
                    ipBlock = SwigAlert.ip_block_notification.to_int(),
                    logs =
                        listOf(
                            SwigAlert.session_log_notification,
                            SwigAlert.torrent_log_notification,
                            SwigAlert.peer_log_notification,
                            SwigAlert.dht_log_notification,
                            SwigAlert.port_mapping_log_notification,
                            SwigAlert.picker_log_notification,
                        ).map { it.to_int() },
                )
            started.applySettings(SettingsPack().setInteger(settings_pack.int_types.alert_mask.swigValue(), mask))
        } catch (e: Throwable) {
            started.removeListener(listener)
            runCatching { started.stop() }
            throw e
        }
        addressFilters = filters
        filterLsd = lsd
        log("address filter set n=${blockedRanges(lsd).size}")
        log("alert mask set ip_block=false")
        session = started
        alertSession = started
        ticker =
            scope.launch {
                while (isActive) {
                    runCatching { session?.postTorrentUpdates() }
                    runCatching { timelineTick() }
                    runCatching { searchTick() }
                    runCatching { peerSearchTick() }
                    runCatching { finishTick() }
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
            alertSession = null
            s.removeListener(listener)
            runCatching { s.stop() }
            session = null
            addressFilters = null
            filterLsd = null
            handles.clear()
            byHandle.clear()
            privateIds.clear()
            bep27Ids.clear()
            appliedSession = null
            pendingResume.values.forEach { it.complete(null) }
            pendingResume.clear()
            latest.clear()
            searchStart.clear()
            timeline.sessionEnded()
            peerSearch.clear()
            finishes.clear()
            detailsHandled.clear()
            _snapshots.value = emptyMap()
            log("engine stopped")
        }
    }

    override fun forgetSessionState() {
        post {
            // On this thread, after any stop() already posted: the state it writes is deleted too.
            if (session != null) return@post
            File(sessionFile.parentFile, sessionFile.name + ".tmp").delete()
            sessionFile.delete()
        }
    }

    override fun add(source: TorrentSource, saveDir: File, resumeData: ByteArray?, isPrivate: Boolean): EngineId {
        val id = EngineId(nextId.incrementAndGet())
        val addedAt = clock()
        post { doAdd(id, source, saveDir, resumeData, isPrivate, addedAt) }
        return id
    }

    private suspend fun doAdd(id: EngineId, source: TorrentSource, saveDir: File, resumeData: ByteArray?, isPrivate: Boolean, addedAt: Long) {
        // The torrent is read before the session is built, so the session starts knowing whether it is BEP 27. The
        // first libtorrent4j object loads the native library: UnsatisfiedLinkError (no native library for this ABI) is
        // an Error, caught here and at open so the torrent fails visibly.
        val ec =
            try {
                error_code()
            } catch (e: Throwable) {
                if (e is VirtualMachineError && e !is OutOfMemoryError) throw e
                log("engine start failed: ${e.javaClass.simpleName}")
                emit(TorrentEvent.Error(id, TorrentErrorKind.ENGINE_ERROR))
                return
            }
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
        // A v2 or hybrid torrent (from a .torrent, resume data or a magnet) is never handed to the session; intake
        // refuses them first (TorrentPaths.checkSource), this holds for anything that got past it.
        when (hasV2(p)) {
            false -> Unit
            true -> {
                log("v2 refused at add")
                emit(TorrentEvent.Error(id, TorrentErrorKind.UNSUPPORTED_V2))
                return
            }
            null -> {
                log("v2 check failed at add")
                emit(TorrentEvent.Error(id, TorrentErrorKind.ENGINE_ERROR))
                return
            }
        }
        // Trackers: a .torrent's own (TorrentInfo.bdecode reads only its info dictionary), then only http, https and
        // udp ones from any source (a magnet's tr=, resume data on a restore, the file): no WebSocket path.
        var refusedTrackers = 0
        if (source is TorrentSource.TorrentFile) {
            val announce = TorrentTrackers.fromTorrentFile(source.bytes)
            refusedTrackers += announce.dropped
            addTrackers(p, announce)
        }
        refusedTrackers += dropRefusedTrackers(p)
        if (refusedTrackers > 0) log("announce urls dropped $refusedTrackers")
        val withMetadata = hasInfo(p)
        val bep27 = withMetadata && runCatching { TorrentInfo(p.ti_ptr()).isPrivate }.getOrDefault(false)
        val kind = Kind(privateTab = isPrivate, bep27 = bep27)
        val opens = session == null
        val s =
            session
                ?: try {
                    open(first = kind)
                } catch (e: Throwable) {
                    if (e is VirtualMachineError && e !is OutOfMemoryError) throw e
                    log("engine start failed: ${e.javaClass.simpleName}")
                    null
                }
        if (s == null) {
            emit(TorrentEvent.Error(id, TorrentErrorKind.ENGINE_ERROR))
            return
        }
        val sessionStartedAt = if (opens) clock() else null
        saveDir.mkdirs()
        p.setSave_path(saveDir.absolutePath)
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
        flags = discoveryFlags(flags, kind)
        // The session's address filter applies to this torrent's peers, web seeds and trackers, whatever resume data
        // says ("apply_ip_filter" is read from it).
        flags = flags.or_(TorrentFlags.APPLY_IP_FILTER)
        p.setFlags(flags)
        // No file is wanted until the manager approved the list: libtorrent then creates no empty file, folder or
        // symlink under the torrent's own names. For a magnet the list is longer than any accepted torrent; libtorrent
        // cuts it to the real file count when the metadata arrives.
        val fileCount =
            if (withMetadata) runCatching { TorrentInfo(p.ti_ptr()).numFiles() }.getOrDefault(TorrentLimits.MAX_FILES) else TorrentLimits.MAX_FILES
        AddTorrentParams(p).filePriorities(Priority.array(Priority.IGNORE, fileCount))
        // A private-tab torrent joins a session whose local peer discovery is already off: libtorrent answers an LSD
        // announce for any torrent that is not BEP 27 private, whatever its own DISABLE_LSD flag. One that may use the
        // DHT joins a session whose DHT node already runs (when the setting is on).
        if (!applySessionDiscovery(adding = kind)) {
            // The address filter for the session with this torrent in it could not be set.
            applySessionDiscovery()
            emit(TorrentEvent.Error(id, TorrentErrorKind.ENGINE_ERROR))
            return
        }
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
        timeline.added(id.value, addedAt, sessionStartedAt)
        if (withMetadata) {
            // Checked for v2 above, before the add.
            detailsHandled += id.value
            announceMetadata(id, handle)
        }
    }

    private fun hasInfo(p: add_torrent_params): Boolean = runCatching { p.ti_ptr()?.is_valid() == true }.getOrDefault(false)

    /** [p] carries a v2 info-hash (its own, or its details'); null: it could not be read. */
    private fun hasV2(p: add_torrent_params): Boolean? =
        runCatching { p.getInfo_hashes().has_v2() || (hasInfo(p) && p.ti_ptr().info_hashes().has_v2()) }.getOrNull()

    /**
     * Builds the session address filter for [blockedRanges]. Throws when an address does not parse: the caller must
     * not run a session without it.
     */
    private fun addressFilter(lsd: Boolean): ip_filter {
        val f = ip_filter()
        val ec = error_code()
        val blocked = ip_filter.access_flags.blocked.swigValue().toLong()
        for ((first, last) in blockedRanges(lsd)) {
            ec.clear()
            val a = address.from_string(first, ec)
            check(!ec.failed()) { "address filter: unparsable range" }
            val b = address.from_string(last, ec)
            check(!ec.failed()) { "address filter: unparsable range" }
            f.add_rule(a, b, blocked)
        }
        return f
    }

    /**
     * Sets the address filter that matches the session's local peers now ([SessionDiscovery.lsd]) when it is not the
     * one set. Engine thread. A failure pauses every torrent (none runs under the wrong filter) and returns false;
     * the next call tries again.
     */
    private fun applyAddressFilter(s: SessionManager): Boolean {
        val lsd = appliedSession?.lsd == true
        if (lsd == filterLsd) return true
        try {
            s.swig().set_ip_filter(addressFilters!!.getValue(lsd))
        } catch (e: Throwable) {
            if (e is VirtualMachineError && e !is OutOfMemoryError) throw e
            filterLsd = null
            handles.values.forEach { h -> runCatching { h.pause() } }
            log("address filter not set: ${e.javaClass.simpleName}, paused ${handles.size}")
            return false
        }
        filterLsd = lsd
        log("address filter set n=${blockedRanges(lsd).size}")
        return true
    }

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

    /** Removes every tracker [TorrentTrackers.isAllowed] refuses from [p] (tiers kept in line). Returns how many went. */
    private fun dropRefusedTrackers(p: add_torrent_params): Int {
        val urls = p.getTrackers()
        val tiers = p.getTracker_tiers()
        var dropped = 0
        val keptUrls = string_vector()
        val keptTiers = int_vector()
        var tier = 0
        for (i in 0 until urls.size) {
            if (i < tiers.size) tier = tiers[i]
            val url = urls[i]
            if (!TorrentTrackers.isAllowed(url)) {
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

    /** What a torrent is, for [TorrentDiscovery.of] and [SessionDiscovery.of]. */
    private data class Kind(val privateTab: Boolean, val bep27: Boolean)

    private fun kindOf(key: Long): Kind = Kind(privateTab = key in privateIds, bep27 = key in bep27Ids)

    /** [adding]: one torrent about to be added, counted as if it already were. */
    private fun sessionDiscovery(adding: Kind? = null): SessionDiscovery =
        SessionDiscovery.of(
            settings.dht,
            settings.lsd,
            privateTabTorrents = privateIds.size + if (adding?.privateTab == true) 1 else 0,
            bep27Torrents = bep27Ids.size + if (adding?.bep27 == true) 1 else 0,
            torrents = handles.size + if (adding != null) 1 else 0,
            previous = appliedSession,
        )

    /**
     * Session-wide DHT and local peer discovery follow the torrents in the session ([SessionDiscovery.of]).
     * [adding]: counts one torrent about to be added. Only a change is applied. The address filter follows local
     * peers ([applyAddressFilter]); false: it could not be set.
     */
    private fun applySessionDiscovery(adding: Kind? = null): Boolean {
        val s = session ?: return true
        val d = sessionDiscovery(adding)
        if (d != appliedSession) {
            val p = SettingsPack()
            p.setEnableDht(d.dht)
            p.setEnableLsd(d.lsd)
            s.applySettings(p)
            appliedSession = d
            log("session discovery dht=${d.dht} lsd=${d.lsd}")
        }
        return applyAddressFilter(s)
    }

    private fun discoveryOf(kind: Kind, dht: Boolean = settings.dht, pex: Boolean = settings.pex, lsd: Boolean = settings.lsd): TorrentDiscovery =
        TorrentDiscovery.of(dht, pex, lsd, privateTab = kind.privateTab, bep27 = kind.bep27)

    private fun discoveryFlags(f: torrent_flags_t, kind: Kind): torrent_flags_t {
        val d = discoveryOf(kind)
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
            // The DHT node stops if no torrent left in the session may use it.
            applySessionDiscovery()
        }
    }

    /**
     * Engine thread: the details of [id] came in and are not v2. Stops it until the manager checked the list, then
     * announces them (once: [detailsHandled]).
     */
    private suspend fun onDetails(id: EngineId, h: TorrentHandle, at: Long) {
        detailsHandled += id.value
        timeline.milestone(id.value, TorrentTimeline.Step.DETAILS, at)
        // Nothing may be written under the torrent's own names: stop until the manager checked the list.
        h.pause()
        markIfBep27(id, h)
        announceMetadata(id, h)
    }

    /**
     * Engine thread: the details of [id] are v2 or hybrid ([v2] true) or could not be read (null). It leaves the
     * session and the manager hears UNSUPPORTED_V2 (ENGINE_ERROR when unread). [removed]: already removed from the
     * session on the alert thread. Nothing is deleted: no file is wanted before approval.
     */
    private suspend fun refuseAfterDetails(id: EngineId, v2: Boolean?, removed: Boolean) {
        val h = forget(id) ?: return
        if (!removed) runCatching { session?.remove(h) }.onFailure { log("v2 remove failed: ${it.javaClass.simpleName}") }
        log(if (v2 == true) "v2 after details, removed" else "v2 check failed after details, removed")
        applySessionDiscovery()
        emit(TorrentEvent.Error(id, if (v2 == true) TorrentErrorKind.UNSUPPORTED_V2 else TorrentErrorKind.ENGINE_ERROR))
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
            // The first resume is the one after the file check.
            timeline.milestone(id.value, TorrentTimeline.Step.RESUMED, clock())
        }
    }

    override fun remove(id: EngineId, deleteFiles: Boolean) {
        post {
            val h = forget(id) ?: return@post
            val s = session ?: return@post
            if (deleteFiles) s.remove(h, session_handle.delete_files) else s.remove(h)
            // Local peers come back only once the last private-tab torrent is out of the session; the DHT node stops
            // once only BEP 27 torrents are left.
            applySessionDiscovery()
        }
    }

    /** Drops every trace of [id] on the engine thread (not the session's torrent itself); its handle, or null. */
    private fun forget(id: EngineId): TorrentHandle? {
        val h = handles.remove(id.value) ?: return null
        privateIds -= id.value
        bep27Ids -= id.value
        runCatching { byHandle.remove(h.swig().id()) }
        pendingResume.remove(h.swig().id())?.complete(null)
        latest.remove(id)
        searchStart.remove(id.value)
        peerSearch.forget(id.value)
        finishes.forget(id.value)
        detailsHandled -= id.value
        _snapshots.value = latest.toMap()
        sampleTimeline(id.value, h, clock())
        timeline.removed(id.value, clock())
        return h
    }

    override fun approveFiles(id: EngineId, renames: Map<Int, String>) {
        post {
            val h = handles[id.value] ?: return@post
            renames.forEach { (index, path) -> h.renameFile(index, path) }
            val count = h.torrentFile()?.numFiles() ?: return@post
            h.prioritizeFiles(Priority.array(Priority.DEFAULT, count))
            finishes.approve(id.value)
            // libtorrent posts "finished" only on a not-finished -> finished change, and a torrent with nothing wanted
            // already counts as finished. One that is complete on disk (restored after its last piece) would never
            // get the alert, so it is reported here.
            if (isComplete(h) && finishes.report(id.value)) {
                log("complete at approval ${id.value}")
                timeline.finished(id.value, clock())
                emit(TorrentEvent.Finished(id))
            }
        }
    }

    /**
     * Every wanted byte is on disk. A torrent made only of empty files wants nothing at all (totalWanted 0) and is
     * complete as soon as it has metadata; for any other torrent 0 wanted means "not approved yet", never done.
     */
    private fun isComplete(h: TorrentHandle, st: TorrentStatus = h.status()): Boolean {
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
            session?.let {
                it.applySettings(pack(settings))
                applyAddressFilter(it)
            }
        }
    }

    override fun setDiscovery(dht: Boolean, pex: Boolean, lsd: Boolean) {
        post {
            settings = settings.copy(dht = dht, pex = pex, lsd = lsd)
            // The LAN ranges of the address filter follow "local peers".
            session?.let {
                it.applySettings(pack(settings))
                applyAddressFilter(it)
            }
            handles.forEach { (key, h) ->
                val d = discoveryOf(kindOf(key), dht, pex, lsd)
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

    /**
     * Every setting Orbis makes ([sessionSettings]) as a pack. [d]: session-wide DHT and local peers, recorded as applied
     * (the pack is applied at once).
     */
    private fun pack(s: EngineSettings, d: SessionDiscovery = sessionDiscovery()): SettingsPack {
        appliedSession = d
        val p = SettingsPack()
        for ((key, value) in sessionSettings(s, d)) {
            when (key) {
                SessionSetting.LISTEN_INTERFACES -> p.listenInterfaces(value as String)
                SessionSetting.ENABLE_UPNP -> p.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), value as Boolean)
                SessionSetting.ENABLE_NATPMP -> p.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), value as Boolean)
                SessionSetting.SSRF_MITIGATION -> p.setBoolean(settings_pack.bool_types.ssrf_mitigation.swigValue(), value as Boolean)
                SessionSetting.APPLY_IP_FILTER_TO_TRACKERS ->
                    p.setBoolean(settings_pack.bool_types.apply_ip_filter_to_trackers.swigValue(), value as Boolean)
                SessionSetting.ENABLE_DHT -> p.setEnableDht(value as Boolean)
                SessionSetting.ENABLE_LSD -> p.setEnableLsd(value as Boolean)
                SessionSetting.MAX_METADATA_SIZE -> p.setMaxMetadataSize(value as Int)
                SessionSetting.DOWNLOAD_RATE_LIMIT -> p.downloadRateLimit(value as Int)
                SessionSetting.UPLOAD_RATE_LIMIT -> p.uploadRateLimit(value as Int)
                SessionSetting.ACTIVE_DOWNLOADS -> p.activeDownloads(value as Int)
                SessionSetting.ACTIVE_SEEDS -> p.activeSeeds(value as Int)
                SessionSetting.STOP_TRACKER_TIMEOUT -> p.stopTrackerTimeout(value as Int)
                SessionSetting.ANNOUNCE_TO_ALL_TRACKERS ->
                    p.setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), value as Boolean)
                SessionSetting.ANNOUNCE_TO_ALL_TIERS ->
                    p.setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), value as Boolean)
            }
        }
        return p
    }

    // ---- alerts (libtorrent's alert thread) ----

    private fun onAlert(alert: Alert<*>) {
        when (alert.type()) {
            AlertType.STATE_UPDATE -> {
                val at = clock()
                val updates = (alert as StateUpdateAlert).status().map { st -> Triple(st.swig().getHandle().id(), snapshotOf(st), st.totalPayloadDownload() > 0) }
                post {
                    for ((key, snap, gotPayload) in updates) {
                        val id = byHandle[key] ?: continue
                        if (gotPayload) timeline.milestone(id.value, TorrentTimeline.Step.FIRST_BYTE, at)
                        val before = latest[id]
                        // Status only: the engine id, the state and counts.
                        if (before == null || before.state != snap.state || before.hasMetadata != snap.hasMetadata) {
                            log("torrent ${id.value} ${snap.state.name} metadata=${snap.hasMetadata} peers=${snap.peers}")
                        }
                        // Backstop for a dropped or late metadata_received alert (state_update_alert is high
                        // priority and comes each second): the same pause, v2 check and removal or announcement.
                        if (snap.hasMetadata && id.value !in detailsHandled) {
                            val h = handles[id.value] ?: continue
                            log("details without alert ${id.value}")
                            runCatching { h.pause() }
                            val v2 = runCatching { h.swig().info_hashes().has_v2() }.getOrNull()
                            if (v2 != false) {
                                refuseAfterDetails(id, v2, removed = false)
                                continue
                            }
                            onDetails(id, h, at)
                        }
                        // The search fields come from [searchTick] (engine thread): keep the last ones.
                        latest[id] = if (before == null) snap else snap.withSearch(before)
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
                // A btih magnet can still turn out v2 or hybrid: libtorrent accepts details whose SHA-256, cut to 20
                // bytes, is the btih. Its info-hashes were updated from the details before this alert was posted.
                val v2 = runCatching { alertHandle.swig().info_hashes().has_v2() }.getOrNull()
                if (v2 != false) {
                    // Out of the session here, still on this thread: remove_torrent is a thread-safe session call
                    // (queued to libtorrent's network thread), so the peers go without waiting for the engine thread.
                    // No file exists yet (nothing is wanted before approval): nothing to delete. The handle maps are
                    // engine-thread state and are cleared in the post.
                    val removed =
                        runCatching {
                            val s = alertSession
                            s?.swig()?.remove_torrent(alertHandle.swig())
                            s != null
                        }.onFailure { log("v2 remove failed: ${it.javaClass.simpleName}") }
                            .getOrDefault(false)
                    post { byHandle[key]?.let { refuseAfterDetails(it, v2, removed) } }
                    return
                }
                val at = clock()
                post {
                    val id = byHandle[key] ?: return@post
                    // Already handled by the STATE_UPDATE backstop.
                    if (id.value in detailsHandled) return@post
                    // The alert's handle dies with the alert: use the one add_torrent returned.
                    val h = handles[id.value] ?: return@post
                    onDetails(id, h, at)
                }
            }
            AlertType.TORRENT_FINISHED -> {
                val key = (alert as TorrentAlert<*>).handle().swig().id()
                post {
                    val id = byHandle[key] ?: return@post
                    val h = handles[id.value] ?: return@post
                    // libtorrent also calls a torrent "finished" while no file is wanted yet (before approveFiles), and
                    // that alert can arrive after the approval. Only a torrent with every real file wanted (padding
                    // files are never wanted) and every wanted byte on disk is really done. One this alert misses is
                    // reported by finishTick.
                    val wanted = allRealFilesWanted(h)
                    val complete = isComplete(h)
                    when {
                        !wanted || !complete -> log("finished alert ignored ${id.value} wanted=$wanted complete=$complete")
                        !finishes.report(id.value) -> log("finished alert, already reported ${id.value}")
                        else -> {
                            log("finished by alert ${id.value}")
                            sampleTimeline(id.value, h, clock())
                            timeline.finished(id.value, clock())
                            emit(TorrentEvent.Finished(id))
                        }
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
            else -> onTimelineAlert(alert)
        }
    }

    /**
     * The timeline's alerts (libtorrent's alert thread): only numbers, enum names and yes/no leave the alert; a
     * tracker URL, peer or listen address is never read into a string. Peer alerts can come by the hundred: each one
     * only counts.
     */
    private fun onTimelineAlert(alert: Alert<*>) {
        val at = clock()
        when (alert.type()) {
            AlertType.DHT_BOOTSTRAP ->
                post {
                    timeline.dhtBootstrapped(at)
                    askDhtNow(at)
                }
            AlertType.DHT_STATS -> {
                // One alert per DHT node (one per listen socket): only its family is read, never its address.
                val a = alert as DhtStatsAlert
                val nodes = a.routingTable().sumOf { it.numNodes() }
                val v6 = runCatching { a.localEndpoint().address().isV6 }.getOrDefault(false)
                post { timeline.dhtNodes(v6, nodes) }
            }
            AlertType.DHT_REPLY -> {
                val a = alert as DhtReplyAlert
                val key = a.handle().swig().id()
                val peers = a.numPeers()
                post { byHandle[key]?.let { timeline.peersFound(it.value, TorrentTimeline.PeerSource.DHT, peers, at) } }
            }
            AlertType.LSD_PEER -> {
                val key = (alert as TorrentAlert<*>).handle().swig().id()
                post { byHandle[key]?.let { timeline.peersFound(it.value, TorrentTimeline.PeerSource.LOCAL, 1, at) } }
            }
            AlertType.TRACKER_ANNOUNCE -> {
                val key = (alert as TorrentAlert<*>).handle().swig().id()
                post { byHandle[key]?.let { timeline.trackerAsked(it.value) } }
            }
            AlertType.TRACKER_REPLY -> {
                val a = alert as TrackerReplyAlert
                val key = a.handle().swig().id()
                val peers = a.numPeers()
                post { byHandle[key]?.let { timeline.trackerAnswered(it.value, peers, at) } }
            }
            AlertType.TRACKER_ERROR -> {
                val a = alert as TrackerErrorAlert
                val key = a.handle().swig().id()
                val kind = TorrentTimeline.trackerFailure(a.error().message)
                post { byHandle[key]?.let { timeline.trackerFailed(it.value, kind) } }
            }
            AlertType.PEER_CONNECT -> {
                val a = alert as PeerConnectAlert
                val key = a.handle().swig().id()
                val incoming = a.direction() == PeerConnectAlert.Direction.IN
                post {
                    val id = byHandle[key] ?: return@post
                    if (incoming) timeline.incomingConnection(id.value, at) else timeline.connectAttempt(id.value, at)
                }
            }
            AlertType.PEER_DISCONNECTED -> {
                val a = alert as PeerDisconnectedAlert
                val key = a.handle().swig().id()
                val failedConnect = a.operation() == Operation.CONNECT
                val kind = if (failedConnect) TorrentTimeline.connectFailure(a.error().message) else null
                post {
                    val id = byHandle[key] ?: return@post
                    if (kind != null) timeline.connectFailed(id.value, kind) else timeline.disconnected(id.value)
                }
            }
            AlertType.LISTEN_SUCCEEDED -> {
                val a = alert as ListenSucceededAlert
                val socket = a.socketType().name
                val v6 = a.address().isV6
                post { timeline.listen(ok = true, socket = socket, v6 = v6) }
            }
            AlertType.LISTEN_FAILED -> {
                val a = alert as ListenFailedAlert
                val socket = a.socketType().name
                val v6 = runCatching { a.address().isV6 }.getOrDefault(false)
                // The error number and the operation's enum name only, never the message.
                val error = runCatching { a.error().value }.getOrDefault(-1)
                val operation = runCatching { a.operation().name }.getOrDefault("")
                post { timeline.listen(ok = false, socket = socket, v6 = v6, error = error, operation = operation) }
            }
            AlertType.EXTERNAL_IP -> {
                val v6 = (alert as ExternalIpAlert).externalAddress().isV6
                post { timeline.externalAddress(v6) }
            }
            else -> Unit
        }
    }

    /** Once a second (engine thread): peer-list samples and DHT node counts the timeline asks for, then its tick. */
    private fun timelineTick() {
        val now = clock()
        for ((key, h) in handles) if (timeline.wantsSample(key, now)) sampleTimeline(key, h, now)
        if (timeline.wantsDhtNodes(now)) session?.postDhtStats()
        timeline.tick(now)
    }

    /**
     * Once a second (engine thread): the numbers the row shows while a torrent looks for peers (tor10-4). Counts only:
     * no tracker URL, address or name is read into a string. Publishes a new snapshot only when one of them changed.
     */
    private fun searchTick() {
        val now = clock()
        var changed = false
        for ((key, h) in handles) {
            val eid = EngineId(key)
            val snap = latest[eid] ?: continue
            val st = runCatching { h.status() }.getOrNull() ?: continue
            val running = !st.flags().and_(TorrentFlags.PAUSED).non_zero()
            // Checking files is no search: like a pause, it holds the clock at 0. The clock counts the seconds without a CONNECTED peer.
            val searching = running && snap.state != EngineState.CHECKING
            val connected = st.numPeers().coerceAtLeast(0)
            // A connected peer restarts the clock: losing the last one later counts the 60 seconds again.
            if (searching && connected == 0) searchStart.getOrPut(key) { now } else searchStart.remove(key)
            val found = maxOf(snap.peersFound, st.listPeers().coerceAtLeast(0) + connected)
            // Trackers only matter while no peer is known (the row names them then): skip the read otherwise.
            var total = snap.trackersTotal
            var answered = snap.trackersAnswered
            var failed = snap.trackersFailed
            if (searching && found == 0) {
                runCatching {
                    val list = h.trackers()
                    total = list.size
                    answered = list.count { it.isVerified }
                    // Asked, never answered, and every address it has failed.
                    failed = list.count { t ->
                        val ends = t.endpoints()
                        !t.isVerified && ends.isNotEmpty() && ends.all { e -> e.infohashV1().fails() > 0 }
                    }
                }
            }
            val next =
                snap.copy(
                    peersFound = found,
                    trackersAnswered = answered,
                    trackersFailed = failed,
                    trackersTotal = total,
                    dhtInUse = discoveryOf(kindOf(key)).dht,
                    searchSeconds = if (searching && connected == 0) ((now - (searchStart[key] ?: now)) / 1000).toInt().coerceIn(0, SEARCH_SECONDS_CAP) else 0,
                )
            if (next != snap) {
                latest[eid] = next
                changed = true
            }
        }
        if (changed) _snapshots.value = latest.toMap()
    }

    /**
     * Once a second (engine thread): every running torrent that knows no peer asks the DHT and its trackers again on
     * [PeerSearchRetry]'s schedule, and the DHT at once when the session's DHT node starts running.
     */
    private fun peerSearchTick() {
        val s = session ?: return
        val now = clock()
        val dhtRunning = s.isDhtRunning
        val cameUp = peerSearch.dhtCameUp(dhtRunning)
        for ((key, h) in handles) {
            val t = peerSearchTorrent(key, h, dhtRunning) ?: continue
            var calls = peerSearch.poll(key, now, t)
            if (cameUp && PeerSearchRetry.askDhtNow(t)) calls = calls + PeerSearchRetry.Call.DHT
            forcePeerSearch(key, h, calls, now)
        }
    }

    /** The DHT finished its bootstrap: every running torrent that may use it and knows no peer asks it now. */
    private fun askDhtNow(at: Long) {
        val dhtRunning = session?.isDhtRunning ?: return
        for ((key, h) in handles) {
            val t = peerSearchTorrent(key, h, dhtRunning) ?: continue
            if (PeerSearchRetry.askDhtNow(t)) forcePeerSearch(key, h, setOf(PeerSearchRetry.Call.DHT), at)
        }
    }

    /** What [PeerSearchRetry] needs to know about [h]; its trackers are read only for a running torrent with no peer. */
    private fun peerSearchTorrent(key: Long, h: TorrentHandle, dhtRunning: Boolean): PeerSearchRetry.Torrent? {
        val st = runCatching { h.status() }.getOrNull() ?: return null
        val running = !st.flags().and_(TorrentFlags.PAUSED).non_zero()
        val peersKnown = st.listPeers().coerceAtLeast(0) + st.numPeers().coerceAtLeast(0)
        val hasTrackers = running && peersKnown == 0 && runCatching { h.trackers().isNotEmpty() }.getOrDefault(false)
        return PeerSearchRetry.Torrent(
            running = running,
            peersKnown = peersKnown,
            discovery = discoveryOf(kindOf(key)),
            dhtRunning = dhtRunning,
            hasTrackers = hasTrackers,
        )
    }

    private fun forcePeerSearch(key: Long, h: TorrentHandle, calls: Set<PeerSearchRetry.Call>, at: Long) {
        if (calls.isEmpty()) return
        val dht = PeerSearchRetry.Call.DHT in calls
        val trackers = PeerSearchRetry.Call.TRACKERS in calls
        if (dht) h.forceDHTAnnounce()
        // Every tracker; libtorrent still keeps to each tracker's minimum announce interval.
        if (trackers) h.forceReannounce()
        timeline.forced(key, dht = dht, trackers = trackers, at = at)
    }

    /**
     * Once a second (engine thread): an approved, running torrent that libtorrent calls finished and that has every
     * wanted byte on disk is reported finished, once ([FinishReports.tick]). The net under the alert: a torrent whose
     * alert was ignored or lost does not stay "downloading" at 100% (tor17-1).
     */
    private suspend fun finishTick() {
        // A copy: emit may suspend, and a remove posted meanwhile changes [handles].
        for ((key, h) in handles.toList()) {
            val st = runCatching { h.status() }.getOrNull() ?: continue
            val state = st.state()
            val facts =
                TorrentFinish.TickFacts(
                    running = !st.flags().and_(TorrentFlags.PAUSED).non_zero(),
                    checking = state == TorrentStatus.State.CHECKING_FILES || state == TorrentStatus.State.CHECKING_RESUME_DATA,
                    finishedOrSeeding = state == TorrentStatus.State.FINISHED || state == TorrentStatus.State.SEEDING,
                    complete = runCatching { isComplete(h, st) }.getOrDefault(false),
                )
            if (!finishes.tick(key, facts)) continue
            val id = EngineId(key)
            log("finished by tick $key")
            sampleTimeline(key, h, clock())
            timeline.finished(key, clock())
            emit(TorrentEvent.Finished(id))
        }
    }

    /** Every file of [h] that is not a padding file is wanted ([TorrentFinish.allRealFilesWanted]). */
    private fun allRealFilesWanted(h: TorrentHandle): Boolean {
        val wanted = h.filePriorities().map { it != Priority.IGNORE }
        val fs = h.torrentFile()?.files()
        val pad = if (fs == null) emptyList() else (0 until fs.numFiles()).map { fs.padFileAt(it) }
        return TorrentFinish.allRealFilesWanted(wanted, pad)
    }

    /** Counts from the peer list of [h]; no address leaves this function. */
    private fun sampleTimeline(key: Long, h: TorrentHandle, at: Long) {
        val peers = runCatching { h.peerInfo() }.getOrNull() ?: return
        val connecting = peer_info.connecting.to_int()
        val notYet = connecting or peer_info.handshake.to_int()
        val pex = peer_info.pex.to_int()
        var connected = 0
        var connectingNow = 0
        var fromPex = 0
        for (p in peers) {
            val f = p.flags()
            when {
                f and connecting != 0 -> connectingNow++
                f and notYet != 0 -> Unit
                else -> {
                    connected++
                    if (p.source().toInt() and pex != 0) fromPex++
                }
            }
        }
        val known = runCatching { h.status().listPeers() }.getOrDefault(-1)
        timeline.sample(key, at, connected, connectingNow, fromPex, known)
    }

    private fun clock(): Long = System.nanoTime() / 1_000_000

    private fun errorKind(code: Int, isFile: Boolean): TorrentErrorKind =
        when {
            code == ENOSPC -> TorrentErrorKind.NO_SPACE
            isFile -> TorrentErrorKind.FILE_ERROR
            else -> TorrentErrorKind.ENGINE_ERROR
        }

    private fun TorrentSnapshot.withSearch(o: TorrentSnapshot) =
        copy(
            peersFound = maxOf(peersFound, o.peersFound),
            trackersAnswered = o.trackersAnswered,
            trackersFailed = o.trackersFailed,
            trackersTotal = o.trackersTotal,
            dhtInUse = o.dhtInUse,
            searchSeconds = o.searchSeconds,
        )

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
        /** The row tells "no peers found" at this many seconds; the count stops there. */
        const val SEARCH_SECONDS_CAP = 60
        const val RESUME_TIMEOUT_MS = 15_000L
        const val ENOSPC = 28
    }
}

/** A libtorrent setting Orbis makes, named as in libtorrent's settings_pack; [type]: the type of its value. */
internal enum class SessionSetting(val type: KClass<*>) {
    LISTEN_INTERFACES(String::class),
    ENABLE_UPNP(Boolean::class),
    ENABLE_NATPMP(Boolean::class),
    SSRF_MITIGATION(Boolean::class),
    APPLY_IP_FILTER_TO_TRACKERS(Boolean::class),
    ENABLE_DHT(Boolean::class),
    ENABLE_LSD(Boolean::class),
    MAX_METADATA_SIZE(Int::class),
    DOWNLOAD_RATE_LIMIT(Int::class),
    UPLOAD_RATE_LIMIT(Int::class),
    ACTIVE_DOWNLOADS(Int::class),
    ACTIVE_SEEDS(Int::class),
    STOP_TRACKER_TIMEOUT(Int::class),
    ANNOUNCE_TO_ALL_TRACKERS(Boolean::class),
    ANNOUNCE_TO_ALL_TIERS(Boolean::class),
}

private const val STOP_TRACKER_TIMEOUT_S = 2

/**
 * Every session setting Orbis makes, in the order it is set; libtorrent's defaults hold for the rest. [d]: the
 * session-wide DHT and local peers. Pure (no libtorrent class), so a test can read it.
 */
internal fun sessionSettings(s: EngineSettings, d: SessionDiscovery): List<Pair<SessionSetting, Any>> =
    buildList {
        // No user_agent, peer_fingerprint or handshake_client_version: libtorrent's own defaults go out, so Orbis
        // looks like any libtorrent client.
        add(SessionSetting.LISTEN_INTERFACES to "0.0.0.0:${s.listenPort},[::]:${s.listenPort}")
        add(SessionSetting.ENABLE_UPNP to false)
        add(SessionSetting.ENABLE_NATPMP to false)
        // libtorrent's default, stated so it cannot drift. It is no guard on its own: it only checks loopback, a
        // tracker there only by an "/announce" path prefix ("/announce/../x" passes), and a web seed only on IPv4
        // ([::1] is never checked); it also refuses web seed query strings. The guard is the session address filter
        // ([blockedRanges], set by Libtorrent4jEngine), which libtorrent consults first for peers, web seeds and
        // (with the next setting, also stated so it cannot drift) trackers.
        add(SessionSetting.SSRF_MITIGATION to true)
        add(SessionSetting.APPLY_IP_FILTER_TO_TRACKERS to true)
        add(SessionSetting.ENABLE_DHT to d.dht)
        add(SessionSetting.ENABLE_LSD to d.lsd)
        add(SessionSetting.MAX_METADATA_SIZE to TorrentLimits.MAX_METADATA_BYTES)
        add(SessionSetting.DOWNLOAD_RATE_LIMIT to s.downBps.coerceAtLeast(0))
        add(SessionSetting.UPLOAD_RATE_LIMIT to s.upBps.coerceAtLeast(0))
        add(SessionSetting.ACTIVE_DOWNLOADS to s.maxActive)
        if (s.stopSeeding) add(SessionSetting.ACTIVE_SEEDS to 0)
        add(SessionSetting.STOP_TRACKER_TIMEOUT to STOP_TRACKER_TIMEOUT_S)
        // Every tracker and every tier at once (tor10-2): a torrent that relies on its trackers is not held up while
        // dead ones ahead of the good one time out one after another.
        add(SessionSetting.ANNOUNCE_TO_ALL_TRACKERS to true)
        add(SessionSetting.ANNOUNCE_TO_ALL_TIERS to true)
    }

/**
 * The session's alert mask: [all] categories minus ip_block ([ipBlock]) and the [logs], which is libtorrent4j's own
 * mask without ip_block. With the address filter every blocked address would post a peer_blocked_alert (normal
 * priority, like metadata_received), so one tracker answer could fill the 2000-alert queue and push the others out.
 * No type the listener takes is in the ip_block category. Pure (category bits as numbers), so a test can read it.
 */
internal fun alertMask(all: Int, ipBlock: Int, logs: List<Int>): Int = logs.fold(all and ipBlock.inv()) { m, c -> m and c.inv() }

/** This device and "no address": never a peer, web seed or tracker. */
private val ALWAYS_V4 = listOf("0.0.0.0" to "0.255.255.255", "127.0.0.0" to "127.255.255.255")
private val ALWAYS_V6 = listOf("::" to "::", "::1" to "::1")

/** The home network and carrier NAT: blocked unless local peers are on (the set of libtorrent's aux::is_local). */
private val LOCAL_V4 =
    listOf(
        "10.0.0.0" to "10.255.255.255",
        "172.16.0.0" to "172.31.255.255",
        "192.168.0.0" to "192.168.255.255",
        "169.254.0.0" to "169.254.255.255",
        "100.64.0.0" to "100.127.255.255",
    )
private val LOCAL_V6 =
    listOf(
        "fe80::" to "febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        "fec0::" to "feff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        "fc00::" to "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
    )

/**
 * The address ranges of the session's address filter (first and last address as text), which libtorrent checks
 * before it connects to a peer or web seed and before an http tracker request. [lsd]: local peers on, so the home
 * network stays reachable. Pure (no libtorrent class), so a test can read it.
 *
 * Every IPv4 range is also given as IPv4-mapped IPv6 (::ffff:a.b.c.d): libtorrent's ip_filter::access sends any IPv6
 * address to its IPv6 table without unmapping it, and a dual-stack socket reaches the IPv4 address behind it.
 */
internal fun blockedRanges(lsd: Boolean): List<Pair<String, String>> {
    val v4 = if (lsd) ALWAYS_V4 else ALWAYS_V4 + LOCAL_V4
    val v6 = if (lsd) ALWAYS_V6 else ALWAYS_V6 + LOCAL_V6
    return v4 + v6 + v4.map { (first, last) -> "::ffff:$first" to "::ffff:$last" }
}
