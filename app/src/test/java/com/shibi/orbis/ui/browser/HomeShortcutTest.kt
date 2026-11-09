package com.shibi.orbis.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HomeShortcutTest {
    @Test
    fun letterSkipsWwwAndFallsBack() {
        assertEquals("E", shortcutLetter("https://www.example.org/a"))
        assertEquals("9", shortcutLetter("http://9gag.com/"))
        assertEquals("#", shortcutLetter("about:blank"))
    }

    @Test
    fun labelPrefersTitleThenHost() {
        assertEquals("News", shortcutLabel("https://www.example.org/", "  News "))
        assertEquals("example.org", shortcutLabel("https://www.example.org/", ""))
        assertEquals(60, shortcutLabel("https://example.org/", "x".repeat(100)).length)
    }
}
