package com.shibi.orbis.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import com.shibi.orbis.R
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

private fun animatedTheme(id: String, nameRes: Int, descriptionRes: Int, light: Palette, dark: Palette, scene: ChromeScene) =
    AppTheme(
        id = id,
        nameRes = nameRes,
        descriptionRes = descriptionRes,
        light = paletteMode(light, dark = false, inversePrimary = dark.primary),
        dark = paletteMode(dark, dark = true, inversePrimary = light.primary),
        scene = scene,
    )

internal val NightSkyTheme =
    animatedTheme(
        id = "night_sky",
        nameRes = R.string.theme_name_night_sky,
        descriptionRes = R.string.theme_desc_night_sky,
        light = Palette(Color(0xFF2F4A96), Color(0xFFF5F8FF), Color(0xFF0F1630)),
        dark = Palette(Color(0xFFB4C5FF), Color(0xFF080C1C), Color(0xFFE9EDFA)),
        scene = NightSkyScene,
    )

internal val AuroraTheme =
    animatedTheme(
        id = "aurora",
        nameRes = R.string.theme_name_aurora,
        descriptionRes = R.string.theme_desc_aurora,
        light = Palette(Color(0xFF175A4D), Color(0xFFF0F8F5), Color(0xFF0E1F1B)),
        dark = Palette(Color(0xFF88D8C2), Color(0xFF07141A), Color(0xFFE6F4F0)),
        scene = AuroraScene,
    )

internal val FairWeatherTheme =
    animatedTheme(
        id = "fair_weather",
        nameRes = R.string.theme_name_fair_weather,
        descriptionRes = R.string.theme_desc_fair_weather,
        light = Palette(Color(0xFF1F5682), Color(0xFFF2F8FD), Color(0xFF0F1D2A)),
        dark = Palette(Color(0xFF9CCBF0), Color(0xFF0D1824), Color(0xFFE8F1F9)),
        scene = FairWeatherScene,
    )

/** "Wide" is a surface at least 840 dp across: it gets a few more elements. */
private fun Size.isWide(density: Density): Boolean = width / density.density >= 840f

private const val TWO_PI = (2.0 * PI).toFloat()

/** A pure integer hash of [n] (and a [salt]) to 0..1. */
private fun hash01(n: Int, salt: Int): Float {
    var x = n * -1640531535 + salt * 0x27D4EB2D
    x = x xor (x ushr 15)
    x *= 0x2C1B3C6D
    x = x xor (x ushr 12)
    x *= 0x297A2D39
    x = x xor (x ushr 15)
    return ((x ushr 8) and 0xFFFFFF) / 16777216f
}

/** Stars or sparkles that stay put and twinkle, each with a seeded period in 3.2..5.6 s. */
private class Twinklers(val field: ParticleField) {
    private val periods = FloatArray(field.count) { 3.2f + 2.4f * field.extraOf(it) }

    fun DrawScope.drawAll(t: Float, ink: Color) {
        drawParticles(field, t) { i, r, a ->
            drawCircle(ink, radius = r, center = Offset.Zero, alpha = a * twinkle(t, periods[i], field.phaseOf(i)))
        }
    }
}

private fun fixedDots(count: Int, seed: Int, radiusDp: ClosedFloatingPointRange<Float>) =
    ParticleSystem(count, seed, Drift.Fixed, 0f..0f, swayDp = 0f, swayHz = 0f, sizeDp = radiusDp, alpha = 1f, fadeInFrac = 0f, fadeOutFrac = 0f)

/** Night Sky: fixed twinkling stars and one shooting star every 9 s. */
internal object NightSkyScene : ChromeScene {
    private val DarkInk = Color(0xFFDDE6FF)
    private val LightInk = Color(0xFF5B76C4)
    private const val PERIOD_SEC = 9.0f
    private const val VISIBLE_SEC = 0.8f
    private const val PATH_DP = 180f
    private const val TRAIL_DP = 48f
    private const val STROKE_DP = 1.5f
    private const val COS_30 = 0.8660254f
    private const val SIN_30 = 0.5f
    private val TrailAlpha = floatArrayOf(1.0f, 0.7f, 0.4f, 0.15f)

    override val staticTimeSec = 3.0f

    override fun inks(dark: Boolean) = listOf(if (dark) DarkInk else LightInk)

    override fun coverage(dark: Boolean) = if (dark) 0.20f else 0.40f

    override fun build(size: Size, density: Density, dark: Boolean, budget: SceneBudget): SceneFrame {
        val want = if (size.isWide(density)) 35 else 28
        val shooting = budget.take(1) == 1
        val stars = Twinklers(ParticleField(fixedDots(want, 7301, 0.8f..1.6f), size, density, budget.take(want)))
        val ink = if (dark) DarkInk else LightInk
        val dp = density.density
        val area = size // the root's size, not the layer's: layers are windows onto this
        return object : SceneFrame(budget.used) {
            override fun DrawScope.draw(timeSec: Float) {
                with(stars) { drawAll(timeSec, ink) }
                if (shooting) shootingStar(timeSec, area, dp, ink)
            }
        }
    }

