package com.shibi.orbis.engine

/**
 * What [topLevelLoadVerdict] decided for one top-level load. The session maps each value to its own handling.
 *
 * - [WEB]: http(s). The site policy, tracking protection and "open in app" rules apply.
 * - [IMPORT_FILE]: `file:`. Only a document in the import cache may load ([importFileAllowed] decides).
 * - [EXTERNAL]: a scheme for another app. The tap rule in the session still applies.
 * - [PASS]: Gecko applies its own rules; nothing of ours to set.
 * - [DENY]: refused.
 */
enum class LoadVerdict { WEB, IMPORT_FILE, EXTERNAL, PASS, DENY }

/** Schemes a top-level load may use from a web page, a blank tab or an extension page without further checks. */
private val PASS_SCHEMES = setOf("about", "data", "blob", "javascript")

/** Schemes no subframe may load. `file:` subresources are stopped by [IMPORT_CONTENT_SECURITY_POLICY] instead. */
private val SUBFRAME_DENIED_SCHEMES = setOf("file", "content", "resource", "chrome", "view-source", "jar")

private const val EXTENSION_SCHEME = "moz-extension"

/**
 * Decides a top-level load of [target] while the tab shows [committed] (null for a tab that has shown nothing).
 * [appExtensionLoad] is true only for the load Orbis itself started for an extension's own page.
 *
 * An imported document may go to the web (the auto-navigation rule runs before this), to `about:blank`, to its own
 * `file:` cache, or to another app. It may not go to `content:`, `resource:`, `data:`, `blob:`, `javascript:`,
 * `moz-extension:` or any other scheme Gecko loads itself.
 */
fun topLevelLoadVerdict(target: String, committed: String?, appExtensionLoad: Boolean): LoadVerdict {
    val s = schemeOf(target) ?: return LoadVerdict.DENY
    when (s) {
        "http", "https" -> return LoadVerdict.WEB
        "file" -> return LoadVerdict.IMPORT_FILE
    }
    if (committed != null && isImportLoadUrl(committed)) {
        return when {
            target == "about:blank" -> LoadVerdict.PASS
            s !in ENGINE_SCHEMES -> LoadVerdict.EXTERNAL
            else -> LoadVerdict.DENY
        }
    }
    return when {
        s in PASS_SCHEMES -> LoadVerdict.PASS
        s == EXTENSION_SCHEME ->
            if (appExtensionLoad || sameExtension(target, committed)) LoadVerdict.PASS else LoadVerdict.DENY
        s in ENGINE_SCHEMES -> LoadVerdict.DENY
        else -> LoadVerdict.EXTERNAL
    }
}

/**
 * Whether a subframe may load [url]. `file:`, `content:`, `resource:`, `chrome:`, `view-source:` and `jar:` are
 * refused; everything else is Gecko's call.
 *
 * This is the whole of what GeckoView can gate below the top-level document. `<img>`, `<script>` and stylesheets
 * never arrive here (no such callback on `NavigationDelegate` or `ContentDelegate` in GeckoView 160), and bug
 * 1684947 means Gecko will fetch a `file:` subresource from the app data directory. Those are stopped by
 * [IMPORT_CONTENT_SECURITY_POLICY], which is written into the imported HTML before it is shown.
 * XHTML is not imported: a late `head`, a non-XHTML default namespace, or `<?xml-stylesheet?>` can bypass a meta.
 */
fun subframeLoadAllowed(url: String): Boolean = schemeOf(url) !in SUBFRAME_DENIED_SCHEMES

/** The scheme of [url] for a log line: a member of [ENGINE_SCHEMES], or `other`. Never the URL itself. */
fun loggableScheme(url: String): String = schemeOf(url)?.takeIf { it in ENGINE_SCHEMES } ?: "other"

/** True when [committed] is a `moz-extension:` page of the same extension (host) as [target]. */
private fun sameExtension(target: String, committed: String?): Boolean {
    if (committed == null || schemeOf(committed) != EXTENSION_SCHEME) return false
    val a = extensionHost(target) ?: return false
    val b = extensionHost(committed) ?: return false
    return a.equals(b, ignoreCase = true)
}

/** The host of a `moz-extension://host/...` URL, or null when it has no `//` part or an empty host. */
private fun extensionHost(url: String): String? {
    val colon = url.indexOf(':')
    if (colon < 0 || !url.startsWith("//", colon + 1)) return null
    val start = colon + 3
    var end = start
    while (end < url.length && url[end] != '/' && url[end] != '?' && url[end] != '#') end++
    return url.substring(start, end).takeIf { it.isNotEmpty() }
}
