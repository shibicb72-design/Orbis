package com.shibi.orbis.media

import com.shibi.orbis.StartedActivities
import com.shibi.orbis.media.PipWindowExit.Activity
import org.junit.Assert.assertEquals
import org.junit.Test

class PipWindowExitTest {
    private var pending = 0
    private var closed = 0
    private val exit = PipWindowExit(onClosePending = { pending++ }) { closed++ }

    /** Enter PiP from fullscreen with Home: the activity pauses but stays started. */
    private fun enterPip() = exit.onPipModeChanged(true, Activity.STARTED)

    @Test
    fun closedWithTheModeChangeFirst() {
        enterPip()
        exit.onPipModeChanged(false, Activity.STARTED)
        assertEquals(0, closed)
        exit.onStop()
        assertEquals("pending, not yet closed", 0, closed)
        exit.confirmClose()
        assertEquals(1, closed)
    }

    @Test
    fun closedWithOnStopFirst() {
        enterPip()
        exit.onStop()
        assertEquals(0, closed)
        exit.onPipModeChanged(false, Activity.STOPPED)
        assertEquals("pending, not yet closed", 0, closed)
        exit.confirmClose()
        assertEquals(1, closed)
    }

    @Test
    fun expandedWithTheModeChangeFirstThenAnOrdinaryHomePress() {
        enterPip()
        exit.onPipModeChanged(false, Activity.STARTED)
        exit.onResume()
        exit.onStop()
        exit.confirmClose()
        assertEquals(0, closed)
        assertEquals(0, pending)
    }

    @Test
    fun expandedWithOnResumeFirstThenAnOrdinaryHomePress() {
        enterPip()
        exit.onResume()
        exit.onPipModeChanged(false, Activity.RESUMED)
        exit.onStop()
        exit.confirmClose()
        assertEquals(0, closed)
        assertEquals(0, pending)
    }

    @Test
    fun aCloseCountsOnce() {
        enterPip()
        exit.onPipModeChanged(false, Activity.STARTED)
        exit.onStop()
        exit.onStop()
        exit.confirmClose()
        assertEquals(1, closed)
    }

    @Test
    fun withoutPipOnStopDoesNothing() {
        exit.onResume()
        exit.onStop()
        exit.confirmClose()
        assertEquals(0, closed)
        assertEquals(0, pending)
    }

    @Test
    fun aRealCloseIsPendingOnceThenConfirmedExactlyOnce() {
        enterPip()
        exit.onPipModeChanged(false, Activity.STARTED)
        exit.onStop()
        assertEquals(1, pending)
        assertEquals(0, closed)
        exit.confirmClose()
        assertEquals(1, closed)
    }

    @Test
    fun confirmingTwiceClosesOnce() {
        enterPip()
        exit.onPipModeChanged(false, Activity.STARTED)
        exit.onStop()
        exit.confirmClose()
        exit.confirmClose()
        assertEquals(1, closed)
    }

    /** A device that stops and restarts the activity during an expand: the restart cancels the pending close. */
    @Test
    fun stopThenStartBeforeTheConfirmIsAnExpand() {
        enterPip()
        exit.onPipModeChanged(false, Activity.STARTED)
        exit.onStop()
        assertEquals("cancelled", true, exit.onStart())
        exit.confirmClose()
        assertEquals(0, closed)
    }

    @Test
    fun stopThenResumeBeforeTheConfirmIsAnExpand() {
        enterPip()
        exit.onPipModeChanged(false, Activity.STARTED)
        exit.onStop()
        assertEquals("cancelled", true, exit.onResume())
        exit.confirmClose()
        assertEquals(0, closed)
    }

    @Test
    fun stoppedFirstThenResumedIsAnExpand() {
        enterPip()
        exit.onStop()
        exit.onPipModeChanged(false, Activity.STOPPED)
        exit.onResume()
        exit.confirmClose()
        assertEquals(0, closed)
    }

    @Test
    fun startAndResumeReportNoCancelWhenNothingWasPending() {
        enterPip()
        exit.onPipModeChanged(false, Activity.STARTED)
        assertEquals(false, exit.onResume())
        assertEquals(false, exit.onStart())
    }

    @Test
    fun enteringPipAgainDropsAPendingClose() {
        enterPip()
        exit.onPipModeChanged(false, Activity.STARTED)
        exit.onStop()
        enterPip()
        exit.confirmClose()
        assertEquals(0, closed)
    }

