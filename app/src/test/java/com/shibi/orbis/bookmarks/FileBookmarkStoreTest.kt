package com.shibi.orbis.bookmarks

import com.shibi.orbis.storage.AtomicTextFile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileBookmarkStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val file get() = File(tmp.root, "bookmarks/bookmarks.json")
    private fun store() = FileBookmarkStore(AtomicTextFile(file))
    private val a = Bookmark(1, "https://a.example/", "A", 10)

    @Test
    fun missingFileLoadsEmptyWithoutCreatingAnything() {
        assertEquals(emptyList<Bookmark>(), store().load())
        assertFalse(file.exists())
    }

    @Test
    fun saveThenLoad() {
        store().save(listOf(a))
        assertEquals(listOf(a), store().load())
    }

    @Test
    fun corruptFileIsSetAsideAndNeverOverwritten() {
        file.parentFile!!.mkdirs()
        file.writeText("{this is not json")
        val s = store()
        assertEquals(emptyList<Bookmark>(), s.load())
        val corrupt = File(file.parentFile, "bookmarks.json.corrupt")
        assertEquals("{this is not json", corrupt.readText())
        assertFalse(file.exists())
        // The next save writes a new file and leaves the set-aside copy alone.
        s.save(listOf(a))
        assertEquals(listOf(a), store().load())
        assertEquals("{this is not json", corrupt.readText())
    }

    @Test
    fun wrongShapeIsAlsoSetAside() {
        file.parentFile!!.mkdirs()
        file.writeText("{\"version\":1}")
        assertEquals(emptyList<Bookmark>(), store().load())
        assertTrue(File(file.parentFile, "bookmarks.json.corrupt").exists())
    }

    @Test
    fun aFileWithOnlySomeBadEntriesIsKeptInPlace() {
        file.parentFile!!.mkdirs()
        file.writeText(
            """{"bookmarks":[{"id":1,"url":"javascript:x","title":"bad"},{"id":2,"url":"https://ok.example/","title":"OK"}]}""",
        )
        assertEquals(listOf("https://ok.example/"), store().load().map { it.url })
        assertTrue(file.exists())
        assertFalse(File(file.parentFile, "bookmarks.json.corrupt").exists())
    }
}
