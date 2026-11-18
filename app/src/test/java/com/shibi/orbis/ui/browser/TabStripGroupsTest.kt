package com.shibi.orbis.ui.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.shibi.orbis.tabs.TabGroup
import com.shibi.orbis.tabs.TabGroupColor
import com.shibi.orbis.tabs.TabUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Groups in the tab strip: the label, the collapsed rule and the colour line. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w1200dp-h1000dp")
class TabStripGroupsTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val events = mutableListOf<String>()
    private var selectedId by mutableStateOf(3L)
    private var groups by mutableStateOf(listOf(TabGroup(10, "Work", TabGroupColor.BLUE)))

    private fun tab(id: Long, groupId: Long? = null) =
        TabUi(id, "Page$id", "https://page$id.example/", selected = id == selectedId, groupId = groupId)

    private fun start() {
        rule.setContent {
            MaterialTheme {
                TabStrip(
                    tabs = listOf(tab(3), tab(1, groupId = 10), tab(2, groupId = 10), tab(4)),
                    onSelect = { selectedId = it },
                    onClose = {},
                    onNewTab = {},
                    onDropOpen = {},
                    groups = groups,
                    onToggleGroup = { id, collapsed ->
                        events += "collapsed:$id:$collapsed"
                        groups = groups.map { if (it.id == id) it.copy(collapsed = collapsed) else it }
                    },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun theLabelAppearsOnceBeforeBothChipsOfItsGroup() {
        start()
        rule.onAllNodesWithTag(stripGroupLabelTag(10)).assertCountEquals(1)
        val label = rule.onNodeWithTag(stripGroupLabelTag(10)).getUnclippedBoundsInRoot()
        val first = rule.onNodeWithTag(tabChipTag(1)).getUnclippedBoundsInRoot()
        val second = rule.onNodeWithTag(tabChipTag(2)).getUnclippedBoundsInRoot()
        val before = rule.onNodeWithTag(tabChipTag(3)).getUnclippedBoundsInRoot()
        assertTrue(before.left < label.left)
        assertTrue(label.right <= first.left)
        assertTrue(first.right <= second.left)
        rule.onNodeWithContentDescription("Group Work, 2 tabs, expanded").assertIsDisplayed()
    }

    @Test
    fun collapsedWithTheSelectedTabOutsideShowsNoChipOfTheGroup() {
        groups = listOf(TabGroup(10, "Work", TabGroupColor.BLUE, collapsed = true))
        start()
        rule.onNodeWithTag(stripGroupLabelTag(10)).assertIsDisplayed()
        rule.onAllNodesWithTag(tabChipTag(1)).assertCountEquals(0)
        rule.onAllNodesWithTag(tabChipTag(2)).assertCountEquals(0)
        rule.onNodeWithTag(tabChipTag(3)).assertIsDisplayed()
        rule.onNodeWithContentDescription("Group Work, 2 tabs, collapsed").assertIsDisplayed()
    }

    @Test
    fun collapsedWithTheSelectedTabInsideKeepsOnlyThatChip() {
        groups = listOf(TabGroup(10, "Work", TabGroupColor.BLUE, collapsed = true))
        selectedId = 2
        start()
        rule.onAllNodesWithTag(tabChipTag(1)).assertCountEquals(0)
        rule.onNodeWithTag(tabChipTag(2)).assertIsDisplayed()
    }

    @Test
    fun tappingTheLabelCollapsesAndThenExpands() {
        start()
        rule.onNodeWithTag(stripGroupLabelTag(10)).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag(tabChipTag(1)).assertCountEquals(0)
        rule.onNodeWithTag(stripGroupLabelTag(10)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(tabChipTag(1)).assertIsDisplayed()
        assertEquals(listOf("collapsed:10:true", "collapsed:10:false"), events)
    }

    @Test
    fun onlyGroupedChipsHaveTheColourLine() {
        start()
        rule.onNodeWithTag(tabChipLineTag(1), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(tabChipLineTag(2), useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodesWithTag(tabChipLineTag(3), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun aTabOfAnUnknownGroupIsAnOrdinaryTab() {
        val items = stripItems(listOf(tab(1, groupId = 99)), emptyList())
        assertEquals(1, items.size)
        assertTrue(items[0] is StripItem.Chip && (items[0] as StripItem.Chip).group == null)
    }
}
