package com.shibi.orbis.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.shibi.orbis.ui.theme.PROGRESS_FADE_MS
import com.shibi.orbis.ui.theme.frames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** [LoadingBar] on a white page with a pure red indicator, so every pixel can be classified. The clock is held. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoadingBarTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private var loading by mutableStateOf(true)
    private var progress by mutableFloatStateOf(0.2f)
    private var motion by mutableStateOf(true)
    private var parentRuns = 0
    private var feederRuns = 0

    @Composable
    private fun Feeder() {
        // Reads the state here, so a progress change recomposes this scope and nothing above it.
        val p = progress
        val l = loading
        SideEffect { feederRuns++ }
        LoadingBar(l, p, motion, Modifier.fillMaxWidth().height(4.dp).testTag(BAR), color = Color.Red)
    }

    private fun show(initialProgress: Float = 0.2f, isLoading: Boolean = true, motionOn: Boolean = true) {
        progress = initialProgress
        loading = isLoading
        motion = motionOn
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SideEffect { parentRuns++ }
            MaterialTheme(colorScheme = lightColorScheme()) {
                Box(Modifier.fillMaxWidth().background(Color.White)) { Feeder() }
            }
        }
        frame()
    }

    private fun frame(n: Int = 1) = rule.frames(n)

    private fun pixels() = rule.onNodeWithTag(BAR).captureToImage().toPixelMap()

    private fun isRed(c: Color) = c.red > 0.9f && c.green < 0.1f && c.blue < 0.1f

    /** The right-most red pixel of the middle row, ignoring the stop dot in the last few pixels. */
    private fun fillEdge(): Int {
        val map = pixels()
        val y = map.height / 2
        return (0 until map.width - 12).lastOrNull { isRed(map[it, y]) } ?: -1
    }

    /** True when any pixel of the bar's rows is not the white page. */
    private fun anyBarPixel(): Boolean {
        val map = pixels()
        return (0 until map.height).any { y -> (0 until map.width).any { x -> map[x, y] != Color.White } }
    }

    private fun assertFourDp() = rule.onNodeWithTag(BAR).assertHeightIsEqualTo(4.dp)

    @Test
    fun theBarIsFourDpTallInEveryStateOnEveryFrame() {
        show(0.2f)
        assertFourDp()
        progress = 0.8f
        repeat(20) { frame(); assertFourDp() }
        loading = false
        repeat(20) { frame(); assertFourDp() } // the fade
        repeat(5) { frame(); assertFourDp() } // nothing drawn
        loading = true
        progress = 0.1f
        repeat(5) { frame(); assertFourDp() }
    }

    @Test
    fun whileLoadingTheFilledWidthNeverShrinks() {
        show(0.1f)
        var last = fillEdge()
        assertTrue("something is drawn", last > 0)
        for (i in 1..60) {
            if (i % 6 == 0) progress = 0.1f + 0.012f * i
            frame()
            val edge = fillEdge()
            assertTrue("shrank from $last to $edge at frame $i", edge >= last)
            last = edge
        }
    }

    @Test
    fun aNewLoadSnapsToItsOwnValue() {
        show(0.7f)
        frame(40)
        val high = fillEdge()
        loading = false
        frame(20) // the fade is over
        loading = true
        progress = 0.1f
        frame()
        val edge = fillEdge()
        assertTrue("snapped from $high to a small value, was $edge", edge in 1 until high / 3)
    }

    @Test
    fun afterLoadingEndsTheBarIsThereAt48msAndGoneAfterTheFade() {
        show(0.5f)
        loading = false
        frame(3) // 48 ms
        assertTrue("still drawn at 48 ms", anyBarPixel())
        frame((PROGRESS_FADE_MS + 32) / 16 - 3 + 1)
        assertFalse("gone after the fade", anyBarPixel())
    }

    @Test
    fun aNewLoadDuringTheFadeIsOpaqueOnTheNextFrame() {
        show(0.6f)
        frame(20)
        loading = false
        frame(3)
        loading = true
        progress = 0.5f
        frame()
        val map = pixels()
        val y = map.height / 2
        val x = map.width / 4 // well inside the fill
        assertEquals(Color.Red, map[x, y])
        assertFourDp()
    }

    @Test
    fun withMotionOffTheBarComesAndGoesOnTheNextFrame() {
        show(0.5f, isLoading = false, motionOn = false)
        assertFalse(anyBarPixel())
        loading = true
        frame()
        assertTrue(anyBarPixel())
        progress = 0.9f
        frame()
        assertTrue("progress snapped", fillEdge() > pixels().width * 8 / 10)
        loading = false
        frame()
        assertFalse(anyBarPixel())
        assertFourDp()
    }

    @Test
    fun progressFramesDoNotRecomposeTheParent() {
        show(0.1f)
        progress = 0.9f
        frame()
        val parent = parentRuns
        val feeder = feederRuns
        frame(60) // the glide
        assertEquals("parent", parent, parentRuns)
        assertEquals("the scope that feeds the bar", feeder, feederRuns)
    }

    private companion object {
        const val BAR = "loading-bar"
    }
}
