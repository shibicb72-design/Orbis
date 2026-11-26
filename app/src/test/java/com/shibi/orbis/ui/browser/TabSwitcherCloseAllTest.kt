package com.shibi.orbis.ui.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shibi.orbis.tabs.TabUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The "Close all tabs" entry of the production [TabSwitcher] sheet. The manager's side is tested in tabs/. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h2400dp")
class TabSwitcherCloseAllTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val events = mutableListOf<String>()

    private fun start(tabs: List<TabUi>, withEntry: Boolean = true) {
        rule.setContent {
            MaterialTheme {
                TabSwitcher(
                    tabs = tabs,
                    onSelect = { events += "select:$it" },
                    onClose = { events += "close:$it" },
                    onNewTab = {},
                    onNewPrivateTab = {},
                    onClosePrivateTabs = { events += "closePrivate" },
                    onDismiss = { events += "dismiss" },
                    onCloseAllTabs = if (withEntry) ({ events += "closeAll" }) else null,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun normal(id: Long) = TabUi(id, "Page $id", "https://p$id.example/", selected = id == 1L)

    private val private = TabUi(9, "Secret", "https://secret.example/", selected = false, isPrivate = true)

    @Test
    fun twoNormalTabsShowTheEntryAndTappingItRunsTheActionOnlyOnce() {
        start(listOf(normal(1), normal(2), private))
        rule.onNodeWithText("Close all tabs").assertExists()
        // The private tabs keep their own entry.
        rule.onNodeWithText("Close private tabs").assertExists()
        rule.onNodeWithText("Close all tabs").performClick()
        rule.waitForIdle()
        assertEquals(listOf("closeAll"), events)
        // The sheet stays open: neither a dismiss nor a selection happened.
        rule.onNodeWithText("Close all tabs").assertExists()
    }

    @Test
    fun oneNormalTabHasNoEntryEvenWithPrivateTabsOpen() {
        start(listOf(normal(1), private))
        rule.onNodeWithText("Close all tabs").assertDoesNotExist()
        rule.onNodeWithText("Close private tabs").assertExists()
    }

    @Test
    fun onlyPrivateTabsHaveNoEntry() {
        start(listOf(private.copy(id = 8), private))
        rule.onNodeWithText("Close all tabs").assertDoesNotExist()
    }

    @Test
    fun withoutTheActionThereIsNoEntry() {
        start(listOf(normal(1), normal(2)), withEntry = false)
        rule.onNodeWithText("Close all tabs").assertDoesNotExist()
    }
}
