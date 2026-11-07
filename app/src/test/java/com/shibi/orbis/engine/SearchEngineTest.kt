package com.shibi.orbis.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEngineTest {
    private class FakePrefs(override var engine: SearchEngine = SearchEngine.DEFAULT) : SearchEnginePrefs

    @Test
    fun defaultIsDuckDuckGoAndOmittedEngineKeepsOldBehaviour() {
        assertEquals(SearchEngine.DUCKDUCKGO, SearchEngine.DEFAULT)
        assertEquals("https://duckduckgo.com/?q=cats", normalizeAddressInput("cats"))
        assertEquals(normalizeAddressInput("a b", SearchEngine.DUCKDUCKGO), normalizeAddressInput("a b"))
    }

    @Test
    fun everyEngineSearchesItsOwnUrl() {
        assertEquals("https://duckduckgo.com/?q=hello+world", normalizeAddressInput("hello world", SearchEngine.DUCKDUCKGO))
        assertEquals("https://www.google.com/search?q=hello+world", normalizeAddressInput("hello world", SearchEngine.GOOGLE))
        assertEquals("https://www.bing.com/search?q=hello+world", normalizeAddressInput("hello world", SearchEngine.BING))
        assertEquals("https://search.brave.com/search?q=hello+world", normalizeAddressInput("hello world", SearchEngine.BRAVE))
        assertEquals("https://www.startpage.com/do/search?q=hello+world", normalizeAddressInput("hello world", SearchEngine.STARTPAGE))
    }

    @Test
    fun everyPrefixIsHttpsAndEndsWithTheQueryParameter() {
        SearchEngine.entries.forEach {
            assertTrue(it.name, it.searchPrefix.startsWith("https://"))
            assertTrue(it.name, it.searchPrefix.endsWith("?q="))
        }
    }

    @Test
    fun queryEncodingIsTheSameForEveryEngine() {
        SearchEngine.entries.forEach {
            assertEquals(it.searchPrefix + "cats+%26+dogs", normalizeAddressInput("cats & dogs", it))
            assertEquals(it.searchPrefix + "javascript%3Aalert%281%29", normalizeAddressInput("javascript:alert(1)", it))
        }
    }

    @Test
    fun navigationAndBlankRulesIgnoreTheEngine() {
        SearchEngine.entries.forEach {
            assertNull(normalizeAddressInput("   ", it))
            assertEquals("https://example.com", normalizeAddressInput("example.com", it))
            assertEquals("http://example.com/a", normalizeAddressInput("http://example.com/a", it))
            assertEquals("https://localhost:3000/x", normalizeAddressInput("localhost:3000/x", it))
            // Whitespace still turns a web address into a search, now on the chosen engine.
            assertEquals(it.searchPrefix + "https%3A%2F%2Fexample.com%2Fa+b", normalizeAddressInput("https://example.com/a b", it))
        }
    }

    @Test
    fun storedNameParsing() {
        SearchEngine.entries.forEach { assertEquals(it, SearchEngine.fromName(it.name)) }
        assertEquals(SearchEngine.DEFAULT, SearchEngine.fromName(null))
        assertEquals(SearchEngine.DEFAULT, SearchEngine.fromName(""))
        assertEquals(SearchEngine.DEFAULT, SearchEngine.fromName("YAHOO"))
        assertEquals(SearchEngine.DEFAULT, SearchEngine.fromName("google"))
    }

    @Test
    fun settingsStartsFromTheStoredChoice() {
        assertEquals(SearchEngine.DUCKDUCKGO, SearchEngineSettings(FakePrefs()).selected.value)
        assertEquals(SearchEngine.BRAVE, SearchEngineSettings(FakePrefs(SearchEngine.BRAVE)).selected.value)
    }

    @Test
    fun selectPersistsAndPublishes() {
        val prefs = FakePrefs()
        val settings = SearchEngineSettings(prefs)
        settings.select(SearchEngine.STARTPAGE)
        assertEquals(SearchEngine.STARTPAGE, prefs.engine)
        assertEquals(SearchEngine.STARTPAGE, settings.selected.value)
        // A fresh instance over the same prefs (a new process) sees the choice.
        assertEquals(SearchEngine.STARTPAGE, SearchEngineSettings(prefs).selected.value)
        settings.select(SearchEngine.DUCKDUCKGO)
        assertEquals(SearchEngine.DUCKDUCKGO, prefs.engine)
    }
}
