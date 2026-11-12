package com.shibi.orbis

/**
 * How many MainActivity instances are started. Two can overlap: Back finishes an activity that a link opened, and a
 * link opened right after that creates a new instance, which starts before the old one stops. The app is in the
 * background only when the last started instance stops; the old instance's onStop must not put the page of the new
 * one to sleep. Main thread only.
 */
class StartedActivities {
    private var started = 0

    fun onStart() {
        started++
    }

    /** True when no instance is started any more: the app has just gone to the background. */
    fun onStop(): Boolean {
        if (started > 0) started--
        return started == 0
    }
}
