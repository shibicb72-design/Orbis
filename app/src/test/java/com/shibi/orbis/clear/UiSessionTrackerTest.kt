package com.shibi.orbis.clear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSessionTrackerTest {
    @Test
    fun firstActivityWithoutSavedStateIsFresh() {
        assertTrue(UiSessionTracker().onActivityCreated(hasSavedState = false))
    }

    @Test
    fun firstActivityWithSavedStateIsNotFresh() {
        // Android restored the task, also after it killed the process for memory: nothing is wiped.
        assertFalse(UiSessionTracker().onActivityCreated(hasSavedState = true))
    }

    @Test
    fun secondActivityWhileOneIsAliveIsNotFresh() {
        val tracker = UiSessionTracker()
        tracker.onActivityCreated(hasSavedState = false)
        assertFalse(tracker.onActivityCreated(hasSavedState = false))
    }

    @Test
    fun recreateWithSavedStateAfterDestroyIsNotFresh() {
        val tracker = UiSessionTracker()
        tracker.onActivityCreated(hasSavedState = false)
        tracker.onActivityDestroyed()
        // Rotation or another configuration change.
        assertFalse(tracker.onActivityCreated(hasSavedState = true))
    }

    @Test
    fun createWithoutSavedStateAfterAllDestroyedIsFresh() {
        val tracker = UiSessionTracker()
        tracker.onActivityCreated(hasSavedState = false)
        tracker.onActivityCreated(hasSavedState = false)
        tracker.onActivityDestroyed()
        tracker.onActivityDestroyed()
        // The task was swiped away while the process lived on (a download kept it), then Orbis was opened again.
        assertTrue(tracker.onActivityCreated(hasSavedState = false))
    }

    // The eight cases of feat08-3d, as the activities report them on Android 16 (scratch\feat08-3d\step2-signals.md).
    // MainActivity always passes browserTaskInRecents = false; LinkRouterActivity and CustomTabActivity pass the signal.

    @Test
    fun row1SwipedThenLauncherIsFresh() {
        assertTrue(UiSessionTracker().onActivityCreated(hasSavedState = false, browserTaskInRecents = false))
    }

    @Test
    fun row2ForceStoppedThenLauncherIsFresh() {
        assertTrue(UiSessionTracker().onActivityCreated(hasSavedState = false, browserTaskInRecents = false))
    }

    @Test
    fun row3BackgroundKillThenRecentsIsNotFresh() {
        assertFalse(UiSessionTracker().onActivityCreated(hasSavedState = true, browserTaskInRecents = false))
    }

    @Test
    fun row4BackgroundKillThenLinkIsNotFresh() {
        val tracker = UiSessionTracker()
        // The link router joins the browser's task, which is still in Recents.
        assertFalse(tracker.onActivityCreated(hasSavedState = false, browserTaskInRecents = true))
        tracker.onActivityDestroyed()
        // The browser activity is then restored from its saved state.
        assertFalse(tracker.onActivityCreated(hasSavedState = true))
    }

    @Test
    fun row5BackgroundKillThenCustomTabIsNotFresh() {
        val tracker = UiSessionTracker()
        assertFalse(tracker.onActivityCreated(hasSavedState = false, browserTaskInRecents = true))
        tracker.onActivityDestroyed()
        // The Custom Tab has its own task and no saved state; the browser's task is still in Recents.
        assertFalse(tracker.onActivityCreated(hasSavedState = false, browserTaskInRecents = true))
    }

    @Test
    fun row6SwipedThenLinkIsFresh() {
        assertTrue(UiSessionTracker().onActivityCreated(hasSavedState = false, browserTaskInRecents = false))
    }

    @Test
    fun row7ForceStoppedThenLinkIsFresh() {
        assertTrue(UiSessionTracker().onActivityCreated(hasSavedState = false, browserTaskInRecents = false))
    }

    @Test
    fun row8DontKeepActivitiesThenLinkIsNotFresh() {
        val tracker = UiSessionTracker()
        tracker.onActivityCreated(hasSavedState = false)
        // Home: the browser activity is destroyed, its task stays in Recents, the process lives on.
        tracker.onActivityDestroyed()
        assertFalse(tracker.onActivityCreated(hasSavedState = false, browserTaskInRecents = true))
    }

    @Test
    fun extraDestroyDoesNotMakeALiveActivityLookGone() {
        val tracker = UiSessionTracker()
        tracker.onActivityDestroyed()
        tracker.onActivityCreated(hasSavedState = true)
        assertFalse(tracker.onActivityCreated(hasSavedState = false))
    }
}