    private fun DrawScope.shootingStar(t: Float, area: Size, dp: Float, ink: Color) {
        val cycle = floor(t / PERIOD_SEC).toInt()
        val local = t - cycle * PERIOD_SEC
        if (local < 0f || local >= VISIBLE_SEC) return
        val p = local / VISIBLE_SEC
        val startX = (0.10f + 0.60f * hash01(cycle, 1)) * area.width
        val startY = (0.05f + 0.25f * hash01(cycle, 2)) * area.height
        val head = PATH_DP * dp * p
        val segment = TRAIL_DP * dp / TrailAlpha.size
        val fade = sin(PI.toFloat() * p)
        for (k in TrailAlpha.indices) {
            // The trail grows out of the start point; it never reaches behind it.
            val from = max(0f, head - k * segment)
            val to = max(0f, head - (k + 1) * segment)
            if (from <= to) continue
            drawLine(
                ink,
                start = Offset(startX + COS_30 * from, startY + SIN_30 * from),
                end = Offset(startX + COS_30 * to, startY + SIN_30 * to),
                strokeWidth = STROKE_DP * dp,
                cap = StrokeCap.Butt,
                alpha = TrailAlpha[k] * fade,
            )
        }
    }
}

/** Aurora: three slow ribbons of light and a few twinkling sparkles in the top 60 %. */
internal object AuroraScene : ChromeScene {
    private val DarkGreen = Color(0xFF5FE0B0)
    private val DarkCyan = Color(0xFF58C8E8)
    private val LightGreen = Color(0xFF1F9C80)
    private val LightCyan = Color(0xFF2A9DB8)
    private val Centre = floatArrayOf(0.22f, 0.38f, 0.55f)
    private val AmplitudeDp = floatArrayOf(18f, 24f, 30f)
    private val Wavelength = floatArrayOf(1.4f, 1.0f, 0.8f) // × width
    private val FrequencyHz = floatArrayOf(0.03f, 0.05f, 0.07f)
    private val ThicknessDp = floatArrayOf(90f, 70f, 56f)
    private val RelativeAlpha = floatArrayOf(0.55f, 0.45f, 0.35f)
    private val Phase = floatArrayOf(0.0f, 2.1f, 4.2f)
    private const val PULSE_HZ = 0.02f
    private const val COLUMNS = 7

    override val staticTimeSec = 6.0f

    override fun inks(dark: Boolean) = if (dark) listOf(DarkGreen, DarkCyan) else listOf(LightGreen, LightCyan)

    override fun coverage(dark: Boolean) = if (dark) 0.23f else 0.40f

    override fun build(size: Size, density: Density, dark: Boolean, budget: SceneBudget): SceneFrame {
        val green = if (dark) DarkGreen else LightGreen
        val cyan = if (dark) DarkCyan else LightCyan
        val ribbons = budget.take(3)
        val want = if (size.isWide(density)) 15 else 12
        val sparkleArea = Size(size.width, size.height * 0.6f)
        val sparkles = Twinklers(ParticleField(fixedDots(want, 4409, 0.8f..1.4f), sparkleArea, density, budget.take(want)))
        val dp = density.density
        val w = size.width
        val h = size.height
        val paths = Array(ribbons) { Path() }
        val brushes =
            Array(ribbons) { i ->
                val ink = if (i == 1) cyan else green
                val reach = (AmplitudeDp[i] + ThicknessDp[i]) * dp
                Brush.verticalGradient(
                    0f to ink.copy(alpha = 0f),
                    0.5f to ink,
                    1f to ink.copy(alpha = 0f),
                    startY = Centre[i] * h - reach,
                    endY = Centre[i] * h + reach,
                )
            }
        val top = FloatArray(COLUMNS)
        val bottom = FloatArray(COLUMNS)
        val step = w / (COLUMNS - 1)
        return object : SceneFrame(budget.used) {
            override fun DrawScope.draw(timeSec: Float) {
                for (i in 0 until ribbons) {
                    val half = ThicknessDp[i] * dp / 2f
                    for (k in 0 until COLUMNS) {
                        val x = k * step
                        val y = Centre[i] * h + AmplitudeDp[i] * dp * sin(TWO_PI * (x / (Wavelength[i] * w) + FrequencyHz[i] * timeSec) + Phase[i])
                        top[k] = y - half
                        bottom[k] = y + half
                    }
                    val path = paths[i]
                    path.rewind()
                    path.moveTo(0f, top[0])
                    smoothThrough(path, top, step, forward = true)
                    path.lineTo(w, bottom[COLUMNS - 1])
                    smoothThrough(path, bottom, step, forward = false)
                    path.close()
                    val pulse = 0.8f + 0.2f * sin(TWO_PI * PULSE_HZ * timeSec + Phase[i])
                    drawPath(path, brushes[i], alpha = RelativeAlpha[i] * pulse)
                }
                with(sparkles) { drawAll(timeSec, green) }
            }
        }
    }

