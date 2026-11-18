package com.shibi.orbis.engine

import com.shibi.orbis.engine.SearchProvider.BuiltIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEngineTest {
    private class FakePrefs(
        override var selectedId: String? = null,
        override var custom: List<SearchProvider.Custom> = emptyList(),
        override var nextCustomNumber: Int = 1,
    ) : SearchEnginePrefs

    private val builtIns = SearchEngine.entries.map { BuiltIn(it) }

    @Test
    fun defaultIsDuckDuckGoAndOmittedEngineKeepsOldBehaviour() {
        assertEquals(SearchEngine.DUCKDUCKGO, SearchEngine.DEFAULT)
        assertEquals(BuiltIn(SearchEngine.DUCKDUCKGO), SearchProvider.DEFAULT)
        assertEquals("https://duckduckgo.com/?q=cats", normalizeAddressInput("cats"))
        assertEquals(normalizeAddressInput("a b", BuiltIn(SearchEngine.DUCKDUCKGO)), normalizeAddressInput("a b"))
    }

    @Test
    fun everyEngineSearchesItsOwnUrl() {
        assertEquals("https://duckduckgo.com/?q=hello+world", normalizeAddressInput("hello world", BuiltIn(SearchEngine.DUCKDUCKGO)))
        assertEquals("https://www.google.com/search?q=hello+world", normalizeAddressInput("hello world", BuiltIn(SearchEngine.GOOGLE)))
        assertEquals("https://www.bing.com/search?q=hello+world", normalizeAddressInput("hello world", BuiltIn(SearchEngine.BING)))
        assertEquals("https://search.brave.com/search?q=hello+world", normalizeAddressInput("hello world", BuiltIn(SearchEngine.BRAVE)))
        assertEquals("https://www.startpage.com/do/search?q=hello+world", normalizeAddressInput("hello world", BuiltIn(SearchEngine.STARTPAGE)))
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
        builtIns.forEach {
            assertEquals(it.engine.searchPrefix + "cats+%26+dogs", normalizeAddressInput("cats & dogs", it))
            assertEquals(it.engine.searchPrefix + "javascript%3Aalert%281%29", normalizeAddressInput("javascript:alert(1)", it))
        }
    }

    @Test
    fun navigationAndBlankRulesIgnoreTheEngine() {
        (builtIns + SearchProvider.Custom("custom:1", "S", "https://s.example/?q=%s")).forEach {
            assertNull(normalizeAddressInput("   ", it))
            assertEquals("https://example.com", normalizeAddressInput("example.com", it))
            assertEquals("http://example.com/a", normalizeAddressInput("http://example.com/a", it))
            assertEquals("https://localhost:3000/x", normalizeAddressInput("localhost:3000/x", it))
            // Whitespace still turns a web address into a search, now on the chosen engine.
            assertEquals(it.searchUrl("https://example.com/a b"), normalizeAddressInput("https://example.com/a b", it))
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
        assertEquals(SearchProvider.DEFAULT, SearchEngineSettings(FakePrefs()).selected.value)
        assertEquals(BuiltIn(SearchEngine.BRAVE), SearchEngineSettings(FakePrefs("BRAVE")).selected.value)
    }

    @Test
    fun storedBuiltInNamesStillReadAndUnknownIdsReadAsDuckDuckGo() {
        // Values written before custom engines existed are enum names.
        assertEquals(BuiltIn(SearchEngine.GOOGLE), SearchEngineSettings(FakePrefs("GOOGLE")).selected.value)
        // A removed custom engine, or a downgrade-then-upgrade.
        assertEquals(SearchProvider.DEFAULT, SearchEngineSettings(FakePrefs("custom:3")).selected.value)
        assertEquals(SearchProvider.DEFAULT, SearchEngineSettings(FakePrefs("YAHOO")).selected.value)
        val kept = SearchProvider.Custom("custom:3", "Mine", "https://s.example/?q=%s")
        assertEquals(kept, SearchEngineSettings(FakePrefs("custom:3", listOf(kept))).selected.value)
    }

    @Test
    fun selectPersistsAndPublishes() {
        val prefs = FakePrefs()
        val settings = SearchEngineSettings(prefs)
        settings.select(BuiltIn(SearchEngine.STARTPAGE))
        assertEquals("STARTPAGE", prefs.selectedId)
        assertEquals(BuiltIn(SearchEngine.STARTPAGE), settings.selected.value)
        // A fresh instance over the same prefs (a new process) sees the choice.
        assertEquals(BuiltIn(SearchEngine.STARTPAGE), SearchEngineSettings(prefs).selected.value)
        settings.select(SearchProvider.DEFAULT)
        assertEquals("DUCKDUCKGO", prefs.selectedId)
    }

    @Test
    fun customEngineEncodesTheQueryAsUrlInputDoes() {
        val custom = SearchProvider.Custom("custom:1", "S", "https://s.example/?q=%s")
        // URLEncoder form, as for the built-in engines: space is "+", "&" is "%26".
        assertEquals("https://s.example/?q=a+b%26c", custom.searchUrl("a b&c"))
        assertEquals("https://s.example/?q=a+b%26c", normalizeAddressInput("a b&c", custom))
        val middle = SearchProvider.Custom("custom:2", "M", "https://s.example/find/%s/all?x=1")
        assertEquals("https://s.example/find/cats/all?x=1", middle.searchUrl("cats"))
        // A query that itself contains "%s" is encoded, not substituted again.
        assertEquals("https://s.example/?q=100%25s", custom.searchUrl("100%s"))
    }

    @Test
    fun builtInSearchUrlIsTheOldPrefixPlusEncodedQuery() {
        // The selection-menu Search used searchPrefix + URLEncoder.encode before custom engines; the address must not change.
        SearchEngine.entries.forEach {
            assertEquals(it.name, it.searchPrefix + java.net.URLEncoder.encode("a b&c", "UTF-8"), BuiltIn(it).searchUrl("a b&c"))
        }
    }

    @Test
    fun everyRuleRefusesItsBadInput() {
        fun refused(name: String, template: String) = validateCustomEngine(name, template, emptyList())
        assertNull(refused("Mine", "https://s.example/?q=%s"))
        assertEquals(CustomEngineError.NOT_HTTPS, refused("Mine", "http://s.example/?q=%s"))
        assertEquals(CustomEngineError.NOT_HTTPS, refused("Mine", "s.example/?q=%s"))
        assertEquals(CustomEngineError.NOT_HTTPS, refused("Mine", "javascript:alert(%s)"))
        assertEquals(CustomEngineError.PLACEHOLDER, refused("Mine", "https://s.example/?q="))
        assertEquals(CustomEngineError.PLACEHOLDER, refused("Mine", "https://s.example/?q=%s&r=%s"))
        assertEquals(CustomEngineError.BAD_ADDRESS, refused("Mine", "https:///?q=%s"))
        assertEquals(CustomEngineError.BAD_ADDRESS, refused("Mine", "https://exa mple/?q=%s"))
        val long = "https://s.example/?q=%s&p=" + "a".repeat(MAX_CUSTOM_TEMPLATE)
        assertEquals(CustomEngineError.BAD_ADDRESS, refused("Mine", long))
        assertNull(refused("Mine", long.take(MAX_CUSTOM_TEMPLATE)))
        assertEquals(CustomEngineError.NAME_BLANK, refused("", "https://s.example/?q=%s"))
        assertEquals(CustomEngineError.NAME_BLANK, refused("   ", "https://s.example/?q=%s"))
        assertEquals(CustomEngineError.NAME_BLANK, refused("n".repeat(MAX_CUSTOM_NAME + 1), "https://s.example/?q=%s"))
        assertNull(refused("n".repeat(MAX_CUSTOM_NAME), "https://s.example/?q=%s"))
        assertEquals(CustomEngineError.NAME_USED, refused("google", "https://s.example/?q=%s"))
        val mine = SearchProvider.Custom("custom:1", "Mine", "https://s.example/?q=%s")
        assertEquals(CustomEngineError.NAME_USED, validateCustomEngine(" MINE ", "https://t.example/?q=%s", listOf(mine)))
    }

    @Test
    fun theEleventhEngineIsRefused() {
        val settings = SearchEngineSettings(FakePrefs())
        repeat(MAX_CUSTOM_ENGINES) {
            assertTrue(settings.addCustom("E$it", "https://e$it.example/?q=%s") is AddResult.Added)
        }
        assertEquals(
            AddResult.Refused(CustomEngineError.TOO_MANY),
            settings.addCustom("E10", "https://e10.example/?q=%s"),
        )
        assertEquals(MAX_CUSTOM_ENGINES, settings.custom.value.size)
    }

    @Test
    fun addStoresTrimmedWithAFreshIdAndDoesNotSelect() {
        val prefs = FakePrefs()
        val settings = SearchEngineSettings(prefs)
        val added = settings.addCustom("  Mine ", " https://s.example/?q=%s ") as AddResult.Added
        assertEquals(SearchProvider.Custom("custom:1", "Mine", "https://s.example/?q=%s"), added.engine)
        assertEquals(listOf(added.engine), prefs.custom)
        assertEquals(SearchProvider.DEFAULT, settings.selected.value)
        settings.removeCustom("custom:1")
        // Ids are never reused, so an old stored choice cannot point at a different engine.
        val again = settings.addCustom("Mine", "https://s.example/?q=%s") as AddResult.Added
        assertEquals("custom:2", again.engine.id)
    }

    @Test
    fun removingTheSelectedEngineSelectsDuckDuckGo() {
        val prefs = FakePrefs("BING")
        val settings = SearchEngineSettings(prefs)
        val a = (settings.addCustom("A", "https://a.example/?q=%s") as AddResult.Added).engine
        val b = (settings.addCustom("B", "https://b.example/?q=%s") as AddResult.Added).engine
        settings.select(a)
        assertEquals("custom:1", prefs.selectedId)
        assertEquals(a, SearchEngineSettings(prefs).selected.value)
        settings.removeCustom(b.id)
        assertEquals(a, settings.selected.value)
        settings.removeCustom(a.id)
        assertEquals(SearchProvider.DEFAULT, settings.selected.value)
        assertEquals("DUCKDUCKGO", prefs.selectedId)
        assertTrue(settings.custom.value.isEmpty())
        // A removed engine cannot be selected again.
        settings.select(a)
        assertEquals(SearchProvider.DEFAULT, settings.selected.value)
    }

    @Test
    fun storedListRoundTripsAndDamagedEntriesAreDropped() {
        val list =
            listOf(
                SearchProvider.Custom("custom:1", "A \"quoted\" name", "https://a.example/?q=%s&x=é"),
                SearchProvider.Custom("custom:4", "B", "https://b.example/%s"),
            )
        val text = SharedPreferencesSearchEnginePrefs.encodeCustomEngines(list)
        assertEquals(list, SharedPreferencesSearchEnginePrefs.decodeCustomEngines(text))
        assertEquals(emptyList<SearchProvider.Custom>(), SharedPreferencesSearchEnginePrefs.decodeCustomEngines(null))
        assertEquals(emptyList<SearchProvider.Custom>(), SharedPreferencesSearchEnginePrefs.decodeCustomEngines("not json"))
        val damaged =
            """[{"id":"custom:1","name":"Ok","template":"https://ok.example/?q=%s"},""" +
                """{"id":"custom:2","name":"Http","template":"http://x.example/?q=%s"},""" +
                """{"id":"GOOGLE","name":"Fake","template":"https://f.example/?q=%s"},""" +
                """{"id":"custom:1","name":"Dup","template":"https://d.example/?q=%s"}]"""
        assertEquals(
            listOf(SearchProvider.Custom("custom:1", "Ok", "https://ok.example/?q=%s")),
            SharedPreferencesSearchEnginePrefs.decodeCustomEngines(damaged),
        )
    }
}
