package com.shibi.orbis.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "live stream dropped" flag in the session state: what sets it and everything that clears it. */
class LiveStreamDroppedStateTest {
    private val page = SessionState(url = "https://live.example/watch", loadStarts = 4)
    private val dropped = page.afterStreamSignal(StreamSignal.DROPPED, pageAtConnect = 4)

    @Test
    fun `dropped sets the flag, and nothing else`() {
        assertFalse(page.liveStreamDropped)
        assertTrue(dropped.liveStreamDropped)
        assertEquals(page, dropped.copy(liveStreamDropped = false))
        assertTrue(dropped.afterStreamSignal(StreamSignal.DROPPED, 4).liveStreamDropped)
    }

    @Test
    fun `playing clears it`() {
        assertFalse(dropped.afterStreamSignal(StreamSignal.PLAYING, 4).liveStreamDropped)
        assertFalse(page.afterStreamSignal(StreamSignal.PLAYING, 4).liveStreamDropped)
    }

    @Test
    fun `a navigation or a reload (a page start) clears it`() {
        assertFalse(dropped.afterPageStart("https://live.example/watch").liveStreamDropped)
        assertFalse(dropped.afterPageStart("https://other.example/").liveStreamDropped)
    }

    @Test
    fun `a navigation inside the page (fragment, pushState) clears it`() {
        assertFalse(dropped.afterLocationChange("https://live.example/watch#chat").liveStreamDropped)
        assertFalse(dropped.afterLocationChange("https://live.example/other").liveStreamDropped)
    }

    @Test
    fun `a lost page clears it`() {
        assertFalse(dropped.afterContentProcessGone(showBanner = true).liveStreamDropped)
        assertFalse(dropped.afterContentProcessGone(showBanner = false).liveStreamDropped)
    }

    @Test
    fun `a message whose port connected on the page before the last page start changes nothing`() {
        val next = dropped.afterPageStart("https://other.example/")
        assertEquals(next, next.afterStreamSignal(StreamSignal.DROPPED, pageAtConnect = 4))
        assertTrue(next.afterStreamSignal(StreamSignal.DROPPED, pageAtConnect = 5).liveStreamDropped)
        val gone = page.afterContentProcessGone()
        assertEquals(gone, gone.afterStreamSignal(StreamSignal.DROPPED, gone.loadStarts))
    }

    @Test
    fun `nothing else in the session state moves the flag`() {
        assertTrue(dropped.afterPageStop(true).liveStreamDropped)
        assertTrue(dropped.afterTitleChange("Live").liveStreamDropped)
        assertTrue(dropped.afterSecurityChange(true, false, false).liveStreamDropped)
    }

    @Test
    fun `an unknown message or another extension's message reaches no state change`() {
        // The session applies only what parseStallMessage returns; for these it returns null and the state is untouched.
        val unknown = parseStallMessage(StallExtension.ID, JSONObject().put("type", "stop"))
        val foreign = parseStallMessage(CompatExtensionHolder.ID, JSONObject().put("type", "dropped"))
        assertEquals(null, unknown)
        assertEquals(null, foreign)
        val state = listOfNotNull(unknown, foreign).fold(page) { s, sig -> s.afterStreamSignal(sig, 4) }
        assertEquals(page, state)
    }

    @Test
    fun `the flag is off in a new session`() {
        assertFalse(SessionState().liveStreamDropped)
    }
}
