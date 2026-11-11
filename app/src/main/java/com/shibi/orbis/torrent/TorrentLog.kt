package com.shibi.orbis.torrent

import com.shibi.orbis.debug.DebugLog

/**
 * The torrent lines of the local debug log (tag "Torrent"). Callers pass class names, enum names and numbers only:
 * never a magnet, info hash, tracker, file or torrent name, path or peer address (TorrentManagerTest checks this).
 */
object TorrentLog {
    const val TAG = "Torrent"

    fun sink(debugLog: DebugLog): (String) -> Unit = { debugLog.log(TAG, it) }
}
