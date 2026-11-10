package com.shibi.orbis.ui.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shibi.orbis.tabs.TabUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Slice 2: the production [TabSwitcher] sheet. TabManager behavior is covered unchanged by tabs/TabManagerTest et al. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h2400dp")
class TabSwitcherComposeTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val events = mutableListOf<String>()
    private var ended = mutableListOf<TabSheetOutcome>()
    private var tabs by mutableStateOf(
        listOf(
            TabUi(1, "Alpha page", "https://alpha.example/", selected = true),
            TabUi(2, "Beta page", "https://beta.example/a", selected = false),
            TabUi(3, "Secret page", "https://secret.example/", selected = false, isPrivate = true),
            TabUi(4, "", "", selected = false),
        ),
    )

    private fun start() {
        rule.setContent {
            MaterialTheme {
                TabSwitcher(
                    tabs = tabs,
                    onSelect = { events += "select:$it" },
                    onClose = { id ->
                        events += "close:$id"
                        tabs = tabs.filterNot { it.id == id }
                    },
                    onNewTab = { events += "new" },
                    onNewPrivateTab = { events += "newPrivate" },
                    onClosePrivateTabs = { events += "closePrivate" },
                    onDismiss = { events += "dismiss" },
                    onEnded = { ended += it },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun theSelectedRowSaysSoInTextNotOnlyByColor() {
        start()
        rule.onNodeWithTag(CURRENT_TAB_LABEL_TAG, useUnmergedTree = true).assertExists()
        assertEquals(
            "exactly one current-tab label",
            1,
            rule.onAllNodesWithTag(CURRENT_TAB_LABEL_TAG, useUnmergedTree = true).fetchSemanticsNodes().size,
        )
        assertEquals(0, rule.onAllNodesWithText("Current tab").fetchSemanticsNodes().size)
        rule.onNodeWithTag(tabRowTag(1)).assertIsSelected()
    }

    @Test
    fun withManyTabsTheSheetOpensScrolledToTheCurrentOne() {
        tabs = (1L..40L).map { TabUi(it, "Page $it", "https://p$it.example/", selected = it == 33L) }
        start()
        rule.onNodeWithTag(tabRowTag(33)).assertIsDisplayed()
        rule.onNodeWithTag(CURRENT_TAB_LABEL_TAG, useUnmergedTree = true).assertExists()
    }

    @Test
    fun closeButtonsNameTheirTabAndThePrivateOnesSaySo() {
        start()
        // assertExists: the sheet opens half-expanded, so lower rows may be below the fold.
        rule.onNodeWithContentDescription("Close tab Alpha page").assertExists()
        rule.onNodeWithContentDescription("Close tab Beta page").assertExists()
        rule.onNodeWithContentDescription("Close private tab Secret page").assertExists()
        // A blank tab is named as such.
        rule.onNodeWithContentDescription("Close tab New tab").assertExists()
    }

    @Test
    fun aDoubleTapSelectsOnceAndNothingElseRunsAfterIt() {
        start()
        rule.onNodeWithTag(tabRowTag(2)).performClick()
        rule.onNodeWithTag(tabRowTag(2)).performClick()
        rule.onNodeWithTag(tabRowTag(1)).performClick()
        rule.onAllNodesWithText("New tab")[0].performClick()
        rule.waitForIdle()
        assertEquals(listOf("select:2"), events)
        assertEquals(listOf(TabSheetOutcome.SELECTED), ended)
    }

    @Test
    fun newTabRunsOnceAndThenSelectionIsIgnored() {
        start()
        rule.onAllNodesWithText("New tab")[0].performClick()
        rule.onNodeWithTag(tabRowTag(2)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("new"), events)
        assertEquals(listOf(TabSheetOutcome.NEW_TAB), ended)
    }

    @Test
    fun closingARowDoesNotEndTheSheetAndTheClosedIdIsGone() {
        start()
        rule.onNodeWithTag(tabCloseTag(2)).performClick()
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithText("Beta page").fetchSemanticsNodes().size)
        rule.onNodeWithTag(tabRowTag(1)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("close:2", "select:1"), events)
        assertEquals(listOf(TabSheetOutcome.SELECTED), ended)
    }

    @Test
    fun theHighlightFollowsTheSingleSelectedTab() {
        start()
        fun show(id: Long) {
            tabs = tabs.map { it.copy(selected = it.id == id) }
            rule.waitForIdle()
            for (row in listOf(1L, 2L, 3L, 4L)) {
                val node = rule.onNodeWithTag(tabRowTag(row))
                if (row == id) node.assertIsSelected() else node.assertIsNotSelected()
            }
            assertEquals(1, rule.onAllNodesWithTag(CURRENT_TAB_LABEL_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)
        }
        show(2)
        show(3)
        // The same tab again stays the only current one.
        show(3)
        show(1)
    }

    @Test
    fun closePrivateTabsEndsTheSheetOnce() {
        start()
        rule.onNodeWithText("Close private tabs").performClick()
        rule.onNodeWithTag(tabRowTag(1)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("closePrivate"), events)
        assertEquals(listOf(TabSheetOutcome.CLOSED_PRIVATE), ended)
    }
}
