package com.shibi.orbis.ui.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarLayoutTest {
    @Test
    fun wrapsOnlyForLargeTextOnNarrowWindows() {
        assertFalse(toolbarWraps(fontScale = 1.0f, screenWidthDp = 411))
        assertFalse(toolbarWraps(fontScale = 1.15f, screenWidthDp = 411))
        assertTrue(toolbarWraps(fontScale = 1.3f, screenWidthDp = 411))
        assertTrue(toolbarWraps(fontScale = 2.0f, screenWidthDp = 360))
        assertFalse("a wide window (Fold unfolded, landscape) keeps one row", toolbarWraps(fontScale = 1.6f, screenWidthDp = 700))
    }

    @Test
    fun castIconHidesOnlyOnNarrowOneRowToolbars() {
        assertFalse(showsToolbarCast(windowWidthDp = 320, twoRow = false))
        assertTrue(showsToolbarCast(windowWidthDp = 360, twoRow = false))
        assertTrue("a two-row toolbar has room", showsToolbarCast(windowWidthDp = 320, twoRow = true))
        assertTrue(showsToolbarCast(windowWidthDp = 700, twoRow = false))
    }
}
