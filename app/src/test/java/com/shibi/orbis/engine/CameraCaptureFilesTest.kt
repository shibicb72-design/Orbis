package com.shibi.orbis.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CameraCaptureFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun files() = CameraCaptureFiles(tmp.root)

    @Test
    fun rootIsCameraCaptureUnderTheCacheDir() {
        val f = files()
        assertEquals("camera_capture", f.root.name)
        assertEquals(tmp.root, f.root.parentFile)
    }

    @Test
    fun rootIsNeverInsideTheUploadCache() {
        val uploads = UploadCache(tmp.root).root
        val root = files().root
        assertFalse(root.canonicalPath.startsWith(uploads.canonicalPath))
        assertFalse(uploads.canonicalPath.startsWith(root.canonicalPath))
    }

    @Test
    fun newFileMakesTheDirectoryButNotTheFile() {
        val f = files()
        val file = f.newFile()!!
        assertTrue(f.root.isDirectory)
        assertFalse("the camera creates the file", file.exists())
        assertEquals(f.root, file.parentFile)
    }

    @Test
    fun newFileNamesAreUniqueJpgsWithNoOtherInformation() {
        val f = files()
        val names = List(20) { f.newFile()!!.name }
        assertEquals(20, names.toSet().size)
        for (name in names) {
            assertTrue(name, Regex("photo-[0-9a-f]{32}\\.jpg").matches(name))
        }
    }

    @Test
    fun newFileIsNullWhenTheDirectoryCannotBeMade() {
        // A plain file where the cache directory should be.
        val blocker = tmp.newFile("cache")
        assertNull(CameraCaptureFiles(blocker).newFile())
    }

    @Test
    fun hasPictureIsFalseForMissingAndEmptyFiles() {
        val f = files()
        val file = f.newFile()!!
        assertFalse(f.hasPicture(file))
        file.createNewFile()
        assertEquals(0L, file.length())
        assertFalse(f.hasPicture(file))
    }

    @Test
    fun hasPictureIsTrueWithBytes() {
        val f = files()
        val file = f.newFile()!!
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(f.hasPicture(file))
    }

    @Test
    fun hasPictureIsFalseForADirectory() {
        val f = files()
        val dir = File(f.newFile()!!.parentFile, "dir").also { it.mkdirs() }
        assertFalse(f.hasPicture(dir))
    }

    @Test
    fun discardDeletesOnlyDirectChildrenOfTheRoot() {
        val f = files()
        val mine = f.newFile()!!
        mine.writeBytes(byteArrayOf(1))
        f.discard(mine)
        assertFalse(mine.exists())
    }

    @Test
    fun discardOfAMissingFileIsHarmless() {
        val f = files()
        f.discard(f.newFile()!!)
    }

    @Test
    fun discardRefusesPathsOutsideTheRoot() {
        val f = files()
        f.newFile()
        val outside = tmp.newFile("outside.jpg")
        f.discard(outside)
        assertTrue(outside.exists())

        val nested = File(File(f.root, "sub").also { it.mkdirs() }, "photo.jpg").also { it.writeBytes(byteArrayOf(1)) }
        f.discard(nested)
        assertTrue(nested.exists())

        val escaping = File(File(f.root, "sub"), "../../outside.jpg")
        f.discard(escaping)
        assertTrue(outside.exists())
    }

    @Test
    fun discardRefusesTheRootItself() {
        val f = files()
        f.newFile()
        f.discard(f.root)
        assertTrue(f.root.isDirectory)
    }

    @Test
    fun clearAllRemovesEverythingUnderTheRootAndTheRoot() {
        val f = files()
        f.newFile()!!.writeBytes(byteArrayOf(1))
        File(f.root, "stray").mkdirs()
        f.clearAll()
        assertFalse(f.root.exists())
        assertNotNull(f.newFile())
    }

    @Test
    fun clearAllLeavesTheUploadCacheAlone() {
        val uploads = UploadCache(tmp.root).root.also { it.mkdirs() }
        val marker = File(uploads, "keep").also { it.writeBytes(byteArrayOf(1)) }
        val f = files()
        f.newFile()
        f.clearAll()
        assertTrue(marker.exists())
    }
}
