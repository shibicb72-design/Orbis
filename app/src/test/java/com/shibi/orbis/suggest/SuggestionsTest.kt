package com.shibi.orbis.suggest

import com.shibi.orbis.bookmarks.Bookmark
import com.shibi.orbis.engine.SearchEngine
import com.shibi.orbis.history.HistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionsTest {
    private val history =
        listOf(
            HistoryEntry("https://www.wikipedia.org/", "Wikipedia", 10, visits = 9),
            HistoryEntry("https://weather.test/today", "Local weather", 20, visits = 3),
            HistoryEntry("https://news.test/world", "World news", 30, visits = 5),
            HistoryEntry("https://duckduckgo.com/?q=weather", "weather at DuckDuckGo", 40, visits = 50),
        )
    private val bookmarks = listOf(Bookmark(1, "https://weather.gov/", "National Weather Service", 1))

    @Test
    fun matchesAddressStartHostAndTitleWordsBookmarksFirst() {
        val s = localSuggestions("wea", history, bookmarks)
        assertEquals(listOf("https://weather.gov/", "https://weather.test/today"), s.map { it.url })
        assertTrue(s.first().bookmarked)
        assertEquals(listOf("https://www.wikipedia.org/"), localSuggestions("wiki", history, bookmarks).map { it.url })
        assertEquals(listOf("https://news.test/world"), localSuggestions("WORLD", history, bookmarks).map { it.url })
    }

    @Test
    fun searchPagesAndBlankQueriesGiveNothing() {
        assertTrue(localSuggestions("duck", history, emptyList()).isEmpty())
        assertTrue(localSuggestions("   ", history, bookmarks).isEmpty())
    }

    @Test
    fun localResultsAreCapped() {
        val many = (1..20).map { HistoryEntry("https://site$it.test/", "Site $it", it.toLong()) }
        assertEquals(LOCAL_SUGGESTION_LIMIT, localSuggestions("site", many, emptyList()).size)
    }

    @Test
    fun endpointsPerEngineAndBraveHasNone() {
        assertEquals("https://duckduckgo.com/ac/?q=a+b%26c&type=list", suggestUrl(SearchEngine.DUCKDUCKGO, "a b&c"))
        assertEquals("https://suggestqueries.google.com/complete/search?client=firefox&q=x", suggestUrl(SearchEngine.GOOGLE, "x"))
        assertEquals("https://api.bing.com/osjson.aspx?query=x", suggestUrl(SearchEngine.BING, "x"))
        assertEquals("https://www.startpage.com/osuggestions?q=x", suggestUrl(SearchEngine.STARTPAGE, "x"))
        assertNull(suggestUrl(SearchEngine.BRAVE, "x"))
    }

    @Test
    fun parsesOpenSearchAndToleratesJunk() {
        // Shapes captured from the live endpoints on 2026-11-08 (trimmed).
        assertEquals(listOf("weather", "weather channel"), parseOpenSearchSuggestions("""["weather",["weather","weather channel"]]"""))
        assertEquals(
            listOf("a", "b"),
            parseOpenSearchSuggestions("""["q",["a","b","a",""],[],{"google:suggestsubtypes":[[512]]}]"""),
        )
        assertEquals(SEARCH_SUGGESTION_LIMIT, parseOpenSearchSuggestions("""["q",["1","2","3","4","5","6","7"]]""").size)
        assertTrue(parseOpenSearchSuggestions("<!DOCTYPE html><html>").isEmpty())
        assertTrue(parseOpenSearchSuggestions("").isEmpty())
        assertTrue(parseOpenSearchSuggestions("""{"q":1}""").isEmpty())
    }

    @Test
    fun mergeKeepsTheTypedTextAsTheFirstSearchAndDropsItsDuplicate() {
        val local = listOf(Suggestion.Page("https://weather.gov/", "NWS", true))
        val merged = mergeSuggestions("Weather", local, listOf("weather", "weather radar"))
        assertEquals(
            listOf(Suggestion.Page("https://weather.gov/", "NWS", true), Suggestion.Search("Weather"), Suggestion.Search("weather radar")),
            merged,
        )
        assertTrue(mergeSuggestions(" ", local, listOf("x")).isEmpty())
    }
}
