package com.shibi.orbis.diagnostics

import com.shibi.orbis.engine.DiagTabEvent
import com.shibi.orbis.stats.BlockKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDiagnosticsTest {
    private val day = 24L * 60 * 60 * 1000
    private val frame = Any()

    private fun recordingHarness(): DiagHarness = DiagHarness().apply {
        diag.start()
        diag.setRecording(true)
    }

    private fun entries(h: DiagHarness): List<String> = h.log.lines().filterNot { it.startsWith("# ") }

    // ---- off by default, nothing while off ----

    @Test
    fun offByDefaultNothingIsWrittenAndTheScriptIsNotAnswered() {
        val h = DiagHarness()
        h.diag.start()
        val tab = FakeTab()
        assertFalse(h.diag.switches.value.recording)
        assertFalse(h.diag.mayRecord(tab))
        h.diag.onPageMessage(tab, frame, media(1))
        h.diag.onPageMessage(tab, frame, event(1, "playing"))
        h.diag.onTabEvent(tab, DiagTabEvent.FULL_SCREEN_ON)
        h.diag.onForeground(false)
        h.diag.onPictureInPicture(true)
        assertTrue(h.log.isEmpty)
        assertNull(h.file.text)
        // The engine is told "off" at start.
        assertEquals(listOf(Triple(false, false, false)), h.engineCalls)
    }

    @Test
    fun switchingOnWritesTheHeaderAndEnablesTheScript() {
        val h = recordingHarness()
        val header = h.log.lines().takeWhile { it.startsWith("# ") }
        assertTrue(header.contains("# Orbis video diagnostics log"))
        assertTrue(header.contains("# Tracking protection: strict"))
        assertTrue(header.contains("# uBlock Origin on or off for a site: not known to Orbis"))
        assertTrue(header.contains("# Include site names: off. Also record private tabs: off."))
        assertTrue(entries(h).any { it.endsWith("Recording on.") })
        assertEquals(Triple(true, false, true), h.engineCalls.last())
        assertEquals(h.text() + "\n", h.file.text)
    }

    @Test
    fun switchingOffStoresOffBeforeTheScriptIsTakenOutAndKeepsTheLog() {
        val h = recordingHarness()
        h.diag.setPrivateTabs(true)
        val connection = h.network.listener!!
        h.diag.setRecording(false)
        // (recording, private, stored recording) at the engine call: "off" was already on disk.
        assertEquals(Triple(false, false, false), h.engineCalls.last())
        assertFalse(h.prefs.privateTabs)
        assertTrue(entries(h).last().endsWith("Recording off."))
        assertFalse(h.log.isEmpty)
        val before = h.text()
        h.diag.onPageMessage(FakeTab(), frame, media(1))
        // A network callback already queued when recording went off.
        connection(ConnectionKind.WIFI)
        h.diag.onForeground(false)
        assertEquals(before, h.text())
        assertNull(h.network.listener)
    }

    // ---- privacy ----

    @Test
    fun noAddressPathQueryOrTitleReachesTheLog() {
        val h = recordingHarness()
        val tab = FakeTab()
        h.diag.onPageMessage(tab, frame, media(1, source = "hls", same = false))
        h.diag.onPageMessage(tab, frame, event(1, "playing"))
        // Messages that carry an address or a title are not the expected shape: dropped whole.
        h.diag.onPageMessage(tab, frame, event(1, "waiting").put("url", "https://cdn.example.net/live/stream.m3u8?token=abc"))
        h.diag.onPageMessage(tab, frame, media(2).put("title", "Secret Title"))
        h.diag.onPageMessage(tab, frame, JSONObject().put("type", "page").put("what", "Secret Title"))
        h.diag.onTabEvent(tab, DiagTabEvent.FULL_SCREEN_ON)
        h.diag.onPageMessage(tab, frame, sample(1))
        val text = h.text()
        for (forbidden in listOf("://", "example", "secret-path", "private-query", "frag", "Secret Title", "token", "stream.m3u8", "cdn")) {
            assertFalse("found $forbidden in:\n$text", text.contains(forbidden))
        }
        assertTrue(text.contains("site 1 tab 1: video 1 appeared: playlist on the element (.m3u8), other site"))
        assertTrue(text.contains("site 1 tab 1: full screen on"))
        assertFalse(text.contains("media 2"))
    }

    @Test
    fun siteNamesAreTheHostOnlyAndOnlyForEntriesAfterTheSwitch() {
        val h = recordingHarness()
        val tab = FakeTab()
        h.diag.onPageMessage(tab, frame, media(1))
        val before = entries(h)
        h.diag.setIncludeSiteNames(true)
        h.diag.onPageMessage(tab, frame, event(1, "playing"))
        val text = h.text()
        assertTrue(text.contains("site 1 (www.example.com) tab 1: video 1 playing"))
        for (forbidden in listOf("://", "secret-path", "private-query", "frag", "/watch")) assertFalse(forbidden, text.contains(forbidden))
        // Entries written before the switch are unchanged and have no host.
        assertEquals(before, entries(h).take(before.size))
        assertTrue(before.none { it.contains("example") })
        assertTrue(entries(h).contains(entries(h).first { it.endsWith("Include site names: on") }))
    }

    @Test
    fun sitesAreNumberedInOrderOfAppearance() {
        val h = recordingHarness()
        val a = FakeTab("https://a.example/one")
        val b = FakeTab("https://b.example/two")
        h.diag.onPageMessage(a, frame, media(1))
        h.diag.onPageMessage(b, Any(), media(1))
        a.url = "https://b.example/three"
        h.diag.onPageMessage(a, Any(), media(1))
        val text = h.text()
        assertTrue(text.contains("site 1 tab 1: video 1 appeared"))
        assertTrue(text.contains("site 2 tab 2: video 1 appeared"))
        assertTrue(text.contains("site 2 tab 1: video 2 appeared"))
    }

    @Test
    fun privateTabsAreLeftOutUnlessTheirSwitchIsOnAndItGoesOffWithRecording() {
        val h = recordingHarness()
        val priv = FakeTab(isPrivate = true)
        assertFalse(h.diag.mayRecord(priv))
        h.diag.onPageMessage(priv, frame, media(1))
        h.diag.onTabEvent(priv, DiagTabEvent.PUT_TO_SLEEP)
        assertFalse(h.text().contains("tab 1"))
        h.diag.setPrivateTabs(true)
        assertEquals(Triple(true, true, true), h.engineCalls.last())
        assertTrue(h.diag.mayRecord(priv))
        h.diag.onPageMessage(priv, frame, media(1))
        assertTrue(h.text().contains("site 1 tab 1 (private): video 1 appeared"))
        h.diag.setRecording(false)
        h.diag.setRecording(true)
        assertFalse(h.diag.switches.value.privateTabs)
        assertFalse(h.prefs.privateTabs)
        assertFalse(h.diag.mayRecord(priv))
        assertEquals(Triple(true, false, true), h.engineCalls.last())
    }

    @Test
    fun theExtraSwitchesDoNothingWhileRecordingIsOff() {
        val h = DiagHarness()
        h.diag.start()
        h.diag.setIncludeSiteNames(true)
        h.diag.setPrivateTabs(true)
        assertEquals(VideoDiagSwitches(), h.diag.switches.value)
        assertTrue(h.log.isEmpty)
    }

    // ---- 7 days ----

    @Test
    fun recordingTurnsItselfOffSevenDaysAfterItWasSwitchedOn() {
        val h = recordingHarness()
        h.advance(7 * day - 1)
        assertEquals(1L, h.diag.checkAge())
        assertTrue(h.diag.switches.value.recording)
        h.advance(1)
        h.diag.checkAge()
        assertFalse(h.diag.switches.value.recording)
        assertFalse(h.prefs.recording)
        assertTrue(entries(h).last().endsWith("Recording turned off after 7 days."))
        assertEquals(Triple(false, false, false), h.engineCalls.last())
    }

    @Test
    fun theLogIsDeletedSevenDaysAfterItsLastEntry() {
        val h = recordingHarness()
        h.diag.setRecording(false)
        h.advance(7 * day - 1)
        h.diag.checkAge()
        assertFalse(h.log.isEmpty)
        h.advance(1)
        assertNull(h.diag.checkAge())
        assertTrue(h.log.isEmpty)
        assertNull(h.file.text)
    }

    @Test
    fun atStartAnOldLogIsDeletedBeforeAnythingReadsIt() {
        val h = recordingHarness()
        h.diag.setRecording(false)
        val text = h.file.text
        // A new process eight days later.
        val later = DiagHarness()
        later.file.text = text
        later.prefs.lastEntryAt = h.prefs.lastEntryAt
        later.clock.millis = h.clock.millis + 8 * day
        later.diag.start()
        assertTrue(later.log.isEmpty)
        assertNull(later.file.text)
    }

    @Test
    fun atStartRecordingThatRanOutWhileOrbisWasClosedIsSwitchedOff() {
        val h = recordingHarness()
        val later = DiagHarness()
        later.file.text = h.file.text
        later.prefs.setRecording(true, h.prefs.recordingSince)
        later.prefs.lastEntryAt = h.prefs.lastEntryAt
        later.clock.millis = h.clock.millis + 7 * day + 1
        later.diag.start()
        assertFalse(later.diag.switches.value.recording)
        assertTrue(later.engineCalls.isNotEmpty())
        assertTrue(later.engineCalls.all { it == Triple(false, false, false) })
        assertTrue(later.log.lines().last().endsWith("Recording turned off after 7 days."))
    }

    @Test
    fun aNewProcessWhileRecordingSaysTheNumbersStartAgain() {
        val h = recordingHarness()
        val later = DiagHarness()
        later.file.text = h.file.text
        later.prefs.setRecording(true, h.prefs.recordingSince)
        later.prefs.lastEntryAt = h.prefs.lastEntryAt
        later.diag.start()
        assertTrue(later.log.lines().last().endsWith("Orbis started; site and tab numbers start again."))
        assertEquals(Triple(true, false, true), later.engineCalls.single())
    }

    // ---- delete ----

    @Test
    fun deleteLogEmptiesItAndWritesANewHeaderOnlyWhileRecording() {
        val h = recordingHarness()
        h.diag.onPageMessage(FakeTab(), frame, media(1))
        h.diag.deleteLog()
        assertTrue(h.log.lines().all { it.startsWith("# ") })
        assertFalse(h.log.isEmpty)
        h.diag.setRecording(false)
        h.diag.deleteLog()
        assertTrue(h.log.isEmpty)
        assertNull(h.file.text)
    }

    // ---- lines ----

    @Test
    fun everyMediaEventIsOneLine() {
        val h = recordingHarness()
        val tab = FakeTab()
        h.diag.onPageMessage(tab, frame, media(1))
        for (e in MediaEvent.entries) {
            val before = h.log.lines().size
            h.diag.onPageMessage(tab, frame, event(1, e.wire))
            val added = h.log.lines().drop(before)
            // A stall summary may follow; the event itself is exactly one line.
            assertTrue(e.wire, added.isNotEmpty() && added.first().contains("video 1 "))
        }
        val text = h.text()
        assertTrue(text.contains("video 1 playing at 1.5 s: ready 4, network 2, position 0.0, buffered ahead 4.3 s, duration finite"))
        assertTrue(text.contains("video 1 error code 2 at 1.5 s"))
        assertTrue(text.contains("video 1 resize to 1280x720 at 1.5 s"))
        assertTrue(text.contains("video 1 found already on the page at 1.5 s"))
    }

    @Test
    fun aStallEndsWithOneSummaryLine() {
        val h = recordingHarness()
        val tab = FakeTab()
        h.diag.onPageMessage(tab, frame, media(1))
        h.diag.onPageMessage(tab, frame, event(1, "waiting"))
        h.advance(4_200)
        h.diag.onPageMessage(tab, frame, event(1, "waiting"))
        h.advance(100)
        h.diag.onPageMessage(tab, frame, event(1, "playing"))
        val summaries = entries(h).filter { it.contains("stall ended") }
        assertEquals(1, summaries.size)
        assertTrue(summaries.single().endsWith("video 1 stall ended after 4.3 s (playing)"))
    }

    @Test
    fun aRestartOfTheSameSourceIsLabelled() {
        val h = recordingHarness()
        val tab = FakeTab()
        h.diag.onPageMessage(tab, frame, media(1))
        h.diag.onPageMessage(tab, frame, event(1, "emptied"))
        h.diag.onPageMessage(tab, frame, event(1, "loadstart").put("again", true))
        assertTrue(entries(h).last().contains("video 1 loadstart (reloaded, same source) at"))
    }

    @Test
    fun aSampleRendersAsOneLineAndBlockingCountsFollowWhenTheyChange() {
        val h = recordingHarness()
        val tab = FakeTab()
        h.diag.onPageMessage(tab, frame, media(1))
        h.diag.onPageMessage(tab, frame, media(2, kind = "audio"))
        h.diag.onPageMessage(tab, frame, sample(1))
        assertTrue(entries(h).last().endsWith("video 1 sample: buffered ahead 8.0 s, picture 1280x720, frames 1520, dropped 3"))
        tab.blocked = mapOf(BlockKind.TRACKER to 12, BlockKind.AD to 3)
        tab.badge = "45"
        h.diag.onPageMessage(tab, frame, sample(1))
        assertTrue(entries(h).last().endsWith("blocked on this page: ad 3, tracker 12; uBlock Origin badge 45"))
        val count = entries(h).size
        h.diag.onPageMessage(tab, frame, sample(2, 0, 0))
        // Unchanged counts are not written again.
        assertEquals(count + 1, entries(h).size)
        assertTrue(entries(h).last().endsWith("audio 2 sample: buffered ahead 8.0 s"))
    }

    @Test
    fun theUboBadgeIsWrittenOnlyWhenItIsOneToSixDigits() {
        assertEquals("uBlock Origin badge 7", formatBlocking(emptyMap(), "7"))
        assertEquals("uBlock Origin badge 999999", formatBlocking(emptyMap(), "999999"))
        assertNull(formatBlocking(emptyMap(), "1234567"))
        assertNull(formatBlocking(emptyMap(), "1k"))
        assertNull(formatBlocking(emptyMap(), ""))
        assertNull(formatBlocking(emptyMap(), null))
    }

    @Test
    fun tabAndAppEventsAreLines() {
        val h = recordingHarness()
        val tab = FakeTab()
        // A tab the log does not know yet: nothing.
        h.diag.onTabEvent(tab, DiagTabEvent.NOT_IN_FRONT)
        assertFalse(h.text().contains("tab not in front"))
        h.diag.onPageMessage(tab, frame, media(1))
        assertTrue(h.text().contains("site 1 tab 1: new page: tab in front, awake, full screen off, shield on"))
        for (e in DiagTabEvent.entries) h.diag.onTabEvent(tab, e)
        h.diag.onForeground(true)
        h.diag.onForeground(false)
        h.diag.onPictureInPicture(true)
        h.network.listener!!(ConnectionKind.MOBILE)
        h.network.listener!!(ConnectionKind.MOBILE)
        h.network.listener!!(ConnectionKind.NONE)
        val text = h.text()
        for (words in listOf("tab in front", "tab not in front", "tab woken", "tab put to sleep", "full screen on", "full screen off", "shield on", "shield off")) {
            assertTrue(words, text.contains("site 1 tab 1: $words"))
        }
        assertTrue(text.contains("Orbis in front"))
        assertTrue(text.contains("Orbis in the background"))
        assertTrue(text.contains("picture-in-picture on"))
        assertEquals(1, entries(h).count { it.endsWith("connection: mobile data") })
        assertTrue(text.contains("connection: none"))
    }

    // ---- the page is untrusted ----

    @Test
    fun atMost120LinesAMinuteReachTheLogPerPageThenOneLineSaysSo() {
        val h = recordingHarness()
        val tab = FakeTab()
        val start = entries(h).size
        h.diag.onPageMessage(tab, frame, media(1))
        repeat(500) { h.diag.onPageMessage(tab, frame, event(1, "seeking")) }
        val added = entries(h).drop(start)
        assertEquals(121, added.size)
        assertTrue(added.last().endsWith("site 1 tab 1: … more events not recorded"))
        h.advance(60_000)
        h.diag.onPageMessage(tab, frame, event(1, "playing"))
        assertTrue(entries(h).last().contains("video 1 playing"))
        // Another tab has its own limit.
        h.diag.onPageMessage(FakeTab("https://other.example/"), Any(), media(1))
        assertTrue(entries(h).last().contains("site 2 tab 2: video 1 appeared"))
    }

    @Test
    fun aSampleSummary() {
        // One real sample of the log, made-up values (printed for the PR report).
        val h = recordingHarness()
        val tab = FakeTab()
        h.diag.onForeground(true)
        h.network.listener!!(ConnectionKind.WIFI)
        h.diag.onPageMessage(tab, frame, JSONObject().put("type", "hls").put("mpegurl", "maybe").put("xmpegurl", ""))
        h.diag.onPageMessage(tab, frame, media(1))
        h.diag.onPageMessage(tab, frame, event(1, "loadstart", t = 0.0, ahead = 0.0))
        h.advance(900)
        h.diag.onPageMessage(tab, frame, event(1, "loadedmetadata", t = 0.9, ahead = 0.0))
        h.diag.onPageMessage(tab, frame, event(1, "resize", t = 0.9, ahead = 0.0))
        h.advance(1_300)
        h.diag.onPageMessage(tab, frame, event(1, "playing", t = 2.2, ahead = 6.0))
        h.advance(10_000)
        tab.blocked = mapOf(BlockKind.TRACKER to 4)
        tab.badge = "17"
        h.diag.onPageMessage(tab, frame, sample(1))
        h.advance(3_000)
        h.diag.onPageMessage(tab, frame, event(1, "waiting", t = 15.2, pos = 13.0, ahead = 0.0))
        h.advance(4_200)
        h.diag.onPageMessage(tab, frame, event(1, "playing", t = 19.4, pos = 13.0, ahead = 3.1))
        h.network.listener!!(ConnectionKind.MOBILE)
        h.diag.onTabEvent(tab, DiagTabEvent.FULL_SCREEN_ON)
        println("VIDEO-DIAG-SAMPLE-BEGIN\n" + h.text() + "\nVIDEO-DIAG-SAMPLE-END")
        assertNotNull(h.file.text)
    }
}
