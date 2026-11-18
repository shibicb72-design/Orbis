package com.shibi.orbis.clear

/**
 * Counts the Orbis activities that are alive (created and not yet destroyed) and tells a fresh start from a restore.
 *
 * Fresh start ("fully closed" before): the first activity is created with no saved state while no other Orbis activity
 * is alive, and the browser's task is gone from Recents. That is a task swiped out of Recents, a force stop or a phone
 * restart, also when the process survived the swipe. Android restoring the task (also after it killed the process for
 * memory) hands back a saved state: not fresh. A second activity while one is alive is never fresh. Pure; main thread
 * only.
 */
class UiSessionTracker {
    private var alive = 0

    /**
     * Call from every Orbis activity's onCreate. True for a fresh start.
     *
     * [browserTaskInRecents]: Recents still holds the browser's task with its main activity in it. A link or Custom
     * Tab that is the first to start after Android killed Orbis in the background (or destroyed the main activity under
     * "Don't keep activities") has no saved state of its own; this tells that case from a full close. The main
     * activity itself passes false: its own task is that task, and its saved state already says whether it was restored.
     */
    fun onActivityCreated(hasSavedState: Boolean, browserTaskInRecents: Boolean = false): Boolean {
        val fresh = alive == 0 && !hasSavedState && !browserTaskInRecents
        alive++
        return fresh
    }

    /** Call from the same activity's onDestroy (also when onCreate finished it at once). */
    fun onActivityDestroyed() {
        if (alive > 0) alive--
    }
}
