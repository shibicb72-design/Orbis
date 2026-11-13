package com.shibi.orbis.ui.theme

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticlesTest {
    private val density = Density(2.625f)
    private val size = Size(1080f, 2400f)
    private val times = listOf(0f, 1f, 37.3f, 900f, 3599.9f)

    private fun system(drift: Drift, count: Int = 30, seed: Int = 99, fadeIn: Float = 0.1f, fadeOut: Float = 0.2f) =
        ParticleSystem(count, seed, drift, 4f..16f, swayDp = 6f, swayHz = 0.15f, sizeDp = 2f..40f, alpha = 0.9f, fadeInFrac = fadeIn, fadeOutFrac = fadeOut)

    @Test
    fun theFieldHasExactlyItsGrant() {
        for (grant in listOf(0, 1, 13, 28, 40)) {
            assertEquals(grant, ParticleField(system(Drift.Fixed, count = 40), size, density, grant).count)
        }
        assertEquals("never past the cap", MAX_PARTICLES, ParticleField(system(Drift.Down, count = 100), size, density, 100).count)
    }

    @Test
    fun theSameSeedSizeAndTimeGiveTheSamePositions() {
        for (drift in Drift.entries) {
            val a = ParticleField(system(drift), size, density, 30)
            val b = ParticleField(system(drift), size, density, 30)
            val other = ParticleField(system(drift, seed = 100), size, density, 30)
            var differs = false
            for (t in times) {
                for (i in 0 until a.count) {
                    val pa = a.progress(i, t)
                    assertEquals(pa, b.progress(i, t))
                    assertEquals(a.x(i, t, pa), b.x(i, t, pa))
                    assertEquals(a.y(i, t, pa), b.y(i, t, pa))
                    assertEquals(a.sizeOf(i), b.sizeOf(i))
                    assertEquals(a.phaseOf(i), b.phaseOf(i))
                    if (a.x(i, t, pa) != other.x(i, t, other.progress(i, t))) differs = true
                }
            }
            assertTrue("$drift: another seed gives another field", differs)
        }
    }

    @Test
    fun alphaStaysInRange() {
        for (drift in Drift.entries) {
            val f = ParticleField(system(drift), size, density, 30)
            var p = 0f
            while (p <= 1f) {
                val a = f.alphaAt(p)
                assertTrue("$drift alpha $a at $p", a in 0f..1f)
                p += 0.01f
            }
            for (t in times) for (i in 0 until f.count) assertTrue(f.progress(i, t) in 0f..1f)
        }
        val loud = ParticleField(ParticleSystem(5, 1, Drift.Down, 1f..1f, 0f, 0f, 1f..1f, alpha = 3f, fadeInFrac = 0f, fadeOutFrac = 0f), size, density, 5)
        assertEquals(1f, loud.alphaAt(0.5f))
    }

    @Test
    fun positionsStayNearTheSurfaceForEveryDrift() {
        for (drift in Drift.entries) {
            val f = ParticleField(system(drift), size, density, 30)
            for (t in times) {
                for (i in 0 until f.count) {
                    val p = f.progress(i, t)
                    val x = f.x(i, t, p)
                    val y = f.y(i, t, p)
                    assertTrue("$drift x $x at t=$t", x >= -size.width && x <= 2 * size.width)
                    assertTrue("$drift y $y at t=$t", y >= -size.height && y <= 2 * size.height)
                }
            }
        }
    }

    @Test
    fun fixedParticlesNeverMoveAndCoverTheWholeSurface() {
        val f = ParticleField(system(Drift.Fixed, count = 36), size, density, 36)
        val quadrants = IntArray(4)
        for (i in 0 until f.count) {
            val x = f.x(i, 0f, f.progress(i, 0f))
            val y = f.y(i, 0f, f.progress(i, 0f))
            assertEquals(x, f.x(i, 3599.9f, f.progress(i, 3599.9f)))
            assertEquals(y, f.y(i, 3599.9f, f.progress(i, 3599.9f)))
            assertTrue(x in 0f..size.width && y in 0f..size.height)
            quadrants[(if (x < size.width / 2) 0 else 1) + (if (y < size.height / 2) 0 else 2)]++
        }
        // Spread evenly: every quarter of the surface gets a fair share, not a clump.
        for (q in quadrants) assertTrue("quadrants ${quadrants.toList()}", q in 6..12)
    }
}
