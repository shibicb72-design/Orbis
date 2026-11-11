package com.shibi.orbis.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentNotificationModelTest {
    private fun entry(
        id: String,
        name: String = "Sintel",
        status: TorrentStatus = TorrentStatus.DOWNLOADING,
        total: Long = 100,
        done: Long = 50,
        isPrivate: Boolean = false,
        speed: Long = 1000,
    ) = TorrentEntry(id, name, total, done, status, 0, "/x/$id", isPrivate = isPrivate, downBps = speed)

    @Test
    fun nothingActiveGivesNoModel() {
        assertNull(TorrentNotificationModel.from(emptyList()))
        assertNull(
            TorrentNotificationModel.from(
                listOf(entry("a", status = TorrentStatus.PAUSED), entry("b", status = TorrentStatus.COMPLETED), entry("c", status = TorrentStatus.WAITING_FOR_WIFI)),
            ),
        )
    }

    @Test
    fun singleTorrentIsNamed() {
        val m = TorrentNotificationModel.from(listOf(entry("a"), entry("b", status = TorrentStatus.PAUSED)))!!
        assertEquals(listOf("a"), m.ids)
        assertEquals("Sintel", m.name)
        assertFalse(m.isPrivate)
        assertEquals(50, m.percent)
        assertEquals(1000L, m.downBps)
    }

    @Test
    fun privateTorrentIsNeverNamed() {
        val m = TorrentNotificationModel.from(listOf(entry("a", name = "SecretName", isPrivate = true)))!!
        assertNull(m.name)
        assertTrue(m.isPrivate)
        assertFalse(m.toString().contains("SecretName"))
    }

    @Test
    fun anyPrivateTorrentMakesTheSummaryPrivate() {
        val m = TorrentNotificationModel.from(listOf(entry("b", name = "Public"), entry("a", name = "SecretName", isPrivate = true)))!!
        assertNull(m.name)
        assertTrue(m.isPrivate)
        assertEquals(listOf("a", "b"), m.ids)
        assertEquals(2000L, m.downBps)
    }

    @Test
    fun severalTorrentsAreSummed() {
        val m = TorrentNotificationModel.from(listOf(entry("a", total = 100, done = 100), entry("b", total = 300, done = 0, status = TorrentStatus.PUBLISHING)))!!
        assertEquals(2, m.count)
        assertNull(m.name)
        assertEquals(25, m.percent)
    }

    @Test
    fun unknownTotalIsIndeterminate() {
        val m = TorrentNotificationModel.from(listOf(entry("a", status = TorrentStatus.FETCHING_METADATA, total = -1, done = 0)))!!
        assertNull(m.percent)
    }

    @Test
    fun percentIsClamped() {
        assertEquals(100, TorrentNotificationModel.from(listOf(entry("a", total = 10, done = 20)))!!.percent)
    }
}
