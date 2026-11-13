package com.shibi.orbis.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.shibi.orbis.settings.DarkMode
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** [ThemeFade] under [OrbisTheme], with three swatches painted from the provided scheme. The clock is held. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeFadeTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private var theme by mutableStateOf(TealTheme)
    private var dark by mutableStateOf(false)
    private var enabled by mutableStateOf(true)
    private var contentRuns = 0
    private val composedPlums = mutableListOf<Color>()

    private fun show(enabledAtStart: Boolean = true, darkAtStart: Boolean = false) {
        enabled = enabledAtStart
        dark = darkAtStart
        rule.mainClock.autoAdvance = false
        rule.setContent {
            OrbisTheme(if (dark) DarkMode.DARK else DarkMode.LIGHT, theme) {
                ThemeFade(enabled) {
                    SideEffect { contentRuns++ }
                    val s = MaterialTheme.colorScheme
                    SideEffect { composedPlums += s.tertiaryContainer }
                    Column {
                        Swatch(BG, s.background)
                        Swatch(PRIMARY, s.primary)
                        Swatch(PLUM, s.tertiaryContainer)
                    }
                }
            }
        }
        frame()
    }

    @Composable
    private fun Swatch(tag: String, color: Color) =
        Box(Modifier.size(24.dp).background(color).testTag(tag))

    private fun frame(n: Int = 1) = rule.frames(n)

    private fun pixel(tag: String): Int = rule.onNodeWithTag(tag).captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }.toArgb()

    private fun scheme(t: AppTheme, isDark: Boolean = false): ColorScheme = t.mode(isDark).scheme

    private fun channelDistance(a: Int, b: Int): Int = listOf(16, 8, 0).maxOf { abs((a shr it and 0xFF) - (b shr it and 0xFF)) }

    private fun ColorScheme.allRoles() = roles() + ("scrim" to scrim)

    @Test
    fun lerpSchemeEndsAreExactForEveryRole() {
        val themes = AppThemes.all
        for (isDark in listOf(false, true)) {
            for ((a, b) in themes.zip(themes.drop(1) + themes.first())) {
                val from = scheme(a, isDark)
                val to = scheme(b, isDark)
                assertEquals("${a.id} to ${b.id} at 0", from.allRoles(), lerpScheme(from, to, 0f).allRoles())
                assertEquals("${a.id} to ${b.id} at 1", to.allRoles(), lerpScheme(from, to, 1f).allRoles())
            }
        }
    }

    @Test
    fun aThemeChangeGlidesAndEndsOnTheNewTheme() {
        show()
        val teal = scheme(TealTheme)
        val ember = scheme(EmberTheme)
        assertEquals(teal.background.toArgb(), pixel(BG))
        theme = EmberTheme
        frame(6) // 96 ms
        val mid = pixel(BG)
        assertNotEquals("left Teal", teal.background.toArgb(), mid)
        assertNotEquals("not yet Ember", ember.background.toArgb(), mid)
        frame((THEME_CROSSFADE_MS + 32) / 16 - 6 + 1)
        assertEquals(ember.background.toArgb(), pixel(BG))
        assertEquals(ember.primary.toArgb(), pixel(PRIMARY))
    }

    @Test
    fun aSecondChangeMidFadeStartsFromTheColoursOnScreen() {
        show()
        theme = EmberTheme
        val steps = mutableListOf<Int>()
        var last = pixel(PRIMARY)
        repeat(6) {
            frame()
            val p = pixel(PRIMARY)
            steps += channelDistance(last, p)
            last = p
        }
        val bgBefore = pixel(BG)
        theme = FernTheme
        frame()
        assertTrue("background jumped", channelDistance(bgBefore, pixel(BG)) <= 8)
        // Primary moves far more than the background in a normal frame, so it is held to that normal step instead.
        val jump = channelDistance(last, pixel(PRIMARY))
        assertTrue("primary jumped $jump, a frame of the fade moves at most ${steps.max()}", jump <= steps.max() + 2)
        frame((THEME_CROSSFADE_MS + 32) / 16 + 1)
        assertEquals(scheme(FernTheme).background.toArgb(), pixel(BG))
        assertEquals(scheme(FernTheme).primary.toArgb(), pixel(PRIMARY))
    }

    @Test
    fun disabledShowsTheTargetOnTheFirstFrame() {
        show(enabledAtStart = false)
        theme = EmberTheme
        frame()
        assertEquals(scheme(EmberTheme).background.toArgb(), pixel(BG))
        assertEquals(scheme(EmberTheme).primary.toArgb(), pixel(PRIMARY))
        dark = true
        frame()
        assertEquals("dark mode too", scheme(EmberTheme, isDark = true).primary.toArgb(), pixel(PRIMARY))
    }

    @Test
    fun turningDisabledMidFadeShowsTheTargetOnTheNextFrame() {
        show()
        theme = EmberTheme
        frame(6)
        assertNotEquals(scheme(EmberTheme).primary.toArgb(), pixel(PRIMARY))
        enabled = false
        frame()
        assertEquals(scheme(EmberTheme).background.toArgb(), pixel(BG))
        assertEquals(scheme(EmberTheme).primary.toArgb(), pixel(PRIMARY))
        enabled = true
        frame(3)
        assertEquals("stays on the target", scheme(EmberTheme).primary.toArgb(), pixel(PRIMARY))
    }

    @Test
    fun withNoFadeRunningTheContentDoesNotRecompose() {
        show()
        frame(2)
        val before = contentRuns
        frame(60)
        assertEquals("at rest", before, contentRuns)
        theme = EmberTheme
        frame((THEME_CROSSFADE_MS + 32) / 16 + 1)
        assertTrue("the fade recomposed the content", contentRuns > before)
        val after = contentRuns
        frame(60)
        assertEquals("after a fade", after, contentRuns)
    }

    @Test
    fun thePrivatePlumIsExactOnEveryFrameBetweenLightThemes() = plumStaysExact(isDark = false, PrivateColors.Light.second)

    @Test
    fun thePrivatePlumIsExactOnEveryFrameBetweenDarkThemes() = plumStaysExact(isDark = true, PrivateColors.Dark.second)

    private fun plumStaysExact(isDark: Boolean, plum: Color) {
        show(darkAtStart = isDark)
        composedPlums.clear()
        theme = EmberTheme
        repeat((THEME_CROSSFADE_MS + 32) / 16 + 1) {
            frame()
            assertEquals("frame $it", plum.toArgb(), pixel(PLUM))
        }
        theme = PaperTheme
        repeat((THEME_CROSSFADE_MS + 32) / 16 + 1) {
            frame()
            assertEquals("to Paper, frame $it", plum.toArgb(), pixel(PLUM))
        }
        assertTrue("the fades composed frames: ${composedPlums.size}", composedPlums.size > 10)
        assertTrue("every composed scheme", composedPlums.all { it == plum })
    }

    private companion object {
        const val BG = "bg"
        const val PRIMARY = "primary"
        const val PLUM = "plum"
    }
}
