package com.shibi.orbis.torrent

/** Hard limits for torrents. Anything above them is refused before a byte is written. */
object TorrentLimits {
    const val MAX_FILES = 10_000
    const val MAX_PIECES = 1_000_000
    const val MAX_TOTAL_BYTES = 1L shl 40 // 1 TiB
    const val MAX_TORRENT_FILE_BYTES = 4 * 1024 * 1024
    const val MAX_METADATA_BYTES = 4 * 1024 * 1024
    const val MAX_MAGNET_CHARS = 16_384
    const val MAX_DEPTH = 32

    /** Space left free on the volume after the torrent is complete. */
    const val FREE_SPACE_RESERVE_BYTES = 200L * 1024 * 1024
    const val MAX_ACTIVE = 5
    const val MAX_ENTRIES = 200

    /** Resume data is written at most this often while a torrent downloads (and only when it moved). */
    const val CHECKPOINT_INTERVAL_MS = 60_000L
}
