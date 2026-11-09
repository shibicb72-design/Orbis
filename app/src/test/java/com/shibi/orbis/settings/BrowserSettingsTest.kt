package com.shibi.orbis.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSettingsTest {
    private class MemPrefs : BrowserPrefs {
        override var homepage: String? = null
        override var searchSuggestions: Boolean = true
        override var textZoomPercent: Int = DEFAULT_TEXT_ZOOM
        override var forceZoom: Boolean = false
        override var pinnedSites: List<com.shibi.orbis.home.PinnedSite> = emptyList()
        override var hiddenSites: Set<String> = emptySet()
        override var offerSavePasswords: Boolean = true
        override var readerTextSize: Int = 18
        override var readerSerif: Boolean = true
        override var readerTheme: ReaderTheme = ReaderTheme.LIGHT
        override var httpsOnly: HttpsOnlyMode = HttpsOnlyMode.ALL
        override var appTheme: AppTheme = AppTheme.SYSTEM
        override var darkWebsites: Boolean = false
        override var javaScript: Boolean = true
        override var javaScriptOn: Set<String> = emptySet()
        override var javaScriptOff: Set<String> = emptySet()
        override var alwaysDesktop: Boolean = false
        override var desktopOn: Set<String> = emptySet()
        override var desktopOff: Set<String> = emptySet()
        override var openLinksInApps: Boolean = true
        override var toolbarBottom: Boolean = false
    }

    @Test
    fun desktopChoiceIsRememberedPerSiteOverTheDefault() {
        val prefs = MemPrefs()
        val s = BrowserSettings(prefs)
        assertFalse(s.desktopFor("https://a.example/x", isPrivate = false))
        s.rememberDesktop("https://a.example/x", on = true, isPrivate = false)
        assertTrue(s.desktopFor("https://A.example/other", isPrivate = false))
        assertFalse(s.desktopFor("https://b.example/", isPrivate = false))
        assertEquals(setOf("a.example"), prefs.desktopOn)
        // Always desktop: every other site follows, the site switched off keeps its choice.
        s.setAlwaysDesktop(true)
        s.rememberDesktop("https://c.example/", on = false, isPrivate = false)
        assertTrue(s.desktopFor("https://b.example/", isPrivate = false))
        assertFalse(s.desktopFor("https://c.example/", isPrivate = false))
        // Choosing the default again drops the entry.
        s.rememberDesktop("https://c.example/", on = true, isPrivate = false)
        assertEquals(emptySet<String>(), prefs.desktopOff)
    }

    @Test
    fun privateSiteChoicesStayInMemoryAndEndWithPrivateBrowsing() {
        val prefs = MemPrefs()
        val s = BrowserSettings(prefs)
        s.rememberJavaScript("https://a.example/", on = false, isPrivate = true)
        assertFalse(s.javaScriptFor("https://a.example/", isPrivate = true))
        assertTrue(s.javaScriptFor("https://a.example/", isPrivate = false))
        assertEquals(emptySet<String>(), prefs.javaScriptOff)
        s.forgetPrivate()
        assertTrue(s.javaScriptFor("https://a.example/", isPrivate = true))
        // A private tab still reads the normal choice.
        s.rememberJavaScript("https://b.example/", on = false, isPrivate = false)
        assertFalse(s.javaScriptFor("https://b.example/", isPrivate = true))
    }

    @Test
    fun javaScriptDefaultAppliesToSitesWithoutAChoice() {
        val s = BrowserSettings(MemPrefs())
        s.rememberJavaScript("https://keep.example/", on = false, isPrivate = false)
        s.setJavaScript(false)
        assertFalse(s.javaScriptFor("https://any.example/", isPrivate = false))
        s.rememberJavaScript("https://news.example/", on = true, isPrivate = false)
        assertTrue(s.javaScriptFor("https://news.example/", isPrivate = false))
        assertFalse(s.javaScriptFor("https://keep.example/", isPrivate = false))
    }

    @Test
    fun httpsOnlyDefaultsToAllTabsAndThemeToSystem() {
        val s = BrowserSettings(MemPrefs())
        assertEquals(HttpsOnlyMode.ALL, s.httpsOnly.value)
        assertEquals(AppTheme.SYSTEM, s.appTheme.value)
        s.setHttpsOnly(HttpsOnlyMode.PRIVATE)
        s.setAppTheme(AppTheme.DARK)
        assertEquals(HttpsOnlyMode.PRIVATE, s.httpsOnly.value)
        assertEquals(AppTheme.DARK, s.appTheme.value)
    }

    @Test
    fun defaultsAreStartPageSuggestionsOnAndNormalText() {
        val s = BrowserSettings(MemPrefs())
        assertNull(s.homepage.value)
        assertTrue(s.searchSuggestions.value)
        assertEquals(100, s.textZoomPercent.value)
    }

    @Test
    fun homepageAcceptsWebAddressesOnlyAndPersists() {
        val prefs = MemPrefs()
        val s = BrowserSettings(prefs)
        assertTrue(s.setHomepage("https://example.org/"))
        assertEquals("https://example.org/", prefs.homepage)
        assertFalse(s.setHomepage("javascript:alert(1)"))
        assertFalse(s.setHomepage("file:///sdcard/x.html"))
        assertEquals("https://example.org/", s.homepage.value)
        assertTrue(s.setHomepage(null))
        assertNull(prefs.homepage)
    }

    @Test
    fun typedHomepageIsNormalizedAndSearchesAreRefused() {
        assertEquals("https://example.com", parseHomepageInput("example.com"))
        assertEquals("http://intranet.test/", parseHomepageInput(" http://intranet.test/ "))
        assertNull(parseHomepageInput("cute cats"))
        assertNull(parseHomepageInput(""))
        assertNull(parseHomepageInput("javascript:alert(1)"))
    }

    @Test
    fun forceZoomIsOffByDefaultAndPersists() {
        val prefs = MemPrefs()
        val s = BrowserSettings(prefs)
        assertFalse(s.forceZoom.value)
        s.setForceZoom(true)
        assertTrue(prefs.forceZoom)
        assertTrue(s.forceZoom.value)
    }

    @Test
    fun pinUnpinAndRemoveSitesPersist() {
        val prefs = MemPrefs()
        val s = BrowserSettings(prefs)
        s.pinSite("https://a.test/x", "A")
        s.pinSite("https://b.test/", "B")
        s.pinSite("https://a.test/y", "A again")
        assertEquals("re-pinning a site moves it to the end", listOf("https://b.test/", "https://a.test/y"), prefs.pinnedSites.map { it.url })
        s.removeSite("https://b.test/")
        assertEquals(listOf("https://a.test/y"), s.pinnedSites.value.map { it.url })
        assertEquals(setOf("b.test"), prefs.hiddenSites)
        s.pinSite("https://b.test/", "B")
        assertTrue("pinning un-hides", s.hiddenSites.value.isEmpty())
        s.unpinSite("https://a.test/y")
        assertEquals(listOf("https://b.test/"), s.pinnedSites.value.map { it.url })
        s.pinSite("javascript:alert(1)", "x")
        assertEquals(1, s.pinnedSites.value.size)
    }

    @Test
    fun textZoomIsClamped() {
        val s = BrowserSettings(MemPrefs())
        s.setTextZoomPercent(500)
        assertEquals(200, s.textZoomPercent.value)
        s.setTextZoomPercent(10)
        assertEquals(50, s.textZoomPercent.value)
    }
}
