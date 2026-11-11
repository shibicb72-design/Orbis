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
        flow.showPicker(youtube = false, searching = false)
        val generation = flow.beginConnect(0)
        assertFalse(flow.connectExpired(generation, CONNECT_TIMEOUT_MS - 1))
        assertTrue(flow.connectExpired(generation, CONNECT_TIMEOUT_MS))
        flow.failConnect(generation, youtube = false)
        assertEquals(CastPhase.Picker(youtube = false, searching = false), flow.phase)
        assertEquals(CastNotice.FAILED, flow.notice)
        assertFalse(flow.acceptStart(generation))
    }

    private fun ticket(): CastPins.Ticket {
        val store =
            object : CastPinStore {
                override fun get(key: String): String? = null
                override fun put(key: String, value: String) = Unit
                override fun remove(key: String) = Unit
                override fun all(): Map<String, String> = emptyMap()
            }
        return (CastPins(store).check("tv-1", "Living Room", byteArrayOf(1, 2, 3), 0) as CastPins.Verdict.NeedsConfirm).ticket
    }

    @Test
    fun aTrustQuestionLeavesConnectingAndTheConnectTimeoutNoLongerFires() {
        val flow = CastFlow()
        flow.showPicker(youtube = false, searching = false)
        val generation = flow.beginConnect(0)
        val pending = PendingTrust("tv-1", "Living Room", CastPins.Reason.KEY_CHANGED, ticket())
        assertTrue(flow.needsTrust(generation, youtube = false, pending))
        assertEquals(CastPhase.Picker(youtube = false, searching = false), flow.phase)
        assertEquals(CastNotice.CONFIRM_DEVICE, flow.notice)
        assertEquals(pending, flow.trust)
        // The prompt may stay up past the 15 s connect deadline; the deadline belongs to the attempt that ended.
        assertFalse(flow.connectExpired(generation, CONNECT_TIMEOUT_MS * 10))
        assertFalse(flow.acceptStart(generation))
        // A late question for an attempt that is no longer live is dropped.
        assertFalse(flow.needsTrust(generation, youtube = false, pending))
    }

    @Test
    fun confirmingTakesTheQuestionOnceSoTheReconnectHappensOnce() {
        val flow = CastFlow()
        flow.showPicker(youtube = false, searching = false)
        val pending = PendingTrust("tv-1", "Living Room", CastPins.Reason.FIRST_USE, ticket())
        flow.needsTrust(flow.beginConnect(0), youtube = false, pending)
        assertEquals(pending, flow.takeTrust())
        assertNull(flow.notice)
        assertNull(flow.trust)
        assertNull("a second tap does not reconnect again", flow.takeTrust())
        // The reconnect asks again (the device proved another key): a new question, not an approval.
        val again = PendingTrust("tv-1", "Living Room", CastPins.Reason.KEY_CHANGED, ticket())
        assertTrue(flow.needsTrust(flow.beginConnect(1), youtube = false, again))
        assertEquals(CastPins.Reason.KEY_CHANGED, flow.trust?.reason)
    }

    @Test
    fun closingTheSheetOrStartingAnotherConnectDropsTheQuestion() {
        val flow = CastFlow()
        flow.showPicker(youtube = false, searching = false)
        flow.needsTrust(flow.beginConnect(0), youtube = false, PendingTrust("tv-1", "TV", CastPins.Reason.FIRST_USE, ticket()))
        flow.dismissSheet(hasPlayback = false)
        assertNull(flow.trust)
        flow.showPicker(youtube = false, searching = false)
        flow.needsTrust(flow.beginConnect(0), youtube = false, PendingTrust("tv-1", "TV", CastPins.Reason.FIRST_USE, ticket()))
        flow.beginConnect(1)
        assertNull(flow.trust)
    }

    @Test
    fun pageChangeDuringConnectIsIgnoredSoTheStartStillBelongs() {
        val flow = CastFlow()
        flow.showPicker(youtube = true, searching = true)
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
        flow.showPicker(youtube = true, searching = false)
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
