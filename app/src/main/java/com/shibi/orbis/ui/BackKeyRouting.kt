package com.shibi.orbis.ui

import android.view.KeyEvent
import android.view.Window

/**
 * Routes the Back key to the app's own back handling before any view sees it. GeckoView, once a page has been touched,
 * consumes the first Back key itself, so the browser's BackHandlers (close find, leave page fullscreen, history Back)
 * never ran and Back looked dead (seen on an API 35 emulator). The keyboard (IME) and dialogs still get Back first: they
 * receive it before the activity's window does. Returns true when the event was taken.
 */
fun routeBackKey(event: KeyEvent, onBack: () -> Unit): Boolean {
    if (event.keyCode != KeyEvent.KEYCODE_BACK) return false
    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack()
    return true
}

/** The activity's window callback with the Back key routed by [routeBackKey]; everything else goes to [delegate]. */
class BackKeyWindowCallback(private val delegate: Window.Callback, private val onBack: () -> Unit) : Window.Callback by delegate {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = routeBackKey(event, onBack) || delegate.dispatchKeyEvent(event)
}
