package com.shibi.orbis.ui.browser

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.shibi.orbis.tabs.TabUi
import com.shibi.orbis.ui.theme.TAB_ENTER_MS
import com.shibi.orbis.ui.theme.TAB_EXIT_MS
import com.shibi.orbis.ui.theme.TAB_PLACEMENT_MS
import com.shibi.orbis.ui.theme.frames
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * Tab chips fade in when a tab opens and out when it closes, on a white page so a chip's pixel can be read. The clock
 * is held; one frame is 16 ms. The animator scale is the system setting, as on a device.
 *
 * The exit fade has no pixel assertion. A leaving lazy item is disposed at once and only its layer keeps drawing, and
 * Robolectric does not draw that layer: even a bare LazyRow with `animateItem(fadeOutSpec = tween(1000))` shows nothing
 * where the removed item was, from the first frame. The fade was checked on an API 36 emulator at animator scale 10
 * instead (themes PR 3b): a closed chip fades while the chips after it slide over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w1200dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabStripMotionComposeTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val resolver get() = ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver
    private val selects = mutableListOf<Long>()
    private var container = Color.Unspecified
    private var tabs by mutableStateOf((1L..4L).map { tab(it) })

    private fun tab(id: Long) = TabUi(id, "Tab $id", "https://t$id.example/", selected = id == 1L)

    @After
    fun reset() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    private fun show(scale: Float = 1f) {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                container = MaterialTheme.colorScheme.surfaceContainer
                Box(Modifier.fillMaxWidth().background(Color.White)) {
                    TabStrip(tabs = tabs, onSelect = { selects += it }, onClose = {}, onNewTab = {}, onDropOpen = {})
                }
            }
        }
        rule.frames(2)
    }

    /** Frames that cover [ms] from now. */
    private fun framesFor(ms: Int) = (ms + 15) / 16

    /** A pixel of an unselected chip's container, clear of the corners and the title. */
    private fun chipPixel(id: Long): Int {
        val map = rule.onNodeWithTag(tabChipTag(id)).captureToImage().toPixelMap()
        val inset = with(rule.density) { 4.dp.roundToPx() }
        return map[inset, map.height / 2].toArgb()
    }

    private fun chipNodes() =
        rule.onAllNodes(SemanticsMatcher("a tab chip") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("tab_chip_") == true })
            .fetchSemanticsNodes()

    private fun assertChipsAre180By36() {
        val width = with(rule.density) { 180.dp.roundToPx() }
        val height = with(rule.density) { 36.dp.roundToPx() }
        val nodes = chipNodes()
        assertTrue("chips are on screen", nodes.isNotEmpty())
        for (node in nodes) {
            assertEquals("width of ${node.config.getOrNull(SemanticsProperties.TestTag)}", width, node.size.width)
            assertEquals("height of ${node.config.getOrNull(SemanticsProperties.TestTag)}", height, node.size.height)
        }
    }

    @Test
    fun anOpenedChipFadesInToItsContainerColour() {
        show()
        tabs = tabs + tab(5)
        rule.frames(1)
        assertNotEquals("not yet its container on the first frame", container.toArgb(), chipPixel(5))
        rule.frames(framesFor(TAB_ENTER_MS + 32) - 1)
        assertEquals(Integer.toHexString(container.toArgb()), Integer.toHexString(chipPixel(5)))
    }

    @Test
    fun everyChipIs180By36OnEveryFrameOfAnOpenAndAClose() {
        show()
        assertChipsAre180By36()
        tabs = tabs + tab(5)
        repeat(framesFor(TAB_ENTER_MS + 32)) {
            rule.frames(1)
            assertChipsAre180By36()
        }
        tabs = tabs.filterNot { it.id == 3L }
        repeat(framesFor(TAB_EXIT_MS + 32)) {
            rule.frames(1)
            assertChipsAre180By36()
        }
    }

    @Test
    fun aTapOnAnotherChipDuringTheExitSelectsAtOnce() {
        show()
        tabs = tabs.filterNot { it.id == 2L }
        rule.frames(3) // 48 ms: inside the exit, and tab 3 is still sliding into tab 2's place
        rule.onNodeWithTag(tabChipTag(3)).performClick()
        assertEquals(listOf(3L), selects)
    }

    @Test
    fun atAnimatorScaleZeroACloseAndAnOpenShowOnTheNextFrame() {
        show(scale = 0f)
        tabs = tabs.filterNot { it.id == 2L }
        rule.frames(1)
        rule.onNodeWithTag(tabChipTag(2)).assertDoesNotExist()
        tabs = tabs + tab(5)
        rule.frames(1)
        assertEquals(Integer.toHexString(container.toArgb()), Integer.toHexString(chipPixel(5)))
    }

    // Closing a tab: the chips after it slide into its place, whichever tab is current. The leaving chip's own fade
    // cannot be asserted here (see the class comment); these tests read the x of the chips that remain.

    /** [count] tabs with tab [current] selected, shown until the strip is still. */
    private fun showTabs(count: Int, current: Long, scale: Float = 1f) {
        tabs = (1L..count).map { TabUi(it, "Tab $it", "https://t$it.example/", selected = it == current) }
        show(scale)
        settle()
    }

    private fun settle() = rule.frames(framesFor(1000))

    /** Closes [closed] as the tab list does, with [next] current afterwards. */
    private fun close(closed: Long, next: Long) {
        tabs = tabs.filterNot { it.id == closed }.map { it.copy(selected = it.id == next) }
    }

    private fun selectTab(id: Long) {
        tabs = tabs.map { it.copy(selected = it.id == id) }
    }

    private fun chipX(id: Long): Int = rule.onNodeWithTag(tabChipTag(id)).fetchSemanticsNode().positionInRoot.x.roundToInt()

    /** Where the chip at [index] rests in a strip that is not scrolled: 8 dp of padding, then 180 dp chips 6 dp apart. */
    private fun restingX(index: Int): Int = with(rule.density) { (8 + 186 * index).dp.roundToPx() }

    /**
     * Five tabs, which all fit. [closed] is closed while [current] is current, and [next] is current after. Each chip
     * after the closed one is between its old and its new place at +48 ms, and in its new place at
     * TAB_PLACEMENT_MS + 32 ms. With [selectedBefore], that tab is current first and [current] is selected after it.
     */
    private fun assertCloseSlides(closed: Long, current: Long, next: Long, selectedBefore: Long? = null) {
        showTabs(5, selectedBefore ?: current)
        if (selectedBefore != null) {
            selectTab(current)
            settle()
        }
        val remaining = (1L..5L).filter { it != closed }
        val moving = remaining.filter { it > closed }
        close(closed, next)
        rule.frames(3)
        for (id in moving) {
            val x = chipX(id)
            val from = restingX(id.toInt() - 1)
            val to = restingX(id.toInt() - 2)
            assertTrue("tab $id slides at +48 ms: x $x, from $from to $to", x in (to + 1) until from)
        }
        rule.frames(framesFor(TAB_PLACEMENT_MS + 32) - 3)
        remaining.forEachIndexed { index, id -> assertEquals("tab $id at rest", restingX(index), chipX(id)) }
    }

    @Test
    fun closingTheFirstChipWhileItIsCurrentSlidesTheOthers() = assertCloseSlides(closed = 1, current = 1, next = 2)

    @Test
    fun closingTheFirstChipLeftOfTheCurrentOneSlidesTheOthers() = assertCloseSlides(closed = 1, current = 3, next = 3)

    @Test
    fun closingAMiddleChipRightOfTheCurrentOneSlidesTheOthers() = assertCloseSlides(closed = 3, current = 1, next = 1)

    @Test
    fun closingAMiddleChipWhileItIsCurrentSlidesTheOthers() = assertCloseSlides(closed = 3, current = 3, next = 4)

    @Test
    fun closingAMiddleChipLeftOfTheCurrentOneSlidesTheOthers() = assertCloseSlides(closed = 3, current = 5, next = 5)

    @Test
    fun closingTheCurrentChipBackToItsOpenerOnTheLeftSlidesTheOthers() = assertCloseSlides(closed = 3, current = 3, next = 1)

    @Test
    fun closingAChipLeftOfANewlySelectedTabSlidesTheOthers() =
        assertCloseSlides(closed = 2, current = 4, next = 4, selectedBefore = 1)

    private fun assertClosingTheLastChipLeavesTheOthersInPlace(current: Long, next: Long) {
        showTabs(5, current)
        close(5, next)
        for (frame in 1..framesFor(TAB_PLACEMENT_MS + 32)) {
            rule.frames(1)
            (1L..4L).forEachIndexed { index, id -> assertEquals("tab $id on frame $frame", restingX(index), chipX(id)) }
        }
    }

    @Test
    fun closingTheLastChipLeftOfTheCurrentOneLeavesTheOthersInPlace() = assertClosingTheLastChipLeavesTheOthersInPlace(2, 2)

    @Test
    fun closingTheLastChipWhileItIsCurrentLeavesTheOthersInPlace() = assertClosingTheLastChipLeavesTheOthersInPlace(5, 4)

    @Test
    fun atAnimatorScaleZeroEveryCloseIsInPlaceOnTheNextFrame() {
        showTabs(5, current = 1, scale = 0f)
        // The first, a middle and the last chip, with the current tab left of, at and right of it.
        val cases = listOf(
            Triple(1L, 1L, 2L), Triple(1L, 3L, 3L),
            Triple(3L, 1L, 1L), Triple(3L, 3L, 4L), Triple(3L, 5L, 5L), Triple(3L, 3L, 1L),
            Triple(5L, 2L, 2L), Triple(5L, 5L, 4L),
        )
        for ((closed, current, next) in cases) {
            tabs = (1L..5L).map { TabUi(it, "Tab $it", "https://t$it.example/", selected = it == current) }
            settle()
            close(closed, next)
            rule.frames(1)
            (1L..5L).filter { it != closed }.forEachIndexed { index, id ->
                assertEquals("tab $id after closing $closed with $current current", restingX(index), chipX(id))
            }
        }
    }

    @Test
    fun closingATabLeftOfTheCurrentOneDoesNotScrollWhenTheCurrentChipIsInView() {
        showTabs(9, current = 3)
        assertEquals("the strip starts unscrolled", restingX(0), chipX(1))
        close(1, next = 3)
        settle()
        assertEquals("tab 2 is the first chip", restingX(0), chipX(2))
        assertEquals("tab 3 moved one place left", restingX(1), chipX(3))
    }

    @Test
    fun closingATabLeftOfTheCurrentOneDoesNotScrollBackToTheCurrentOne() {
        showTabs(12, current = 12)
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        settle()
        assertEquals("scrolled back to the start", restingX(0), chipX(1))
        close(2, next = 12)
        settle()
        assertEquals("the strip stays at the start", restingX(0), chipX(1))
        assertEquals("tab 3 took tab 2's place", restingX(1), chipX(3))
    }

    @Test
    fun selectingATabScrolledOffScreenScrollsItIntoView() {
        showTabs(12, current = 1)
        rule.onNodeWithTag(tabChipTag(11)).assertDoesNotExist()
        selectTab(11)
        settle()
        val strip = rule.onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot
        val x = chipX(11)
        val width = with(rule.density) { 180.dp.roundToPx() }
        assertTrue("tab 11 (x $x) is in full in the strip ($strip)", x >= strip.left && x + width <= strip.right)
    }

    @Test
    fun afterScrollingToATabNearTheEndTheNextCloseStillSlides() {
        // The strip cannot put tab 11 first, so that scroll stops short of the place it asked for.
        showTabs(12, current = 1)
        selectTab(11)
        settle()
        // The row is scrolled to its end, so the chips before the closed one move right to fill its place.
        val ids = listOf(7L, 8L, 10L, 11L)
        val before = ids.map { chipX(it) }
        close(9, next = 11)
        rule.frames(3)
        val at48 = ids.map { chipX(it) }
        settle()
        val after = ids.map { chipX(it) }
        assertTrue("the close moves some chip: $before to $after", before != after)
        assertTrue("the chips are still on their way at +48 ms: $before, $at48, $after", at48 != after)
    }
}
