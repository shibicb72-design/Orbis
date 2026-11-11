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
    /**
     * A finished private torrent kept inside Orbis (absolute path below [TorrentDirs.keepRoot]) instead of being
     * published. Memory only, like every private entry; deleted when private browsing ends unless the user saved it.
     */
    val keptDir: String? = null,
    val downBps: Long = 0,
    val upBps: Long = 0,
    val peers: Int = 0,
    val seeds: Int = 0,
    val etaSeconds: Long? = null,
    /** Uploaded divided by downloaded, over the torrent's whole life (libtorrent's all-time counters). */
    val ratio: Float = 0f,
) {
    /** Every byte is on disk (both fields are persisted, so this holds after a restart too). */
    val isFinished: Boolean get() = hasMetadata && totalBytes > 0 && doneBytes >= totalBytes

    /**
     * Finished but not saved to Download/Orbis yet: seeding, or a finished torrent that is paused (by the user or
     * Android) or waiting for Wi-Fi. Removing it without deleting ("Save and remove") saves it first; the end of
     * private browsing deletes a private one (OD-3).
     */
    val finishedUnsaved: Boolean
        get() = status !in UNSAVED_EXCLUDED && (status == TorrentStatus.SEEDING || isFinished)

    // Deliberately no name: a torrent name must not end up in a log line.
    override fun toString(): String = "TorrentEntry(status=$status, private=$isPrivate)"

    private companion object {
        /** Already saved, being saved, or failed (Retry handles a failed copy). */
        val UNSAVED_EXCLUDED = setOf(TorrentStatus.PUBLISHING, TorrentStatus.COMPLETED, TorrentStatus.FAILED, TorrentStatus.REMOVED)
    }
}

/** User choices for torrents (Settings > Torrents, stored by [TorrentSettingsStore]). The defaults are the owner's. */
data class TorrentSettings(
    val wifiOnly: Boolean = true,
    val stopSeeding: Boolean = true,
    val dht: Boolean = true,
    val pex: Boolean = true,
    /** Off by default (OD-2): it tells every device on the network what is being downloaded. */
    val lsd: Boolean = false,
    /** Bytes per second; 0 is no limit. */
    val downBps: Int = 0,
    val upBps: Int = 0,
    /** Only while [stopSeeding] is off: a seeding torrent stops (and is saved) at this ratio. Null: no limit. */
    val seedRatioLimit: Float? = null,
) {
    fun engine(listenPort: Int): EngineSettings =
        EngineSettings(listenPort = listenPort, dht = dht, pex = pex, lsd = lsd, downBps = downBps, upBps = upBps, stopSeeding = stopSeeding)

    /** Caps at 0 or more; a ratio limit of null or 0.1..100 with one decimal (0 or less means no limit). */
    fun clamped(): TorrentSettings =
        copy(downBps = downBps.coerceAtLeast(0), upBps = upBps.coerceAtLeast(0), seedRatioLimit = clampRatio(seedRatioLimit))

    companion object {
        const val MIN_RATIO = 0.1f
        const val MAX_RATIO = 100f

        /** Largest speed cap the settings accept, in KB/s. */
        const val MAX_KBPS = 1_000_000

        /** One KB as the Downloads list shows speeds (Android formats sizes in powers of 1000). */
        private const val BYTES_PER_KB = 1000L

        fun clampRatio(ratio: Float?): Float? {
            if (ratio == null || ratio.isNaN() || ratio <= 0f) return null
            val rounded = Math.round(ratio.coerceAtMost(MAX_RATIO) * 10f) / 10f
            return rounded.coerceIn(MIN_RATIO, MAX_RATIO)
        }

        fun kbpsToBps(kbps: Int): Int = (kbps.toLong() * BYTES_PER_KB).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

        fun bpsToKbps(bps: Int): Int = (bps.coerceAtLeast(0) / BYTES_PER_KB).toInt()
    }
}
