package com.shibi.orbis.downloads

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNotificationModelTest {
    private fun entry(
        id: Long,
        name: String = "f$id.bin",
        received: Long = 0,
        total: Long = 100,
        status: DownloadStatus = DownloadStatus.IN_PROGRESS,
    ) = DownloadEntry(id, name, "application/octet-stream", "example.com", total, received, status, null, 1_700_000_000_000L + id)

    @Test
    fun noActiveGivesNoModel() {
        assertNull(DownloadNotificationModel.from(emptyList()))
        val settled =
            listOf(
                entry(1, status = DownloadStatus.COMPLETED),
                entry(2, status = DownloadStatus.FAILED),
                entry(3, status = DownloadStatus.CANCELLED),
            )
        assertNull(DownloadNotificationModel.from(settled))
    }

    @Test
    fun singleDownloadShowsNameAndPercent() {
        val m = DownloadNotificationModel.from(listOf(entry(7, name = "movie.mp4", received = 25, total = 100)))!!
        assertTrue(m.isSingle)
        assertEquals("movie.mp4", m.name)
        assertEquals(25, m.percent)
        assertEquals(1, m.count)
        assertArrayEquals(longArrayOf(7), m.ids)
    }

    @Test
    fun unknownTotalIsIndeterminate() {
        val m = DownloadNotificationModel.from(listOf(entry(1, received = 500, total = -1)))!!
        assertNull(m.percent)
    }

    @Test
    fun zeroTotalIsIndeterminate() {
        val m = DownloadNotificationModel.from(listOf(entry(1, received = 0, total = 0)))!!
        assertNull(m.percent)
    }

    @Test
    fun receivedBeyondTotalClampsTo100() {
        val m = DownloadNotificationModel.from(listOf(entry(1, received = 140, total = 100)))!!
        assertEquals(100, m.percent)
    }

    @Test
    fun negativeReceivedClampsTo0() {
        val m = DownloadNotificationModel.from(listOf(entry(1, received = -5, total = 100)))!!
        assertEquals(0, m.percent)
    }

    @Test
    fun hugeTotalsDoNotOverflow() {
        val total = Long.MAX_VALUE / 2
        val m = DownloadNotificationModel.from(listOf(entry(1, received = total / 2, total = total)))!!
        assertTrue(m.percent in 49..50)
    }

    @Test
    fun manyDownloadsAggregateWithoutNames() {
        val m =
            DownloadNotificationModel.from(
                listOf(
                    entry(3, received = 50, total = 100),
                    entry(1, received = 150, total = 300),
                    entry(2, received = 0, total = 100),
                ),
            )!!
        assertFalse(m.isSingle)
        assertEquals(3, m.count)
        assertNull(m.name)
        // 200 of 500 bytes.
        assertEquals(40, m.percent)
        assertArrayEquals(longArrayOf(1, 2, 3), m.ids)
    }

    @Test
    fun manyWithOneUnknownTotalIsIndeterminate() {
        val m = DownloadNotificationModel.from(listOf(entry(1, received = 50, total = 100), entry(2, received = 9, total = -1)))!!
        assertNull(m.percent)
        assertEquals(2, m.count)
    }

    @Test
    fun manyAggregateIsClamped() {
        val m = DownloadNotificationModel.from(listOf(entry(1, received = 400, total = 100), entry(2, received = 100, total = 100)))!!
        assertEquals(100, m.percent)
    }

    @Test
    fun onlyActiveEntriesCount() {
        val m =
            DownloadNotificationModel.from(
                listOf(
                    entry(1, received = 10, total = 100),
                    entry(2, status = DownloadStatus.COMPLETED, received = 100),
                    entry(3, status = DownloadStatus.FAILED),
                    entry(4, status = DownloadStatus.CANCELLED),
                ),
            )!!
        assertTrue(m.isSingle)
        assertArrayEquals(longArrayOf(1), m.ids)
        assertEquals(10, m.percent)
    }

    @Test
    fun structureIgnoresPercentButNotNameOrIds() {
        val a = DownloadNotificationModel.from(listOf(entry(1, name = "a", received = 1)))!!
        val b = DownloadNotificationModel.from(listOf(entry(1, name = "a", received = 90)))!!
        val renamed = DownloadNotificationModel.from(listOf(entry(1, name = "b", received = 1)))!!
        val two = DownloadNotificationModel.from(listOf(entry(1, name = "a"), entry(2)))!!
        assertTrue(a.sameStructure(b))
        assertNotNull(a.percent)
        assertFalse(a == b)
        assertFalse(a.sameStructure(renamed))
        assertFalse(a.sameStructure(two))
    }

    @Test
    fun toStringCarriesNoFileName() {
        val m = DownloadNotificationModel.from(listOf(entry(1, name = "secret-tax-return.pdf")))!!
        assertFalse(m.toString().contains("secret"))
    }
}
