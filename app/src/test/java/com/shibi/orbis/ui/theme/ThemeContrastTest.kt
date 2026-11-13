package com.shibi.orbis.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG AA for every theme, light and dark: 4.5:1 for text and icons, 3:1 for non-text parts. */
class ThemeContrastTest {
    private fun forEveryMode(check: (String, ColorScheme, MutableList<String>) -> Unit): List<String> {
        val failures = mutableListOf<String>()
        for (theme in AppThemes.all) {
            for (dark in listOf(false, true)) {
                check("${theme.id}/${if (dark) "dark" else "light"}", theme.mode(dark).scheme, failures)
            }
        }
        return failures
    }

    private fun MutableList<String>.need(where: String, label: String, fg: Color, bg: Color, min: Double) {
        val r = contrastOf(fg, bg)
        if (r < min) add("$where $label ${"%.2f".format(r)} < $min")
    }

    @Test
    fun textAndIconPairsMeetAA() {
        val failures =
            forEveryMode { w, c, f ->
                val aa = 4.5
                f.need(w, "onBackground/background", c.onBackground, c.background, aa)
                for ((n, s) in listOf("surface" to c.surface, "container" to c.surfaceContainer, "containerHigh" to c.surfaceContainerHigh, "containerHighest" to c.surfaceContainerHighest, "containerLow" to c.surfaceContainerLow)) {
                    f.need(w, "onSurface/$n", c.onSurface, s, aa)
                }
                for ((n, s) in listOf("surface" to c.surface, "container" to c.surfaceContainer, "containerHigh" to c.surfaceContainerHigh, "variant" to c.surfaceVariant, "background" to c.background)) {
                    f.need(w, "onSurfaceVariant/$n", c.onSurfaceVariant, s, aa)
                }
                f.need(w, "onPrimary/primary", c.onPrimary, c.primary, aa)
                for ((n, s) in listOf("surface" to c.surface, "background" to c.background, "containerHigh" to c.surfaceContainerHigh)) {
                    f.need(w, "primary/$n", c.primary, s, aa)
                }
                f.need(w, "onPrimaryContainer/primaryContainer", c.onPrimaryContainer, c.primaryContainer, aa)
                f.need(w, "onSecondary/secondary", c.onSecondary, c.secondary, aa)
                f.need(w, "onSecondaryContainer/secondaryContainer", c.onSecondaryContainer, c.secondaryContainer, aa)
                f.need(w, "onTertiary/tertiary", c.onTertiary, c.tertiary, aa)
                f.need(w, "onTertiaryContainer/tertiaryContainer", c.onTertiaryContainer, c.tertiaryContainer, aa)
                f.need(w, "tertiary/surface", c.tertiary, c.surface, aa)
                f.need(w, "tertiary/background", c.tertiary, c.background, aa)
                f.need(w, "onError/error", c.onError, c.error, aa)
                f.need(w, "onErrorContainer/errorContainer", c.onErrorContainer, c.errorContainer, aa)
                f.need(w, "inverseOnSurface/inverseSurface", c.inverseOnSurface, c.inverseSurface, aa)
            }
        assertTrue("Contrast below 4.5:1:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun nonTextPartsMeetThreeToOne() {
        val failures =
            forEveryMode { w, c, f ->
                f.need(w, "primary/containerHighest (progress)", c.primary, c.surfaceContainerHighest, 3.0)
                f.need(w, "outline/surface", c.outline, c.surface, 3.0)
                f.need(w, "outline/background", c.outline, c.background, 3.0)
            }
        assertTrue("Contrast below 3:1:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun backgroundsKeepTheirPolarity() {
        val failures = mutableListOf<String>()
        for (theme in AppThemes.all) {
            if (!polarityOk(theme.light.scheme.background, dark = false)) failures.add("${theme.id} light")
            if (!polarityOk(theme.dark.scheme.background, dark = true)) failures.add("${theme.id} dark")
        }
        assertTrue("Wrong polarity: $failures", failures.isEmpty())
    }

    /** Tertiary is reserved for private tabs: the exact plum in every theme. */
    @Test
    fun privateColoursAreThePlumInEveryTheme() {
        for (theme in AppThemes.all) {
            val l = theme.light.scheme
            assertEquals(theme.id, PrivateColors.Light.first, l.tertiary)
            assertEquals(theme.id, PrivateColors.Light.second, l.tertiaryContainer)
            assertEquals(theme.id, PrivateColors.Light.third, l.onTertiaryContainer)
            val d = theme.dark.scheme
            assertEquals(theme.id, PrivateColors.Dark.first, d.tertiary)
            assertEquals(theme.id, PrivateColors.Dark.second, d.tertiaryContainer)
            assertEquals(theme.id, PrivateColors.Dark.third, d.onTertiaryContainer)
        }
    }

    /** A private tab chip must stand out from the surfaces a tab can sit on, and no accent may be plum. */
    @Test
    fun privateLookIsDistinctInEveryTheme() {
        val failures =
            forEveryMode { w, c, f ->
                val floor = FLOOR
                val others = listOf("surface" to c.surface, "surfaceContainer" to c.surfaceContainer, "secondaryContainer" to c.secondaryContainer, "primaryContainer" to c.primaryContainer, "background" to c.background)
                for ((n, other) in others) {
                    val d = okLabDistance(c.tertiaryContainer, other)
                    if (d < floor) f.add("$w tertiaryContainer vs $n ${"%.3f".format(d)} < $floor")
                }
                val plum = okLab(c.tertiary)
                for ((n, accent) in listOf("primary" to c.primary, "secondary" to c.secondary, "secondaryContainer" to c.secondaryContainer)) {
                    val lab = okLab(accent)
                    // Hue only means something with some chroma; near-grey accents (Graphite) cannot be mistaken for plum.
                    if (lab.chroma > 0.03 && hueDistance(lab.hue, plum.hue) < 40.0) {
                        f.add("$w $n hue ${"%.0f".format(lab.hue)} is close to plum ${"%.0f".format(plum.hue)}")
                    }
                }
            }
        assertTrue("Private look not distinct:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun idsAreUniqueAndTheCountIsDeliberate() {
        val ids = AppThemes.all.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertEquals(listOf("orbis_teal", "graphite", "harbor", "fern", "ember", "paper"), ids)
        assertTrue("no animated themes yet", AppThemes.all.none { it.animated })
    }

    private companion object {
        /** Orbis Teal cannot change and its light plum chip sits close to its background, so the floor is low (measured minimum 0.030, Graphite light). */
        const val FLOOR = 0.025
    }
}
