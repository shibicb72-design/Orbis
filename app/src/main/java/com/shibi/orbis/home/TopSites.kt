package com.shibi.orbis.home

import com.shibi.orbis.engine.SearchEngine
import com.shibi.orbis.engine.hostOfUrl
import com.shibi.orbis.history.HistoryEntry

/** One tile on the start page. [host] is what the tile's letter and label come from. */
data class TopSite(val url: String, val title: String, val host: String, val pinned: Boolean = false)

/** A tile the user pinned: kept in this order, before the history-ranked ones. */
data class PinnedSite(val url: String, val title: String)

/** How many Top sites the start page shows (two rows of four). */
const val TOP_SITES_LIMIT = 8

/**
 * The start page's Top sites from history: one tile per site (host), the site's most visited page, ranked by visit count
 * and then by the latest visit. Search result pages are left out (a tile would re-run an old search). Pure and derived
 * from history only, so clearing history clears Top sites too, and private tabs (which record no history) never add any.
 */
fun topSites(
    history: List<HistoryEntry>,
    limit: Int = TOP_SITES_LIMIT,
    pinned: List<PinnedSite> = emptyList(),
    hiddenHosts: Set<String> = emptySet(),
    customTemplates: List<String> = emptyList(),
): List<TopSite> {
    val seenHosts = HashSet<String>()
    val out = ArrayList<TopSite>(limit)
    // Pinned first, in the user's order (they stay even when history is cleared).
    for (p in pinned) {
        if (out.size >= limit) break
        val host = hostOfUrl(p.url) ?: continue
        if (!seenHosts.add(host)) continue
        out += TopSite(p.url, p.title.ifBlank { host.removePrefix("www.") }, host, pinned = true)
    }
    val ranked = history.filterNot { isSearchResultPage(it.url, customTemplates) }
        .sortedWith(compareByDescending<HistoryEntry> { it.visits }.thenByDescending { it.visitedAt })
    for (entry in ranked) {
        if (out.size >= limit) break
        val host = hostOfUrl(entry.url) ?: continue
        if (host in hiddenHosts) continue
        if (!seenHosts.add(host)) continue
        out += TopSite(entry.url, entry.title.ifBlank { host.removePrefix("www.") }, host)
    }
    return out
}

/**
 * A results page of one of the built-in search engines, or of a custom engine given by its [customTemplates] (each holds
 * %s once). A template matches by the part before %s, and only when that part holds ? or a path longer than /,
 * so a template such as https://s.example/%s matches nothing and never hides a whole site.
 */
fun isSearchResultPage(url: String, customTemplates: List<String> = emptyList()): Boolean =
    SearchEngine.entries.any { url.startsWith(it.searchPrefix) } || customTemplates.any { matchesCustomTemplate(url, it) }

private fun matchesCustomTemplate(url: String, template: String): Boolean {
    val at = template.indexOf("%s")
    if (at < 0) return false
    val prefix = template.substring(0, at)
    val path = prefix.substringAfter("://", "").substringAfter('/', "")
    if (!prefix.contains('?') && path.isEmpty()) return false
    return url.startsWith(prefix)
}

/** The letter a site's tile shows: the first letter or digit of its host without "www.", or "?". */
fun tileLetter(host: String): String =
    host.removePrefix("www.").firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
