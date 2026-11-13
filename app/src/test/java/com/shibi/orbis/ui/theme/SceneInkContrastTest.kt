package com.shibi.orbis.ui.theme

import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Text and icons on scene ink keep 4.5:1. A layer composites its scene once at `coverage × region.opacity`, so no
 * pixel holds more ink than that; this checks the worst pixel, full ink at that alpha, for every ink.
 */
class SceneInkContrastTest {
    @Test
    fun everyInkAtItsCeilingKeepsTextReadable() {
        val failures = mutableListOf<String>()
        for (theme in AnimatedThemes) {
            val scene = checkNotNull(theme.scene)
            for (dark in listOf(false, true)) {
                val scheme = theme.mode(dark).scheme
                for (region in SceneRegion.entries) {
                    val (base, foregrounds) = sceneForegrounds(region, scheme)
                    val alpha = scene.coverage(dark) * region.opacity
                    for (ink in scene.inks(dark)) {
                        val composite = ink.copy(alpha = alpha).compositeOver(base)
                        for ((name, fg) in foregrounds) {
                            val r = contrastOf(fg, composite)
                            if (r < 4.5) failures.add("${theme.id}/${if (dark) "dark" else "light"} $region ink ${hex(ink)} $name ${"%.2f".format(r)}")
                        }
                    }
                }
            }
        }
        assertTrue("Below 4.5:1 on scene ink:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun coverageIsAPositiveFractionAndInksAreOpaque() {
        for (theme in AnimatedThemes) {
            val scene = checkNotNull(theme.scene)
            for (dark in listOf(false, true)) {
                val c = scene.coverage(dark)
                assertTrue("${theme.id} coverage $c", c > 0f && c <= 1f)
                assertTrue(scene.inks(dark).isNotEmpty())
                for (ink in scene.inks(dark)) assertEquals("${theme.id} ink ${hex(ink)}", 1f, ink.alpha)
            }
        }
    }

    /** No ink may look like the private plum: none within 40° of its hue unless it is nearly grey. */
    @Test
    fun noInkIsPlum() {
        val failures = mutableListOf<String>()
        for (theme in AnimatedThemes) {
            for (dark in listOf(false, true)) {
                val plum = okLab(theme.mode(dark).scheme.tertiary)
                for (ink in checkNotNull(theme.scene).inks(dark)) {
                    val lab = okLab(ink)
                    if (lab.chroma > 0.03 && hueDistance(lab.hue, plum.hue) < 40.0) failures.add("${theme.id} ${hex(ink)} hue ${"%.0f".format(lab.hue)}")
                }
            }
        }
        assertTrue("Plum-like inks: $failures", failures.isEmpty())
    }
}
