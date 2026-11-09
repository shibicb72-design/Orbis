package com.shibi.orbis.downloads

import org.junit.Assert.assertEquals
import org.junit.Test

class PublicDownloadsTest {
    private fun e(id: Long, status: DownloadStatus, private: Boolean = false, path: String? = "/x/$id") =
        DownloadEntry(id, "f$id", "application/octet-stream", null, 10, 10, status, path, 0, private)

    @Test
    fun onlyNewFinishedNormalDownloadsAreCopied() {
        val list = listOf(
            e(1, DownloadStatus.COMPLETED),
            e(2, DownloadStatus.COMPLETED, private = true),
            e(3, DownloadStatus.IN_PROGRESS),
            e(4, DownloadStatus.COMPLETED),
            e(5, DownloadStatus.COMPLETED, path = null),
        )
        assertEquals(listOf(4L), newlyCompleted(list, handled = setOf(1L)).map { it.id })
    }
}
