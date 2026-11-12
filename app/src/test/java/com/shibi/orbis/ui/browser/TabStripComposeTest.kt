package com.shibi.orbis.ui.browser

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.shibi.orbis.tabs.TabUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [onlyTheCurrentTabIsHighlightedAfterSwitchingAndRetap] checks TalkBack's selected flag. That flag
 * was already correct before the stuck-highlight bug, so it does not prove what is painted.
 * [leavingATabClearsItsPrimaryBorder] reads the border pixels. The one-argument drag source caches
 * the chip's picture the first time it is drawn and keeps painting that picture, so the test
 * captures tab 1 while it is current and then switches away.
 *
 * The pixel tests turn off the press ripple ([NoRipple]). The chip's ripple is a framework RippleDrawable that fades
 * on wall-clock time, not on the Compose test clock, and it tints the clicked chip's border for about a second. On a
 * slow runner a capture landed inside that fade and read the new tab's border as not primary (runs 43280637189,
 * 43290557225, 43309143702: a darkened primary such as 0x634E9E, never the old tab keeping its border).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w1200dp-h1000dp")
class TabStripComposeTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private var selectedId by mutableStateOf(1L)

    private fun tab(id: Long, title: String, private: Boolean = false) =
        TabUi(id, title, "https://$title.example/", selected = id == selectedId, isPrivate = private)

    @Test
    fun onlyTheCurrentTabIsHighlightedAfterSwitchingAndRetap() {
        rule.setContent {
            MaterialTheme {
                TabStrip(
                    tabs = listOf(tab(1, "Alpha"), tab(2, "Beta"), tab(3, "Gamma"), tab(4, "Secret", private = true)),
                    onSelect = { selectedId = it },
                    onClose = {},
                    onNewTab = {},
                    onDropOpen = {},
                )
            }
        }
        rule.waitForIdle()
        assertOnly(1)
        rule.onNodeWithTag(tabChipTag(2)).performClick()
        rule.waitForIdle()
        assertOnly(2)
        rule.onNodeWithTag(tabChipTag(3)).performClick()
        rule.waitForIdle()
        assertOnly(3)
        // Tapping the current tab again does not clear its selected flag.
        rule.onNodeWithTag(tabChipTag(3)).performClick()
        rule.waitForIdle()
        assertOnly(3)
        rule.onNodeWithTag(tabChipTag(4)).performClick()
        rule.waitForIdle()
        assertOnly(4)
        rule.onNodeWithTag(tabChipTag(4)).performClick()
        rule.waitForIdle()
        assertOnly(4)
        rule.onNodeWithTag(tabChipTag(1)).performClick()
        rule.waitForIdle()
        assertOnly(1)
    }

    /**
     * Records tab 1's picture while it is current, then selects tab 2. Tab 1's top stroke must no
     * longer be the theme primary, and tab 2's must be. Fails with the one-argument
     * `dragAndDropSource`, which draws the cached picture in place of the chip.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun leavingATabClearsItsPrimaryBorder() {
        val primary = setStrip(listOf(tab(1, "Alpha"), tab(2, "Beta")))
        // The drag picture is recorded on first draw. Capture before switching so that draw has happened.
        assertEquals("tab 1 starts with the primary border", primary, topStrokeRgb(1))
        rule.onNodeWithTag(tabChipTag(2)).performClick()
        rule.waitForIdle()
        assertNotEquals("tab 1 kept the primary border $primary", primary, topStrokeRgb(1))
        assertEquals("tab 2 border", primary, topStrokeRgb(2))
    }

    /** After every switch, only the current chip is painted with the primary border. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun everySwitchPaintsOnlyTheCurrentChipsBorder() {
        val primary = setStrip(listOf(tab(1, "Alpha"), tab(2, "Beta"), tab(3, "Gamma")))
        for (target in listOf(2L, 1L, 3L, 2L, 3L, 1L, 2L, 1L, 3L, 2L, 3L, 1L)) {
            rule.onNodeWithTag(tabChipTag(target)).performClick()
            rule.waitForIdle()
            for (id in 1L..3L) {
                val painted = topStrokeRgb(id) == primary
                assertEquals("after selecting tab $target, tab $id primary border", id == target, painted)
            }
        }
    }

    /** Sets a strip of [tabs] without the press ripple; returns the theme primary as RGB. */
    private fun setStrip(tabs: List<TabUi>): Int {
        var primary = 0
        rule.setContent {
            MaterialTheme {
                primary = MaterialTheme.colorScheme.primary.toArgb() and RGB
                CompositionLocalProvider(LocalIndication provides NoRipple) {
                    TabStrip(
                        tabs = tabs.map { it.copy(selected = it.id == selectedId) },
                        onSelect = { selectedId = it },
                        onClose = {},
                        onNewTab = {},
                        onDropOpen = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        return primary
    }

    /** Pixel in the 2.dp top stroke, midway along the chip, clear of the rounded corners. */
    private fun topStrokeRgb(id: Long): Int {
        val map = rule.onNodeWithTag(tabChipTag(id)).captureToImage().toPixelMap()
        return map[map.width / 2, 1].toArgb() and RGB
    }

    private fun assertOnly(selected: Long) {
        for (id in listOf(1L, 2L, 3L, 4L)) {
            val node = rule.onNodeWithTag(tabChipTag(id))
            if (id == selected) node.assertIsSelected() else node.assertIsNotSelected()
        }
    }

    /** Press feedback that draws nothing, so a capture never depends on how far the ripple has faded. */
    private object NoRipple : IndicationNodeFactory {
        override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}

        override fun equals(other: Any?) = other === this

        override fun hashCode() = 0
    }

    private companion object {
        const val RGB = 0xFFFFFF
    }
}
