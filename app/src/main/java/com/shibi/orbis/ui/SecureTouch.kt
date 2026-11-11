package com.shibi.orbis.ui

import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import java.util.WeakHashMap

/** How long a confirm button ignores taps after it appears or its window gets focus back. */
const val TAP_GUARD_DELAY_MS = 500L

/**
 * F-15: while this is composed, the window it is in drops touches that arrive through another app's window drawn on top
 * (Android sets FLAG_WINDOW_IS_OBSCURED on them). Android 12+ already blocks most of those; this covers Android 10
 * and 11. Inside a dialog or bottom sheet that is the dialog's own window.
 *
 * The flag goes on the window's root view, not on [LocalView]: the Compose view handles touches itself and does not
 * run the platform's obscured-touch check, while every ViewGroup above it does.
 */
@Composable
fun ObscuredTouchFilter() {
    val view = LocalView.current
    DisposableEffect(view) {
        val hold = ObscuredTouchHold(view)
        onDispose { hold.release() }
    }
}

/**
 * A confirm button's tap guard: the returned function says whether a tap may act now. False while the window does not
 * have focus, until [delayMs] after the first composition with these [keys] (uptime clock), and again for [delayMs]
 * each time the window regains focus. Pass the identity of what is being confirmed as [keys]: a dialog window can stay
 * up while its content changes to the next prompt, and the next prompt must not inherit a guard that already passed.
 * The button stays enabled and looks the same either way; its onClick just does nothing while this says no. Cancel is
 * never guarded.
 */
@Composable
fun rememberTapGuard(vararg keys: Any?, delayMs: Long = TAP_GUARD_DELAY_MS): () -> Boolean {
    val times = remember(*keys) { GuardTimes(shownAtMs = SystemClock.uptimeMillis()) }
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo, times) {
        var wasFocused = windowInfo.isWindowFocused
        snapshotFlow { windowInfo.isWindowFocused }.collect { focused ->
            if (focused && !wasFocused) times.focusGainedAtMs = SystemClock.uptimeMillis()
            wasFocused = focused
        }
    }
    return remember(times, windowInfo, delayMs) {
        { tapAllowed(times.shownAtMs, times.focusGainedAtMs, windowInfo.isWindowFocused, SystemClock.uptimeMillis(), delayMs) }
    }
}

/**
 * A dialog's positive [TextButton] behind [rememberTapGuard]. Compose it inside the dialog (its confirm slot), so the
 * guard watches the dialog's own window focus. [guardKey] is the identity of what is being confirmed (see
 * [rememberTapGuard]).
 */
@Composable
fun GuardedTextButton(
    guardKey: Any?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    val tapGuard = rememberTapGuard(guardKey)
    TextButton(onClick = { if (tapGuard()) onClick() }, modifier = modifier, colors = colors, content = content)
}

/**
 * The rule behind [rememberTapGuard]: a tap counts only while the window has focus, and once [delayMs] have passed
 * since the button was shown and since the window last regained focus ([focusGainedAtMs] null when it never did). A
 * clock that went backwards counts as "too soon".
 */
fun tapAllowed(shownAtMs: Long, focusGainedAtMs: Long?, windowFocused: Boolean, nowMs: Long, delayMs: Long): Boolean {
    if (!windowFocused) return false
    if (nowMs - shownAtMs < delayMs) return false
    return focusGainedAtMs == null || nowMs - focusGainedAtMs >= delayMs
}

private class GuardTimes(val shownAtMs: Long) {
    var focusGainedAtMs: Long? = null
}

/**
 * One [ObscuredTouchFilter]'s hold on its window root. Several can be composed in the same window at once (a dialog's
 * text and its button), so the roots are counted and the root's own value comes back when the last one goes. Main
 * thread only, like every View.
 */
private class ObscuredTouchHold(private val view: View) {
    private var root: View? = null
    private val attachListener =
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = acquire()

            override fun onViewDetachedFromWindow(v: View) = Unit
        }

    init {
        if (view.isAttachedToWindow) acquire() else view.addOnAttachStateChangeListener(attachListener)
    }

    private fun acquire() {
        if (root != null) return
        view.removeOnAttachStateChangeListener(attachListener)
        val r = view.rootView
        root = r
        val held = holds[r]
        if (held == null) {
            holds[r] = Held(count = 1, original = r.filterTouchesWhenObscured)
            r.filterTouchesWhenObscured = true
        } else {
            held.count++
        }
    }

    fun release() {
        view.removeOnAttachStateChangeListener(attachListener)
        val r = root ?: return
        root = null
        val held = holds[r] ?: return
        held.count--
        if (held.count <= 0) {
            holds.remove(r)
            r.filterTouchesWhenObscured = held.original
        }
    }

    private class Held(var count: Int, val original: Boolean)

    private companion object {
        val holds = WeakHashMap<View, Held>()
    }
}
