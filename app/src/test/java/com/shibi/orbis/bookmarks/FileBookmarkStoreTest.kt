package com.shibi.orbis.bookmarks

import com.shibi.orbis.storage.AtomicTextFile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
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

    // R1144-03: an explicit clear-all must not leave the old addresses behind in the set-aside copy.
    @Test
    fun clearAllThroughTheManagerDeletesTheSetAsideCopy() {
        file.parentFile!!.mkdirs()
        file.writeText("{this is not json https://old-secret.example/")
        val corrupt = File(file.parentFile, "bookmarks.json.corrupt")
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val m = BookmarkManager(store(), scope, log = {}, now = { 1L }, io = dispatcher)
            m.load()
            scheduler.advanceUntilIdle()
            assertTrue("load set the unreadable file aside", corrupt.exists())
            m.add("https://a.example/", "A")
            m.remove(m.entries.value.single().id)
            scheduler.advanceUntilIdle()
            assertTrue("an ordinary delete keeps the copy for recovery", corrupt.exists())
            m.clearAll()
            scheduler.advanceUntilIdle()
            assertFalse("clear all removed the copy with the old addresses", corrupt.exists())
            assertEquals(emptyList<Bookmark>(), store().load())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun discardSetAsideWithoutACopyIsHarmless() {
        val s = store()
        s.save(listOf(a))
        s.discardSetAside()
        assertEquals(listOf(a), store().load())
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
