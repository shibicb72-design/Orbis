package com.shibi.orbis.suggest

import com.shibi.orbis.bookmarks.Bookmark
import com.shibi.orbis.engine.SearchEngine
import com.shibi.orbis.engine.hostOfUrl
import com.shibi.orbis.history.HistoryEntry
import com.shibi.orbis.home.isSearchResultPage
import java.net.URLEncoder
import java.util.Locale
import org.json.JSONArray

/** One row under the address bar. [submitText] is what goes into the bar and is submitted when the row is tapped. */
sealed class Suggestion {
    abstract val submitText: String

    data class Page(val url: String, val title: String, val bookmarked: Boolean) : Suggestion() {
        override val submitText: String get() = url
    }

    data class Search(val query: String) : Suggestion() {
        override val submitText: String get() = query
    }
}

const val LOCAL_SUGGESTION_LIMIT = 4
const val SEARCH_SUGGESTION_LIMIT = 5

/**
 * History and bookmark pages matching [query]: the address without its scheme and "www." starts with it, or any word
 * of the title starts with it, case-insensitively. Bookmarks first, then by visit count and recency; one row per URL;
 * search result pages are left out. Local only: nothing leaves the device. A blank query gives nothing.
 */
fun localSuggestions(
    query: String,
    history: List<HistoryEntry>,
    bookmarks: List<Bookmark>,
    limit: Int = LOCAL_SUGGESTION_LIMIT,
): List<Suggestion.Page> {
    val q = query.trim().lowercase(Locale.ROOT)
    if (q.isEmpty()) return emptyList()
    val bookmarkedUrls = bookmarks.mapTo(HashSet()) { it.url }
    val visits = history.associate { it.url to it }
    val out = LinkedHashMap<String, Suggestion.Page>()
    val bookmarkHits = bookmarks.filter { matches(q, it.url, it.title) }
        .sortedByDescending { visits[it.url]?.visits ?: 0 }
    for (b in bookmarkHits) out.putIfAbsent(b.url, Suggestion.Page(b.url, b.title, bookmarked = true))
    val historyHits = history.filter { !isSearchResultPage(it.url) && matches(q, it.url, it.title) }
        .sortedWith(compareByDescending<HistoryEntry> { it.visits }.thenByDescending { it.visitedAt })
    for (h in historyHits) out.putIfAbsent(h.url, Suggestion.Page(h.url, h.title, h.url in bookmarkedUrls))
    return out.values.take(limit)
}

private fun matches(q: String, url: String, title: String): Boolean {
    val bare = url.lowercase(Locale.ROOT).substringAfter("://").removePrefix("www.")
    if (bare.startsWith(q)) return true
    val host = hostOfUrl(url)?.removePrefix("www.")
    if (host != null && host.startsWith(q)) return true
    return title.lowercase(Locale.ROOT).split(' ', '-', '|', ':', '·').any { it.startsWith(q) }
}

/**
 * The engine's suggestion endpoint for [query], or null when the engine has none that works without a browser
 * session (Brave answered with a web page instead of suggestions when checked on 2026-11-08). All four answer in the
 * OpenSearch form `["query", ["s1", "s2", ...], ...]`.
 */
fun suggestUrl(engine: SearchEngine, query: String): String? {
    val q = URLEncoder.encode(query, "UTF-8")
    return when (engine) {
        SearchEngine.DUCKDUCKGO -> "https://duckduckgo.com/ac/?q=$q&type=list"
        SearchEngine.GOOGLE -> "https://suggestqueries.google.com/complete/search?client=firefox&q=$q"
        SearchEngine.BING -> "https://api.bing.com/osjson.aspx?query=$q"
        SearchEngine.STARTPAGE -> "https://www.startpage.com/osuggestions?q=$q"
        SearchEngine.BRAVE -> null
    }
}

/** The suggestion strings of an OpenSearch response, or empty for anything else. Never throws. */
fun parseOpenSearchSuggestions(body: String, limit: Int = SEARCH_SUGGESTION_LIMIT): List<String> =
    runCatching {
        val list = JSONArray(body.trim()).getJSONArray(1)
        (0 until list.length()).mapNotNull { list.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
            .distinct().take(limit)
    }.getOrDefault(emptyList())

/**
 * What the dropdown shows: the typed text as a search first (unless it is already listed), then the engine's
 * suggestions, then matching pages. Remote suggestions only arrive when allowed (setting on, normal tab).
 */
fun mergeSuggestions(query: String, local: List<Suggestion.Page>, remote: List<String>): List<Suggestion> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val searches = (listOf(q) + remote.filterNot { it.equals(q, ignoreCase = true) }).map { Suggestion.Search(it) }
    return local + searches.take(SEARCH_SUGGESTION_LIMIT)
}
