package com.shibi.orbis.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyOnFinishTest {
    private fun e(
        id: Long,
        status: DownloadStatus = DownloadStatus.COMPLETED,
        private: Boolean = false,
        path: String? = "/x/$id",
        savedTo: SavedTo = SavedTo.UNKNOWN,
    ) = DownloadEntry(id, "f$id", "application/octet-stream", null, 10, 10, status, path, 0, private, savedTo = savedTo)

    private fun CopyOnFinish.ids(list: List<DownloadEntry>, restored: Set<Long> = emptySet()) = due(list, restored).map { it.id }

    @Test(timeout = 10_000)
    fun restoredEntriesAreNeverCopiedEvenWhenTheyArriveAfterAnEmptyList() {
        val c = CopyOnFinish()
        assertTrue(c.ids(emptyList()).isEmpty())
        val restored = listOf(
            e(1, savedTo = SavedTo.UNKNOWN),
            e(2, savedTo = SavedTo.ORBIS),
            e(3, savedTo = SavedTo.DOWNLOADS),
        )
        val ids = setOf(1L, 2L, 3L)
        assertTrue(c.ids(restored, ids).isEmpty())
        assertTrue(c.ids(restored, ids).isEmpty())
        assertTrue(c.ids(restored + e(4, DownloadStatus.IN_PROGRESS), ids).isEmpty())
    }

    @Test(timeout = 10_000)
    fun anEntryThatFinishesInThisRunIsCopiedOnce() {
        val c = CopyOnFinish()
        assertTrue(c.ids(listOf(e(7, DownloadStatus.IN_PROGRESS, path = null))).isEmpty())
        assertEquals(listOf(7L), c.ids(listOf(e(7))))
        assertTrue(c.ids(listOf(e(7))).isEmpty())
        assertTrue(c.ids(listOf(e(7, savedTo = SavedTo.ORBIS))).isEmpty())
    }

    @Test(timeout = 10_000)
    fun anEntryAlreadyCompletedTheFirstTimeItIsSeenIsCopiedOnce() {
        val c = CopyOnFinish()
        assertTrue(c.ids(listOf(e(1)), setOf(1L)).isEmpty())
        assertEquals(listOf(8L), c.ids(listOf(e(8), e(1)), setOf(1L)))
        assertTrue(c.ids(listOf(e(8), e(1)), setOf(1L)).isEmpty())
    }

    @Test(timeout = 10_000)
    fun privateWithoutAFilePathAndFailedAreNeverCopied() {
        val c = CopyOnFinish()
        val list = listOf(
            e(1, private = true),
            e(2, path = null),
            e(3, DownloadStatus.FAILED, path = null),
            e(4, DownloadStatus.CANCELLED, path = null),
        )
        assertTrue(c.ids(list).isEmpty())
        assertTrue(c.ids(list).isEmpty())
    }

    @Test(timeout = 10_000)
    fun anEntryAlreadyMarkedAsCopiedIsNotCopiedAgainEvenWhenNotRestored() {
        val c = CopyOnFinish()
        assertTrue(c.ids(listOf(e(5, savedTo = SavedTo.DOWNLOADS))).isEmpty())
        assertTrue(c.ids(listOf(e(5, savedTo = SavedTo.DOWNLOADS))).isEmpty())
    }
}
