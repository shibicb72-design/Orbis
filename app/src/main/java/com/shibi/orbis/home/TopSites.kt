package com.shibi.orbis.home

import com.shibi.orbis.engine.SearchEngine
import com.shibi.orbis.engine.hostOfUrl
import com.shibi.orbis.history.HistoryEntry

/** One tile on the start page. [host] is what the tile's letter and label come from. */
data class TopSite(val url: String, val title: String, val host: String)

/** How many Top sites the start page shows (two rows of four). */
const val TOP_SITES_LIMIT = 8

/**
 * The start page's Top sites from history: one tile per site (host), the site's most visited page, ranked by visit count
 * and then by the latest visit. Search result pages are left out (a tile would re-run an old search). Pure and derived
 * from history only, so clearing history clears Top sites too, and private tabs (which record no history) never add any.
 */
fun topSites(history: List<HistoryEntry>, limit: Int = TOP_SITES_LIMIT): List<TopSite> {
    val ranked = history.filterNot { isSearchResultPage(it.url) }
        .sortedWith(compareByDescending<HistoryEntry> { it.visits }.thenByDescending { it.visitedAt })
    val seenHosts = HashSet<String>()
    val out = ArrayList<TopSite>(limit)
    for (entry in ranked) {
        if (out.size >= limit) break
        val host = hostOfUrl(entry.url) ?: continue
        if (!seenHosts.add(host)) continue
        out += TopSite(entry.url, entry.title.ifBlank { host.removePrefix("www.") }, host)
    }
    return out
}

/** A results page of one of the built-in search engines. */
fun isSearchResultPage(url: String): Boolean = SearchEngine.entries.any { url.startsWith(it.searchPrefix) }

/** The letter a site's tile shows: the first letter or digit of its host without "www.", or "?". */
fun tileLetter(host: String): String =
    host.removePrefix("www.").firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
