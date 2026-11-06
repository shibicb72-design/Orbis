package com.shibi.orbis.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSettledTrackerTest {
    private val tracker = DownloadSettledTracker()

    private fun entry(id: Long, status: DownloadStatus) =
        DownloadEntry(id, "f$id.bin", "application/octet-stream", null, 10, 10, status, null, 1_700_000_000_000L + id)

    @Test
    fun completedIsReportedOnce() {
        tracker.update(listOf(entry(1, DownloadStatus.IN_PROGRESS)))
        val done = listOf(entry(1, DownloadStatus.COMPLETED))
        assertEquals(listOf(1L), tracker.update(done).map { it.id })
        assertTrue(tracker.update(done).isEmpty())
        assertTrue(tracker.update(done).isEmpty())
    }

    @Test
    fun failedIsReportedOnce() {
        tracker.update(listOf(entry(1, DownloadStatus.IN_PROGRESS)))
        val failed = listOf(entry(1, DownloadStatus.FAILED))
        assertEquals(listOf(1L), tracker.update(failed).map { it.id })
        assertTrue(tracker.update(failed).isEmpty())
    }

    @Test
    fun cancelledIsIgnored() {
        tracker.update(listOf(entry(1, DownloadStatus.IN_PROGRESS)))
        assertTrue(tracker.update(listOf(entry(1, DownloadStatus.CANCELLED))).isEmpty())
    }

    @Test
    fun restoredEntriesAreIgnored() {
        // Nothing was active in this process: a restored FAILED (or COMPLETED) entry just appears in the list.
        assertTrue(tracker.update(emptyList()).isEmpty())
        val restored = listOf(entry(5, DownloadStatus.FAILED), entry(4, DownloadStatus.COMPLETED))
        assertTrue(tracker.update(restored).isEmpty())
        assertTrue(tracker.update(restored).isEmpty())
    }

    @Test
    fun restoreMergingWhileADownloadRunsReportsOnlyTheRealOne() {
        tracker.update(listOf(entry(9, DownloadStatus.IN_PROGRESS)))
        // restore() merges old FAILED rows into the list while download 9 is still running.
        assertTrue(tracker.update(listOf(entry(9, DownloadStatus.IN_PROGRESS), entry(2, DownloadStatus.FAILED))).isEmpty())
        val after = listOf(entry(9, DownloadStatus.COMPLETED), entry(2, DownloadStatus.FAILED))
        assertEquals(listOf(9L), tracker.update(after).map { it.id })
    }

    @Test
    fun deletedWhileRunningIsNotReported() {
        tracker.update(listOf(entry(1, DownloadStatus.IN_PROGRESS)))
        assertTrue(tracker.update(emptyList()).isEmpty())
    }

    @Test
    fun severalSettleTogether() {
        tracker.update(listOf(entry(1, DownloadStatus.IN_PROGRESS), entry(2, DownloadStatus.IN_PROGRESS), entry(3, DownloadStatus.IN_PROGRESS)))
        val now = listOf(entry(1, DownloadStatus.COMPLETED), entry(2, DownloadStatus.FAILED), entry(3, DownloadStatus.CANCELLED))
        assertEquals(setOf(1L, 2L), tracker.update(now).map { it.id }.toSet())
    }
}