    /** Expand, Home, expand: three rounds, in both callback orders and with a stop and restart on the expand. */
    @Test
    fun expandThenHomeThenExpandThreeTimesNeverCloses() {
        repeat(3) {
            // Mode change first, then resume; then Home with the page paused: an ordinary stop.
            enterPip()
            exit.onPipModeChanged(false, Activity.STARTED)
            exit.onResume()
            exit.onStop()
            exit.confirmClose()
            exit.onStart()
            // Resume first, then the mode change.
            enterPip()
            exit.onResume()
            exit.onPipModeChanged(false, Activity.RESUMED)
            exit.confirmClose()
            // A device that stops and restarts the activity on expand.
            enterPip()
            exit.onPipModeChanged(false, Activity.STARTED)
            exit.onStop()
            exit.onStart()
            exit.onResume()
            exit.confirmClose()
        }
        assertEquals(0, closed)
    }

    /** MainActivity's wiring: StartedActivities first, then PipWindowExit, closing with onPipWindowClosed. */
    private class Wiring {
        val events = mutableListOf<String>()
        val started = StartedActivities { events += if (it) "foreground" else "background" }
        val exit =
            PipWindowExit {
                onPipWindowClosed(started.noneStarted, pausePipMedia = { events += "pause pip media" }, sleepSelectedTab = { events += "sleep tab" })
            }

        fun stop() {
            started.onStop()
            exit.onStop()
        }

        fun start() {
            started.onStart()
            exit.onStart()
        }
    }

    @Test
    fun closingThePipWindowPausesItsMediaAndPutsTheTabToSleep() {
        val w = Wiring()
        w.started.onStart()
        w.exit.onPipModeChanged(true, Activity.STARTED)
        w.exit.onPipModeChanged(false, Activity.STARTED)
        w.stop()
        w.exit.confirmClose()
        assertEquals(listOf("foreground", "background", "pause pip media", "sleep tab"), w.events)
    }

    @Test
    fun aNewActivityStartedBeforeTheOldOneStopsKeepsItsTabAwake() {
        val w = Wiring()
        w.started.onStart() // A, in picture-in-picture
        w.exit.onPipModeChanged(true, Activity.STARTED)
        w.exit.onPipModeChanged(false, Activity.STARTED) // the window is closed
        w.started.onStart() // B: a link opened before A stopped
        w.stop() // A stops
        w.exit.confirmClose()
        assertEquals(listOf("foreground", "foreground", "pause pip media"), w.events)
    }

    /** noneStarted is read when the close is confirmed: B started while A's close was pending keeps its tab awake. */
    @Test
    fun noneStartedIsReadWhenTheCloseIsConfirmed() {
        val w = Wiring()
        w.started.onStart() // A, in picture-in-picture
        w.exit.onPipModeChanged(true, Activity.STARTED)
        w.exit.onPipModeChanged(false, Activity.STARTED) // the window is closed
        w.stop() // A stops, the last started instance: the close is pending
        w.started.onStart() // B, a new instance (its own PipWindowExit), starts before A's confirm
        w.exit.confirmClose()
        assertEquals(listOf("foreground", "background", "foreground", "pause pip media"), w.events)
    }

    /** Expand, then Home, then close: only the close pauses. */
    @Test
    fun expandThenHomeThenCloseRunsOnePause() {
        val w = Wiring()
        w.started.onStart()
        w.exit.onPipModeChanged(true, Activity.STARTED)
        w.exit.onPipModeChanged(false, Activity.STARTED) // expanded
        w.exit.onResume()
        w.exit.confirmClose()
        w.exit.onPipModeChanged(true, Activity.STARTED) // Home: PiP again
        w.exit.onPipModeChanged(false, Activity.STARTED) // closed
        w.stop()
        w.exit.confirmClose()
        assertEquals(listOf("foreground", "background", "pause pip media", "sleep tab"), w.events)
    }

    /** An expand on a device that stops and restarts the activity: no pause, and the tab comes back to the foreground. */
    @Test
    fun anExpandThatStopsAndRestartsTheActivityDoesNotPause() {
        val w = Wiring()
        w.started.onStart()
        w.exit.onPipModeChanged(true, Activity.STARTED)
        w.exit.onPipModeChanged(false, Activity.STARTED)
        w.stop()
        w.start()
        w.exit.confirmClose()
        assertEquals(listOf("foreground", "background", "foreground"), w.events)
    }
}
