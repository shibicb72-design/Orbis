package com.shibi.orbis.downloads

import org.junit.Assert.assertArrayEquals
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

    @Test
    fun relativePathIsDownloadOrAFolderBelowIt() {
        assertEquals("Download", publicRelativePath(null))
        assertEquals("Download", publicRelativePath(""))
        assertEquals("Download/Orbis/Sintel", publicRelativePath("Orbis/Sintel"))
        assertEquals("Download/Orbis/Sintel/subs", publicRelativePath("/Orbis/Sintel/subs/"))
    }

    @Test
    fun theFolderAndPackageAreBoundArgumentsNeverPartOfTheSql() {
        val sel = publishedSelection("Download/Orbis/Sintel", "com.shibi.orbis")
        assertEquals("relative_path LIKE ? ESCAPE '\\' AND owner_package_name = ?", sel.selection)
        assertArrayEquals(arrayOf("Download/Orbis/Sintel/%", "com.shibi.orbis"), sel.args)
        // A trailing slash makes no difference.
        assertArrayEquals(sel.args, publishedSelection("Download/Orbis/Sintel/", "com.shibi.orbis").args)
    }

    @Test
    fun likeWildcardsInANameMatchOnlyThemselves() {
        val sel = publishedSelection("Download/Orbis/50%_off' OR 1=1 --", "p")
        assertEquals("Download/Orbis/50\\%\\_off' OR 1=1 --/%", sel.args[0])
        assertEquals(sel.selection, publishedSelection("Download/Orbis/x", "p").selection)
        assertEquals("Download/Orbis/a\\\\b/%", publishedSelection("Download/Orbis/a\\b", "p").args[0])
    }
}
