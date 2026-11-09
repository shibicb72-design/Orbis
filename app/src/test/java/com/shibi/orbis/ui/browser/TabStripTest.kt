package com.shibi.orbis.ui.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabStripTest {
    @Test
    fun stripOnlyOnWideWindows() {
        assertFalse(showsTabStrip(411)) // a phone, or the Fold's outer screen
        assertTrue(showsTabStrip(600))
        assertTrue(showsTabStrip(914)) // a phone in landscape, the Fold unfolded
    }
}
