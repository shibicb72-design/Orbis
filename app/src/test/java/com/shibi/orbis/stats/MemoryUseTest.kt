package com.shibi.orbis.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The split by kind from the process names, the smaps_rollup parser, and the reader's rate limit and gone processes. */
class MemoryUseTest {
    private val pkg = "com.shibi.orbis.debug"

    @Test
    fun theSplitByKindComesFromTheProcessNames() {
        assertEquals(ProcessKind.APP, processKind(pkg, pkg))
        assertEquals(ProcessKind.WEB, processKind("$pkg:sandboxed_tab_disable_art_image_7", pkg))
        assertEquals(ProcessKind.WEB, processKind("$pkg:sandboxed_tab_disable_art_image_34", pkg))
        assertEquals(ProcessKind.OTHER, processKind("$pkg:gpu_disable_art_image_", pkg))
        assertEquals(ProcessKind.OTHER, processKind("$pkg:socket_disable_art_image_", pkg))
        assertEquals(ProcessKind.OTHER, processKind("$pkg:media", pkg))
        assertEquals(ProcessKind.OTHER, processKind("$pkg:utility_disable_art_image_", pkg))
    }

    @Test
    fun theRollupGivesThePssInKilobytes() {
        val text = "5583b2c000-7ffd8a3e4000 ---p 00000000 00:00 0 [rollup]\nRss:              201348 kB\nPss:              123456 kB\nPss_Anon:          80000 kB\n"
        assertEquals(123_456L, pssKbFromSmapsRollup(text))
        assertNull(pssKbFromSmapsRollup("Rss: 5 kB\n"))
        assertNull(pssKbFromSmapsRollup(""))
    }

    private var now = 10_000_000L
    private val systemCalls = mutableListOf<List<Int>>()
    private var systemValues = mapOf<Int, Long>()
    private var rollup = mapOf<Int, Long>()
    private var processes = listOf<RunningProcess>()

    private fun reader(own: Long? = null) =
        MemoryReader(
            listProcesses = { processes },
            ownPid = 1,
            readRollupKb = { rollup[it] },
            readOwnKb = { own },
            readSystemKb = { pids -> systemCalls += pids.toList(); LongArray(pids.size) { systemValues[pids[it]] ?: 0L } },
            packageName = pkg,
            clock = { now },
        )

    @Test
    fun freshValuesFromTheRollupAreMeasuredNowAndNotRateLimited() {
        processes = listOf(RunningProcess(1, pkg), RunningProcess(2, "$pkg:sandboxed_tab_disable_art_image_0"), RunningProcess(3, "$pkg:gpu_disable_art_image_"))
        rollup = mapOf(1 to 300_000L, 2 to 200_000L, 3 to 50_000L)
        val r = reader().read()!!
        assertEquals(550_000L, r.totalKb)
        assertEquals(300_000L, r.kb(ProcessKind.APP))
        assertEquals(200_000L, r.kb(ProcessKind.WEB))
        assertEquals(50_000L, r.kb(ProcessKind.OTHER))
        assertEquals(r.totalKb, ProcessKind.entries.sumOf { r.kb(it) })
        assertEquals(now, r.measuredAt)
        assertFalse(r.rateLimited)
        assertTrue(systemCalls.isEmpty())
    }

    @Test
    fun theRateLimitedCallIsAskedAtMostEveryFiveMinutesAndTheTimeShownIsWhenItWasRead() {
        processes = listOf(RunningProcess(1, pkg), RunningProcess(2, "$pkg:sandboxed_tab_disable_art_image_0"))
        val r = reader(own = 100_000L)
        systemValues = mapOf(2 to 200_000L)
        val first = r.read()!!
        assertTrue(first.rateLimited)
        assertEquals(listOf(listOf(2)), systemCalls)
        val firstAt = now
        now += 60_000
        systemValues = mapOf(2 to 999_000L)
        val second = r.read()!!
        assertEquals("no second call inside five minutes", 1, systemCalls.size)
        assertEquals(300_000L, second.totalKb)
        assertEquals("the oldest part decides the time shown", firstAt, second.measuredAt)
        now = firstAt + SYSTEM_MEMORY_RATE_LIMIT_MILLIS
        val third = r.read()!!
        assertEquals(2, systemCalls.size)
        assertEquals(1_099_000L, third.totalKb)
        assertEquals(now, third.measuredAt)
    }

    @Test
    fun aProcessThatWentAwayIsSkippedNotAnError() {
        processes = listOf(RunningProcess(1, pkg), RunningProcess(2, "$pkg:sandboxed_tab_disable_art_image_0"), RunningProcess(3, "$pkg:sandboxed_tab_disable_art_image_1"))
        rollup = mapOf(1 to 100_000L, 2 to 40_000L)
        // Process 3 has no rollup (gone) and the system gives zero for it.
        val r = reader().read()!!
        assertEquals(listOf(40_000L), r.webProcessesKb)
        assertEquals(140_000L, r.totalKb)
        assertFalse(r.rateLimited)
        processes = emptyList()
        assertNull(reader().read())
        processes = listOf(RunningProcess(4, "$pkg:gpu_disable_art_image_"))
        assertNull("nothing could be measured", reader().read())
    }

    @Test
    fun webProcessesAreListedLargestFirst() {
        processes = (2..5).map { RunningProcess(it, "$pkg:sandboxed_tab_disable_art_image_$it") }
        rollup = mapOf(2 to 10L, 3 to 400L, 4 to 30L, 5 to 200L)
        assertEquals(listOf(400L, 200L, 30L, 10L), reader().read()!!.webProcessesKb)
    }
}