    /** Cubic segments through every column (Catmull-Rom), left to right or back; the path is already at the first. */
    private fun smoothThrough(path: Path, ys: FloatArray, step: Float, forward: Boolean) {
        val n = ys.size
        for (s in 0 until n - 1) {
            val a = if (forward) s else n - 1 - s
            val b = if (forward) a + 1 else a - 1
            val before = if (forward) a - 1 else a + 1
            val after = if (forward) b + 1 else b - 1
            val dx = (b - a) * step / 3f
            path.cubicTo(
                a * step + dx, at(ys, a) + (at(ys, b) - at(ys, before)) / 6f,
                b * step - dx, at(ys, b) - (at(ys, after) - at(ys, a)) / 6f,
                b * step, at(ys, b),
            )
        }
    }

    private fun at(ys: FloatArray, k: Int) = ys[k.coerceIn(0, ys.size - 1)]
}

/** Fair Weather: three layers of soft clouds drifting left to right, nearer ones bigger, faster and more opaque. */
internal object FairWeatherScene : ChromeScene {
    private val LightBody = Color(0xFF8FB4D6)
    private val DarkBody = Color(0xFFC9DCEC)
    private val DarkUnderside = Color(0xFF9CCBF0)

    private class Layer(
        val count: Int,
        val wideCount: Int,
        val seed: Int,
        val speedDp: Float,
        val widthDp: ClosedFloatingPointRange<Float>,
        val bandTop: Float,
        val bandBottom: Float,
        val alpha: Float,
        /** Height of the middle oval, so the three layers do not share one outline. */
        val crown: Float,
    )

    private val Layers =
        listOf(
            Layer(4, 5, 2201, 6f, 72f..96f, 0.08f, 0.30f, 0.5f, crown = 0.20f),
            Layer(3, 4, 2202, 10f, 96f..128f, 0.25f, 0.55f, 0.7f, crown = 0.23f),
            Layer(2, 2, 2203, 16f, 128f..168f, 0.50f, 0.80f, 1.0f, crown = 0.25f),
        )
    private const val BOB_DP = 3f
    private const val BOB_HZ = 0.1f

    override val staticTimeSec = 7.5f

    override fun inks(dark: Boolean) = if (dark) listOf(DarkBody, DarkUnderside) else listOf(LightBody)

    override fun coverage(dark: Boolean) = if (dark) 0.20f else 0.35f

    /** One cloud, 1 unit wide, with its left edge at x = 0 and its flat base at y = 0.18: three ovals on a base. */
    private fun cloudPath(crown: Float) =
        Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(0.08f, -0.14f, 0.52f, 0.18f))
            addOval(androidx.compose.ui.geometry.Rect(0.29f, -0.06f - crown, 0.81f, 0.14f))
            addOval(androidx.compose.ui.geometry.Rect(0.58f, -0.11f, 0.94f, 0.17f))
            addRoundRect(androidx.compose.ui.geometry.RoundRect(0.08f, 0.02f, 0.94f, 0.18f, radiusX = 0.08f, radiusY = 0.08f))
        }

    /** How much of its full width a cloud keeps on a surface [heightDp] high: a low bar gets smaller clouds. */
    internal fun cloudWidthScale(heightDp: Float): Float = (heightDp / 400f).coerceIn(0.35f, 1f)

    /** The clouds of each layer on [size], widths scaled by [cloudWidthScale]. */
    internal fun cloudFields(size: Size, density: Density, budget: SceneBudget): Array<ParticleField> {
        val wide = size.isWide(density)
        val k = cloudWidthScale(size.height / density.density)
        return Layers.map { l ->
            val want = if (wide) l.wideCount else l.count
            val width = l.widthDp.start * k..l.widthDp.endInclusive * k
            val system =
                ParticleSystem(want, l.seed, Drift.Sideways, l.speedDp..l.speedDp, swayDp = BOB_DP, swayHz = BOB_HZ, sizeDp = width, alpha = l.alpha, fadeInFrac = 0f, fadeOutFrac = 0f)
            ParticleField(system, Size(size.width, size.height * (l.bandBottom - l.bandTop)), density, budget.take(want))
        }.toTypedArray()
    }

    override fun build(size: Size, density: Density, dark: Boolean, budget: SceneBudget): SceneFrame {
        val fields = cloudFields(size, density, budget)
        val tops = FloatArray(Layers.size) { Layers[it].bandTop * size.height }
        val paths = Array(Layers.size) { cloudPath(Layers[it].crown) }
        val body = if (dark) DarkBody else LightBody
        return object : SceneFrame(budget.used) {
            override fun DrawScope.draw(timeSec: Float) {
                for (l in fields.indices) {
                    val path = paths[l]
                    translate(0f, tops[l]) {
                        drawParticles(fields[l], timeSec) { _, width, a ->
                            scale(width, width, pivot = Offset.Zero) {
                                if (dark) {
                                    // A deeper underside shows below the body.
                                    translate(0f, 0.04f) { drawPath(path, DarkUnderside, alpha = a) }
                                }
                                drawPath(path, body, alpha = a)
                            }
                        }
                    }
                }
            }
        }
    }
}
