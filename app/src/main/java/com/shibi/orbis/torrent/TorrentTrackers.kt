package com.shibi.orbis.torrent

import java.net.URLDecoder
import java.util.Locale

/**
 * Tracker URLs, checked before the engine sees them. Pure and Android-free.
 *
 * Only http://, https:// and udp:// trackers get in ([isAllowed]). The point is to keep out WebSocket trackers, which
 * are libtorrent's WebTorrent path: libdatachannel peer connections, STUN and DTLS on the bundled OpenSSL
 * (src/tracker_manager.cpp:310-311 in libtorrent 2.1.0). libtorrent picks that path by the text before the first ':'
 * (tracker_manager.cpp:275), so "ws:x" counts as WebSocket just as "wss://x" does. An allowlist judged the same way
 * closes every spelling. libtorrent refuses every other scheme anyway (unsupported_url_protocol), so nothing that
 * worked is lost. Callers log only the count dropped.
 */
object TorrentTrackers {
    /** More than any real torrent lists; the rest are ignored. */
    const val MAX_TRACKERS = 100

    /** Longer URLs (in UTF-8 bytes) are ignored. */
    const val MAX_URL_BYTES = 2048

    private val ALLOWED_SCHEMES = setOf("http", "https", "udp")

    /**
     * The scheme, the text before the first ':' as libtorrent reads it, is exactly http, https or udp (any case), and
     * "://" follows. No control character, space or other whitespace anywhere.
     */
    fun isAllowed(url: String): Boolean {
        val colon = url.indexOf(':')
        if (colon <= 0) return false
        if (url.substring(0, colon).lowercase(Locale.ROOT) !in ALLOWED_SCHEMES) return false
        if (!url.startsWith("://", colon)) return false
        return url.none { it.isWhitespace() || it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
    }

    /** A magnet without the trackers [isAllowed] refuses, and how many went. */
    class StrippedMagnet(val uri: String, val dropped: Int) {
        override fun toString(): String = "StrippedMagnet(dropped=$dropped)"
    }

    /** `tr` or `tr.<digits>`, in any case: the keys libtorrent reads trackers from (src/magnet_uri.cpp:300-306). */
    private fun isTrackerKey(key: String): Boolean {
        val k = key.lowercase(Locale.ROOT)
        if (k == "tr") return true
        return k.length > 3 && k.startsWith("tr.") && k.substring(3).all { it in '0'..'9' }
    }

    /** Removes every tracker parameter [isAllowed] refuses. Everything else is kept as it was, in order. */
    fun stripMagnet(uri: String): StrippedMagnet {
        val q = uri.indexOf('?')
        if (q < 0) return StrippedMagnet(uri, 0)
        val parts = uri.substring(q + 1).split('&')
        val kept = ArrayList<String>(parts.size)
        var dropped = 0
        for (part in parts) {
            val eq = part.indexOf('=')
            if (eq > 0 && isTrackerKey(part.substring(0, eq))) {
                val raw = part.substring(eq + 1)
                // An undecodable value is judged as written.
                val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                if (!isAllowed(decoded)) {
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
     * absent or empty. URLs [isAllowed] refuses are dropped (counted), as are over-long or non-text ones and anything
     * past [MAX_TRACKERS]. Tiers are numbered densely from 0, counting only tiers that kept a URL (libtorrent stores a
     * tier in one byte). [NONE] when the file does not parse (the engine refuses such a file anyway).
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
        var tier = 0

        fun take(node: TorrentPreview.Node?): Boolean {
            val s = node as? TorrentPreview.Node.Str ?: return false
            if (s.length == 0 || s.length > MAX_URL_BYTES) return false
            val url = s.text()
            if (!isAllowed(url)) {
                dropped++
                return false
            }
            if (urls.size >= MAX_TRACKERS || url in urls) return false
            urls += url
            tiers += tier
            return true
        }
        val list = root.map["announce-list"] as? TorrentPreview.Node.List
        list?.items?.forEach { t ->
            var any = false
            (t as? TorrentPreview.Node.List)?.items?.forEach { if (take(it)) any = true }
            if (any) tier++
        }
        if (urls.isEmpty() && dropped == 0) {
            tier = 0
            take(root.map["announce"])
        }
        return Announce(urls, tiers, dropped)
    }
}
