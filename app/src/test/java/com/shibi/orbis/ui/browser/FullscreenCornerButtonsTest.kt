package com.shibi.orbis.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [FullscreenCornerButtons] in a 300 dp square: sizes, placement, hit areas, semantics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FullscreenCornerButtonsTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private var casts = 0
    private var pips = 0
    private var pageTaps = 0

    private fun show(showPip: Boolean = true) {
        casts = 0
        pips = 0
        pageTaps = 0
        rule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Box(Modifier.size(300.dp).testTag(CONTAINER)) {
                    // Stand-in for the page (YouTube's own controls), under the buttons as in the app.
                    Box(Modifier.fillMaxSize().clickable { pageTaps++ })
                    FullscreenCornerButtons(
                        casting = false,
                        showPip = showPip,
                        onCast = { casts++ },
                        onPip = { pips++ },
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun touch(description: String): SemanticsNodeInteraction =
        rule.onNode(hasContentDescription(description) and hasClickAction())

    private fun visual(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true)

    @Test
    fun visualsAre24dpAndTouchSquaresAre32dp() {
        show()
        for (tag in listOf(FULLSCREEN_CAST_TAG, FULLSCREEN_PIP_TAG)) {
            visual(tag).assertWidthIsEqualTo(FULLSCREEN_CORNER_VISUAL).assertHeightIsEqualTo(FULLSCREEN_CORNER_VISUAL)
        }
        for (d in listOf("Cast", "Picture-in-picture")) {
            touch(d).assertWidthIsEqualTo(FULLSCREEN_CORNER_TOUCH).assertHeightIsEqualTo(FULLSCREEN_CORNER_TOUCH)
        }
        assertEquals(24.dp, FULLSCREEN_CORNER_VISUAL)
        assertEquals(32.dp, FULLSCREEN_CORNER_TOUCH)
    }

    @Test
    fun iconIs16dp() {
        show()
        rule.onNode(hasContentDescription("Cast") and !hasClickAction(), useUnmergedTree = true)
            .assertWidthIsEqualTo(FULLSCREEN_CORNER_ICON).assertHeightIsEqualTo(FULLSCREEN_CORNER_ICON)
        rule.onNode(hasContentDescription("Picture-in-picture") and !hasClickAction(), useUnmergedTree = true)
            .assertWidthIsEqualTo(FULLSCREEN_CORNER_ICON).assertHeightIsEqualTo(FULLSCREEN_CORNER_ICON)
        assertEquals(16.dp, FULLSCREEN_CORNER_ICON)
    }

    @Test
    fun castVisualIs4dpFromTopAndEndWithNoRoundedCorner() {
        show()
        val box = rule.onNodeWithTag(CONTAINER).getBoundsInRoot()
        val cast = visual(FULLSCREEN_CAST_TAG).getBoundsInRoot()
        assertEquals(4f, (cast.top - box.top).value, 0.01f)
        assertEquals(4f, (box.right - cast.right).value, 0.01f)
        assertEquals(FULLSCREEN_CORNER_MARGIN, 4.dp)
    }

    @Test
    fun pipVisualIsInsetFromTheEndLikeCast() {
        show()
        val cast = visual(FULLSCREEN_CAST_TAG).getBoundsInRoot()
        val pip = visual(FULLSCREEN_PIP_TAG).getBoundsInRoot()
        assertEquals(cast.right.value, pip.right.value, 0.01f)
    }

    @Test
    fun pipTouchNodeStartsWhereCastEnds() {
        show()
        val cast = touch("Cast").getBoundsInRoot()
        val pip = touch("Picture-in-picture").getBoundsInRoot()
        assertEquals(cast.bottom.value, pip.top.value, 0.01f)
        assertEquals(cast.left.value, pip.left.value, 0.01f)
    }

    @Test
    fun noPipNodeWhenShowPipIsFalse() {
        show(showPip = false)
        visual(FULLSCREEN_PIP_TAG).assertDoesNotExist()
        rule.onNode(hasContentDescription("Picture-in-picture"), useUnmergedTree = true).assertDoesNotExist()
        visual(FULLSCREEN_CAST_TAG).assertExists()
    }

    @Test
    fun clickAnywhereInsideATouchSquareCallsItOnce() {
        show()
        for ((description, read) in listOf<Pair<String, () -> Int>>("Cast" to { casts }, "Picture-in-picture" to { pips })) {
            val node = touch(description)
            val size = node.fetchSemanticsNode().size
            val inside = listOf(
                Offset(1f, 1f),
                Offset(size.width - 2f, 1f),
                Offset(1f, size.height - 2f),
                Offset(size.width - 2f, size.height - 2f),
                Offset(size.width / 2f, size.height / 2f),
            )
            for ((i, p) in inside.withIndex()) {
                val before = read()
                node.performTouchInput { click(p) }
                rule.waitForIdle()
                assertEquals("$description point $i", before + 1, read())
            }
        }
        assertEquals(5, casts)
        assertEquals(5, pips)
    }

    @Test
    fun tapsJustOutsideATouchSquareReachThePageNotTheButtons() {
        show()
        val box = rule.onNodeWithTag(CONTAINER).getBoundsInRoot()
        val cast = touch("Cast").getBoundsInRoot()
        val pip = touch("Picture-in-picture").getBoundsInRoot()
        // Container coordinates, in dp.
        fun x(v: androidx.compose.ui.unit.Dp) = (v - box.left).value
        fun y(v: androidx.compose.ui.unit.Dp) = (v - box.top).value
        val taps = listOf(
            x(cast.left) - 2f to y((cast.top + cast.bottom) / 2),
            x(pip.left) - 2f to y((pip.top + pip.bottom) / 2),
            x((pip.left + pip.right) / 2) to y(pip.bottom) + 2f,
            x(cast.left) - 6f to y((cast.top + cast.bottom) / 2),
            x(pip.left) - 6f to y((pip.top + pip.bottom) / 2),
        )
        for ((i, t) in taps.withIndex()) {
            rule.onNodeWithTag(CONTAINER).performTouchInput { click(Offset(t.first.dp.toPx(), t.second.dp.toPx())) }
            rule.waitForIdle()
            assertEquals("tap $i reached the page", i + 1, pageTaps)
        }
        assertEquals(0, casts)
        assertEquals(0, pips)
    }

    @Test
    fun descriptionsAndRoles() {
        show()
        for (d in listOf("Cast", "Picture-in-picture")) {
            touch(d).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button),
            )
        }
    }

    @Test
    fun cornerInsetIsTheMarginOrThirtyPercentOfTheRadius() {
        assertEquals(4f, cornerInsetDp(0f), 0.001f)
        assertEquals(9f, cornerInsetDp(30f), 0.001f)
        assertEquals(12f, cornerInsetDp(40f), 0.001f)
    }

    private companion object {
        const val CONTAINER = "container"
    }
}
