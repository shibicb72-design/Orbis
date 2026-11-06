package com.shibi.orbis.engine

/** Longest URL that is stored (bookmarks, history) or imported. */
const val MAX_STORED_URL_LENGTH = 4096

/** Longest title that is stored. */
const val MAX_STORED_TITLE_LENGTH = 512

/** Bidirectional formatting characters: they can make a URL or title read as a different one. */
private fun isBidiFormatting(c: Char): Boolean =
    c == '\u200E' || c == '\u200F' || c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069'

/**
 * True if [url] may be saved as a bookmark or history row or accepted from an import: http(s), at most
 * [MAX_STORED_URL_LENGTH] chars, a non-empty host, no whitespace, control or bidi characters, and no userinfo
 * (`https://good.example@evil.example/` shows the wrong site, so it is refused outright).
 */
fun isStorableWebUrl(url: String): Boolean {
    if (url.isEmpty() || url.length > MAX_STORED_URL_LENGTH) return false
    if (!isWebUrl(url)) return false
    if (url.any { it.isWhitespace() || it.isISOControl() || isBidiFormatting(it) }) return false
    if (hostOfUrl(url).isNullOrEmpty()) return false
    val authority = url.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' && it != '\\' }
    return '@' !in authority
}

/**
 * A page title made safe to store and show: control and bidi characters removed, every run of white space (newlines,
 * tabs included) becomes one space, trimmed, capped at [MAX_STORED_TITLE_LENGTH] without cutting a surrogate pair.
 */
fun cleanStoredTitle(raw: String?): String {
    if (raw.isNullOrEmpty()) return ""
    val sb = StringBuilder(minOf(raw.length, MAX_STORED_TITLE_LENGTH + 2))
    var pendingSpace = false
    for (c in raw) {
        when {
            c.isWhitespace() -> pendingSpace = true
            c.isISOControl() || isBidiFormatting(c) -> Unit
            else -> {
                if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                pendingSpace = false
                sb.append(c)
            }
        }
        // Stop early on a huge title; the cap below trims the exact length.
        if (sb.length > MAX_STORED_TITLE_LENGTH + 1) break
    }
    if (sb.length <= MAX_STORED_TITLE_LENGTH) return sb.toString()
    var end = MAX_STORED_TITLE_LENGTH
    if (Character.isHighSurrogate(sb[end - 1]) && Character.isLowSurrogate(sb[end])) end--
    return sb.substring(0, end).trimEnd()
}

/**
 * The URL typed into the bookmark editor, or null if it is not storable. Surrounding spaces are trimmed and a missing
 * scheme means https. Scheme-like input that is not host:port (`javascript:alert(1)`, `data:text/html,x`) is refused
 * instead of being turned into a bogus https host.
 */
fun parseEditedBookmarkUrl(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val candidate =
        if (text.contains("://")) {
            text
        } else {
            if (hasNonPortColonPrefix(text)) return null
            "https://$text"
        }
    return candidate.takeIf { isStorableWebUrl(it) }
}

/** True for `scheme:rest` where rest (up to a path, query or fragment) is not a port number. */
private fun hasNonPortColonPrefix(text: String): Boolean {
    if (text.startsWith("[")) return false // bracketed IPv6 host
    val end = text.indexOfFirst { it == '/' || it == '?' || it == '#' || it == '\\' }.let { if (it < 0) text.length else it }
    val authority = text.substring(0, end)
    val colon = authority.indexOf(':')
    if (colon < 0) return false
    val afterColon = authority.substring(colon + 1)
    return afterColon.isEmpty() || !afterColon.all { it in '0'..'9' }
}
