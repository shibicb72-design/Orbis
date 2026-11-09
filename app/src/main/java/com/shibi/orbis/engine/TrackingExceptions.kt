package com.shibi.orbis.engine

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Sites where the user turned tracking protection off. The engine's own permission record (PERMISSION_TRACKING =
 * ALLOW) is the stored truth; this in-memory copy lets a session decide BEFORE a navigation starts whether tracking
 * protection applies, because sessions force it (useTrackingProtection) and a late switch would let the first requests
 * of the next site through, or block the excepted site's. Private exceptions are kept apart and last only for the
 * private session.
 */
class TrackingExceptions {
    private val normal: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val private: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun isExcepted(url: String?, isPrivate: Boolean): Boolean {
        val origin = trackingOrigin(url) ?: return false
        return origin in (if (isPrivate) private else normal)
    }

    fun set(url: String?, isPrivate: Boolean, excepted: Boolean) {
        val origin = trackingOrigin(url) ?: return
        val target = if (isPrivate) private else normal
        if (excepted) target += origin else target -= origin
    }

    /** Replaces the normal set with what the engine has stored (at startup). */
    fun loadNormal(urls: Collection<String>) {
        normal.clear()
        urls.mapNotNullTo(normal) { trackingOrigin(it) }
    }

    /** The private session ended: its exceptions end with it. */
    fun clearPrivate() = private.clear()
}

/** "scheme://host[:port]" for an http(s) address (lowercase, default port dropped), else null. */
fun trackingOrigin(url: String?): String? {
    if (url == null) return null
    val scheme = url.substringBefore("://", "").lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return null
    val authority = url.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
    if (authority.isEmpty()) return null
    val lower = authority.lowercase(Locale.ROOT)
    val defaultPort = if (scheme == "http") ":80" else ":443"
    return "$scheme://" + lower.removeSuffix(defaultPort)
}
