package com.shibi.orbis.bookmarks

import com.shibi.orbis.storage.AtomicTextFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class MemBookmarkStore(var data: List<Bookmark> = emptyList()) : BookmarkStore {
    val saves = mutableListOf<List<Bookmark>>()
    var failLoad = false
    var failSave = false
    var loads = 0
    var discards = 0

    override fun load(): List<Bookmark> {
        loads++
        if (failLoad) throw IOException("boom")
        return data
    }

    override fun save(items: List<Bookmark>) {
        if (failSave) throw IOException("boom")
        saves += items
        data = items
    }

    override fun discardSetAside() {
        discards++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val logs = mutableListOf<String>()
    private var clock = 1_000L

    @After
    fun tearDown() = scope.cancel()

    private fun manager(store: BookmarkStore, load: Boolean = true): BookmarkManager {
        val m = BookmarkManager(store = store, scope = scope, log = { logs += it }, now = { clock }, io = dispatcher)
        if (load) {
            m.load()
            scheduler.advanceUntilIdle()
        }
        return m
    }

    private fun settle() = scheduler.advanceUntilIdle()

    private fun urls(m: BookmarkManager) = m.entries.value.map { it.url }

    private fun stored(n: Int) = (1..n).map { Bookmark(it.toLong(), "https://s$it.example/", "S$it", it.toLong()) }

    @Test
    fun addsNewestFirstWithTimeAndTitleFallback() {
        val m = manager(MemBookmarkStore())
        assertEquals(AddResult.ADDED, m.add("https://a.example/", "A"))
        clock = 2_000
        assertEquals(AddResult.ADDED, m.add("https://b.example/", "  "))
        assertEquals(listOf("https://b.example/", "https://a.example/"), urls(m))
        assertEquals("https://b.example/", m.entries.value[0].title)
        assertEquals(2_000L, m.entries.value[0].createdAt)
        assertEquals(1_000L, m.entries.value[1].createdAt)
        assertEquals(2, m.entries.value.map { it.id }.toSet().size)
        assertTrue(m.isBookmarked("https://a.example/"))
        assertFalse(m.isBookmarked("https://a.example"))
    }

    @Test
    fun duplicateAndInvalidAddsChangeNothing() {
        val store = MemBookmarkStore()
        val m = manager(store)
        m.add("https://a.example/", "A")
        settle()
        assertEquals(AddResult.EXISTS, m.add("https://a.example/", "Other"))
        assertEquals("A", m.entries.value[0].title)
        assertEquals(AddResult.INVALID, m.add("javascript:alert(1)", "x"))
        assertEquals(AddResult.INVALID, m.add("file:///sdcard/x", "x"))
        assertEquals(AddResult.INVALID, m.add("https://user@evil.example/", "x"))
        assertEquals(AddResult.INVALID, m.add("", "x"))
        settle()
        assertEquals(1, m.entries.value.size)
        assertEquals(1, store.saves.size)
    }

    @Test
    fun titlesAreCleaned() {
        val m = manager(MemBookmarkStore())
        m.add("https://a.example/", "  Hello\n\u202Eworld  ")
        assertEquals("Hello world", m.entries.value[0].title)
    }

    @Test
    fun fullListRefusesAddsAndImports() {
        val m = manager(MemBookmarkStore(stored(MAX_BOOKMARKS)))
        assertEquals(MAX_BOOKMARKS, m.entries.value.size)
        assertEquals(AddResult.FULL, m.add("https://new.example/", "n"))
        // An existing URL is still reported as existing, not as full.
        assertEquals(AddResult.EXISTS, m.add("https://s1.example/", "n"))
        val result = m.importAll(ParsedBookmarks(listOf(ParsedBookmark("https://new.example/", "n", null)), skipped = 0))
        assertEquals(ImportResult(0, 1), result)
        assertEquals(MAX_BOOKMARKS, m.entries.value.size)
    }

    @Test
    fun importThatPartlyFitsCountsTheOverflowAsSkipped() {
        val m = manager(MemBookmarkStore(stored(MAX_BOOKMARKS - 1)))
        val parsed = ParsedBookmarks(
            listOf(
                ParsedBookmark("https://n1.example/", "n1", null),
                ParsedBookmark("https://n2.example/", "n2", null),
                ParsedBookmark("https://n3.example/", "n3", null),
            ),
            skipped = 4,
        )
        assertEquals(ImportResult(1, 6), m.importAll(parsed))
        assertEquals(MAX_BOOKMARKS, m.entries.value.size)
    }

    @Test
    fun removeByIdAndByUrl() {
        val store = MemBookmarkStore()
        val m = manager(store)
        m.add("https://a.example/", "A")
        m.add("https://b.example/", "B")
        m.add("https://c.example/", "C")
        m.remove(m.entries.value.first { it.url == "https://b.example/" }.id)
        assertEquals(listOf("https://c.example/", "https://a.example/"), urls(m))
        m.removeByUrl("https://a.example/")
        assertEquals(listOf("https://c.example/"), urls(m))
        assertFalse(m.isBookmarked("https://a.example/"))
        m.remove(9_999)
        m.removeByUrl("https://nothing.example/")
        settle()
        assertEquals(listOf("https://c.example/"), store.saves.last().map { it.url })
    }

    @Test
    fun updateKeepsIdAndPosition() {
        val m = manager(MemBookmarkStore())
        m.add("https://a.example/", "A")
        m.add("https://b.example/", "B")
        val a = m.entries.value[1]
        assertEquals(UpdateResult.UPDATED, m.update(a.id, "New A", "https://a2.example/"))
        val after = m.entries.value[1]
        assertEquals(a.id, after.id)
        assertEquals("New A", after.title)
        assertEquals("https://a2.example/", after.url)
        assertEquals(a.createdAt, after.createdAt)
        assertTrue(m.isBookmarked("https://a2.example/"))
        assertFalse(m.isBookmarked("https://a.example/"))
        // Saving the same address again (title-only edit) is fine.
        assertEquals(UpdateResult.UPDATED, m.update(a.id, "Title only", "https://a2.example/"))
        assertEquals("Title only", m.entries.value[1].title)
    }

    @Test
    fun updateErrors() {
        val m = manager(MemBookmarkStore())
        m.add("https://a.example/", "A")
        m.add("https://b.example/", "B")
        val a = m.entries.value[1]
        assertEquals(UpdateResult.DUPLICATE, m.update(a.id, "A", "https://b.example/"))
        assertEquals(UpdateResult.INVALID, m.update(a.id, "A", "javascript:alert(1)"))
        assertEquals(UpdateResult.INVALID, m.update(a.id, "A", "not a url"))
        assertEquals(UpdateResult.MISSING, m.update(12345, "A", "https://z.example/"))
        assertEquals("https://a.example/", m.entries.value[1].url)
    }

    @Test
    fun emptyTitleOnUpdateFallsBackToTheUrl() {
        val m = manager(MemBookmarkStore())
        m.add("https://a.example/", "A")
        assertEquals(UpdateResult.UPDATED, m.update(m.entries.value[0].id, "   ", "https://a.example/"))
        assertEquals("https://a.example/", m.entries.value[0].title)
    }

    @Test
    fun everyMutationIsWrittenAtOnce() {
        val store = MemBookmarkStore()
        val m = manager(store)
        m.add("https://a.example/", "A")
        settle()
        assertEquals(1, store.saves.size)
        m.remove(m.entries.value[0].id)
        settle()
        assertEquals(2, store.saves.size)
        assertEquals(emptyList<Bookmark>(), store.saves.last())
    }

    @Test
    fun persistsAcrossANewManagerThroughTheFileStore() {
        val file = AtomicTextFile(File(tmp.root, "bookmarks/bookmarks.json"))
        val first = manager(FileBookmarkStore(file))
        first.add("https://a.example/", "A")
        first.add("https://b.example/", "B")
        first.flush()
        settle()
        val second = manager(FileBookmarkStore(file))
        assertEquals(listOf("https://b.example/", "https://a.example/"), urls(second))
        // New ids never collide with stored ones.
        second.add("https://c.example/", "C")
        assertEquals(3, second.entries.value.map { it.id }.toSet().size)
    }

    @Test
    fun importAddsNewAndSkipsKnownAndRepeated() {
        val m = manager(MemBookmarkStore())
        m.add("https://a.example/", "Mine")
        val parsed = ParsedBookmarks(
            listOf(
                ParsedBookmark("https://a.example/", "Theirs", 5_000),
                ParsedBookmark("https://n.example/", "N", 5_000_000),
                ParsedBookmark("https://n.example/", "N again", null),
                ParsedBookmark("https://m.example/", "", null),
            ),
            skipped = 2,
        )
        assertEquals(ImportResult(2, 4), m.importAll(parsed))
        assertEquals("Mine", m.entries.value.first { it.url == "https://a.example/" }.title)
        assertEquals(5_000_000L, m.entries.value.first { it.url == "https://n.example/" }.createdAt)
        val imported = m.entries.value.first { it.url == "https://m.example/" }
        assertEquals("https://m.example/", imported.title)
        assertEquals(clock, imported.createdAt)
        assertEquals(3, m.entries.value.map { it.id }.toSet().size)
    }

    @Test
    fun searchFiltersTitleAndUrlCaseInsensitively() {
        val list = listOf(
            Bookmark(1, "https://Kotlin.example/docs", "Language", 1),
            Bookmark(2, "https://b.example/", "Coffee BREWING", 2),
            Bookmark(3, "https://c.example/", "Other", 3),
        )
        assertEquals(list, filterBookmarks(list, ""))
        assertEquals(list, filterBookmarks(list, "   "))
        assertEquals(listOf(1L), filterBookmarks(list, "KOTLIN").map { it.id })
        assertEquals(listOf(2L), filterBookmarks(list, " brewing ").map { it.id })
        assertEquals(listOf(1L, 2L, 3L), filterBookmarks(list, "example").map { it.id })
        assertEquals(emptyList<Bookmark>(), filterBookmarks(list, "zzz"))
    }

    // ---- operations before the stored list has arrived ---------------------------------------------------------

    @Test
    fun nothingIsWrittenBeforeTheLoadFinished() {
        val store = MemBookmarkStore(stored(2))
        val m = manager(store, load = false)
        m.add("https://a.example/", "A")
        m.flush()
        settle()
        assertTrue(store.saves.isEmpty())
        // load() started but not yet run: still nothing.
        m.load()
        m.add("https://b.example/", "B")
        m.flush()
        assertTrue(store.saves.isEmpty())
        settle()
        assertEquals(1, store.loads)
        // After the load, one write holds the stored list plus the queued adds.
        assertEquals(1, store.saves.size)
        assertEquals(
            setOf("https://s1.example/", "https://s2.example/", "https://a.example/", "https://b.example/"),
            store.saves.last().map { it.url }.toSet(),
        )
    }

    @Test
    fun loadRunsOnce() {
        val store = MemBookmarkStore(stored(1))
        val m = manager(store)
        m.load()
        settle()
        assertEquals(1, store.loads)
    }

    @Test
    fun addBeforeLoadIsKeptAndTheStoredOneWinsOnConflict() {
        val store = MemBookmarkStore(listOf(Bookmark(7, "https://a.example/", "Stored title", 5)))
        val m = manager(store, load = false)
        m.load()
        assertEquals(AddResult.ADDED, m.add("https://a.example/", "Early title"))
        assertEquals(AddResult.ADDED, m.add("https://new.example/", "New"))
        settle()
        assertEquals(2, m.entries.value.size)
        val a = m.entries.value.first { it.url == "https://a.example/" }
        assertEquals("Stored title", a.title)
        assertEquals(7L, a.id)
        assertTrue(m.entries.value.any { it.url == "https://new.example/" })
        assertEquals(2, m.entries.value.map { it.id }.toSet().size)
    }

    @Test
    fun removeBeforeLoadRemovesTheStoredBookmark() {
        val store = MemBookmarkStore(stored(3))
        val m = manager(store, load = false)
        m.load()
        m.removeByUrl("https://s2.example/")
        m.remove(404)
        settle()
        assertEquals(listOf("https://s1.example/", "https://s3.example/"), urls(m).sorted())
        assertEquals(2, store.saves.last().size)
    }

    @Test
    fun removingAnEarlyAddByIdBeforeLoadCancelsIt() {
        val store = MemBookmarkStore(stored(1))
        val m = manager(store, load = false)
        m.load()
        m.add("https://early.example/", "E")
        m.remove(m.entries.value.first { it.url == "https://early.example/" }.id)
        settle()
        assertEquals(listOf("https://s1.example/"), urls(m))
    }

    @Test
    fun importBeforeLoadIsReplayedOnTheStoredList() {
        val store = MemBookmarkStore(stored(2))
        val m = manager(store, load = false)
        m.load()
        val parsed = ParsedBookmarks(
            listOf(
                ParsedBookmark("https://s1.example/", "dup of stored", null),
                ParsedBookmark("https://fresh.example/", "Fresh", null),
            ),
            skipped = 0,
        )
        m.importAll(parsed)
        settle()
        assertEquals(3, m.entries.value.size)
        assertEquals("S1", m.entries.value.first { it.url == "https://s1.example/" }.title)
        assertEquals("https://fresh.example/", m.entries.value[0].url)
        assertEquals(3, m.entries.value.map { it.id }.toSet().size)
    }

    @Test
    fun updateBeforeLoadIsReplayedByUrl() {
        val store = MemBookmarkStore(stored(0))
        val m = manager(store, load = false)
        m.load()
        m.add("https://a.example/", "A")
        m.update(m.entries.value[0].id, "A2", "https://a2.example/")
        settle()
        assertEquals(listOf("https://a2.example/"), urls(m))
        assertEquals("A2", m.entries.value[0].title)
    }

    // ---- clear all ---------------------------------------------------------------------------------------------

    @Test
    fun clearAllEmptiesTheListFreesTheUrlsAndWritesAtOnce() {
        val store = MemBookmarkStore(stored(3))
        val m = manager(store)
        m.clearAll()
        assertTrue(m.entries.value.isEmpty())
        assertFalse(m.isBookmarked("https://s1.example/"))
        settle()
        assertEquals(1, store.saves.size)
        assertTrue(store.data.isEmpty())
        // The same URL can be bookmarked again afterwards.
        assertEquals(AddResult.ADDED, m.add("https://s1.example/", "again"))
        settle()
        assertEquals(listOf("https://s1.example/"), store.data.map { it.url })
    }

    @Test
    fun onlyClearAllDiscardsTheSetAsideCopyAndOnlyAfterItsWriteSucceeded() {
        val store = MemBookmarkStore(stored(2))
        val m = manager(store)
        m.remove(m.entries.value.first().id)
        m.add("https://n.example/", "N")
        settle()
        assertEquals("ordinary changes keep the copy", 0, store.discards)
        store.failSave = true
        m.clearAll()
        settle()
        assertEquals("a failed write discards nothing", 0, store.discards)
        store.failSave = false
        m.flush()
        settle()
        assertEquals("the next successful write after the clear does", 1, store.discards)
        m.add("https://later.example/", "L")
        settle()
        assertEquals("once only", 1, store.discards)
    }

    @Test
    fun clearAllBeforeLoadDiscardsAfterTheReplayedWrite() {
        val store = MemBookmarkStore(stored(2))
        val m = manager(store, load = false)
        m.load()
        m.clearAll()
        assertEquals(0, store.discards)
        settle()
        assertEquals(1, store.discards)
        assertTrue(store.data.isEmpty())
    }

    @Test
    fun clearAllOnAnEmptyListStillWritesTheEmptyList() {
        val store = MemBookmarkStore()
        val m = manager(store)
        m.clearAll()
        settle()
        assertEquals(1, store.saves.size)
        assertTrue(store.saves.single().isEmpty())
    }

    @Test
    fun clearAllBeforeLoadWipesTheStoredListAndLaterEarlyChanges() {
        val store = MemBookmarkStore(stored(3))
        val m = manager(store, load = false)
        m.load()
        m.add("https://early.example/", "before the clear")
        m.clearAll()
        m.add("https://after.example/", "after the clear")
        // Nothing may be written until the stored list has been read.
        m.flush()
        assertTrue(store.saves.isEmpty())
        settle()
        assertEquals(listOf("https://after.example/"), urls(m))
        assertEquals(listOf("https://after.example/"), store.data.map { it.url })
        assertEquals(1, store.saves.size)
    }

    @Test
    fun clearAllBeforeLoadWithNothingElseLeavesAnEmptyStore() {
        val store = MemBookmarkStore(stored(2))
        val m = manager(store, load = false)
        m.load()
        m.clearAll()
        assertTrue(m.entries.value.isEmpty())
        settle()
        assertTrue(m.entries.value.isEmpty())
        assertTrue(store.data.isEmpty())
        assertFalse(m.isBookmarked("https://s1.example/"))
    }

    // ---- logging -----------------------------------------------------------------------------------------------

    @Test
    fun logsNeverContainUrlsOrTitles() {
        val store = MemBookmarkStore(listOf(Bookmark(1, "https://secret-stored.example/", "Secret Stored", 1)))
        store.failSave = true
        val m = manager(store)
        m.add("https://secret-page.example/path?token=abc", "Secret Page Title")
        m.update(m.entries.value[0].id, "Another Secret", "https://secret-two.example/")
        m.importAll(ParsedBookmarks(listOf(ParsedBookmark("https://secret-imp.example/", "Secret Imp", null)), 0))
        m.remove(m.entries.value[0].id)
        settle()
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.any { it.contains("IOException") })
        logs.forEach {
            assertFalse(it, it.contains("secret", ignoreCase = true))
            assertFalse(it, it.contains("example"))
            assertFalse(it, it.contains("token"))
        }
    }

    @Test
    fun aFailingLoadStartsEmptyAndLogsOnlyTheClassName() {
        val store = MemBookmarkStore(stored(2))
        store.failLoad = true
        val m = manager(store)
        assertEquals(emptyList<Bookmark>(), m.entries.value)
        assertTrue(logs.any { it.contains("IOException") })
        assertEquals(AddResult.ADDED, m.add("https://a.example/", "A"))
    }
}
