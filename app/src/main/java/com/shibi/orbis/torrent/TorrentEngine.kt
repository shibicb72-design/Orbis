package com.shibi.orbis.torrent

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The torrent session behind a thin interface: no Android and no libtorrent types, so [TorrentManager] runs on the JVM
 * with a fake. Only [Libtorrent4jEngine] implements it for real (and only it may import org.libtorrent4j).
 *
 * Every call returns at once and never blocks on native code: the real engine posts the work onto its own thread.
 * Calls are applied in the order they were made. An id the engine no longer knows is ignored.
 */
interface TorrentEngine {
    /** Starts the session (loads the native library the first time). Calling it while started only applies [settings]. */
    fun start(settings: EngineSettings)

    /** Saves the session state and ends the session; every [EngineId] becomes unknown. */
    fun stop()

    /**
     * Adds a torrent saving into [saveDir]. Every file starts at "don't download", so libtorrent creates no file, folder
     * or symlink for it. A torrent with metadata (a .torrent, or resume data that carries it) is added paused and
     * announced with [TorrentEvent.MetadataReceived]; a magnet without metadata runs to fetch it and is paused when it
     * arrives. Nothing is written until the caller has checked the file list and called [approveFiles] (and [resume]).
     * [isPrivate]: started from a private tab; it uses its trackers only (see [TorrentDiscovery.of]), and while one
     * is in the session no local peer discovery runs at all (nothing announced, nothing answered). WebSocket trackers
     * are dropped from every source ([TorrentTrackers]).
     */
    fun add(source: TorrentSource, saveDir: java.io.File, resumeData: ByteArray?, isPrivate: Boolean): EngineId

    fun pause(id: EngineId)

    fun resume(id: EngineId)

    fun remove(id: EngineId, deleteFiles: Boolean)

    /**
     * The file list passed the caller's checks: renames file index -> relative path (below the save directory, '/'
     * separated), then lets every file download. Until this call no file is wanted.
     */
    fun approveFiles(id: EngineId, renames: Map<Int, String>)

    /** Flushes what was written and returns the resume data; null when the torrent is unknown or the save failed. */
    suspend fun saveResumeData(id: EngineId): ByteArray?

    /**
     * The file list of a stored .torrent (as [TorrentEvent.MetadataReceived.torrentFile] gave it), without adding it.
     * Null when it does not parse. Works with the session stopped.
     */
    suspend fun readFiles(torrentFile: ByteArray): TorrentFiles?

    /** Bytes per second; 0 is unlimited. */
    fun setLimits(downBps: Int, upBps: Int)

    /** Applies to every torrent, through [TorrentDiscovery.of]: a private one stays on trackers only. */
    fun setDiscovery(dht: Boolean, pex: Boolean, lsd: Boolean)

    fun pauseAll()

    fun resumeAll()

    /** About once a second while the session runs; empty when it does not. */
    val snapshots: StateFlow<Map<EngineId, TorrentSnapshot>>

    val events: SharedFlow<TorrentEvent>
}

/** The engine's handle on one torrent, valid for one session only. Never the info hash. */
@JvmInline
value class EngineId(val value: Long)

data class EngineSettings(
    /** TCP and uTP, IPv4 and IPv6; random per install (TorrentSettingsStore.listenPort), never 6881. */
    val listenPort: Int,
    val dht: Boolean = true,
    val pex: Boolean = true,
    val lsd: Boolean = false,
    val downBps: Int = 0,
    val upBps: Int = 0,
    /** No seeding at all: a finished torrent is removed from the session by the manager. */
    val stopSeeding: Boolean = true,
    val maxActive: Int = TorrentLimits.MAX_ACTIVE,
)

