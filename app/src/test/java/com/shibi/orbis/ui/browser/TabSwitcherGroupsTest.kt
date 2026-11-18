package com.shibi.orbis.ui.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.shibi.orbis.tabs.TabGroup
import com.shibi.orbis.tabs.TabGroupColor
import com.shibi.orbis.tabs.TabUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The group controls of the production [TabSwitcher] sheet. TabManager's own group rules are tested in tabs/. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h2400dp")
class TabSwitcherGroupsTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val events = mutableListOf<String>()
    private var groups by mutableStateOf(listOf(TabGroup(10, "Work", TabGroupColor.BLUE)))
    private var tabs by mutableStateOf(
        listOf(
            TabUi(1, "Alpha page", "https://alpha.example/", selected = true, groupId = 10),
            TabUi(2, "Beta page", "https://beta.example/a", selected = false, groupId = 10),
            TabUi(3, "Gamma page", "https://gamma.example/", selected = false),
            TabUi(4, "Secret page", "https://secret.example/", selected = false, isPrivate = true),
        ),
    )

    private val actions = TabGroupActions(
        onCreate = { tab, name, color -> events += "create:$tab:$name:$color" },
        onAdd = { tab, group -> events += "add:$tab:$group" },
        onRemove = { events += "remove:$it" },
        onRename = { id, name -> events += "rename:$id:$name" },
        onColor = { id, color -> events += "color:$id:$color" },
        onCollapsed = { id, collapsed ->
            events += "collapsed:$id:$collapsed"
            groups = groups.map { if (it.id == id) it.copy(collapsed = collapsed) else it }
        },
        onUngroup = { events += "ungroup:$it" },
        onCloseGroup = { events += "closeGroup:$it" },
    )

    private fun start(withActions: Boolean = true) {
        rule.setContent {
            MaterialTheme {
                TabSwitcher(
                    tabs = tabs,
                    onSelect = { events += "select:$it" },
                    onClose = { events += "close:$it" },
                    onNewTab = {},
                    onNewPrivateTab = {},
                    onClosePrivateTabs = {},
                    onDismiss = {},
                    groups = groups,
                    groupActions = if (withActions) actions else null,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun settle() = rule.waitForIdle()

    private fun longPress(tag: String) {
        rule.onNodeWithTag(tag).performTouchInput { longClick() }
        rule.waitForIdle()
    }

    @Test
    fun aGroupShowsItsNameAndCountAboveItsTabs() {
        start()
        rule.onNodeWithTag(groupHeaderTag(10)).assertExists()
        rule.onNodeWithText("Work").assertExists()
        rule.onNodeWithText("2 tabs").assertExists()
        rule.onNodeWithTag(tabRowTag(1)).assertExists()
        rule.onNodeWithTag(tabRowTag(2)).assertExists()
        rule.onNodeWithTag(tabRowTag(3)).assertExists()
    }

    @Test
    fun aCollapsedGroupHidesItsTabsButKeepsItsHeader() {
        groups = listOf(TabGroup(10, "Work", TabGroupColor.BLUE, collapsed = true))
        start()
        rule.onNodeWithTag(groupHeaderTag(10)).assertExists()
        rule.onNodeWithText("2 tabs").assertExists()
        rule.onNodeWithTag(tabRowTag(1)).assertDoesNotExist()
        rule.onNodeWithTag(tabRowTag(2)).assertDoesNotExist()
        rule.onNodeWithTag(tabRowTag(3)).assertExists()
    }

    @Test
    fun theArrowCollapsesAndExpandsTheGroup() {
        start()
        rule.onNodeWithContentDescription("Collapse group Work").performClick()
        settle()
        rule.onNodeWithTag(tabRowTag(1)).assertDoesNotExist()
        rule.onNodeWithContentDescription("Expand group Work").performClick()
        settle()
        rule.onNodeWithTag(tabRowTag(1)).assertExists()
        assertEquals(listOf("collapsed:10:true", "collapsed:10:false"), events)
    }

    @Test
    fun aPrivateTabHasNoGroupMenu() {
        start()
        longPress(tabRowTag(4))
        // Without a long-press action the lift counts as a tap, as it did before groups: the tab is selected.
        rule.onNodeWithText("Add to group").assertDoesNotExist()
        assertEquals(listOf("select:4"), events)
    }

    @Test
    fun aNormalTabOffersAddToGroupAndRemoveOnlyWhenGrouped() {
        start()
        longPress(tabRowTag(3))
        rule.onNodeWithText("Add to group").assertExists()
        rule.onNodeWithText("Remove from group").assertDoesNotExist()
        rule.onNodeWithText("Add to group").performClick()
        settle()
        rule.onNode(hasText("Work") and hasAnyAncestor(isPopup())).assertExists()
        rule.onNodeWithText("New group").assertExists()
    }

    @Test
    fun aGroupedTabCanLeaveItsGroup() {
        start()
        longPress(tabRowTag(1))
        rule.onNodeWithText("Remove from group").performClick()
        settle()
        assertEquals(listOf("remove:1"), events)
    }

    @Test
    fun addingToAGroupGoesThroughTheGroupList() {
        start()
        longPress(tabRowTag(3))
        rule.onNodeWithText("Add to group").performClick()
        settle()
        rule.onNode(hasText("Work") and hasAnyAncestor(isPopup())).performClick()
        settle()
        assertEquals(listOf("add:3:10"), events)
    }

    @Test
    fun closeGroupAsksFirst() {
        start()
        rule.onNodeWithTag(groupMenuTag(10)).performClick()
        rule.onNodeWithText("Close group").performClick()
        settle()
        rule.onNodeWithText("Close 2 tabs?").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        settle()
        assertEquals(emptyList<String>(), events)
        rule.onNodeWithTag(groupMenuTag(10)).performClick()
        rule.onNodeWithText("Close group").performClick()
        settle()
        rule.onNodeWithText("Close").performClick()
        settle()
        assertEquals(listOf("closeGroup:10"), events)
    }

    @Test
    fun ungroupReachesTheTabManager() {
        start()
        rule.onNodeWithTag(groupMenuTag(10)).performClick()
        settle()
        rule.onNodeWithText("Ungroup").performClick()
        settle()
        assertEquals(listOf("ungroup:10"), events)
    }

    @Test
    fun withoutGroupActionsNothingChangesInTheGrid() {
        start(withActions = false)
        rule.onNodeWithTag(groupHeaderTag(10)).assertDoesNotExist()
        longPress(tabRowTag(3))
        rule.onNodeWithText("Add to group").assertDoesNotExist()
    }
}
