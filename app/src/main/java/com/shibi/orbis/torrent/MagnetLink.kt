package com.shibi.orbis.torrent

import java.util.Locale

/**
 * A magnet link checked well enough to show in the confirm sheet. Pure and Android-free.
 *
 * [infoHash] is lowercase hex, 40 characters (v1 only: Orbis refuses v2 and hybrid links, see [Check.V2]). It is only ever compared and
 * shown nowhere: never logged, never persisted by intake, never put in a notification ([toString] leaves it out).
 */
class MagnetLink private constructor(
    val infoHash: String,
    /** The `dn` parameter, decoded and made safe ([TorrentPaths.magnetDisplayName]); null when absent or unusable. */
    val displayName: String?,
    val trackerCount: Int,
) {
    override fun toString(): String = "MagnetLink(trackers=$trackerCount)"

    /** What [check] makes of a link: usable (v1), refused as v2 or hybrid, or not a usable magnet at all. */
    sealed interface Check {
        class Ok(val link: MagnetLink) : Check

        /** Any `xt=urn:btmh:` (v2 or hybrid): refused until libtorrent >= 2.1.3 is bundled. */
        object V2 : Check

        object Invalid : Check
    }

    companion object {
        private val HEX40 = Regex("[0-9a-fA-F]{40}")
        private val BASE32 = Regex("[A-Za-z2-7]{32}")
        private const val BTIH = "urn:btih:"
        private const val BTMH = "urn:btmh:"

        /** The link if [check] accepts it; null for v2, hybrid and everything unusable. */
        fun parse(uri: String): MagnetLink? = (check(uri) as? Check.Ok)?.link

        /**
         * [Check.Invalid] unless [uri] is `magnet:?` (any case), at most [TorrentLimits.MAX_MAGNET_CHARS] long, free of
         * control characters and whitespace, and names its torrent with exactly one `xt=urn:btih:` (40 hex or 32
         * base32). A link with any `xt=urn:btmh:` (v2 only, or hybrid with a btih too) is [Check.V2], so the caller can
         * say so. Two btih, or an `xt` of any other form, is invalid.
         */
        fun check(uri: String): Check {
            if (uri.length > TorrentLimits.MAX_MAGNET_CHARS) return Check.Invalid
            if (!uri.startsWith("magnet:?", ignoreCase = true)) return Check.Invalid
            if (uri.any { it.isWhitespace() || it.isISOControl() }) return Check.Invalid
            var v1: String? = null
            var v2 = false
            var trackers = 0
            for (part in uri.substring("magnet:?".length).split('&')) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val key = baseKey(part.substring(0, eq).lowercase(Locale.ROOT))
                val value = part.substring(eq + 1)
                when (key) {
                    "tr" -> trackers++
                    "xt" -> {
                        when {
                            value.startsWith(BTIH, ignoreCase = true) -> {
                                if (v1 != null) return Check.Invalid
                                v1 = v1Hash(value.substring(BTIH.length)) ?: return Check.Invalid
                            }
                            value.startsWith(BTMH, ignoreCase = true) -> v2 = true
                            else -> return Check.Invalid
                        }
                    }
                }
            }
            if (v2) return Check.V2
            val hash = v1 ?: return Check.Invalid
            return Check.Ok(MagnetLink(hash, TorrentPaths.magnetDisplayName(uri), trackers))
        }

        /** libtorrent drops a ".<digits>" suffix from every key (`xt.1` is `xt`); anything else stays as it is. */
        private fun baseKey(key: String): String {
            val dot = key.lastIndexOf('.')
            if (dot <= 0 || dot == key.length - 1) return key
            return if (key.substring(dot + 1).all { it in '0'..'9' }) key.substring(0, dot) else key
        }

        private fun v1Hash(raw: String): String? =
            when {
                HEX40.matches(raw) -> raw.lowercase(Locale.ROOT)
                BASE32.matches(raw) -> base32ToHex(raw.uppercase(Locale.ROOT))
                else -> null
            }

        /** 32 base32 characters (RFC 4648, no padding) are exactly 160 bits: the 20-byte v1 hash. */
        private fun base32ToHex(s: String): String {
            val out = StringBuilder(40)
            var buffer = 0L
            var bits = 0
            for (c in s) {
                val v = if (c in 'A'..'Z') c - 'A' else c - '2' + 26
                buffer = (buffer shl 5) or v.toLong()
                bits += 5
                while (bits >= 8) {
                    bits -= 8
                    val b = ((buffer shr bits) and 0xFF).toInt()
                    out.append("%02x".format(b))
                }
                buffer = buffer and ((1L shl bits) - 1)
            }
            return out.toString()
        }
    }
}
