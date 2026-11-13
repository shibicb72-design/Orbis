package com.shibi.orbis.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The ink ceiling depends on a group alpha: everything a scene draws is composited once at the coverage alpha, so
 * overlapping elements can never be more opaque than one. This proves Robolectric's native graphics really composite
 * an offscreen layer that way (an overlap pixel equals a single-circle pixel), so the scene pixel tests mean something.
 * It also pins why ModulateAlpha is not allowed: it applies the alpha per draw call and the overlap gets darker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SceneGroupAlphaSpikeTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private fun DrawScope.twoCircles() {
        // Left circle alone at x = 20, the overlap at x = 50, the right circle alone at x = 80.
        drawCircle(Color.White, radius = 35f, center = Offset(35f, 50f))
        drawCircle(Color.White, radius = 35f, center = Offset(65f, 50f))
    }

    private fun capture(strategy: CompositingStrategy): Triple<Int, Int, Int> {
        rule.setContent {
            Box(Modifier.size(100.dp).background(Color.Black).testTag("group")) {
                Canvas(
                    Modifier.size(100.dp).graphicsLayer {
                        alpha = 0.2f
                        compositingStrategy = strategy
                    },
                ) { twoCircles() }
            }
        }
        val px = rule.onNodeWithTag("group").captureToImage().toPixelMap()
        val sx = px.width / 100f
        fun at(x: Int) = px[(x * sx).toInt(), (50 * sx).toInt()].toArgb()
        return Triple(at(20), at(50), at(80))
    }

    @Test
    fun anOffscreenLayerAlphaIsAHardCeiling() {
        val (left, overlap, right) = capture(CompositingStrategy.Offscreen)
        val black = Color.Black.toArgb()
        assertNotEquals("the circle must be visible at all", black, left)
        assertEquals("overlap equals one circle", left, overlap)
        assertEquals(left, right)
        // 20 % white over black, within one step of rounding.
        val r = (left ushr 16) and 0xFF
        assertTrue("red channel $r", r in 50..52)
    }

    @Test
    fun modulateAlphaBreaksTheCeiling() {
        val (left, overlap, _) = capture(CompositingStrategy.ModulateAlpha)
        assertNotEquals("per-draw alpha stacks where circles overlap", left, overlap)
    }
}
