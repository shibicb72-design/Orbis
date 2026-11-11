package com.shibi.orbis.torrent

import java.net.URLDecoder
import java.util.Locale

/**
 * Tracker URLs, checked before the engine sees them. Pure and Android-free.
 *
 * WebSocket trackers (ws://, wss://) are dropped everywhere: they are libtorrent's WebTorrent path, which runs
 * libdatachannel's DTLS transport on the bundled OpenSSL (src/tracker_manager.cpp:310-311 in libtorrent 2.1.0). That
 * code stays out of reach at least until the self-built natives ship. Callers log only the count dropped.
 */
object TorrentTrackers {
    /** More than any real torrent lists; the rest are ignored. */
    const val MAX_TRACKERS = 100

    /** Longer URLs are ignored. */
    const val MAX_URL_CHARS = 2048

    fun isWebSocket(url: String): Boolean {
        val u = url.trimStart().lowercase(Locale.ROOT)
        return u.startsWith("ws://") || u.startsWith("wss://")
    }

    /** A magnet without its WebSocket `tr=` parameters, and how many went. */
    class StrippedMagnet(val uri: String, val dropped: Int) {
        override fun toString(): String = "StrippedMagnet(dropped=$dropped)"
    }

    /** Removes every `tr=` whose decoded value is a WebSocket URL. Everything else is kept as it was, in order. */
    fun stripMagnet(uri: String): StrippedMagnet {
        val q = uri.indexOf('?')
        if (q < 0) return StrippedMagnet(uri, 0)
        val parts = uri.substring(q + 1).split('&')
        val kept = ArrayList<String>(parts.size)
        var dropped = 0
        for (part in parts) {
            val eq = part.indexOf('=')
            if (eq > 0 && part.substring(0, eq).equals("tr", ignoreCase = true)) {
                val raw = part.substring(eq + 1)
                // An undecodable value is judged as written; libtorrent decodes it the same way or refuses it.
                val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                if (isWebSocket(decoded) || isWebSocket(raw)) {
                    dropped++
                    continue
                }
            }
            kept += part
        }
        if (dropped == 0) return StrippedMagnet(uri, 0)
        return StrippedMagnet(uri.substring(0, q + 1) + kept.joinToString("&"), dropped)
    }

    /** Tracker URLs with their tier, in the torrent's order. */
    class Announce(val urls: List<String>, val tiers: List<Int>, val dropped: Int) {
        override fun toString(): String = "Announce(trackers=${urls.size}, dropped=$dropped)"
    }

    val NONE = Announce(emptyList(), emptyList(), 0)

    /**
     * The trackers of a .torrent file: `announce-list` (BEP 12, one tier per inner list), or `announce` when that is
     * absent or empty. WebSocket URLs are dropped (counted), as are empty, over-long or non-text ones and anything
     * past [MAX_TRACKERS]. [NONE] when the file does not parse (the engine refuses such a file anyway).
     *
     * Needed because libtorrent4j's TorrentInfo.bdecode reads only the `info` dictionary: without this a .torrent's
     * own trackers never reach the session.
     */
    fun fromTorrentFile(bytes: ByteArray): Announce {
        if (bytes.isEmpty() || bytes.size > TorrentLimits.MAX_TORRENT_FILE_BYTES) return NONE
        val root = runCatching { TorrentPreview.Reader(bytes).readTop() }.getOrNull() as? TorrentPreview.Node.Dict ?: return NONE
        val urls = ArrayList<String>()
        val tiers = ArrayList<Int>()
        var dropped = 0
        fun take(node: TorrentPreview.Node?, tier: Int) {
            val s = node as? TorrentPreview.Node.Str ?: return
            if (s.length == 0 || s.length > MAX_URL_CHARS) return
            val url = s.text()
            if (isWebSocket(url)) {
                dropped++
                return
            }
            if (urls.size >= MAX_TRACKERS || url in urls) return
            urls += url
            tiers += tier
        }
        val list = root.map["announce-list"] as? TorrentPreview.Node.List
        list?.items?.forEachIndexed { tier, t -> (t as? TorrentPreview.Node.List)?.items?.forEach { take(it, tier) } }
        if (urls.isEmpty() && dropped == 0) take(root.map["announce"], 0)
        return Announce(urls, tiers, dropped)
    }
}
