package com.shibi.orbis.torrent

import com.shibi.orbis.torrent.PeerSearchRetry.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerSearchRetryTest {
    private val retry = PeerSearchRetry()

    private fun torrent(
        running: Boolean = true,
        peersKnown: Int = 0,
        bep27: Boolean = false,
        privateTab: Boolean = false,
        dhtRunning: Boolean = true,
        hasTrackers: Boolean = true,
    ) = PeerSearchRetry.Torrent(
        running = running,
        peersKnown = peersKnown,
        discovery = TorrentDiscovery.of(dht = true, pex = true, lsd = false, privateTab = privateTab, bep27 = bep27),
        dhtRunning = dhtRunning,
        hasTrackers = hasTrackers,
    )

    /** Polls once a second from [fromMs] to [toMs] (inclusive) and returns the ms (since [startMs]) at which calls were due. */
    private fun dueTimes(key: Long, startMs: Long, fromMs: Long, toMs: Long, t: PeerSearchRetry.Torrent = torrent()): List<Long> {
        val due = mutableListOf<Long>()
        var now = fromMs
        while (now <= toMs) {
            if (retry.poll(key, now, t).isNotEmpty()) due += now - startMs
            now += 1_000
        }
        return due
    }

    @Test
    fun withNoPeerKnownTheRetryIsDueAt3_8_20_45And90sThenEvery120sEachTimeOnce() {
        val start = 50_000L
        assertEquals(
            listOf(3_000L, 8_000L, 20_000L, 45_000L, 90_000L, 210_000L, 330_000L, 450_000L),
            dueTimes(key = 1, startMs = start, fromMs = start, toMs = start + 500_000),
        )
    }

    @Test
    fun theScheduleIsAPureFunctionOfTimeAndPeersKnown() {
        assertEquals(0, PeerSearchRetry.pointsPassed(2_999))
        assertEquals(1, PeerSearchRetry.pointsPassed(3_000))
        assertEquals(5, PeerSearchRetry.pointsPassed(90_000))
        assertEquals(5, PeerSearchRetry.pointsPassed(209_999))
        assertEquals(6, PeerSearchRetry.pointsPassed(210_000))
        assertTrue(PeerSearchRetry.isDue(3_000, peersKnown = 0, done = 0))
        assertFalse(PeerSearchRetry.isDue(3_000, peersKnown = 0, done = 1))
        assertFalse(PeerSearchRetry.isDue(3_000, peersKnown = 1, done = 0))
        assertFalse(PeerSearchRetry.isDue(1_000_000, peersKnown = 1, done = 0))
    }

    @Test
    fun pointsMissedBetweenTwoPollsMakeOneRetry() {
        assertTrue(retry.poll(1, 0, torrent()).isEmpty())
        assertEquals(setOf(Call.DHT, Call.TRACKERS), retry.poll(1, 50_000, torrent())) // 3, 8, 20 and 45 s passed
        assertTrue(retry.poll(1, 51_000, torrent()).isEmpty())
        assertEquals(setOf(Call.DHT, Call.TRACKERS), retry.poll(1, 90_000, torrent()))
    }

    @Test
    fun withOnePeerKnownItIsNeverDueAgainAndAtZeroTheScheduleStartsOver() {
        assertEquals(listOf(3_000L), dueTimes(key = 2, startMs = 0, fromMs = 0, toMs = 5_000))
        // A peer is known from 6 s on: nothing for a long time.
        assertEquals(emptyList<Long>(), dueTimes(key = 2, startMs = 0, fromMs = 6_000, toMs = 1_000_000, t = torrent(peersKnown = 1)))
        // Back to zero at 1001 s: 3 s, 8 s ... after that, not after the first start.
        assertEquals(
            listOf(3_000L, 8_000L, 20_000L),
            dueTimes(key = 2, startMs = 1_001_000, fromMs = 1_001_000, toMs = 1_030_000),
        )
    }

    @Test
    fun aTrackersOnlyTorrentGetsTheTrackerCallAndNeverTheDhtCall() {
        val t = torrent(bep27 = true)
        assertEquals(setOf(Call.TRACKERS), PeerSearchRetry.calls(t))
        assertFalse(PeerSearchRetry.askDhtNow(t))
        retry.poll(3, 0, t)
        assertEquals(setOf(Call.TRACKERS), retry.poll(3, 3_000, t))
        // With no tracker either, a retry asks nothing.
        assertEquals(emptySet<Call>(), PeerSearchRetry.calls(torrent(bep27 = true, privateTab = true, hasTrackers = false)))
    }

    @Test
    fun aPrivateTabTorrentWithNoTrackerGetsTheDhtCall() {
        val t = torrent(privateTab = true, hasTrackers = false)
        assertEquals(setOf(Call.DHT), PeerSearchRetry.calls(t))
        assertTrue(PeerSearchRetry.askDhtNow(t))
        retry.poll(5, 0, t)
        assertEquals(setOf(Call.DHT), retry.poll(5, 3_000, t))
    }

    @Test
    fun aPausedTorrentGetsNeither() {
        val paused = torrent(running = false)
        assertEquals(emptySet<Call>(), PeerSearchRetry.calls(paused))
        assertFalse(PeerSearchRetry.askDhtNow(paused))
        assertEquals(emptyList<Long>(), dueTimes(key = 4, startMs = 0, fromMs = 0, toMs = 600_000, t = paused))
        // Resumed at 600 s: its schedule starts there.
        assertEquals(listOf(3_000L), dueTimes(key = 4, startMs = 601_000, fromMs = 601_000, toMs = 605_000))
    }

    @Test
    fun theDhtCallNeedsTheDhtRunningAndATorrentWithTrackersStillAsksThem() {
        val noDht = torrent(dhtRunning = false)
        assertEquals(setOf(Call.TRACKERS), PeerSearchRetry.calls(noDht))
        assertFalse(PeerSearchRetry.askDhtNow(noDht))
        assertEquals(setOf(Call.DHT), PeerSearchRetry.calls(torrent(hasTrackers = false)))
        val dhtOff = PeerSearchRetry.Torrent(true, 0, TorrentDiscovery.of(dht = false, pex = true, lsd = false, privateTab = false, bep27 = false), true, true)
        assertEquals(setOf(Call.TRACKERS), PeerSearchRetry.calls(dhtOff))
    }

    @Test
    fun whenTheDhtComesUpEveryRunningTorrentThatMayUseItAndKnowsNoPeerAsksAtOnce() {
        assertTrue(PeerSearchRetry.askDhtNow(torrent()))
        assertFalse(PeerSearchRetry.askDhtNow(torrent(peersKnown = 3)))
        assertFalse(retry.dhtCameUp(false))
        assertTrue(retry.dhtCameUp(true))
        assertFalse(retry.dhtCameUp(true))
        assertFalse(retry.dhtCameUp(false))
        assertTrue(retry.dhtCameUp(true))
        retry.clear()
        assertTrue(retry.dhtCameUp(true))
    }

    @Test
    fun torrentsHaveTheirOwnSchedules() {
        retry.poll(1, 0, torrent())
        retry.poll(2, 2_000, torrent())
        assertEquals(setOf(Call.DHT, Call.TRACKERS), retry.poll(1, 3_000, torrent()))
        assertTrue(retry.poll(2, 3_000, torrent()).isEmpty())
        assertEquals(setOf(Call.DHT, Call.TRACKERS), retry.poll(2, 5_000, torrent()))
        retry.forget(1)
        assertTrue(retry.poll(1, 8_000, torrent()).isEmpty()) // starts over at 8 s
        assertEquals(setOf(Call.DHT, Call.TRACKERS), retry.poll(1, 11_000, torrent()))
    }
}
