package com.shibi.orbis.media

import com.shibi.orbis.media.PipWindowExit.Activity
import org.junit.Assert.assertEquals
import org.junit.Test

class PipWindowExitTest {
    private var closed = 0
    private val exit = PipWindowExit { closed++ }

    /** Enter PiP from fullscreen with Home: the activity pauses but stays started. */
    private fun enterPip() = exit.onPipModeChanged(true, Activity.STARTED)

    @Test
    fun closedWithTheModeChangeFirst() {
        enterPip()
        exit.onPipModeChanged(false, Activity.STARTED)
        assertEquals(0, closed)
        exit.onStop()
        assertEquals(1, closed)
    }

    @Test
    fun closedWithOnStopFirst() {
        enterPip()
        exit.onStop()
        assertEquals(0, closed)
        exit.onPipModeChanged(false, Activity.STOPPED)
        assertEquals(1, closed)
    }

    @Test
    fun expandedWithTheModeChangeFirstThenAnOrdinaryHomePress() {
        enterPip()
        exit.onPipModeChanged(false, Activity.STARTED)
        exit.onResume()
        exit.onStop()
        assertEquals(0, closed)
    }

    @Test
    fun expandedWithOnResumeFirstThenAnOrdinaryHomePress() {
        enterPip()
        exit.onResume()
        exit.onPipModeChanged(false, Activity.RESUMED)
        exit.onStop()
        assertEquals(0, closed)
    }

    @Test
    fun aCloseCountsOnce() {
        enterPip()
        exit.onPipModeChanged(false, Activity.STARTED)
        exit.onStop()
        exit.onStop()
        assertEquals(1, closed)
    }

    @Test
    fun withoutPipOnStopDoesNothing() {
        exit.onResume()
        exit.onStop()
        assertEquals(0, closed)
    }
}
