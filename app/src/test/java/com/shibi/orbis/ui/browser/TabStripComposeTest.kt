package com.shibi.orbis.ui.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
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
        var primary = 0
        rule.setContent {
            MaterialTheme {
                primary = MaterialTheme.colorScheme.primary.toArgb()
                TabStrip(
                    tabs = listOf(tab(1, "Alpha"), tab(2, "Beta")),
                    onSelect = { selectedId = it },
                    onClose = {},
                    onNewTab = {},
                    onDropOpen = {},
                )
            }
        }
        rule.waitForIdle()
        // The drag picture is recorded on first draw. Capture before switching so that draw has happened.
        assertEquals("tab 1 starts with the primary border", primary and RGB, topStrokeArgb(1) and RGB)
        rule.onNodeWithTag(tabChipTag(2)).performClick()
        // The border colour is animated: the first frame after the click can still carry the old colour. Let the
        // animation finish, then wait until a frame with tab 2's border has been drawn: on a slow CI runner the
        // captured pixels lagged behind (run 43280637189), so a fixed clock step alone was not enough.
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 5_000) { topStrokeArgb(2) and RGB == primary and RGB }
        assertNotEquals("tab 1 kept the primary border $primary", primary and RGB, topStrokeArgb(1) and RGB)
        assertEquals("tab 2 border", primary and RGB, topStrokeArgb(2) and RGB)
    }

    /** Pixel in the 2.dp top stroke, midway along the chip, clear of the rounded corners. */
    private fun topStrokeArgb(id: Long): Int {
        val map = rule.onNodeWithTag(tabChipTag(id)).captureToImage().toPixelMap()
        return map[map.width / 2, 1].toArgb()
    }

    private fun assertOnly(selected: Long) {
        for (id in listOf(1L, 2L, 3L, 4L)) {
            val node = rule.onNodeWithTag(tabChipTag(id))
            if (id == selected) node.assertIsSelected() else node.assertIsNotSelected()
        }
    }

    private companion object {
        const val RGB = 0xFFFFFF
    }
}
