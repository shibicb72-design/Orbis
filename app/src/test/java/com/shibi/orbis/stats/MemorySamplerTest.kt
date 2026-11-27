package com.shibi.orbis.stats

import com.shibi.orbis.tabs.NormalTabCounts
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** When a memory sample is taken and stored: the dashboard, every 15 minutes in front, never in the background. */
@OptIn(ExperimentalCoroutinesApi::class)
class MemorySamplerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val minute = 60_000L
    private val start = 1_000_000_000L
    private var reads = 0
    private var onRead: () -> Unit = {}
    private var readFails = false
    private var counts = NormalTabCounts(open = 4, awake = 2)

    private class Setup(val sampler: MemorySampler, val history: MemoryHistory, val scope: CoroutineScope)

    private fun TestScope.setup(): Setup {
        val d = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(d + SupervisorJob())
        val clock = { start + currentTime }
        val history = MemoryHistory(File(tmp.root, "m.json"), scope, d, clock = clock)
        history.load()
        advanceUntilIdle()
        val sampler =
            MemorySampler(
                read = {
                    reads++
                    onRead()
                    if (readFails) null
                    else MemoryReading(clock(), listOf(ProcessMemory(ProcessKind.APP, 300_000L), ProcessMemory(ProcessKind.WEB, 200_000L)), rateLimited = false)
                },
                tabCounts = { counts },
                history = history,
                scope = scope,
                io = d,
                clock = clock,
            )
        return Setup(sampler, history, scope)
    }

    @Test
    fun everyFifteenMinutesWhileInFront() = runTest {
        val s = setup()
        s.sampler.onForeground(true)
        advanceTimeBy(15 * minute - 1); runCurrent()
        assertEquals(0, s.history.samples().size)
        advanceTimeBy(2); runCurrent()
        assertEquals(1, s.history.samples().size)
        advanceTimeBy(15 * minute); runCurrent()
        assertEquals(2, s.history.samples().size)
        val times = s.history.samples().map { it.at }
        assertEquals(15 * minute, times[1] - times[0])
        s.scope.cancel()
    }

    @Test
    fun nothingInTheBackground() = runTest {
        val s = setup()
        s.sampler.onForeground(true)
        advanceTimeBy(5 * minute); runCurrent()
        s.sampler.onForeground(false)
        advanceTimeBy(5 * 60 * minute); runCurrent()
        s.sampler.onDashboardOpened()
        advanceUntilIdle()
        assertEquals(0, reads)
        assertTrue(s.history.samples().isEmpty())
        s.scope.cancel()
    }

    @Test
    fun aReadingThatEndsAfterOrbisLeftTheFrontIsNotStored() = runTest {
        val s = setup()
        s.sampler.onForeground(true)
        onRead = { s.sampler.onForeground(false) }
        s.sampler.onDashboardOpened()
        advanceUntilIdle()
        assertEquals(1, reads)
        assertTrue(s.history.samples().isEmpty())
        s.scope.cancel()
    }

    @Test
    fun openingTheDashboardMeasuresAndStoresAtMostEveryFiveMinutes() = runTest {
        val s = setup()
        s.sampler.onForeground(true)
        assertNull(s.sampler.latest.value)
        s.sampler.onDashboardOpened(); runCurrent()
        assertNotNull(s.sampler.latest.value)
        assertEquals(1, s.history.samples().size)
        advanceTimeBy(2 * minute); runCurrent()
        s.sampler.onDashboardOpened(); runCurrent()
        assertEquals(2, reads)
        assertEquals("measured for the screen, not stored", 1, s.history.samples().size)
        advanceTimeBy(3 * minute); runCurrent()
        s.sampler.onDashboardOpened(); runCurrent()
        assertEquals(2, s.history.samples().size)
        s.scope.cancel()
    }

    @Test
    fun anOpeningTenSecondsAfterAReadingTakesNoNewReading() = runTest {
        val s = setup()
        try {
            s.sampler.onForeground(true)
            s.sampler.onDashboardOpened(); runCurrent()
            val first = s.sampler.latest.value
            assertEquals(1, reads)
            advanceTimeBy(10_000); runCurrent()
            s.sampler.onDashboardOpened(); runCurrent()
            assertEquals(1, reads)
            assertEquals(first, s.sampler.latest.value)
        } finally {
            s.scope.cancel()
        }
    }

    @Test
    fun anOpeningThirtySecondsAfterAReadingReadsAgain() = runTest {
        val s = setup()
        try {
            s.sampler.onForeground(true)
            s.sampler.onDashboardOpened(); runCurrent()
            advanceTimeBy(30_000); runCurrent()
            s.sampler.onDashboardOpened(); runCurrent()
            assertEquals(2, reads)
        } finally {
            s.scope.cancel()
        }
    }

    @Test
    fun afterTheHistoryIsClearedTheNextOpeningReadsAndStoresAtOnce() = runTest {
        val s = setup()
        try {
            s.sampler.onForeground(true)
            s.sampler.onDashboardOpened(); runCurrent()
            s.history.clear(); runCurrent()
            assertTrue(s.history.samples().isEmpty())
            advanceTimeBy(10_000); runCurrent()
            s.sampler.onDashboardOpened(); runCurrent()
            assertEquals(2, reads)
            assertEquals(1, s.history.samples().size)
        } finally {
            s.scope.cancel()
        }
    }

    @Test
    fun aFailedReadingDoesNotStartTheFloor() = runTest {
        val s = setup()
        try {
            s.sampler.onForeground(true)
            readFails = true
            s.sampler.onDashboardOpened(); runCurrent()
            assertEquals(1, reads)
            assertNull(s.sampler.latest.value)
            readFails = false
            advanceTimeBy(10_000); runCurrent()
            s.sampler.onDashboardOpened(); runCurrent()
            assertEquals(2, reads)
            assertNotNull(s.sampler.latest.value)
        } finally {
            s.scope.cancel()
        }
    }

    @Test
    fun anOpeningFiveSecondsAfterTheFifteenMinuteReadingTakesNoNewReading() = runTest {
        val s = setup()
        try {
            s.sampler.onForeground(true)
            advanceTimeBy(15 * minute + 1); runCurrent()
            assertEquals(1, reads)
            advanceTimeBy(5_000); runCurrent()
            s.sampler.onDashboardOpened(); runCurrent()
            assertEquals(1, reads)
            assertNotNull(s.sampler.latest.value)
        } finally {
            s.scope.cancel()
        }
    }

    @Test
    fun theSampleHoldsTheTotalsAndTheNormalTabCounts() = runTest {
        val s = setup()
        counts = NormalTabCounts(open = 7, awake = 3)
        s.sampler.onForeground(true)
        s.sampler.onDashboardOpened(); runCurrent()
        val sample = s.history.samples().single()
        assertEquals(MemorySample(start, 500_000L, 300_000L, 200_000L, 0L, normalTabs = 7, awakeNormalTabs = 3), sample)
        s.scope.cancel()
    }
}
