package com.shibi.orbis.ui.browser

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.remember
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.shibi.orbis.engine.SearchEngine
import com.shibi.orbis.engine.SessionState
import com.shibi.orbis.ui.theme.ADDRESS_FOCUS_MS
import com.shibi.orbis.ui.theme.frames
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The production [BrowserAddressBar]: on focus the full address fades in, on focus loss the resting host line is back
 * on the next frame. The clock is held; one frame is 16 ms. Animator scale 0 is set as on a device: the system setting
 * (read by rememberMotionEnabled) and the duration scale Compose's own animations run at (Material's border).
 * xxhdpi so glyph stems are several pixels wide and some pixels are fully covered by the text colour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddressBarMotionTest {
    private val durationScale = object : MotionDurationScale {
        var factor = 1f
        override val scaleFactor: Float get() = factor
    }

    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule(durationScale)

    private val resolver get() = ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver
    private val url = "https://www.example.com/some/long/path/to/a/page"
    private val flow = MutableStateFlow(SessionState(url = url))
    private lateinit var sink: FocusRequester
    private lateinit var scheme: ColorScheme

    @After
    fun reset() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    private fun show(scale: Float = 1f) {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        durationScale.factor = scale
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                scheme = MaterialTheme.colorScheme
                sink = remember { FocusRequester() }
                Column(Modifier.background(Color.White).focusRequester(sink).focusable()) {
                    BrowserAddressBar(
                        tabId = 1L,
                        isPrivate = false,
                        tabUrl = url,
                        sessionState = flow,
                        downloadOffer = null,
                        isLoading = false,
                        canReload = true,
                        searchEngine = { SearchEngine.DEFAULT },
                        focusOnStart = false,
                        onFocusOnStartConsumed = {},
                        loadUrl = {},
                        stop = {},
                        reload = {},
                        hideKeyboard = {},
                        moveFocusToSink = { sink.requestFocus() },
                        modifier = Modifier.width(360.dp),
                    )
                }
            }
        }
        rule.frames(2)
    }

    private fun framesFor(ms: Int) = (ms + 15) / 16

    private fun field() = rule.onNodeWithTag(ADDRESS_FIELD_TAG)

    private fun fieldText(): String = field().fetchSemanticsNode().config.first { it.key.name == "EditableText" }.value.toString()

    private fun fieldHeight() = field().fetchSemanticsNode().size.height

    private fun restingShown() = rule.onAllNodesWithTag(ADDRESS_RESTING_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun focus() {
        field().requestFocus()
        rule.frames(1)
    }

    private fun blur() {
        rule.runOnUiThread { sink.requestFocus() }
        rule.frames(1)
    }

    private fun capture(): PixelMap = field().captureToImage().toPixelMap()

    private fun px(dp: Int) = with(rule.density) { dp.dp.roundToPx() }

    /** The text band: inside the border and its rounded corners, before the trailing button, around the middle line. */
    private fun band(map: PixelMap): List<Pair<Int, Int>> {
        val xs = px(20) until map.width - px(56)
        val ys = (map.height / 2 - px(8))..(map.height / 2 + px(8))
        return xs.flatMap { x -> ys.map { y -> x to y } }
    }

    private fun PixelMap.argb(p: Pair<Int, Int>) = this[p.first, p.second].toArgb()

    private fun hasFullTextPixel(map: PixelMap): Boolean {
        val text = scheme.onSurface.toArgb()
        return band(map).any { map.argb(it) == text }
    }

    @Test
    fun theFieldKeepsItsHeightOnEveryFrameOfFocusAndBlur() {
        show()
        val height = fieldHeight()
        focus()
        repeat(framesFor(ADDRESS_FOCUS_MS + 32)) {
            assertEquals(height, fieldHeight())
            rule.frames(1)
        }
        blur()
        repeat(10) {
            assertEquals(height, fieldHeight())
            rule.frames(1)
        }
    }

    @Test
    fun theFullAddressFadesInOnFocus() {
        show()
        focus()
        assertEquals("the full address from the first focused frame", url, fieldText())
        assertFalse(restingShown())
        val start = capture()
        rule.frames(3) // 48 ms into the fade
        val mid = capture()
        rule.frames(framesFor(ADDRESS_FOCUS_MS + 32) - 3)
        val end = capture()
        val text = scheme.onSurface.toArgb()
        val container = scheme.surface.toArgb()
        val glyph = band(end).filter { end.argb(it) == text && start.argb(it) == container }
        assertTrue("the address is drawn in onSurface after the fade", glyph.isNotEmpty())
        val p = glyph.first()
        assertNotEquals("mid-fade differs from the start at $p", start.argb(p), mid.argb(p))
        assertNotEquals("mid-fade differs from the end at $p", end.argb(p), mid.argb(p))
        assertFalse("nothing is at full strength mid-fade", hasFullTextPixel(mid))
    }

    @Test
    fun materialAnimatesTheBorderOnFocus() {
        show()
        val primary = scheme.primary.toArgb()
        focus()
        assertNotEquals("the border is still on its way to primary", primary, borderPixel())
        rule.frames(40)
        assertEquals(Integer.toHexString(primary), Integer.toHexString(borderPixel()))
    }

    /** A pixel of the top border midway along the field, inside both the 1 dp unfocused and the 2 dp focused stroke. */
    private fun borderPixel(): Int = capture().let { it[it.width / 2, 1].toArgb() }

    @Test
    fun onFocusLossTheRestingLineIsBackOnTheNextFrameWithNoFullAddressLeft() {
        show()
        val rest = capture()
        assertTrue(restingShown())
        focus()
        rule.frames(framesFor(ADDRESS_FOCUS_MS + 32))
        blur()
        assertTrue("the resting line on the next frame", restingShown())
        val after = capture()
        val leftovers = band(after).filter { after.argb(it) != rest.argb(it) }
        assertEquals("pixels that differ from the resting bar: ${leftovers.take(5)}", 0, leftovers.size)
    }

    @Test
    fun typingMidFadeLandsInTheFieldAtFullStrength() {
        show()
        focus()
        rule.frames(3)
        field().performTextReplacement("typed words")
        rule.frames(1)
        assertEquals("typed words", fieldText())
        assertTrue("the typed text is drawn in onSurface at once", hasFullTextPixel(capture()))
    }

    /**
     * Material's border snaps through its own animate*AsState, which shows the new value one frame after the focus
     * frame. Accepted: the border is final by the second frame and never shows an intermediate colour.
     */
    @Test
    fun atAnimatorScaleZeroTheTextIsFinalOnTheFirstFrameAndTheBorderByTheSecond() {
        show(scale = 0f)
        val unfocused = scheme.outline.toArgb()
        val primary = scheme.primary.toArgb()
        focus()
        assertEquals(url, fieldText())
        assertTrue("the address is fully drawn on the first focused frame", hasFullTextPixel(capture()))
        val first = borderPixel()
        assertTrue("frame 1 border ${Integer.toHexString(first)} is the unfocused or the final colour", first == unfocused || first == primary)
        rule.frames(1)
        assertEquals("frame 2 border", Integer.toHexString(primary), Integer.toHexString(borderPixel()))
    }
}
