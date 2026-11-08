package com.shibi.orbis.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
