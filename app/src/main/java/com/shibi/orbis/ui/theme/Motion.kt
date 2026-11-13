package com.shibi.orbis.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Gate optional motion; Compose applies the system's nonzero duration scale to its animations. */
@Composable
fun rememberMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun enabled() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    var motionEnabled by remember(resolver) { mutableStateOf(enabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                motionEnabled = enabled()
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        motionEnabled = enabled()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return motionEnabled
}

/** Recorded baselines (OrbisRoot, TabSwitcher); kept, not tuned: no measurement justified a change. */
internal const val ROUTE_ENTER_MS = 160
internal const val ROUTE_EXIT_MS = 100
internal const val TAB_PLACEMENT_MS = 180

/**
 * Whether a root navigation between [fromRoute] and [toRoute] may fade. Never when motion is off (zero animator scale),
 * and never across the browser boundary: the browser route hosts the engine surface, which is not faded, scaled or
 * translated, so entering or leaving it is immediate. Fades stay on Compose-only secondary-to-secondary transitions.
 */
internal fun routeFades(motionEnabled: Boolean, fromRoute: String?, toRoute: String?, browserRoute: String): Boolean =
    motionEnabled && fromRoute != browserRoute && toRoute != browserRoute

/**
 * Whether a theme change may cross-fade on [route]. Never with motion off, before the first route is known, or on the
 * browser route: there the change is immediate, so the chrome around the engine view never shows a mixed colour.
 */
internal fun themeFades(motionEnabled: Boolean, route: String?, browserRoute: String): Boolean =
    motionEnabled && route != null && route != browserRoute

internal const val TAB_ENTER_MS = 140
internal const val TAB_EXIT_MS = 100
internal const val ADDRESS_FOCUS_MS = 120
internal const val THEME_CROSSFADE_MS = 220
internal const val PROGRESS_FADE_MS = 150

/** For animate*AsState and Animatable: a tween, or a snap when motion is off. */
internal fun <T> motionTween(enabled: Boolean, ms: Int): FiniteAnimationSpec<T> =
    if (enabled) tween(ms, easing = FastOutSlowInEasing) else snap()

/** For Modifier.animateItem, where null means "no animation". */
internal fun <T> motionTweenOrNull(enabled: Boolean, ms: Int): FiniteAnimationSpec<T>? =
    if (enabled) tween(ms, easing = FastOutSlowInEasing) else null

/** For values that are retargeted while moving (progress): no bounce. */
internal fun <T> motionSpring(enabled: Boolean): FiniteAnimationSpec<T> =
    if (enabled) spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow) else snap()

/**
 * A progress fraction that glides to [target]. It starts at [target] (never animates up from zero on first show),
 * never moves backwards (a lower target snaps), and snaps when [resetKey] changes or motion is off.
 * Read the result inside the indicator's `progress = { }` lambda only, so a frame redraws and never recomposes.
 */
@Composable
internal fun rememberSmoothProgress(target: Float, motionEnabled: Boolean, resetKey: Any? = null): State<Float> {
    val glide = remember(resetKey) { Animatable(target) }
    val latestTarget = rememberUpdatedState(target)
    val latestMotion = rememberUpdatedState(motionEnabled)
    LaunchedEffect(glide, target, motionEnabled) {
        if (!motionEnabled || target < glide.value) glide.snapTo(target) else glide.animateTo(target, motionSpring(true))
    }
    // The snap cases are decided here, not in the effect, so they show on the very next frame.
    return remember(glide) {
        derivedStateOf {
            val t = latestTarget.value
            if (!latestMotion.value || t < glide.value) t else glide.value
        }
    }
}
