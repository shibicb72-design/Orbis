package com.shibi.orbis.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The coordinator's phase rules: connect timeout, a page change during Connecting, and an orphaned start. */
class CastFlowTest {
    @Test
    fun connectTimesOutAndALateStartIsAnOrphan() {
        val flow = CastFlow()
        flow.showPicker(youtube = false, searching = false, googleUnavailable = false)
        val generation = flow.beginConnect(0)
        assertFalse(flow.connectExpired(generation, CONNECT_TIMEOUT_MS - 1))
        assertTrue(flow.connectExpired(generation, CONNECT_TIMEOUT_MS))
        flow.failConnect(generation, youtube = false)
        assertEquals(CastPhase.Picker(youtube = false, searching = false), flow.phase)
        assertEquals(CastNotice.FAILED, flow.notice)
        assertFalse(flow.acceptStart(generation))
    }

    @Test
    fun pageChangeDuringConnectIsIgnoredSoTheStartStillBelongs() {
        val flow = CastFlow()
        flow.showPicker(youtube = true, searching = true, googleUnavailable = false)
        val generation = flow.beginConnect(0)
        assertFalse(flow.present(hasPlayback = false))
        assertEquals(CastPhase.Connecting, flow.phase)
        assertTrue(flow.acceptStart(generation))
        assertEquals(CastPhase.Controls, flow.phase)
    }

    @Test
    fun closingThePrivateTabDropsTheTargetAndStopsALiveCast() {
        val flow = CastFlow()
        flow.target = CastTarget.Direct(media())
        flow.rememberOrigin(tabId = 4, isPrivate = true)
        flow.beginConnect(0)
        assertEquals(true, flow.privateTabClosed(4))
        assertNull(flow.target)
        assertNull(flow.privateOrigin)
        assertNull(flow.privateTabClosed(4))
    }

    @Test
    fun closingThePrivateTabClearsAPickerThatNeverConnected() {
        val flow = CastFlow()
        flow.target = CastTarget.YouTube(YouTubeVideo("dQw4w9WgXcQ", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", 0.0, "Song"))
        flow.rememberOrigin(tabId = 9, isPrivate = true)
        flow.showPicker(youtube = true, searching = false, googleUnavailable = false)
        assertEquals(false, flow.privateTabClosed(9))
        assertNull(flow.target)
        assertNull(flow.privateOrigin)
    }

    @Test
    fun aNormalTabIsNotClearedWhenSomeOtherPrivateTabCloses() {
        val flow = CastFlow()
        flow.target = CastTarget.Direct(media())
        flow.rememberOrigin(tabId = 2, isPrivate = false)
        assertNull(flow.privateTabClosed(2))
        assertTrue(flow.target is CastTarget.Direct)
    }

    @Test
    fun probeTimesOutOnceAndALateReplyIsIgnored() {
        val gate = ProbeGate(timeoutMs = PROBE_TIMEOUT_MS)
        val generation = gate.start(0)
        assertFalse(gate.timedOut(generation, PROBE_TIMEOUT_MS - 1))
        assertTrue(gate.timedOut(generation, PROBE_TIMEOUT_MS))
        assertTrue(gate.take(generation))
        assertFalse(gate.take(generation))
        assertFalse(gate.timedOut(generation, PROBE_TIMEOUT_MS + 1))
    }

    @Test
    fun aNewProbeInvalidatesThePreviousWait() {
        val gate = ProbeGate()
        val first = gate.start(0)
        val second = gate.start(10)
        assertFalse(gate.take(first))
        assertFalse(gate.timedOut(first, 10 + PROBE_TIMEOUT_MS))
        assertTrue(gate.timedOut(second, 10 + PROBE_TIMEOUT_MS))
        assertTrue(gate.take(second))
    }

    private fun media() =
        CastableMedia(
            url = "https://cdn.example/a.mp4",
            mime = "video/mp4",
            video = true,
            title = "Show",
            poster = null,
            positionSec = 0.0,
            durationSec = 1.0,
        )
}
