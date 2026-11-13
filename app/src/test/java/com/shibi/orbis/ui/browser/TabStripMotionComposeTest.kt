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
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.shibi.orbis.tabs.TabUi
import com.shibi.orbis.ui.theme.TAB_ENTER_MS
import com.shibi.orbis.ui.theme.TAB_EXIT_MS
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
}
