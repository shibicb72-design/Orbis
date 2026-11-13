package com.shibi.orbis.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import com.shibi.orbis.settings.DarkMode

/** Every gate open: a scene with a host in this state moves. */
internal val OpenAmbient = SceneAmbient(motionEnabled = true, resumed = true, inPip = false, fullScreen = false, mediaPlaying = false, pageShowing = false)

/** The three animated themes, in picker order. */
internal val AnimatedThemes: List<AppTheme> get() = AppThemes.all.filter { it.animated }

/**
 * A test host: [OrbisTheme] for [theme] in [dark], and [host] with its one frame loop. The test sets
 * [SceneHost.ambient] itself, so a gate closes at once (no composition in between).
 */
@Composable
internal fun SceneTestHost(host: SceneHost, theme: AppTheme, dark: Boolean, animate: Boolean = true, content: @Composable () -> Unit) {
    OrbisTheme(if (dark) DarkMode.DARK else DarkMode.LIGHT, theme, animate) {
        ProvideSceneHost(host, content)
    }
}

/**
 * Fixed-time mode for stills: the clock stops (not resumed) and every non-static surface of [host] draws [scene] at
 * exactly [timeSec].
 */
internal fun SceneHost.showStill(scene: ChromeScene, timeSec: Float) {
    ambient = OpenAmbient.copy(resumed = false)
    seek(timeSec - scene.staticTimeSec)
}

/**
 * What a layer in [region] sits on, and the text and icons that may sit on its ink.
 *
 * `outline` is left out on purpose: in light mode `outline` on `background` is only 3.38 to 3.47 with no ink at
 * all, so any ink would break a 3:1 rule against the bar. The address field border keeps its 3:1 against the field's
 * own opaque `surface`. Do not widen this list without measuring.
 */
internal fun sceneForegrounds(region: SceneRegion, scheme: ColorScheme): Pair<Color, List<Pair<String, Color>>> =
    when (region) {
        SceneRegion.Page, SceneRegion.Bar, SceneRegion.Preview ->
            scheme.background to listOf("onBackground" to scheme.onBackground, "onSurfaceVariant" to scheme.onSurfaceVariant, "primary" to scheme.primary)
        SceneRegion.Sheet ->
            scheme.surfaceContainerLow to listOf("onSurface" to scheme.onSurface, "onSurfaceVariant" to scheme.onSurfaceVariant, "primary" to scheme.primary)
    }

/**
 * [n] frames of the held clock. State the test wrote on its own thread is announced first: with the clock held,
 * nothing else would tell the recomposer or a snapshotFlow about it.
 */
internal fun ComposeContentTestRule.frames(n: Int) {
    Snapshot.sendApplyNotifications()
    repeat(n) {
        mainClock.advanceTimeByFrame()
        Snapshot.sendApplyNotifications()
    }
}

/** The pixels of [image] as ARGB ints, row by row. */
internal fun ImageBitmap.argb(): IntArray {
    val map = toPixelMap()
    return IntArray(map.width * map.height) { i -> map.buffer[map.bufferOffset + (i / map.width) * map.stride + i % map.width] }
}
