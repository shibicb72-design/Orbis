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

    @Test
    fun extraDestroyDoesNotMakeALiveActivityLookGone() {
        val tracker = UiSessionTracker()
        tracker.onActivityDestroyed()
        tracker.onActivityCreated(hasSavedState = true)
        assertFalse(tracker.onActivityCreated(hasSavedState = false))
    }
}
