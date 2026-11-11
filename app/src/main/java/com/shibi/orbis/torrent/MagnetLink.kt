package com.shibi.orbis.torrent

import java.util.Locale

/**
 * A magnet link checked well enough to show in the confirm sheet. Pure and Android-free.
 *
 * [infoHash] is lowercase hex: 40 characters for a v1 torrent, 64 for a v2-only one. It is only ever compared and
 * shown nowhere: never logged, never persisted by intake, never put in a notification ([toString] leaves it out).
 */
class MagnetLink private constructor(
    val infoHash: String,
    /** The `dn` parameter, decoded and made safe ([TorrentPaths.magnetDisplayName]); null when absent or unusable. */
    val displayName: String?,
    val trackerCount: Int,
) {
    override fun toString(): String = "MagnetLink(trackers=$trackerCount)"

    companion object {
        private val HEX40 = Regex("[0-9a-fA-F]{40}")
        private val BASE32 = Regex("[A-Za-z2-7]{32}")
        private val HEX64 = Regex("[0-9a-fA-F]{64}")
        private const val BTIH = "urn:btih:"
        private const val BTMH = "urn:btmh:1220"

        /**
         * Null unless [uri] is `magnet:?` (any case), at most [TorrentLimits.MAX_MAGNET_CHARS] long, free of control
         * characters and whitespace, and names its torrent with exactly one `xt=urn:btih:` (40 hex or 32 base32) or
         * exactly one `xt=urn:btmh:1220` (64 hex). A hybrid link carrying one of each is accepted (v1 hash reported);
         * two of the same kind, or an `xt` of any other form, is not.
         */
        fun parse(uri: String): MagnetLink? {
            if (uri.length > TorrentLimits.MAX_MAGNET_CHARS) return null
            if (!uri.startsWith("magnet:?", ignoreCase = true)) return null
            if (uri.any { it.isWhitespace() || it.isISOControl() }) return null
            var v1: String? = null
            var v2: String? = null
            var trackers = 0
            for (part in uri.substring("magnet:?".length).split('&')) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val key = part.substring(0, eq).lowercase(Locale.ROOT)
                val value = part.substring(eq + 1)
                when (key) {
                    "tr" -> trackers++
                    "xt" -> {
                        when {
                            value.startsWith(BTIH, ignoreCase = true) -> {
                                if (v1 != null) return null
                                v1 = v1Hash(value.substring(BTIH.length)) ?: return null
                            }
                            value.startsWith(BTMH, ignoreCase = true) -> {
                                if (v2 != null) return null
                                val hex = value.substring(BTMH.length)
                                if (!HEX64.matches(hex)) return null
                                v2 = hex.lowercase(Locale.ROOT)
                            }
                            else -> return null
                        }
                    }
                }
            }
            val hash = v1 ?: v2 ?: return null
            return MagnetLink(hash, TorrentPaths.magnetDisplayName(uri), trackers)
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
