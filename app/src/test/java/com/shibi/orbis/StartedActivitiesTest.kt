package com.shibi.orbis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartedActivitiesTest {
    private val calls = mutableListOf<Boolean>()
    private val started = StartedActivities { calls += it }

    @Test
    fun oneInstanceGoesToTheBackgroundWhenItStops() {
        started.onStart()
        assertFalse(started.noneStarted)
        assertTrue(started.onStop())
        assertTrue(started.noneStarted)
        assertEquals(listOf(true, false), calls)
    }

    @Test
    fun theOldInstanceStoppingAfterTheNewOneStartedKeepsThePageActive() {
        // Back finished instance A; a link started instance B before A stopped (seen on API 36).
        started.onStart() // A
        started.onStart() // B
        assertFalse("A stops while B is on screen", started.onStop())
        assertEquals("never put to the background while B is started", listOf(true, true), calls)
        assertTrue("B stops: now in the background", started.onStop())
        assertEquals(listOf(true, true, false), calls)
    }

    @Test
    fun anUnmatchedStopStillReportsTheBackground() {
        assertTrue(started.onStop())
        assertEquals(listOf(false), calls)
    }
}
