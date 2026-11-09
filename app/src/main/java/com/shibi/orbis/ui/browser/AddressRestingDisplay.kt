package com.shibi.orbis.ui.browser

import java.util.Locale

/**
 * How the resting (unfocused) address line draws its text. [text] is the host when [isHost] (drawn start-ellipsized, so
 * the end of the domain always shows), else the text unchanged. [insecurePrefix] is "http://" for a plain-http page,
 * drawn before the host and never cut.
 */
internal data class RestingAddress(val text: String, val isHost: Boolean, val insecurePrefix: String?)

/**
 * Anti-spoofing display for a web address at rest: the host (and port) only, without userinfo, path or query, like
 * Chrome and Firefox on Android. A long lookalike such as "accounts.google.com.evil.example" otherwise rested as
 * "http://accounts.googl…" with the real site cut off (seen on an API 35 emulator). Anything that is not an http(s)
 * URL with a host is returned unchanged. Edit mode and screen readers keep the full address ([addressBarRestingText]).
 */
internal fun addressRestingDisplay(resting: String): RestingAddress {
    val lower = resting.lowercase(Locale.ROOT)
    val http = lower.startsWith("http://")
    if (!http && !lower.startsWith("https://")) return RestingAddress(resting, isHost = false, insecurePrefix = null)
    val authority = resting.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' && it != '\\' }
    val hostPort = authority.substringAfterLast('@').lowercase(Locale.ROOT)
    if (hostPort.isEmpty()) return RestingAddress(resting, isHost = false, insecurePrefix = null)
    return RestingAddress(hostPort, isHost = true, insecurePrefix = if (http) "http://" else null)
}
