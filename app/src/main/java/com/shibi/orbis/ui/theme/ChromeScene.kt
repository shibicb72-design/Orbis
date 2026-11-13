package com.shibi.orbis.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density

/** What one screen may draw. [take] never grants more than is left, so the total can never pass [MAX_PARTICLES]. */
class SceneBudget(limit: Int = MAX_PARTICLES) {
    val limit: Int = limit.coerceIn(0, MAX_PARTICLES)
    var used: Int = 0
        private set

    /** Grants min([want], what is left), at least 0, and counts it as used. */
    fun take(want: Int): Int {
        val granted = want.coerceIn(0, limit - used)
        used += granted
        return granted
    }
}

/**
 * One scene, built for one size. [elements] is what the build took from its [SceneBudget]. [draw] runs every frame
 * and allocates nothing: every Path, Brush and array is made when the frame is built.
 */
abstract class SceneFrame(val elements: Int) {
    abstract fun DrawScope.draw(timeSec: Float)
}

/** The moving background of an animated theme. It draws only in Orbis's own chrome, never on a web page. */
@Immutable
interface ChromeScene {
    /** The designed still frame, shown when motion is off or the theme is set to Off. */
    val staticTimeSec: Float

    /** Every colour the scene draws with, opaque. */
    fun inks(dark: Boolean): List<Color>

    /**
     * The group alpha on a [SceneRegion.Page] layer: the whole scene is composited once at this alpha, so it is the
     * ceiling of ink opacity at any pixel, however many elements overlap.
     */
    fun coverage(dark: Boolean): Float

    /** Builds the frame for [size]; every drawn element is paid for with [SceneBudget.take]. */
    fun build(size: Size, density: Density, dark: Boolean, budget: SceneBudget): SceneFrame
}

/** Where a layer sits. [opacity] multiplies the scene's coverage. */
enum class SceneRegion(val opacity: Float) { Page(1f), Bar(0.8f), Sheet(1f), Preview(1f) }
