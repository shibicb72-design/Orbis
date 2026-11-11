package com.shibi.orbis.torrent

import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Scripted [TorrentEngine] for JVM tests: records every call; the test plays libtorrent with emitMetadata/advance/finish/fail. */
class FakeTorrentEngine : TorrentEngine {
    class Added(val id: EngineId, val source: TorrentSource, val saveDir: File, val resumeData: ByteArray?, val isPrivate: Boolean)

    val added = ArrayList<Added>()

    /** The settings of every start call, in order. */
    val starts = ArrayList<EngineSettings>()

    /** What each known torrent uses right now, as the real engine applies it (through [TorrentDiscovery.of]). */
    val discovery = HashMap<EngineId, TorrentDiscovery>()

    /** Every per-torrent discovery change: at add and on each setDiscovery, in order. */
    val discoveryLog = ArrayList<Pair<EngineId, TorrentDiscovery>>()
    private var sessionDiscovery = TorrentDiscovery(dht = true, pex = true, lsd = false)
    private val privateIds = HashSet<EngineId>()
    val calls = ArrayList<String>()
    val running = HashSet<EngineId>()
    val removed = ArrayList<Pair<EngineId, Boolean>>()
    val renames = HashMap<EngineId, Map<Int, String>>()
    var started = false
        private set
    var startCount = 0
        private set

    /** What saveResumeData returns for a known torrent. */
    var resumeBytes: (EngineId) -> ByteArray? = { "resume-${it.value}".toByteArray() }

    private var next = 0L
    private val known = HashSet<EngineId>()

    private val _snapshots = MutableStateFlow<Map<EngineId, TorrentSnapshot>>(emptyMap())
    override val snapshots: StateFlow<Map<EngineId, TorrentSnapshot>> = _snapshots

    private val _events = MutableSharedFlow<TorrentEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<TorrentEvent> = _events

    override fun start(settings: EngineSettings) {
        calls += "start"
        starts += settings
        sessionDiscovery = TorrentDiscovery(settings.dht, settings.pex, settings.lsd)
        if (!started) startCount++
        started = true
    }

    override fun stop() {
        calls += "stop"
        started = false
        known.clear()
        privateIds.clear()
        discovery.clear()
        running.clear()
        _snapshots.value = emptyMap()
    }

    override fun add(source: TorrentSource, saveDir: File, resumeData: ByteArray?, isPrivate: Boolean): EngineId {
        check(started) { "add before start" }
        val id = EngineId(++next)
        added += Added(id, source, saveDir, resumeData, isPrivate)
        known += id
        if (isPrivate) privateIds += id
        applyDiscovery(id)
        calls += "add ${id.value}"
        return id
    }

    override fun pause(id: EngineId) {
        calls += "pause ${id.value}"
        running -= id
    }

    override fun resume(id: EngineId) {
        calls += "resume ${id.value}"
        if (id in known) running += id
    }

    override fun remove(id: EngineId, deleteFiles: Boolean) {
        calls += "remove ${id.value} $deleteFiles"
        removed += id to deleteFiles
        known -= id
        privateIds -= id
        discovery -= id
        running -= id
        _snapshots.update { it - id }
    }

    /** Engine ids whose file list the manager approved (only those may download). */
    val approved = HashSet<EngineId>()

    override fun approveFiles(id: EngineId, renames: Map<Int, String>) {
        calls += "approve ${id.value}"
        if (renames.isNotEmpty()) this.renames[id] = renames
        approved += id
        // The real engine reports a torrent that is already complete on disk when its files are approved.
        if (id in completeOnApproval) check(_events.tryEmit(TorrentEvent.Finished(id)))
    }

    /** Engine ids whose payload is already complete when approved (restored after the last piece). */
    val completeOnApproval = HashSet<EngineId>()

    override suspend fun saveResumeData(id: EngineId): ByteArray? {
        calls += "save ${id.value}"
        return if (id in known) resumeBytes(id) else null
    }

    /** What readFiles returns for any stored .torrent (the fake does not parse bencode). */
    var parsedFiles: TorrentFiles? = null

    override suspend fun readFiles(torrentFile: ByteArray): TorrentFiles? {
        calls += "readFiles"
        return parsedFiles
    }

    override fun setLimits(downBps: Int, upBps: Int) {
        calls += "limits"
    }

    override fun setDiscovery(dht: Boolean, pex: Boolean, lsd: Boolean) {
        calls += "discovery"
        sessionDiscovery = TorrentDiscovery(dht, pex, lsd)
        known.sortedBy { it.value }.forEach { applyDiscovery(it) }
    }

    private fun applyDiscovery(id: EngineId) {
        val s = sessionDiscovery
        val d = TorrentDiscovery.of(s.dht, s.pex, s.lsd, trackersOnly = id in privateIds)
        discovery[id] = d
        discoveryLog += id to d
    }

    override fun pauseAll() {
        calls += "pauseAll"
        running.clear()
    }

    override fun resumeAll() {
        calls += "resumeAll"
        running += known
    }

    // ---- script ----

    fun emitMetadata(id: EngineId, files: TorrentFiles, torrentFile: ByteArray? = "d4:infod4:name3:abcee".toByteArray()) {
        check(_events.tryEmit(TorrentEvent.MetadataReceived(id, files, torrentFile)))
    }

    fun advance(
        id: EngineId,
        done: Long,
        total: Long,
        state: EngineState = EngineState.DOWNLOADING,
        downBps: Long = 1000,
        peers: Int = 3,
        etaSeconds: Long? = null,
        upBps: Long = 0,
        ratio: Float = 0f,
    ) {
        _snapshots.update {
            it + (id to TorrentSnapshot(state, total, done, downBps, upBps, peers, 1, etaSeconds, ratio, hasMetadata = total > 0))
        }
    }

    fun finish(id: EngineId) {
        check(_events.tryEmit(TorrentEvent.Finished(id)))
    }

    fun fail(id: EngineId, kind: TorrentErrorKind) {
        check(_events.tryEmit(TorrentEvent.Error(id, kind)))
    }
}
