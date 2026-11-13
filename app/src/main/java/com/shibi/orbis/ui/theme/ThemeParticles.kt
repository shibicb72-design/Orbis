package com.shibi.orbis.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Hard cap on scene elements on one screen, across every system and every layer of a scene. */
const val MAX_PARTICLES = 40

/** How a particle moves: falls, rises, crosses left to right (all three wrap), or stays put. */
enum class Drift { Down, Up, Sideways, Fixed }

/**
 * A declarative particle system. Speeds, sway and size are in dp ([sizeDp] is whatever extent the scene draws, a
 * radius or a width). [fadeInFrac] and [fadeOutFrac] are fractions of the travel; a [Drift.Fixed] particle never fades.
 */
@Immutable
class ParticleSystem(
    val count: Int,
    val seed: Int,
    val drift: Drift,
    /** dp per second; ignored for [Drift.Fixed]. */
    val speedDp: ClosedFloatingPointRange<Float>,
    val swayDp: Float,
    val swayHz: Float,
    val sizeDp: ClosedFloatingPointRange<Float>,
    val alpha: Float,
    val fadeInFrac: Float,
    val fadeOutFrac: Float,
)

/**
 * Per-particle constants, built once per size. Every getter is a pure function of time and allocates nothing, so the
 * same seed, size and time always give the same picture. [count] is the grant the caller got from [SceneBudget.take].
 */
class ParticleField(private val system: ParticleSystem, val size: Size, density: Density, grant: Int) {
    val count: Int = grant.coerceIn(0, MAX_PARTICLES)
    private val dp = density.density
    private val margin = system.sizeDp.endInclusive * dp
    private val x0 = FloatArray(count)
    private val y0 = FloatArray(count)
    private val speed = FloatArray(count)
    private val phase = FloatArray(count)
    private val extra = FloatArray(count)
    private val sizePx = FloatArray(count)

    init {
        var s = system.seed * -1640531535 + 12345 // the seed times 2654435761, as an Int
        fun next(): Float {
            s = s * 1664525 + 1013904223
            return ((s ushr 8) and 0xFFFFFF) / 16777216f
        }
        if (system.drift == Drift.Fixed && count > 0) {
            // Spread evenly: one particle per cell of a grid shaped like the surface, cells picked in seeded order.
            val aspect = if (size.height > 0f) size.width / size.height else 1f
            val cols = max(1, ceil(sqrt(count * aspect)).toInt())
            val rows = max(1, ceil(count / cols.toFloat()).toInt())
            val cells = IntArray(cols * rows) { it }
            for (i in cells.size - 1 downTo 1) {
                val j = min(i, (next() * (i + 1)).toInt())
                val c = cells[i]
                cells[i] = cells[j]
                cells[j] = c
            }
            for (i in 0 until count) {
                val cell = cells[i]
                x0[i] = ((cell % cols) + next()) / cols
                y0[i] = ((cell / cols) + next()) / rows
            }
        } else {
            for (i in 0 until count) {
                x0[i] = next()
                y0[i] = next()
            }
        }
        for (i in 0 until count) {
            speed[i] = lerpF(system.speedDp.start, system.speedDp.endInclusive, next()) * dp
            phase[i] = next() * (2f * PI.toFloat())
            extra[i] = next()
            sizePx[i] = lerpF(system.sizeDp.start, system.sizeDp.endInclusive, next()) * dp
        }
    }

    private fun travelSpan(): Float =
        when (system.drift) {
            Drift.Sideways -> size.width + 2f * margin
            Drift.Down, Drift.Up -> size.height + 2f * margin
            Drift.Fixed -> 1f
        }

    /** Travel progress of particle [i] at [t], in 0..1 (wrapping); also the fade parameter. Fixed particles sit at 0.5. */
    fun progress(i: Int, t: Float): Float {
        if (system.drift == Drift.Fixed) return 0.5f
        val span = travelSpan()
        if (span <= 0f) return 0f
        val start = if (system.drift == Drift.Sideways) x0[i] else y0[i]
        val w = start + speed[i] * t / span
        return w - floor(w)
    }

    /** The system's alpha, faded in and out over the travel, in 0..1. */
    fun alphaAt(p: Float): Float {
        var f = 1f
        val fi = system.fadeInFrac
        val fo = system.fadeOutFrac
        if (system.drift != Drift.Fixed) {
            if (fi > 0f && p < fi) f = p / fi
            if (fo > 0f && p > 1f - fo) f = min(f, (1f - p) / fo)
        }
        return (system.alpha * f).coerceIn(0f, 1f)
    }

    private fun sway(i: Int, t: Float): Float =
        if (system.swayDp == 0f) 0f else system.swayDp * dp * sin(2f * PI.toFloat() * system.swayHz * t + phase[i])

    fun x(i: Int, t: Float, p: Float): Float =
        when (system.drift) {
            Drift.Sideways -> -margin + p * (size.width + 2f * margin)
            Drift.Fixed -> x0[i] * size.width
            Drift.Down, Drift.Up -> x0[i] * size.width + sway(i, t)
        }

    fun y(i: Int, t: Float, p: Float): Float =
        when (system.drift) {
            Drift.Down -> -margin + p * (size.height + 2f * margin)
            Drift.Up -> size.height + margin - p * (size.height + 2f * margin)
            Drift.Fixed -> y0[i] * size.height
            Drift.Sideways -> y0[i] * size.height + sway(i, t)
        }

    /** The particle's extent in px (a radius or a width, as the scene uses [ParticleSystem.sizeDp]). */
    fun sizeOf(i: Int): Float = sizePx[i]

    /** A seeded phase in 0..2π. */
    fun phaseOf(i: Int): Float = phase[i]

    /** One more seeded number in 0..1, for a scene's own per-particle constant (a twinkle period, say). */
    fun extraOf(i: Int): Float = extra[i]
}

/**
 * Draws every particle of [field] at time [t]: [draw] paints one particle around the origin with its extent in px
 * and its faded alpha. Inline, so the caller's lambda is not an object and nothing is allocated per frame.
 */
inline fun DrawScope.drawParticles(
    field: ParticleField,
    t: Float,
    draw: DrawScope.(i: Int, sizePx: Float, alpha: Float) -> Unit,
) {
    for (i in 0 until field.count) {
        val p = field.progress(i, t)
        val a = field.alphaAt(p)
        if (a <= 0f) continue
        translate(field.x(i, t, p), field.y(i, t, p)) { draw(i, field.sizeOf(i), a) }
    }
}

/** Twinkle brightness, 0.35..1: `0.35 + 0.65 · sin²(π·t / period + phase)`. */
fun twinkle(t: Float, periodSec: Float, phase: Float): Float {
    val s = sin(PI.toFloat() * t / periodSec + phase)
    return 0.35f + 0.65f * s * s
}

private fun lerpF(a: Float, b: Float, f: Float) = a + (b - a) * f
