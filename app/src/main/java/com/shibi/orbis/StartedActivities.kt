package com.shibi.orbis

/**
 * Started MainActivity instances, and the app's foreground and background that follow from them. Two instances can
 * overlap: Back finishes an activity that a link opened, and a link opened right after that creates a new instance,
 * which starts before the old one stops. The app goes to the background only when the last started instance stops;
 * the old instance's onStop must not put the page of the new one to sleep. Main thread only.
 *
 * [onForeground] puts the selected page, downloads and torrents in the foreground (true) or the background (false).
 */
class StartedActivities(private val onForeground: (Boolean) -> Unit) {
    private var started = 0

    /** No instance is started: the app is in the background. */
    val noneStarted: Boolean get() = started == 0

    fun onStart() {
        started++
        onForeground(true)
    }

    /** True when this stop sent the app to the background (no instance is started any more). */
    fun onStop(): Boolean {
        if (started > 0) started--
        if (started > 0) return false
        onForeground(false)
        return true
    }
}
