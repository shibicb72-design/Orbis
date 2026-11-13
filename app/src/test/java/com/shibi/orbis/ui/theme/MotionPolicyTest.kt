package com.shibi.orbis.ui.theme

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun theNewDurationsHaveTheSpecifiedValues() {
        assertEquals(140, TAB_ENTER_MS)
        assertEquals(100, TAB_EXIT_MS)
        assertEquals(120, ADDRESS_FOCUS_MS)
        assertEquals(220, THEME_CROSSFADE_MS)
        assertEquals(150, PROGRESS_FADE_MS)
    }

    @Test
    fun motionOffSnapsOrGivesNoSpec() {
        assertTrue(motionTween<Float>(false, 140) is SnapSpec<*>)
        assertTrue(motionSpring<Float>(false) is SnapSpec<*>)
        assertNull(motionTweenOrNull<Float>(false, 140))
    }

    @Test
    fun motionOnGivesATweenOfTheGivenDurationAndAnUnbouncySpring() {
        val tween = motionTween<Float>(true, 140) as TweenSpec<Float>
        assertEquals(140, tween.durationMillis)
        assertEquals(0, tween.delay)
        assertEquals(100, (motionTweenOrNull<Float>(true, 100) as TweenSpec<Float>).durationMillis)
        val spring = motionSpring<Float>(true) as SpringSpec<Float>
        assertEquals(1f, spring.dampingRatio)
    }
}
