package com.shibi.orbis.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.shibi.orbis.stats.MemoryReading
import com.shibi.orbis.stats.MemorySample
import com.shibi.orbis.stats.ProcessKind
import com.shibi.orbis.stats.ProcessMemory
import com.shibi.orbis.tabs.TabActivity
import com.shibi.orbis.tabs.TabOverview
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MemorySectionComposeTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val zone = ZoneId.of("UTC")
    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val selected = mutableListOf<Long>()
    private val closed = mutableListOf<Long>()
    private var deletes = 0

    private fun reading(webCount: Int = 3, limited: Boolean = false) =
        MemoryReading(
            measuredAt = now - 2 * minute,
            processes =
                listOf(ProcessMemory(ProcessKind.APP, 300 * 1024L), ProcessMemory(ProcessKind.OTHER, 50 * 1024L)) +
                    (1..webCount).map { ProcessMemory(ProcessKind.WEB, (100L + it) * 1024) },
            rateLimited = limited,
        )

    private fun show(
        reading: MemoryReading? = reading(),
        tabs: List<TabOverview> = emptyList(),
        history: List<MemorySample> = emptyList(),
    ) {
        rule.setContent {
            var range by androidx.compose.runtime.remember { mutableStateOf(MemoryRange.DAY) }
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    MemorySection(
                        reading = reading,
                        tabs = tabs,
                        history = history,
                        now = now,
                        range = range,
                        onRange = { range = it },
                        onSelectTab = { selected += it },
                        onCloseTab = { closed += it },
                        onDeleteHistory = { deletes++ },
                        zone = zone,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun text(s: String): SemanticsNodeInteraction = rule.onNodeWithText(s).performScrollTo()

    private fun tab(id: Long, title: String, lookedMinutesAgo: Long?, activity: TabActivity = TabActivity.AWAKE, private: Boolean = false) =
        TabOverview(id, title, private, activity, lookedMinutesAgo?.let { now - it * minute })

    @Test
    fun theWordsOfTheSection() {
        show(reading(webCount = 3, limited = true))
        text("Memory")
        text("Memory used by Orbis")
        // 300 + 50 + 101 + 102 + 103 MB.
        text("656 MB")
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(now - 2 * minute).atZone(zone))
        text("measured at $time")
        text("Android updates this at most every 5 minutes.")
        text("Orbis app")
        text("300 MB")
        text("Web pages")
        text("306 MB")
        text("Other browser processes")
        text("50 MB")
        text("Largest web-page processes")
        text("Each process holds the tabs of one site. Orbis cannot tell which site: the browser engine does not say.")
        text("Web-page process 1")
        text("103 MB")
        text("Web-page process 3")
        text("24 hours")
        text("7 days")
        text("30 days")
        text("No memory history yet.")
        text("Delete memory history")
    }

    @Test
    fun theRateLimitLineIsShownOnlyForALimitedReading() {
        show(reading(limited = false))
        assertEquals(0, rule.onAllNodesWithText("Android updates this at most every 5 minutes.").fetchSemanticsNodes().size)
    }

    @Test
    fun tenProcessRowsThenShowAll() {
        show(reading(webCount = 12))
        assertEquals(10, rule.onAllNodesWithTag(MEMORY_PROCESS_ROW_TAG).fetchSemanticsNodes().size)
        text("Show all").performClick()
        assertEquals(12, rule.onAllNodesWithTag(MEMORY_PROCESS_ROW_TAG).fetchSemanticsNodes().size)
        text("Show fewer")
    }

    @Test
    fun tabsAreListedLongestNotLookedAtFirstWithTheirStates() {
        show(
            tabs =
                listOf(
                    tab(1, "Recent", 0),
                    tab(2, "Old", 3 * 60, TabActivity.ASLEEP),
                    tab(3, "Music", 5, TabActivity.PLAYING),
                    tab(4, "Unknown", null),
                    tab(5, "Days", 3 * 24 * 60),
                ),
        )
        text("Open tabs")
        val titles = rowTitles()
        assertEquals(listOf("Unknown", "Days", "Old", "Music", "Recent"), titles)
        text("awake · last looked at: not known")
        text("awake · last looked at 3 days ago")
        text("asleep · last looked at 3 h ago")
        text("playing · last looked at 5 min ago")
        text("awake · last looked at just now")
    }

    @Test
    fun aPrivateTabShowsAsPrivateTabWithNoTitle() {
        show(tabs = listOf(tab(1, "Secret page title", 2, private = true), tab(2, "Normal", 1)))
        text("Private tab")
        assertEquals(0, rule.onAllNodesWithText("Secret page title", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun theTabListHasNoMemoryFigure() {
        show(tabs = (1L..12L).map { tab(it, "Tab $it", it) })
        text("Show all").performClick()
        val rows = rule.onAllNodesWithTag(MEMORY_TAB_ROW_TAG, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(12, rows.size)
        val words = rows.flatMap { textsOf(it) }
        assertTrue(words.isNotEmpty())
        assertFalse(words.joinToString(" "), words.any { Regex("\\d\\s*(MB|kB|KB|GB)").containsMatchIn(it) })
    }

    @Test
    fun aTapSwitchesToTheTabAndCloseClosesIt() {
        show(tabs = listOf(tab(7, "Seven", 30), tab(8, "Eight", 10)))
        text("Seven").performClick()
        assertEquals(listOf(7L), selected)
        rule.onAllNodesWithText("Close")[1].performScrollTo().performClick()
        assertEquals(listOf(8L), closed)
    }

    @Test
    fun tenTabRowsThenShowAll() {
        show(reading = null, tabs = (1L..11L).map { tab(it, "Tab $it", it) })
        text("Measuring…")
        assertEquals(10, rule.onAllNodesWithTag(MEMORY_TAB_ROW_TAG).fetchSemanticsNodes().size)
        text("Show all").performClick()
        assertEquals(11, rule.onAllNodesWithTag(MEMORY_TAB_ROW_TAG).fetchSemanticsNodes().size)
    }

    @Test
    fun theChartAndItsDeleteButton() {
        val sample = MemorySample(now - 60 * minute, 500 * 1024L, 300 * 1024L, 150 * 1024L, 50 * 1024L, 4, 2)
        val old = sample.copy(at = now - 3 * 24 * 60 * minute, totalKb = 900 * 1024L)
        show(history = listOf(old, sample))
        assertTrue(rule.onAllNodes(hasContentDescription("Memory used by Orbis: lowest 500 MB, highest 500 MB")).fetchSemanticsNodes().isNotEmpty())
        text("7 days").performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(hasContentDescription("Memory used by Orbis: lowest 500 MB, highest 900 MB")).fetchSemanticsNodes().isNotEmpty())
        text("Delete memory history").performClick()
        rule.onNodeWithText("Delete memory history?").assertExists()
        rule.onNodeWithText("The memory chart starts again from now. Nothing else is deleted.").assertExists()
        rule.onNodeWithText("Delete").performClick()
        assertEquals(1, deletes)
    }

    private fun hasContentDescription(value: String) =
        androidx.compose.ui.test.SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(value))

    private fun textsOf(node: androidx.compose.ui.semantics.SemanticsNode): List<String> =
        node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } +
            node.children.flatMap { textsOf(it) }

    private fun rowTitles(): List<String> =
        rule.onAllNodesWithTag(MEMORY_TAB_ROW_TAG).fetchSemanticsNodes().map { row ->
            row.config.getOrElse(SemanticsProperties.Text) { emptyList() }.first().text
        }
}
