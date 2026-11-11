package com.shibi.orbis.torrent

enum class TorrentStatus {
    FETCHING_METADATA,
    DOWNLOADING,
    PAUSED,
    WAITING_FOR_WIFI,
    CHECKING,
    SEEDING,
    PUBLISHING,
    COMPLETED,
    FAILED,
    REMOVED,
    ;

    /** The torrent wants the engine (it runs, or runs as soon as the network or a slot allows). */
    val wantsToRun: Boolean get() = this == FETCHING_METADATA || this == DOWNLOADING || this == CHECKING || this == SEEDING

    /** Shown in the ongoing notification and keeps the foreground service up. */
    val isActive: Boolean get() = wantsToRun || this == PUBLISHING
}

/** Why a torrent is PAUSED: only [QUEUED] ones resume by themselves (when one of the active slots frees up). */
enum class PauseReason { USER, QUEUED, TIMEOUT }

/**
 * One row of the torrent list. Holds no magnet, info hash or tracker. Persisted fields are the ones in
 * [TorrentCodec]; the live ones (speeds, peers) come from the engine's snapshots and are never written.
 */
data class TorrentEntry(
    /** 16 random bytes in hex, never the info hash. */
    val id: String,
    val displayName: String,
    /** -1 until the metadata is known. */
    val totalBytes: Long,
    val doneBytes: Long,
    val status: TorrentStatus,
    val addedAt: Long,
    /** Absolute path of the private payload directory (getExternalFilesDir("torrents")/<id>). */
    val saveDir: String,
    /** Where the finished files went: "Download/Orbis/<name>" in the public Download folder. */
    val publishedPath: String? = null,
    /** Started from a private tab: never written to disk (no index entry, no resume data) and dropped with private browsing. */
    val isPrivate: Boolean = false,
    val errorKind: TorrentErrorKind? = null,
    val pauseReason: PauseReason? = null,
    val hasMetadata: Boolean = false,
    // ---- live, not persisted ----
    val downBps: Long = 0,
    val upBps: Long = 0,
    val peers: Int = 0,
    val seeds: Int = 0,
    val etaSeconds: Long? = null,
) {
    // Deliberately no name: a torrent name must not end up in a log line.
    override fun toString(): String = "TorrentEntry(status=$status, private=$isPrivate)"
}

/** User choices for torrents. Settings UI comes later; the defaults are the owner's. */
data class TorrentSettings(
    val wifiOnly: Boolean = true,
    val stopSeeding: Boolean = true,
    val dht: Boolean = true,
    val pex: Boolean = true,
    val lsd: Boolean = true,
    val downBps: Int = 0,
    val upBps: Int = 0,
) {
    fun engine(): EngineSettings =
        EngineSettings(dht = dht, pex = pex, lsd = lsd, downBps = downBps, upBps = upBps, stopSeeding = stopSeeding)
}
