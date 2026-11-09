package com.shibi.orbis.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun idsDoNotCollideLikeHashCode() {
        val a = "http://10.0.2.2:8765/bankAa"
        val b = "http://10.0.2.2:8765/bankBB"
        assertEquals(a.hashCode(), b.hashCode()) // the old id scheme collided
        assertNotEquals(shortcutId(a), shortcutId(b))
        assertEquals(shortcutId(a), shortcutId(a))
        assertEquals(6 + 32, shortcutId(a).length)
    }

    @Test
    fun labelPrefersTitleThenHost() {
        assertEquals("News", shortcutLabel("https://www.example.org/", "  News "))
        assertEquals("example.org", shortcutLabel("https://www.example.org/", ""))
        assertEquals(60, shortcutLabel("https://example.org/", "x".repeat(100)).length)
    }
}
