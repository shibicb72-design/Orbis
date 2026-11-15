package com.shibi.orbis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.shibi.orbis.settings.DarkMode
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import com.shibi.orbis.ui.theme.AppTheme
import com.shibi.orbis.ui.theme.EmberTheme
import com.shibi.orbis.ui.theme.OrbisTheme
import com.shibi.orbis.ui.theme.TealTheme
import com.shibi.orbis.ui.theme.ThemeFade
import com.shibi.orbis.ui.theme.argb
import com.shibi.orbis.ui.theme.frames
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

/**
 * [OrbisTopAppBar] under [ThemeFade]: the bar has to be the colour of a plain sibling background on every frame of a
 * glide. Material's own bar animates the colour it is handed and trails the fade; the guard in the first test shows
 * that, and the mutation of the wrapper (Material's containerColor instead of Transparent) fails the every-frame tests.
 * The clock is held.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalMaterial3Api::class)
class OrbisTopAppBarTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private var theme by mutableStateOf<AppTheme>(TealTheme)
    private var dark by mutableStateOf(false)
    private var fade by mutableStateOf(true)
    private var statusInsetPx by mutableStateOf(0)

    @Composable
    private fun Bars() {
        val c = MaterialTheme.colorScheme
        Column {
            OrbisTopAppBar(title = { Text("T") }, modifier = Modifier.testTag(BAR))
            Box(Modifier.fillMaxWidth().height(32.dp).background(c.surface).testTag(BOX))
            OrbisTopAppBar(title = { Text("T") }, containerColor = c.background, contentColor = c.onBackground, modifier = Modifier.testTag(BAR_BG))
            Box(Modifier.fillMaxWidth().height(32.dp).background(c.background).testTag(BOX_BG))
            TopAppBar(
                title = { Text("T") },
                modifier = Modifier.testTag(MATERIAL_BG),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.background),
            )
        }
    }

    private fun show(startTheme: AppTheme = TealTheme, startDark: Boolean = false, fadeOn: Boolean = true) {
        theme = startTheme
        dark = startDark
        fade = fadeOn
        rule.mainClock.autoAdvance = false
        rule.setContent {
            OrbisTheme(if (dark) DarkMode.DARK else DarkMode.LIGHT, theme) { ThemeFade(fade) { Bars() } }
        }
        rule.frames(2)
    }

    private fun pixel(tag: String): Int =
        rule.onNodeWithTag(tag).captureToImage().toPixelMap().let { it[it.width - 8, it.height - 8] }.toArgb()

    private fun distance(a: Int, b: Int): Int = listOf(16, 8, 0).maxOf { abs((a shr it and 0xFF) - (b shr it and 0xFF)) }

    private fun assertBarsMatchBoxes(label: String) {
        assertTrue("$label surface: bar ${pixel(BAR).toString(16)} box ${pixel(BOX).toString(16)}", distance(pixel(BAR), pixel(BOX)) <= 1)
        assertTrue("$label background: bar ${pixel(BAR_BG).toString(16)} box ${pixel(BOX_BG).toString(16)}", distance(pixel(BAR_BG), pixel(BOX_BG)) <= 1)
    }

    /** Runs a glide from the current state to [toTheme]/[toDark], checking every frame, and returns the colours seen. */
    private fun glideChecking(toTheme: AppTheme, toDark: Boolean) {
        val from = (theme.mode(dark).scheme)
        val to = toTheme.mode(toDark).scheme
        val seenBg = mutableSetOf<Int>()
        val seenSurface = mutableSetOf<Int>()
        var materialLagged = false
        theme = toTheme
        dark = toDark
        repeat(com.shibi.orbis.ui.theme.THEME_CROSSFADE_MS / 16 + 3) {
            rule.frames(1)
            assertBarsMatchBoxes("frame $it")
            seenBg += pixel(BOX_BG)
            seenSurface += pixel(BOX)
            if (distance(pixel(MATERIAL_BG), pixel(BOX_BG)) > 1) materialLagged = true
        }
        assertTrue("Material's own bar differs from a plain background during the glide", materialLagged)
        assertTrue("the background glided through steps: ${seenBg.size}", seenBg.size > 3)
        if (from.surface != to.surface) assertTrue("the surface glided through steps: ${seenSurface.size}", seenSurface.size > 3)
        assertEquals("surface bar ends on the new theme", to.surface.toArgb(), pixel(BAR))
        assertEquals("background bar ends on the new theme", to.background.toArgb(), pixel(BAR_BG))
    }

    @Test
    fun theBarFollowsAPlainBackgroundOnEveryFrameOfAThemeChangeInLight() {
        show()
        glideChecking(EmberTheme, toDark = false)
    }

    @Test
    fun theBarFollowsAPlainBackgroundOnEveryFrameOfAThemeChangeInDark() {
        show(startDark = true)
        assertNotEquals(TealTheme.mode(true).scheme.surface, EmberTheme.mode(true).scheme.surface)
        glideChecking(EmberTheme, toDark = true)
    }

    @Test
    fun theBarFollowsAPlainBackgroundOnEveryFrameOfLightToDark() {
        show()
        glideChecking(TealTheme, toDark = true)
    }

    @Test
    fun theBarFollowsAPlainBackgroundOnEveryFrameOfDarkToLight() {
        show(startDark = true)
        glideChecking(TealTheme, toDark = false)
    }

    @Test
    fun withTheFadeOffTheBarIsTheNewColourOnTheFirstFrame() {
        show(fadeOn = false)
        theme = EmberTheme
        dark = true
        rule.frames(1)
        val to = EmberTheme.mode(true).scheme
        assertEquals(to.surface.toArgb(), pixel(BAR))
        assertEquals(to.background.toArgb(), pixel(BAR_BG))
    }

    @Test
    fun atRestTheBarIsPixelIdenticalToMaterialsInTealLight() = atRestMatchesMaterial(isDark = false)

    @Test
    fun atRestTheBarIsPixelIdenticalToMaterialsInTealDark() = atRestMatchesMaterial(isDark = true)

    private fun atRestMatchesMaterial(isDark: Boolean) {
        theme = TealTheme
        dark = isDark
        fade = false
        rule.mainClock.autoAdvance = false
        rule.setContent {
            OrbisTheme(if (dark) DarkMode.DARK else DarkMode.LIGHT, theme) {
                ThemeFade(fade) {
                    val c = MaterialTheme.colorScheme
                    Column {
                        OrbisTopAppBar(
                            title = { Text("Title") },
                            navigationIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) },
                            actions = { Icon(Icons.Filled.MoreVert, contentDescription = null) },
                            modifier = Modifier.testTag(BAR),
                        )
                        TopAppBar(
                            title = { Text("Title") },
                            navigationIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) },
                            actions = { Icon(Icons.Filled.MoreVert, contentDescription = null) },
                            modifier = Modifier.testTag(MATERIAL),
                        )
                        OrbisTopAppBar(
                            title = { Text("Title") },
                            navigationIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) },
                            containerColor = c.background,
                            contentColor = c.onBackground,
                            modifier = Modifier.testTag(BAR_BG),
                        )
                        TopAppBar(
                            title = { Text("Title") },
                            navigationIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) },
                            colors =
                                TopAppBarDefaults.topAppBarColors(
                                    containerColor = c.background,
                                    titleContentColor = c.onBackground,
                                    navigationIconContentColor = c.onBackground,
                                ),
                            modifier = Modifier.testTag(MATERIAL_BG),
                        )
                    }
                }
            }
        }
        rule.frames(3)
        assertTrue("dark=$isDark default bar", pixels(BAR).contentEquals(pixels(MATERIAL)))
        assertTrue("dark=$isDark background bar", pixels(BAR_BG).contentEquals(pixels(MATERIAL_BG)))
    }

    private fun pixels(tag: String): IntArray = rule.onNodeWithTag(tag).captureToImage().argb()

    @Test
    fun theStatusBarInsetAreaOfTheBarIsTheBarColour() {
        theme = TealTheme
        dark = true
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val view = LocalView.current
            val inset = statusInsetPx
            SideEffect {
                ViewCompat.dispatchApplyWindowInsets(
                    view,
                    WindowInsetsCompat.Builder()
                        .setInsets(WindowInsetsCompat.Type.statusBars(), androidx.core.graphics.Insets.of(0, inset, 0, 0))
                        .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                        .build(),
                )
            }
            OrbisTheme(DarkMode.DARK, theme) {
                ThemeFade(false) {
                    Column {
                        OrbisTopAppBar(title = { Text("T") }, modifier = Modifier.testTag(BAR))
                    }
                }
            }
        }
        rule.frames(2)
        val without = rule.onNodeWithTag(BAR).captureToImage().height
        statusInsetPx = 60
        rule.frames(3)
        val image = rule.onNodeWithTag(BAR).captureToImage()
        assertTrue("the status bar inset made the bar taller: $without to ${image.height}", image.height >= without + 60)
        val map = image.toPixelMap()
        val want = TealTheme.mode(true).scheme.surface.toArgb()
        assertEquals("inside the inset area", want, map[map.width - 8, 10].toArgb())
        assertEquals("below the inset area", want, map[map.width - 8, map.height - 8].toArgb())
    }

    private companion object {
        const val BAR = "bar"
        const val BOX = "box"
        const val BAR_BG = "barBg"
        const val BOX_BG = "boxBg"
        const val MATERIAL = "material"
        const val MATERIAL_BG = "materialBg"
    }
}
