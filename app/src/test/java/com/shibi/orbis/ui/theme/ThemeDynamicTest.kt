package com.shibi.orbis.ui.theme

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.shibi.orbis.settings.DarkMode
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Material You theme: where it sits in the list, that it composes, and that its wallpaper colours stay readable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ThemeDynamicTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun sevenThemesMaterialYouIsLastAndTealIsStillTheDefault() {
        assertEquals(7, AppThemes.all.size)
        assertEquals("material_you", AppThemes.all.last().id)
        assertEquals("orbis_teal", AppThemes.Default.id)
        assertEquals(AppThemes.all.dropLast(1), AppThemes.fixed)
    }

    @Test
    fun materialYouIsDynamicAndAnUnknownIdIsStillTeal() {
        assertTrue(AppThemes.byId("material_you").dynamic)
        val unknown = AppThemes.byId("nope")
        assertEquals("orbis_teal", unknown.id)
        assertFalse(unknown.dynamic)
        assertTrue(AppThemes.fixed.none { it.dynamic })
    }

    private fun composes(mode: DarkMode) {
        var seen: ColorScheme? = null
        rule.setContent { OrbisTheme(mode, AppThemes.byId("material_you")) { seen = MaterialTheme.colorScheme } }
        rule.waitForIdle()
        val scheme = checkNotNull(seen)
        assertEquals(PrivateColors.Light.second.takeIf { mode == DarkMode.LIGHT } ?: PrivateColors.Dark.second, scheme.tertiaryContainer)
    }

    @Test
    fun composesInLight() = composes(DarkMode.LIGHT)

    @Test
    fun composesInDark() = composes(DarkMode.DARK)

    @Test
    fun theWallpaperSchemeReadsAndKeepsThePlumApart() {
        for (dark in listOf(false, true)) {
            val base = dynamicBaseScheme(context, dark)
            assertNotNull("dynamic scheme dark=$dark", base)
            val c = dynamicScheme(context, dark, AppThemes.Default.mode(dark).scheme)
            val where = if (dark) "dark" else "light"
            val pairs =
                listOf(
                    "onBackground/background" to (c.onBackground to c.background),
                    "onSurface/surface" to (c.onSurface to c.surface),
                    "onSurface/container" to (c.onSurface to c.surfaceContainer),
                    "onSurface/containerLow" to (c.onSurface to c.surfaceContainerLow),
                    "onSurfaceVariant/surface" to (c.onSurfaceVariant to c.surface),
                    "onPrimary/primary" to (c.onPrimary to c.primary),
                    "primary/surface" to (c.primary to c.surface),
                    "onPrimaryContainer/primaryContainer" to (c.onPrimaryContainer to c.primaryContainer),
                    "onSecondaryContainer/secondaryContainer" to (c.onSecondaryContainer to c.secondaryContainer),
                    "onTertiaryContainer/tertiaryContainer" to (c.onTertiaryContainer to c.tertiaryContainer),
                    "onTertiary/tertiary" to (c.onTertiary to c.tertiary),
                    "tertiary/surface" to (c.tertiary to c.surface),
                    "onError/error" to (c.onError to c.error),
                    "inverseOnSurface/inverseSurface" to (c.inverseOnSurface to c.inverseSurface),
                )
            val low = pairs.filter { (_, p) -> contrastOf(p.first, p.second) < 4.5 }
            assertTrue("$where contrast below 4.5: $low", low.isEmpty())
            assertTrue("$where outline/surface", contrastOf(c.outline, c.surface) >= 3.0)
            for ((n, other) in listOf("surfaceContainer" to c.surfaceContainer, "surface" to c.surface, "surfaceContainerLow" to c.surfaceContainerLow, "background" to c.background)) {
                val d = okLabDistance(c.tertiaryContainer, other)
                assertTrue("$where plum vs $n $d", d >= MIN_DYNAMIC_PRIVATE_GAP)
            }
        }
    }

    /** A wallpaper scheme whose surfaces equal the plum: the nudge must pull every tab-chip neighbour clear of it. */
    @Test
    fun surfacesTooCloseToThePlumAreMovedClear() {
        val plum = PrivateColors.Light.second
        val clash =
            lightColorScheme(
                surface = plum,
                background = plum,
                surfaceContainer = plum,
                surfaceContainerLow = plum,
                onSurface = Color(0xFF1B1B1F),
            )
        val c = withOrbisPrivateLook(clash, dark = false)
        assertEquals(plum, c.tertiaryContainer)
        for (other in listOf(c.surface, c.background, c.surfaceContainer, c.surfaceContainerLow)) {
            assertTrue(okLabDistance(plum, other) >= MIN_DYNAMIC_PRIVATE_GAP)
        }
        assertTrue(contrastOf(c.onSurface, c.surface) >= 4.5)
    }
}
