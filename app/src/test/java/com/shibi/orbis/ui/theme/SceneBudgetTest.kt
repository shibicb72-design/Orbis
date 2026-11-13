package com.shibi.orbis.ui.theme

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** No screen draws more than [MAX_PARTICLES] scene elements, and each scene draws exactly its designed count. */
@RunWith(RobolectricTestRunner::class) // the scenes build android.graphics Paths
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SceneBudgetTest {
    @Test
    fun theBudgetNeverGrantsMoreThanIsLeft() {
        assertEquals(40, SceneBudget(100).limit)
        assertEquals(0, SceneBudget(-3).limit)
        val b = SceneBudget(13)
        assertEquals(10, b.take(10))
        assertEquals(3, b.take(10))
        assertEquals(0, b.take(1))
        assertEquals(0, b.take(-5))
        assertEquals(13, b.used)
        val full = SceneBudget()
        assertEquals(40, full.take(1000))
        assertEquals(0, full.take(1))
    }

    @Test
    fun everySceneStaysInsideItsBudgetAtEverySize() {
        val designed = mapOf("night_sky" to (29 to 36), "aurora" to (15 to 18), "fair_weather" to (9 to 11))
        val density = Density(2.625f)
        for (theme in AnimatedThemes) {
            val scene = checkNotNull(theme.scene)
            for (w in listOf(320, 360, 412, 600, 840, 1200, 1920)) {
                for (h in listOf(120, 640, 915)) {
                    for (dark in listOf(false, true)) {
                        for (limit in listOf(13, 40)) {
                            val budget = SceneBudget(limit)
                            val frame = scene.build(Size(w * density.density, h * density.density), density, dark, budget)
                            val where = "${theme.id} ${w}x$h dark=$dark budget=$limit"
                            assertEquals("$where: elements are what the build took", budget.used, frame.elements)
                            assertTrue("$where: ${frame.elements} elements", frame.elements <= limit)
                            if (limit == 40) {
                                val (phone, wide) = designed.getValue(theme.id)
                                assertEquals(where, if (w >= 840) wide else phone, frame.elements)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun aSceneWithNoBudgetBuildsNothing() {
        for (theme in AnimatedThemes) {
            assertEquals(theme.id, 0, checkNotNull(theme.scene).build(Size(1080f, 2400f), Density(2.625f), false, SceneBudget(0)).elements)
        }
    }

    /** The picker shows all three animated previews at once; together they stay under the cap. */
    @Test
    fun threePreviewsFitTheCap() {
        val animated = AppThemes.all.count { it.animated }
        assertEquals(3, animated)
        val previewBudget = MAX_PARTICLES / animated
        assertEquals(13, previewBudget)
        assertTrue(previewBudget * animated <= MAX_PARTICLES)
    }
}
