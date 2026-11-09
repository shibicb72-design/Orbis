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
