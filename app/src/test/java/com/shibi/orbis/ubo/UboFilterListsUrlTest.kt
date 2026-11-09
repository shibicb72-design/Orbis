package com.shibi.orbis.ubo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UboFilterListsUrlTest {
    @Test
    fun pointsAtTheFilterListsPaneOfTheDashboard() {
        assertEquals(
            "moz-extension://abc/dashboard.html#3p-filters.html",
            uboFilterListsUrl("moz-extension://abc/dashboard.html"),
        )
        assertEquals(
            "moz-extension://abc/dashboard.html#3p-filters.html",
            uboFilterListsUrl("moz-extension://abc/dashboard.html#settings.html"),
        )
    }

    @Test
    fun onlyAnExtensionPage() {
        assertNull(uboFilterListsUrl(null))
        assertNull(uboFilterListsUrl("https://evil.example/dashboard.html"))
    }
}
