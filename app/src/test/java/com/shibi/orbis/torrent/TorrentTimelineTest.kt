package com.shibi.orbis.torrent

import com.shibi.orbis.torrent.TorrentTimeline.ConnectFailure
import com.shibi.orbis.torrent.TorrentTimeline.PeerSource
import com.shibi.orbis.torrent.TorrentTimeline.Step
import com.shibi.orbis.torrent.TorrentTimeline.TrackerFailure
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentTimelineTest {
    private val lines = mutableListOf<String>()
    private val timeline = TorrentTimeline { lines += it }

    @Test
    fun eachMilestoneIsRecordedOnceAtItsFirstEventLaterEventsOnlyCount() {
        timeline.added(7, at = 1_000, sessionStartedAt = 1_040)
        timeline.trackerAnswered(7, peers = 0, at = 1_300)
        timeline.trackerAnswered(7, peers = 12, at = 1_500)
        timeline.trackerAnswered(7, peers = 30, at = 1_900)
        timeline.peersFound(7, PeerSource.DHT, peers = 5, at = 2_000)
        timeline.connectAttempt(7, at = 2_100)
        timeline.sample(7, at = 2_500, connected = 1, connecting = 0, pexConnected = 0, known = 40)
        timeline.sample(7, at = 3_500, connected = 3, connecting = 0, pexConnected = 0, known = 40)
        timeline.dhtBootstrapped(4_000)
        timeline.dhtBootstrapped(4_200)
        timeline.milestone(7, Step.DETAILS, 4_500)
        timeline.milestone(7, Step.DETAILS, 4_600)
        timeline.milestone(7, Step.RESUMED, 5_000)
        timeline.milestone(7, Step.RESUMED, 9_000)
        timeline.milestone(7, Step.FIRST_BYTE, 6_000)
        timeline.milestone(7, Step.FIRST_BYTE, 7_000)

        assertEquals(
            listOf(
                "timeline 7 t=40 session_started",
                "timeline 7 t=300 tracker_answer",
                "timeline 7 t=500 peer_found",
                "timeline 7 t=1500 peer_connected",
                "timeline 7 t=3000 dht_ready",
                "timeline 7 t=3500 details",
                "timeline 7 t=4000 resumed",
                "timeline 7 t=5000 first_byte",
            ),
            lines,
        )
    }

    @Test
    fun aTorrentAddedAfterTheBootstrapIsDhtReadyAtOnceAndOnlyKnownIdsCount() {
        timeline.dhtBootstrapped(500)
        timeline.added(2, at = 1_000, sessionStartedAt = null)
        timeline.milestone(99, Step.DETAILS, 1_100)
        timeline.trackerAsked(99)
        timeline.added(2, at = 5_000, sessionStartedAt = null)
        assertEquals(listOf("timeline 2 t=0 session_started", "timeline 2 t=0 dht_ready"), lines)
    }

    @Test
    fun theSummaryForAGivenSetOfEventsIsExact() {
        timeline.added(3, at = 10_000, sessionStartedAt = 10_020)
        timeline.dhtNodes(v6 = false, nodes = 8)
        timeline.tick(15_000)
        timeline.dhtNodes(v6 = false, nodes = 40)
        timeline.tick(25_000)
        timeline.dhtBootstrapped(12_000)
        timeline.trackerAsked(3)
        timeline.trackerAsked(3)
        timeline.trackerAsked(3)
        timeline.trackerFailed(3, TrackerFailure.TIMEOUT)
        timeline.trackerFailed(3, TrackerFailure.REFUSED)
        timeline.trackerAnswered(3, peers = 50, at = 13_000)
        timeline.peersFound(3, PeerSource.DHT, peers = 7, at = 14_000)
        timeline.peersFound(3, PeerSource.LOCAL, peers = 1, at = 14_100)
        repeat(10) { timeline.connectAttempt(3, at = 13_100) }
        timeline.connectFailed(3, ConnectFailure.TIMEOUT)
        timeline.connectFailed(3, ConnectFailure.TIMEOUT)
        timeline.connectFailed(3, ConnectFailure.REFUSED)
        timeline.connectFailed(3, ConnectFailure.NO_ROUTE)
        timeline.connectFailed(3, ConnectFailure.OTHER)
        timeline.disconnected(3)
        timeline.incomingConnection(3, at = 20_000)
        timeline.sample(3, at = 14_500, connected = 4, connecting = 1, pexConnected = 2, known = 61)
        timeline.dhtNodes(v6 = false, nodes = 100)
        timeline.dhtNodes(v6 = true, nodes = 20) // one DHT node per family: the checkpoint takes the sum
        timeline.tick(41_000)
        lines.clear()
        timeline.tick(70_000)

        assertEquals(
            listOf(
                "timeline 3 t=60000 at_60s new_session=1 session_started=20 dht_ready=2000 tracker_answer=3000 peer_found=3000 " +
                    "peer_connected=4500 tracker_asks=3 tracker_answers=1 tracker_timeout=1 tracker_refused=1 tracker_other=0 " +
                    "found_tracker=50 found_dht=7 found_local=1 attempts=10 connected=4 fail_timeout=2 fail_refused=1 " +
                    "fail_no_route=1 fail_other=1 dropped=1 incoming=1 forced_dht=0 forced_trackers=0 " +
                    "peers_now=4 connecting_now=1 pex_now=2 known=61 " +
                    "dht_nodes_5s=8 dht_nodes_15s=40 dht_nodes_30s=120 dht_nodes_60s=120",
            ),
            lines,
        )
        // Each summary once.
        lines.clear()
        timeline.tick(80_000)
        assertEquals(emptyList<String>(), lines)
    }

    @Test
    fun summariesAt300sFinishAndRemoveComeOnceEach() {
        timeline.added(4, at = 0, sessionStartedAt = null)
        timeline.finished(4, at = 2_000)
        timeline.finished(4, at = 3_000)
        timeline.tick(300_000)
        timeline.removed(4, at = 301_000)
        timeline.removed(4, at = 302_000)
        timeline.tick(400_000)
        assertEquals(
            listOf(
                "timeline 4 t=0 session_started",
                "timeline 4 t=2000 finished new_session=0 session_started=0 tracker_asks=0 tracker_answers=0 tracker_timeout=0 " +
                    "tracker_refused=0 tracker_other=0 found_tracker=0 found_dht=0 found_local=0 attempts=0 fail_timeout=0 " +
                    "fail_refused=0 fail_no_route=0 fail_other=0 dropped=0 incoming=0 forced_dht=0 forced_trackers=0",
                "timeline 4 t=300000 at_60s new_session=0 session_started=0 tracker_asks=0 tracker_answers=0 tracker_timeout=0 " +
                    "tracker_refused=0 tracker_other=0 found_tracker=0 found_dht=0 found_local=0 attempts=0 fail_timeout=0 " +
                    "fail_refused=0 fail_no_route=0 fail_other=0 dropped=0 incoming=0 forced_dht=0 forced_trackers=0",
                "timeline 4 t=300000 at_300s new_session=0 session_started=0 tracker_asks=0 tracker_answers=0 tracker_timeout=0 " +
                    "tracker_refused=0 tracker_other=0 found_tracker=0 found_dht=0 found_local=0 attempts=0 fail_timeout=0 " +
                    "fail_refused=0 fail_no_route=0 fail_other=0 dropped=0 incoming=0 forced_dht=0 forced_trackers=0",
                "timeline 4 t=301000 removed new_session=0 session_started=0 tracker_asks=0 tracker_answers=0 tracker_timeout=0 " +
                    "tracker_refused=0 tracker_other=0 found_tracker=0 found_dht=0 found_local=0 attempts=0 fail_timeout=0 " +
                    "fail_refused=0 fail_no_route=0 fail_other=0 dropped=0 incoming=0 forced_dht=0 forced_trackers=0",
            ),
            lines,
        )
    }

    @Test
    fun samplingIsAskedForUntilAPeerConnectsAndWhenASummaryIsDue() {
        timeline.added(5, at = 0, sessionStartedAt = null)
        assertTrue(timeline.wantsSample(5, 1_000))
        timeline.sample(5, at = 1_000, connected = 1, connecting = 0, pexConnected = 0, known = 1)
        assertEquals(false, timeline.wantsSample(5, 2_000))
        assertTrue(timeline.wantsSample(5, 60_000))
        timeline.tick(60_000)
        assertEquals(false, timeline.wantsSample(5, 61_000))
        assertTrue(timeline.wantsSample(5, 300_000))
        assertEquals(false, timeline.wantsSample(6, 1_000))
        assertTrue(timeline.wantsDhtNodes(1_000))
        timeline.dhtNodes(v6 = false, nodes = 3)
        timeline.tick(60_000)
        timeline.tick(61_000)
        assertEquals(false, timeline.wantsDhtNodes(61_000))
    }

    @Test
    fun listenAndExternalLinesCarryNoAddressAndComeOncePerSession() {
        timeline.listen(ok = true, socket = "TCP", v6 = false)
        timeline.listen(ok = true, socket = "TCP", v6 = false)
        timeline.listen(ok = false, socket = "UTP", v6 = true)
        timeline.externalAddress(v6 = false)
        timeline.externalAddress(v6 = false)
        timeline.externalAddress(v6 = true)
        timeline.sessionEnded()
        timeline.listen(ok = true, socket = "TCP", v6 = false)
        assertEquals(
            listOf("listen ok tcp v4", "listen failed utp v6 error=0 op=unknown", "external v4=yes v6=no", "external v4=yes v6=yes", "listen ok tcp v4"),
            lines,
        )
    }

    @Test
    fun failureKindsComeFromTheErrorText() {
        assertEquals(TrackerFailure.TIMEOUT, TorrentTimeline.trackerFailure("Connection timed out"))
        assertEquals(TrackerFailure.TIMEOUT, TorrentTimeline.trackerFailure("timeout"))
        assertEquals(TrackerFailure.REFUSED, TorrentTimeline.trackerFailure("Connection refused"))
        assertEquals(TrackerFailure.OTHER, TorrentTimeline.trackerFailure("Host not found (authoritative)"))
        assertEquals(TrackerFailure.OTHER, TorrentTimeline.trackerFailure(null))
        assertEquals(ConnectFailure.TIMEOUT, TorrentTimeline.connectFailure("Connection timed out"))
        assertEquals(ConnectFailure.REFUSED, TorrentTimeline.connectFailure("Connection refused"))
        assertEquals(ConnectFailure.NO_ROUTE, TorrentTimeline.connectFailure("No route to host"))
        assertEquals(ConnectFailure.NO_ROUTE, TorrentTimeline.connectFailure("Network is unreachable"))
        assertEquals(ConnectFailure.OTHER, TorrentTimeline.connectFailure("Connection reset by peer"))
    }

    @Test
    fun noLineCarriesACharacterOutsideTheFixedSet() {
        val allowed = Regex("[a-z0-9 =_<>.\\-]*")
        val rnd = Random(20261121)
        val ids = listOf(1L, 2L, 3L, Long.MAX_VALUE)
        var now = 0L
        repeat(20_000) {
            now += rnd.nextLong(0, 20_000)
            val id = ids[rnd.nextInt(ids.size)]
            val n = rnd.nextInt(-5, 500)
            when (rnd.nextInt(17)) {
                0 -> timeline.added(id, now, if (rnd.nextBoolean()) now + rnd.nextLong(-50, 500) else null)
                1 -> timeline.dhtBootstrapped(now)
                2 -> timeline.trackerAsked(id)
                3 -> timeline.trackerAnswered(id, n, now)
                4 -> timeline.trackerFailed(id, TrackerFailure.entries.random(rnd))
                5 -> timeline.peersFound(id, PeerSource.entries.random(rnd), n, now)
                6 -> timeline.connectAttempt(id, now)
                7 -> timeline.connectFailed(id, ConnectFailure.entries.random(rnd))
                8 -> timeline.incomingConnection(id, now)
                9 -> timeline.disconnected(id)
                10 -> timeline.milestone(id, Step.entries.random(rnd), now - rnd.nextLong(0, 5_000))
                11 -> timeline.sample(id, now, n, rnd.nextInt(-1, 50), rnd.nextInt(-1, 50), n * 2)
                12 -> timeline.dhtNodes(v6 = rnd.nextBoolean(), nodes = n)
                13 -> timeline.tick(now)
                14 -> if (rnd.nextBoolean()) timeline.finished(id, now) else timeline.removed(id, now)
                15 -> timeline.listen(rnd.nextBoolean(), randomText(rnd), rnd.nextBoolean())
                else -> if (rnd.nextInt(20) == 0) timeline.sessionEnded() else timeline.externalAddress(rnd.nextBoolean())
            }
        }
        assertTrue("too few lines to judge: ${lines.size}", lines.size > 200)
        for (kind in listOf(" at_60s ", " at_300s ", " finished ", " removed ", " peer_connected", "listen ", "external ")) {
            assertTrue("no line with '$kind'", lines.any { kind in it })
        }
        lines.forEach { assertTrue("bad line: $it", allowed.matches(it)) }
    }

    @Test
    fun forcedSearchesCountPerTorrentAndShowInTheSummary() {
        timeline.added(8, at = 0, sessionStartedAt = 10)
        timeline.forced(8, dht = true, trackers = false, at = 1_000)
        timeline.forced(8, dht = true, trackers = true, at = 3_000)
        timeline.forced(8, dht = false, trackers = false, at = 4_000) // nothing asked: no line, no count
        timeline.forced(99, dht = true, trackers = true, at = 4_000)
        timeline.removed(8, at = 5_000)
        assertEquals(
            listOf(
                "timeline 8 t=10 session_started",
                "timeline 8 t=1000 forced dht=1 trackers=0",
                "timeline 8 t=3000 forced dht=2 trackers=1",
                "timeline 8 t=5000 removed new_session=1 session_started=10 tracker_asks=0 tracker_answers=0 tracker_timeout=0 " +
                    "tracker_refused=0 tracker_other=0 found_tracker=0 found_dht=0 found_local=0 attempts=0 fail_timeout=0 " +
                    "fail_refused=0 fail_no_route=0 fail_other=0 dropped=0 incoming=0 forced_dht=2 forced_trackers=1",
            ),
            lines,
        )
    }

    @Test
    fun aListenFailureCarriesItsErrorNumberAndOperationNameOnly() {
        timeline.listen(ok = false, socket = "TCP", v6 = false, error = 98, operation = "SOCK_BIND")
        timeline.listen(ok = false, socket = "TCP", v6 = false, error = 98, operation = "SOCK_BIND")
        timeline.listen(ok = false, socket = "TCP", v6 = false, error = -3, operation = "Not An Enum!")
        timeline.listen(ok = true, socket = "TCP", v6 = false, error = 98, operation = "SOCK_BIND")
        assertEquals(
            listOf("listen failed tcp v4 error=98 op=sock_bind", "listen failed tcp v4 error=-3 op=notanenum", "listen ok tcp v4"),
            lines,
        )
        val allowed = Regex("[a-z0-9 =_<>.\\-]*")
        val rnd = Random(98)
        repeat(2_000) { timeline.listen(rnd.nextBoolean(), randomText(rnd), rnd.nextBoolean(), rnd.nextInt(), randomText(rnd)) }
        lines.forEach { assertTrue("bad line: $it", allowed.matches(it)) }
    }

    /** What a native enum name or error text might hold: any character, any case. */
    private fun randomText(rnd: Random): String = String(CharArray(rnd.nextInt(0, 12)) { rnd.nextInt(0, 0x3000).toChar() })
}