/** Which peer sources one torrent uses. */
data class TorrentDiscovery(val dht: Boolean, val pex: Boolean, val lsd: Boolean) {
    companion object {
        /** Trackers only: no DHT, no peer exchange, no local peers. */
        val TRACKERS_ONLY = TorrentDiscovery(dht = false, pex = false, lsd = false)

        /**
         * The settings for a normal torrent. A torrent from a private tab uses its trackers only: DHT would announce
         * its info hash to thousands of strangers, peer exchange tells peers which other swarms' peers we know, and
         * local peer discovery announces it to the local network. A private-tab magnet without a tracker therefore
         * finds no peers; that is the price of keeping a private download out of the DHT.
         * [trackersOnly] is also set by the engine for a BEP 27 private torrent (private=1 in its info dictionary).
         */
        fun of(dht: Boolean, pex: Boolean, lsd: Boolean, trackersOnly: Boolean): TorrentDiscovery =
            if (trackersOnly) TRACKERS_ONLY else TorrentDiscovery(dht, pex, lsd)
    }
}

/** What the whole session runs, beside each torrent's own [TorrentDiscovery]. */
data class SessionDiscovery(val dht: Boolean, val lsd: Boolean) {
    companion object {
        /**
         * Local peer discovery only while no private-tab torrent is in the session: libtorrent adds a peer announced
         * on the local network to any torrent that is not BEP 27 private (session_impl.cpp, on_lsd_peer), whatever
         * the torrent's DISABLE_LSD flag, and then connects to it. The DHT node not while every torrent in the
         * session is from a private tab: none of them uses it, and running it tells strangers that this address runs
         * a torrent client.
         */
        fun of(dht: Boolean, lsd: Boolean, privateTorrents: Int, torrents: Int): SessionDiscovery =
            SessionDiscovery(dht = dht && (privateTorrents == 0 || torrents > privateTorrents), lsd = lsd && privateTorrents == 0)
    }
}

sealed interface TorrentSource {
    class Magnet(val uri: String) : TorrentSource {
        override fun toString(): String = "Magnet"
    }

    class TorrentFile(val bytes: ByteArray) : TorrentSource {
        override fun toString(): String = "TorrentFile(${bytes.size})"
    }

    /** Everything comes from the resume data passed with it (a magnet restored before its metadata arrived). */
    object FromResumeData : TorrentSource {
        override fun toString(): String = "FromResumeData"
    }
}

class TorrentFileEntry(
    /** As the torrent names it, before any sanitising, top folder included for a multi-file torrent. */
    val pathSegments: List<String>,
    val size: Long,
    val isSymlink: Boolean = false,
    val isPadFile: Boolean = false,
) {
    override fun toString(): String = "TorrentFileEntry(size=$size)"
}

class TorrentFiles(
    val name: String,
    val totalBytes: Long,
    val pieceCount: Int,
    val files: List<TorrentFileEntry>,
) {
    override fun toString(): String = "TorrentFiles(files=${files.size}, pieces=$pieceCount)"
}

enum class EngineState { CHECKING, FETCHING_METADATA, DOWNLOADING, FINISHED, SEEDING, PAUSED }

data class TorrentSnapshot(
    val state: EngineState,
    val totalBytes: Long,
    val doneBytes: Long,
    val downBps: Long,
    val upBps: Long,
    val peers: Int,
    val seeds: Int,
    val etaSeconds: Long?,
    val ratio: Float,
    val hasMetadata: Boolean,
)

enum class TorrentErrorKind {
    INVALID_SOURCE,
    UNSAFE_METADATA,
    NO_SPACE,
    FILE_ERROR,
    ENGINE_ERROR,
    MISSING_DATA,
    PUBLISH_FAILED,
}

sealed interface TorrentEvent {
    val id: EngineId

    /** [torrentFile] is the bencoded metadata, kept so the torrent can be restored without fetching it again. */
    class MetadataReceived(override val id: EngineId, val files: TorrentFiles, val torrentFile: ByteArray?) : TorrentEvent {
        override fun toString(): String = "MetadataReceived"
    }

    class Finished(override val id: EngineId) : TorrentEvent {
        override fun toString(): String = "Finished"
    }

    class Error(override val id: EngineId, val kind: TorrentErrorKind) : TorrentEvent {
        override fun toString(): String = "Error(${kind.name})"
    }
}
