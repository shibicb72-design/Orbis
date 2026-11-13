package com.shibi.orbis.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/** WCAG 2 contrast ratio between two opaque colours, 1..21. */
internal fun contrastOf(a: Color, b: Color): Double {
    val la = a.luminance().toDouble()
    val lb = b.luminance().toDouble()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/** Light backgrounds have luminance of at least 0.5 and dark ones at most 0.18, so the status bar icon logic holds. */
internal fun polarityOk(background: Color, dark: Boolean): Boolean {
    val l = background.luminance()
    return if (dark) l <= 0.18f else l >= 0.5f
}

/**
 * [color], moved toward [toward] in small steps until it reads at [min] or better on every colour in [on].
 * Returns [toward] if it never gets there.
 */
internal fun readableOn(color: Color, on: List<Color>, toward: Color, min: Double = 4.55): Color {
    fun ok(c: Color) = on.all { contrastOf(c, it) >= min }
    if (ok(color)) return color
    for (step in 1..50) {
        val c = lerp(color, toward, step / 50f)
        if (ok(c)) return c
    }
    return toward
}

/** The candidate that reads best on [background]. */
internal fun bestOn(background: Color, candidates: List<Color>): Color = candidates.maxBy { contrastOf(it, background) }

/** A colour in OKLab. */
internal class OkLab(val l: Double, val a: Double, val b: Double) {
    val chroma: Double get() = sqrt(a * a + b * b)

    /** Hue in degrees, 0..360. Meaningless when [chroma] is near zero. */
    val hue: Double get() = (Math.toDegrees(atan2(b, a)) + 360.0) % 360.0
}

internal fun okLab(c: Color): OkLab {
    fun lin(v: Float): Double {
        val x = v.toDouble()
        return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    }
    val r = lin(c.red)
    val g = lin(c.green)
    val bl = lin(c.blue)
    val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * bl)
    val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * bl)
    val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * bl)
    return OkLab(
        l = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
        a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
        b = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
    )
}

internal fun okLabDistance(x: Color, y: Color): Double {
    val p = okLab(x)
    val q = okLab(y)
    val dl = p.l - q.l
    val da = p.a - q.a
    val db = p.b - q.b
    return sqrt(dl * dl + da * da + db * db)
}

/** Smallest angle between two hues, in degrees, 0..180. */
internal fun hueDistance(a: Double, b: Double): Double {
    val d = kotlin.math.abs(a - b) % 360.0
    return if (d > 180.0) 360.0 - d else d
}
