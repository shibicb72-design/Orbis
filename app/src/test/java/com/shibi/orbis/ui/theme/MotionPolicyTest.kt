package com.shibi.orbis.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slice 4: which root transitions may animate. */
class MotionPolicyTest {
    private val browser = "browser"

    @Test
    fun secondaryToSecondaryKeepsTheExistingFade() {
        assertTrue(routeFades(motionEnabled = true, fromRoute = "settings", toRoute = "settings/clear_data", browserRoute = browser))
        assertTrue(routeFades(true, "settings/ubo", "settings", browser))
    }

    @Test
    fun theBrowserBoundaryIsAlwaysImmediate() {
        assertFalse("into a secondary screen", routeFades(true, browser, "history", browser))
        assertFalse("back to the browser", routeFades(true, "downloads", browser, browser))
    }

    @Test
    fun zeroMotionNeverFades() {
        assertFalse(routeFades(false, "settings", "settings/ubo", browser))
        assertFalse(routeFades(false, browser, "history", browser))
    }

    @Test
    fun recordedBaselinesAreUnchanged() {
        assertEquals(160, ROUTE_ENTER_MS)
        assertEquals(100, ROUTE_EXIT_MS)
        assertEquals(180, TAB_PLACEMENT_MS)
    }
}
