package com.shibi.orbis.ui.theme

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
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
        val plum = if (mode == DarkMode.DARK) PrivateColors.Dark.second else PrivateColors.Light.second
        assertTrue(okLabDistance(plum, scheme.tertiaryContainer) <= 0.02)
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

    private fun textPairs(c: ColorScheme) =
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

    /** A rose wallpaper palette written out the way Material You tones it (tone 40 accent, tone 98 surface). */
    private fun roseLight() =
        lightColorScheme(
            primary = Color(0xFF8E4958),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFFFD9DD),
            onPrimaryContainer = Color(0xFF3B0715),
            secondary = Color(0xFF75565A),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFFFD9DD),
            onSecondaryContainer = Color(0xFF2C1519),
            background = Color(0xFFFFF8F7),
            onBackground = Color(0xFF22191A),
            surface = Color(0xFFFFF8F7),
            onSurface = Color(0xFF22191A),
            surfaceVariant = Color(0xFFF3DDDF),
            onSurfaceVariant = Color(0xFF524344),
            outline = Color(0xFF857374),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFFFF0F1),
            surfaceContainer = Color(0xFFFCEAEB),
            surfaceContainerHigh = Color(0xFFF6E4E5),
            surfaceContainerHighest = Color(0xFFF0DEDF),
            inverseSurface = Color(0xFF382E2F),
            inverseOnSurface = Color(0xFFFFECEC),
        )

    private fun surfacesOf(c: ColorScheme) =
        listOf("surface" to c.surface, "background" to c.background, "surfaceContainer" to c.surfaceContainer, "surfaceContainerLow" to c.surfaceContainerLow)

    private fun chipChecks(name: String, base: ColorScheme) {
        val c = withOrbisPrivateLook(base, dark = false)
        val plum = PrivateColors.Light.second
        // The wallpaper's own surfaces are left exactly as the system gives them.
        val after = surfacesOf(c).toMap()
        for ((n, before) in surfacesOf(base)) assertEquals("$name $n", before, after.getValue(n))
        val shift = okLabDistance(plum, c.tertiaryContainer)
        println("CHIP $name ${hex(plum)} -> ${hex(c.tertiaryContainer)} shift ${"%.4f".format(shift)}")
        val failures = mutableListOf<String>()
        for ((n, other) in surfacesOf(c)) {
            val d = okLabDistance(c.tertiaryContainer, other)
            println("CHIP $name gap to $n ${"%.4f".format(d)}")
            if (d < MIN_DYNAMIC_PRIVATE_GAP) failures.add("chip vs $n ${"%.4f".format(d)} < $MIN_DYNAMIC_PRIVATE_GAP")
        }
        if (shift > 0.02) failures.add("chip moved ${"%.4f".format(shift)} > 0.02 from the plum")
        assertEquals(PrivateColors.Light.first, c.tertiary)
        assertEquals(PrivateColors.OnTertiaryLight, c.onTertiary)
        assertEquals(PrivateColors.Light.third, c.onTertiaryContainer)
        val all =
            textPairs(c).map { (n, p) -> Triple(n, p, 4.5) } +
                listOf(
                    Triple("onTertiaryContainer/tertiaryContainer (7:1)", c.onTertiaryContainer to c.tertiaryContainer, 7.0),
                    Triple("tertiary/tertiaryContainer", c.tertiary to c.tertiaryContainer, 4.5),
                    Triple("primary/surfaceContainer", c.primary to c.surfaceContainer, 4.5),
                    Triple("onSurfaceVariant/surfaceContainer", c.onSurfaceVariant to c.surfaceContainer, 4.5),
                    Triple("outline/surface", c.outline to c.surface, 3.0),
                    Triple("outline/surfaceContainer", c.outline to c.surfaceContainer, 3.0),
                )
        for ((n, p, min) in all) {
            val r = contrastOf(p.first, p.second)
            println("CHIP $name ratio $n ${"%.2f".format(r)} (min $min)")
            if (r < min) failures.add("$n ${"%.2f".format(r)} < $min")
        }
        println("CHIP $name luminance surfaceContainer ${"%.4f".format(c.surfaceContainer.luminance())} surfaceContainerHigh ${"%.4f".format(c.surfaceContainerHigh.luminance())}")
        assertTrue("$name: " + failures.joinToString("; "), failures.isEmpty())
    }

    @Test
    fun theDefaultLightSchemeKeepsItsSurfacesAndTheChipClearsThem() = chipChecks("default-light", lightColorScheme())

    @Test
    fun aRoseLightSchemeKeepsItsSurfacesAndTheChipClearsThem() = chipChecks("rose-light", roseLight())

    /** No real wallpaper gives this: every surface is the plum itself. Moving away from it clears the gap in a few steps. */
    @Test
    fun surfacesEqualToThePlumMoveTheChipAwayUntilItIsClear() {
        val plum = PrivateColors.Light.second
        val clash = lightColorScheme(surface = plum, background = plum, surfaceContainer = plum, surfaceContainerLow = plum)
        val c = withOrbisPrivateLook(clash, dark = false)
        val reached = okLabDistance(c.tertiaryContainer, plum)
        println("CHIP plum-surfaces moved to ${hex(c.tertiaryContainer)} gap reached ${"%.4f".format(reached)}")
        assertTrue(reached >= MIN_DYNAMIC_PRIVATE_GAP)
        assertEquals(plum, c.surface)
    }

    /** No real wallpaper gives this either: every surface sits 10% along the chip's path, so no step clears it and the cap stops the chip. */
    @Test
    fun surfacesOnTheChipsPathMoveTheChipTheFullFifteenPercent() {
        val plum = PrivateColors.Light.second
        val dark = PrivateColors.Light.first
        val onPath = lerp(plum, dark, 0.10f)
        val clash = lightColorScheme(surface = onPath, background = onPath, surfaceContainer = onPath, surfaceContainerLow = onPath)
        val c = withOrbisPrivateLook(clash, dark = false)
        assertEquals(lerp(plum, dark, MAX_CHIP_SHIFT), c.tertiaryContainer)
        val reached = okLabDistance(c.tertiaryContainer, onPath)
        println("CHIP on-path-surfaces gap reached ${"%.4f".format(reached)}")
        assertTrue(reached < MIN_DYNAMIC_PRIVATE_GAP)
    }

    /** A scheme whose surfaces are already clear of the plum needs no move: the chip is the plum exactly. */
    @Test
    fun aSchemeThatNeedsNoMoveKeepsThePlumExactly() {
        val white = Color.White
        val c = withOrbisPrivateLook(lightColorScheme(surface = white, background = white, surfaceContainer = white, surfaceContainerLow = white), dark = false)
        assertEquals(PrivateColors.Light.second, c.tertiaryContainer)
        val d = Color(0xFF101014)
        val cd = withOrbisPrivateLook(darkColorScheme(surface = d, background = d, surfaceContainer = d, surfaceContainerLow = d), dark = true)
        assertEquals(PrivateColors.Dark.second, cd.tertiaryContainer)
    }

    /** Robolectric's own wallpaper palette: the chip is within 0.02 of the plum and clear of every surface, light and dark. */
    @Test
    fun theRobolectricWallpaperChipIsNearThePlumAndClear() {
        for (dark in listOf(false, true)) {
            val c = dynamicScheme(context, dark, AppThemes.Default.mode(dark).scheme)
            val plum = if (dark) PrivateColors.Dark.second else PrivateColors.Light.second
            println("CHIP robolectric dark=$dark ${hex(plum)} -> ${hex(c.tertiaryContainer)} shift ${"%.4f".format(okLabDistance(plum, c.tertiaryContainer))}")
            assertTrue(okLabDistance(plum, c.tertiaryContainer) <= 0.02)
            for ((n, other) in surfacesOf(c)) assertTrue("dark=$dark $n", okLabDistance(c.tertiaryContainer, other) >= MIN_DYNAMIC_PRIVATE_GAP)
        }
    }
}
