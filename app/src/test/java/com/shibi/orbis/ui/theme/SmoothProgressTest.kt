package com.shibi.orbis.ui.theme

import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [rememberSmoothProgress]: glides up, snaps down, on a new key and with motion off. The clock is held and stepped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SmoothProgressTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private var target by mutableFloatStateOf(0.4f)
    private var enabled by mutableStateOf(true)
    private var key by mutableIntStateOf(0)
    private lateinit var shown: State<Float>

    private fun start(initial: Float = 0.4f) {
        target = initial
        rule.mainClock.autoAdvance = false
        rule.setContent { shown = rememberSmoothProgress(target, enabled, key) }
        rule.frames(1)
    }

    private fun frame() = rule.frames(1)

    /** The value after each of [frames] frames. */
    private fun sample(frames: Int): List<Float> = List(frames) { frame(); shown.value }

    @Test
    fun theFirstValueIsTheTarget() {
        start(0.4f)
        assertEquals(0.4f, shown.value, 0f)
    }

    @Test
    fun aHigherTargetGlidesAndNeverGoesBackwards() {
        start(0.4f)
        target = 0.9f
        val values = sample(38) // 608 ms
        assertTrue("it moved but did not jump: ${values[2]}", values[2] > 0.4f && values[2] < 0.9f)
        values.zipWithNext().forEach { (a, b) -> assertTrue("went back: $a then $b", b >= a - EPS) }
        assertEquals(0.9f, values.last(), 0.02f)
    }

    @Test
    fun aLowerTargetShowsOnTheNextFrame() {
        start(0.4f)
        target = 0.9f
        sample(38)
        target = 0.3f
        frame()
        assertEquals(0.3f, shown.value, 0f)
        // And it stays there; nothing pulls it back up.
        assertEquals(setOf(0.3f), sample(10).toSet())
    }

    @Test
    fun aNewKeySnapsEvenToAHigherValue() {
        start(0.2f)
        target = 0.8f
        key = 1
        frame()
        assertEquals(0.8f, shown.value, 0f)
    }

    @Test
    fun withMotionOffEveryChangeShowsOnTheNextFrame() {
        enabled = false
        start(0.2f)
        target = 0.7f
        frame()
        assertEquals(0.7f, shown.value, 0f)
        target = 0.1f
        frame()
        assertEquals(0.1f, shown.value, 0f)
    }

    @Test
    fun turningMotionOffMidGlideEndsAtTheTargetOnTheNextFrame() {
        start(0.2f)
        target = 0.9f
        sample(3)
        enabled = false
        frame()
        assertEquals(0.9f, shown.value, 0f)
    }

    @Test
    fun retargetingMidGlideNeverGoesBackwards() {
        start(0.1f)
        target = 0.5f
        val first = sample(4)
        target = 0.9f
        val second = sample(4)
        target = 0.95f
        val third = sample(40)
        val all = first + second + third
        all.zipWithNext().forEach { (a, b) -> assertTrue("went back: $a then $b", b >= a - EPS) }
        assertTrue("still moving after the retarget", second.last() > first.last())
        assertEquals(0.95f, third.last(), 0.02f)
    }

    private companion object {
        /** Float rounding of the spring on its first frame; far below one pixel. */
        const val EPS = 1e-5f
    }
}
