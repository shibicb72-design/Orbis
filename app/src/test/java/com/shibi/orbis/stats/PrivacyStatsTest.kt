package com.shibi.orbis.stats

import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivacyStatsTest {
    @get:Rule val tmp = TemporaryFolder()
    private var today = LocalDate.of(2026, 11, 9)

    private fun TestScope.stats(file: File = File(tmp.root, "s.json")): PrivacyStats {
        val d = StandardTestDispatcher(testScheduler)
        return PrivacyStats(file, CoroutineScope(d + SupervisorJob()), d, today = { today })
    }

    @Test
    fun countsByKindAndKeepsOnlyNormalTrackerHosts() = runTest {
        val s = stats()
        s.load(); advanceUntilIdle()
        s.recordBlocked(BlockKind.AD, "https://ads.example/x.js", isPrivate = false)
        s.recordBlocked(BlockKind.TRACKER, "https://www.track.example/p", isPrivate = false)
        s.recordBlocked(BlockKind.TRACKER, "https://secret-tracker.example/p", isPrivate = true)
        s.recordBlocked(BlockKind.COOKIE, "https://c.example/", isPrivate = false)
        val w = s.summary(7)
        assertEquals(4L, w.totalBlocked)
        assertEquals(2L, w.byKind[BlockKind.TRACKER])
        assertEquals(setOf("ads.example", "track.example"), w.topTrackers.map { it.first }.toSet())
        // Cookies save no request: 3 requests x 50 ms, ads 45 kB, trackers 12 kB each.
        assertEquals(150L, w.estimatedMillisSaved)
        assertEquals(45_000L + 24_000L, w.estimatedBytesSaved)
        assertEquals(7, w.days.size)
    }

    @Test
    fun survivesARestartAndOldDaysAreDropped() = runTest {
        val f = File(tmp.root, "s.json")
        val a = stats(f)
        a.load(); advanceUntilIdle()
        a.recordBlocked(BlockKind.AD, "https://ads.example/", false)
        a.recordTraffic(1_000); a.recordTraffic(5_000)
        advanceUntilIdle()
        val b = stats(f)
        b.load(); advanceUntilIdle()
        assertEquals(1L, b.summary(null).totalBlocked)
        assertEquals(4_000L, b.summary(null).bytesUsed)
        today = today.plusDays(STATS_DAYS_KEPT.toLong() + 1)
        b.recordBlocked(BlockKind.AD, null, false)
        assertEquals(1L, b.summary(null).totalBlocked)
    }

    @Test
    fun trafficCounterResetCountsFromZero() {
        assertEquals(0L, trafficDelta(null, 500))
        assertEquals(200L, trafficDelta(500, 700))
        assertEquals(100L, trafficDelta(700, 100))
    }

    @Test
    fun trackerHostParsing() {
        assertEquals("doubleclick.net", trackerHostOf("https://www.doubleclick.net/a?b"))
        assertEquals("x.example", trackerHostOf("https://u@x.example:443/"))
        assertNull(trackerHostOf("not a url"))
    }

    @Test
    fun resetClearsEverything() = runTest {
        val s = stats()
        s.load(); advanceUntilIdle()
        s.recordBlocked(BlockKind.AD, "https://a.example/", false)
        s.reset()
        assertEquals(0L, s.summary(null).totalBlocked)
        assertNull(s.summary(7).since)
        assertTrue(s.summary(7).topTrackers.isEmpty())
    }
}
