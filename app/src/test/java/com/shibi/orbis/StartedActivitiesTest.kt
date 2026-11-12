package com.shibi.orbis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartedActivitiesTest {
    @Test
    fun oneInstanceGoesToTheBackgroundWhenItStops() {
        val started = StartedActivities()
        started.onStart()
        assertTrue(started.onStop())
    }

    @Test
    fun theOldInstanceStoppingAfterTheNewOneStartedKeepsTheAppInTheForeground() {
        // Back finished instance A; a link started instance B before A stopped (seen on API 36).
        val started = StartedActivities()
        started.onStart() // A
        started.onStart() // B
        assertFalse("A stops while B is on screen", started.onStop())
        assertTrue("B stops: now in the background", started.onStop())
    }

    @Test
    fun anUnmatchedStopStillReportsTheBackground() {
        assertTrue(StartedActivities().onStop())
    }
}
