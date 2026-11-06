package com.shibi.orbis.engine

import java.util.Locale

/** Longest URL accepted from another app. Longer data is not a link a person tapped. */
private const val MAX_EXTERNAL_URL_LENGTH = 16_384

private const val ACTION_VIEW = "android.intent.action.VIEW"

fun isWebUrl(url: String): Boolean =
    url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

/**
 * The URL an incoming intent asks us to open, or null. Pure so it can be unit tested.
 * Only ACTION_VIEW, only http(s), no whitespace and a non-empty host; everything else (intent:, file:, content:,
 * javascript:, data:, other apps' schemes) is refused. Callers must pass `intent.dataString` and never extras.
 */
fun externalUrlFrom(action: String?, dataString: String?): String? {
    if (action != ACTION_VIEW) return null
    val data = dataString ?: return null
    if (data.isEmpty() || data.length > MAX_EXTERNAL_URL_LENGTH) return null
    if (data.any { it.isWhitespace() || it.isISOControl() }) return null
    if (!isWebUrl(data)) return null
    if (hostOfUrl(data) == null) return null
    return data
}

/**
 * Host of an http(s)-style URL, lower case, or null. Hand parsed rather than java.net.URI, which rejects many URLs
 * browsers accept. Userinfo is stripped so `https://good.example@evil.example/` yields `evil.example`;
 * a backslash ends the authority like a slash does.
 */
fun hostOfUrl(url: String): String? {
    val afterScheme = url.substringAfter("://", "")
    if (afterScheme.isEmpty()) return null
    val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' && it != '\\' }
    val hostPort = authority.substringAfterLast('@')
    val host =
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            if (end < 0) return null else hostPort.substring(0, end + 1)
        } else {
            hostPort.substringBefore(':')
        }
    return host.lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }
}
