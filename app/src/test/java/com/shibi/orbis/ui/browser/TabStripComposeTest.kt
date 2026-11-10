package com.shibi.orbis.ui.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.shibi.orbis.tabs.TabUi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The strip's selected state (what TalkBack announces, and what paints the highlight) follows one
 * tab. Switching moves it; tapping the current tab again leaves it selected. Ordinary web tabs go
 * through the drag-out modifier; the private tab does not.
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
        // Tapping the current tab again does not clear it or select its neighbours.
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

    private fun assertOnly(selected: Long) {
        for (id in listOf(1L, 2L, 3L, 4L)) {
            val node = rule.onNodeWithTag(tabChipTag(id))
            if (id == selected) node.assertIsSelected() else node.assertIsNotSelected()
        }
    }
}
