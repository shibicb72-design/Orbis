package com.shibi.orbis.ui

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import java.time.Duration
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowSystemClock

/**
 * Lets a guarded confirm button answer the next tap: gives every shown dialog window focus (Robolectric never does,
 * a device does when the dialog appears), lets Compose see it, then moves Robolectric's uptime clock past
 * [TAP_GUARD_DELAY_MS]. The Compose test rule moves neither by itself.
 */
fun ComposeContentTestRule.passTapGuard(extraMs: Long = 100) {
    for (dialog in ShadowDialog.getShownDialogs()) {
        val decor = dialog.window?.decorView ?: continue
        if (decor.isAttachedToWindow) setWindowFocus(decor, true)
    }
    waitForIdle()
    ShadowSystemClock.advanceBy(Duration.ofMillis(TAP_GUARD_DELAY_MS + extraMs))
}

/** What the window's ViewRootImpl does when the window gains or loses focus (Compose listens on the tree observer). */
fun setWindowFocus(view: View, focused: Boolean) {
    ViewTreeObserver::class.java
        .getDeclaredMethod("dispatchOnWindowFocusChange", Boolean::class.javaPrimitiveType)
        .apply { isAccessible = true }
        .invoke(view.viewTreeObserver, focused)
}
