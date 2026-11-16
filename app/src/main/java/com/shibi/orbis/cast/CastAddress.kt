package com.shibi.orbis.cast

import java.net.URI

/**
 * A page on the public internet must not make the TV fetch from the user's home network. Pure string work: no DNS
 * lookups, so a public name that resolves to a private address, a redirect, or a playlist segment is not seen here.
 */
internal fun castAddressAllowed(mediaUrl: String, pageUrl: String): Boolean {
    val mediaHost = hostOf(mediaUrl) ?: return false
    if (!isLocalNetworkHost(mediaHost)) return true
    val pageHost = hostOf(pageUrl) ?: return false
    return isLocalNetworkHost(pageHost)
}

private fun hostOf(url: String): String? =
    try {
        URI(url.trim()).host?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

/** True for a host that only means something inside a private, loopback, link-local, multicast or reserved range. */
internal fun isLocalNetworkHost(host: String): Boolean {
    var h = host.trim().lowercase()
    if (h.startsWith("[") && h.endsWith("]")) {
        h = h.substring(1, h.length - 1).substringBefore('%')
        return isLocalIpv6(h)
    }
    h = h.removeSuffix(".")
    if (h.isEmpty()) return false
    val parts = h.split('.')
    if (parts.all { isNumberPart(it) }) {
        val value = parseIpv4Parts(parts) ?: return true
        return isLocalIpv4(value)
    }
    if (h == "localhost" || h.endsWith(".localhost")) return true
    if (LOCAL_SUFFIXES.any { h.endsWith(it) }) return true
    return '.' !in h
}

private val LOCAL_SUFFIXES = listOf(".local", ".home.arpa", ".internal", ".lan")

private fun isNumberPart(part: String): Boolean {
    if (part.isEmpty()) return false
    if (part.startsWith("0x")) return part.drop(2).all { it in '0'..'9' || it in 'a'..'f' }
    return part.all { it in '0'..'9' }
}

/** WHATWG IPv4 parsing of already validated number parts. Null when a part is out of range or there are too many. */
private fun parseIpv4Parts(parts: List<String>): Long? {
    if (parts.size > 4) return null
    val numbers = ArrayList<Long>(parts.size)
    for (part in parts) {
        val n =
            when {
                part.startsWith("0x") -> if (part.length == 2) 0L else part.substring(2).toLongOrNull(16)
                part.length > 1 && part.startsWith("0") -> part.substring(1).toLongOrNull(8)
                else -> part.toLongOrNull(10)
            } ?: return null
        numbers += n
    }
    for (i in 0 until numbers.size - 1) if (numbers[i] > 255) return null
    val last = numbers.last()
    val limit = 1L shl (8 * (5 - numbers.size))
    if (last >= limit) return null
    var value = last
    for (i in 0 until numbers.size - 1) value += numbers[i] shl (8 * (3 - i))
    return value
}

private fun isLocalIpv4(v: Long): Boolean =
    (v and 0xFF000000L) == 0x00000000L ||
        (v and 0xFF000000L) == 0x0A000000L ||
        (v and 0xFFC00000L) == 0x64400000L ||
        (v and 0xFF000000L) == 0x7F000000L ||
        (v and 0xFFFF0000L) == 0xA9FE0000L ||
        (v and 0xFFF00000L) == 0xAC100000L ||
        (v and 0xFFFF0000L) == 0xC0A80000L ||
        (v and 0xFFFFFF00L) == 0xC0000000L ||
        (v and 0xFFFE0000L) == 0xC6120000L ||
        (v ushr 28) >= 14L

/** An unparsable literal is refused: no TV can fetch it, so nothing is lost. */
private fun isLocalIpv6(literal: String): Boolean {
    val g = parseIpv6(literal) ?: return true
    val high = (g[6] shl 16) or g[7]
    val zero5 = (0..4).all { g[it] == 0L }
    return when {
        zero5 && g[5] == 0L -> isLocalIpv4(high)
        zero5 && g[5] == 0xFFFFL -> isLocalIpv4(high)
        g[0] == 0x64L && g[1] == 0xFF9BL && (2..5).all { g[it] == 0L } -> isLocalIpv4(high)
        g[0] == 0x2002L -> isLocalIpv4((g[1] shl 16) or g[2])
        else -> (g[0] and 0xFFC0L) == 0xFE80L || (g[0] and 0xFE00L) == 0xFC00L || (g[0] and 0xFF00L) == 0xFF00L
    }
}

private fun parseIpv6(text: String): LongArray? {
    if (text.isEmpty() || text.count { it == ':' } < 2) return null
    val dbl = text.indexOf("::")
    if (dbl >= 0 && text.indexOf("::", dbl + 1) >= 0) return null
    fun groups(s: String): List<Long>? {
        if (s.isEmpty()) return emptyList()
        val out = ArrayList<Long>()
        val pieces = s.split(':')
        for ((i, piece) in pieces.withIndex()) {
            if (piece.contains('.')) {
                if (i != pieces.lastIndex) return null
                val octets = piece.split('.')
                if (octets.size != 4 || octets.any { it.isEmpty() || it.length > 3 || !it.all { c -> c in '0'..'9' } }) return null
                val n = octets.map { it.toInt() }
                if (n.any { it > 255 }) return null
                out += ((n[0] shl 8) or n[1]).toLong()
                out += ((n[2] shl 8) or n[3]).toLong()
            } else {
                if (piece.isEmpty() || piece.length > 4) return null
                out += piece.toLongOrNull(16) ?: return null
            }
        }
        return out
    }
    val result = LongArray(8)
    if (dbl < 0) {
        val all = groups(text) ?: return null
        if (all.size != 8) return null
        all.forEachIndexed { i, v -> result[i] = v }
    } else {
        val head = groups(text.substring(0, dbl)) ?: return null
        val tail = groups(text.substring(dbl + 2)) ?: return null
        if (head.size + tail.size > 7) return null
        head.forEachIndexed { i, v -> result[i] = v }
        tail.forEachIndexed { i, v -> result[8 - tail.size + i] = v }
    }
    return result
}
