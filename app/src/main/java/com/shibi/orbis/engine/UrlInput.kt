package com.shibi.orbis.engine

import java.net.URLEncoder
import java.util.Locale

private const val SEARCH_URL = "https://duckduckgo.com/?q="

// host (localhost, or dotted labels of Unicode letters/digits), optional :port, optional /path?query#fragment.
// "javascript:a.b" or "file:///a.html" must not match: the scheme-like prefix is not a valid host:port.
private const val LABEL = "[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]*[\\p{L}\\p{N}])?"
private val HOST_LIKE =
    Regex("^(?:localhost|$LABEL(?:\\.$LABEL)+)(?::\\d{1,5})?(?:[/?#].*)?$")

/**
 * Turns address-bar text into a URL to load, or null for blank input.
 * Only http(s) is ever returned as navigation; every other scheme-like input is searched for.
 * Input containing whitespace is never a navigation, even with an http(s) prefix.
 */
fun normalizeAddressInput(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val hasWhitespace = text.any { it.isWhitespace() }
    val lower = text.lowercase(Locale.ROOT)
    if (!hasWhitespace && (lower.startsWith("http://") || lower.startsWith("https://"))) return text
    if (!hasWhitespace && HOST_LIKE.matches(text)) return "https://$text"
    return SEARCH_URL + URLEncoder.encode(text, "UTF-8")
}
