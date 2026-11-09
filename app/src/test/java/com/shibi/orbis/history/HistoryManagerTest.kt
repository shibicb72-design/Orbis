package com.shibi.orbis.history

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

private class MemHistoryStore(var data: List<HistoryEntry> = emptyList()) : HistoryStore {
    val saves = mutableListOf<List<HistoryEntry>>()
    var failLoad = false
    var failSave = false
    var loads = 0

    override fun load(): List<HistoryEntry> {
        loads++
        if (failLoad) throw IOException("boom")
        return data
    }

    override fun save(items: List<HistoryEntry>) {
        if (failSave) throw IOException("boom")
        saves += items
        data = items
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val logs = mutableListOf<String>()
    private var clock = 1_000L

    @After
    fun tearDown() = scope.cancel()

    private fun manager(store: HistoryStore, load: Boolean = true): HistoryManager {
        val m = HistoryManager(store = store, scope = scope, log = { logs += it }, now = { clock }, io = dispatcher)
        if (load) {
            m.load()
            scheduler.advanceUntilIdle()
        }
        return m
    }

    private fun settle() = scheduler.advanceUntilIdle()

    private fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    private fun urls(m: HistoryManager) = m.entries.value.map { it.url }

    private fun stored(n: Int) =
        (1..n).map { HistoryEntry("https://s$it.example/", "S$it", (n - it + 1).toLong()) }

    @Test
    fun recordsNewestFirst() {
        val m = manager(MemHistoryStore())
        m.recordVisit("https://a.example/", "A")
        clock = 2_000
        m.recordVisit("https://b.example/", "B")
        assertEquals(listOf("https://b.example/", "https://a.example/"), urls(m))
        assertEquals(2_000L, m.entries.value[0].visitedAt)
        assertEquals("A", m.entries.value[1].title)
    }

    @Test
    fun aRevisitMovesTheRowToTheTopWithTheNewTime() {
        val m = manager(MemHistoryStore())
        m.recordVisit("https://a.example/", "A")
        m.recordVisit("https://b.example/", "B")
        clock = 5_000
        m.recordVisit("https://a.example/", "A new")
        assertEquals(listOf("https://a.example/", "https://b.example/"), urls(m))
        assertEquals(5_000L, m.entries.value[0].visitedAt)
        assertEquals("A new", m.entries.value[0].title)
        assertEquals(2, m.entries.value.size)
    }

    @Test
    fun revisitsAreCountedForTopSites() {
        val m = manager(MemHistoryStore())
        m.recordVisit("https://a.example/", "A")
        m.recordVisit("https://b.example/", "B")
        m.recordVisit("https://a.example/", "A")
        m.recordVisit("https://a.example/", "")
        assertEquals(3, m.entries.value.first { it.url == "https://a.example/" }.visits)
        assertEquals(1, m.entries.value.first { it.url == "https://b.example/" }.visits)
        m.delete("https://a.example/")
        m.recordVisit("https://a.example/", "A")
        assertEquals("a deleted row starts over", 1, m.entries.value.first { it.url == "https://a.example/" }.visits)
    }

    @Test
    fun aBlankTitleKeepsTheOlderOne() {
        val m = manager(MemHistoryStore())
        m.recordVisit("https://a.example/", "Old title")
        m.recordVisit("https://a.example/", "   ")
        assertEquals("Old title", m.entries.value[0].title)
        m.recordVisit("https://b.example/", "")
        assertEquals("", m.entries.value[0].title)
    }

    @Test
    fun refusesUrlsThatAreNotStorable() {
        val store = MemHistoryStore()
        val m = manager(store)
        listOf(
            "javascript:alert(1)",
            "data:text/html,x",
            "file:///sdcard/x",
            "about:blank",
            "https://user@evil.example/",
            "https://white space.example/",
            "https://",
            "",
            "https://long.example/" + "x".repeat(5000),
        ).forEach { m.recordVisit(it, "t") }
        advance(10_000)
        assertEquals(emptyList<HistoryEntry>(), m.entries.value)
        assertTrue(store.saves.isEmpty())
    }

    @Test
    fun theCapEvictsTheOldestRows() {
        val m = manager(MemHistoryStore(stored(MAX_HISTORY_ENTRIES)))
        assertEquals(MAX_HISTORY_ENTRIES, m.entries.value.size)
        val oldest = m.entries.value.last().url
        m.recordVisit("https://new.example/", "new")
        assertEquals(MAX_HISTORY_ENTRIES, m.entries.value.size)
        assertEquals("https://new.example/", m.entries.value[0].url)
        assertFalse(urls(m).contains(oldest))
        // Revisiting an existing row does not evict anything.
        m.recordVisit("https://s1.example/", "again")
        assertEquals(MAX_HISTORY_ENTRIES, m.entries.value.size)
    }

    @Test
    fun updateTitleChangesAnExistingRowOnly() {
        val m = manager(MemHistoryStore())
        m.recordVisit("https://a.example/", "")
        m.recordVisit("https://b.example/", "B")
        val before = m.entries.value.first { it.url == "https://a.example/" }
        clock = 9_000
        m.updateTitle("https://a.example/", "  A title \n")
        assertEquals(listOf("https://b.example/", "https://a.example/"), urls(m))
        val after = m.entries.value.first { it.url == "https://a.example/" }
        assertEquals("A title", after.title)
        assertEquals(before.visitedAt, after.visitedAt)
        // No row is created for an unknown URL, and blank titles are ignored.
        m.updateTitle("https://never-visited.example/", "X")
        m.updateTitle("https://a.example/", "   ")
        assertEquals(2, m.entries.value.size)
        assertEquals("A title", m.entries.value.first { it.url == "https://a.example/" }.title)
    }

    @Test
    fun visitsAreWrittenAfterTheQuietPeriodAndOnFlush() {
        val store = MemHistoryStore()
        val m = manager(store)
        m.recordVisit("https://a.example/", "A")
        advance(1_999)
        assertTrue(store.saves.isEmpty())
        advance(2)
        assertEquals(1, store.saves.size)
        // A second visit within the period postpones the write.
        m.recordVisit("https://b.example/", "B")
        advance(1_500)
        m.recordVisit("https://c.example/", "C")
        advance(1_500)
        assertEquals(1, store.saves.size)
        advance(600)
        assertEquals(2, store.saves.size)
        assertEquals(3, store.saves.last().size)
        m.recordVisit("https://d.example/", "D")
        m.flush()
        settle()
        assertEquals(4, store.saves.last().size)
    }

    @Test
    fun updateTitleIsDebouncedToo() {
        val store = MemHistoryStore(listOf(HistoryEntry("https://a.example/", "", 1)))
        val m = manager(store)
        m.updateTitle("https://a.example/", "Now titled")
        advance(1_000)
        assertTrue(store.saves.isEmpty())
        advance(1_100)
        assertEquals("Now titled", store.saves.last()[0].title)
    }

    @Test
    fun deleteWritesAtOnceAndSurvivesAPendingDebounce() {
        val store = MemHistoryStore()
        val m = manager(store)
        m.recordVisit("https://a.example/", "A")
        m.recordVisit("https://b.example/", "B")
        m.delete("https://a.example/")
        settle()
        // The delete wrote at once, and the cancelled debounce does not write the old state again.
        assertEquals(1, store.saves.size)
        assertEquals(listOf("https://b.example/"), store.saves.last().map { it.url })
        advance(10_000)
        assertEquals(1, store.saves.size)
        assertEquals(listOf("https://b.example/"), store.data.map { it.url })
    }

    @Test
    fun clearAllWritesAtOnceAndNothingComesBack() {
        val store = MemHistoryStore(stored(3))
        val m = manager(store)
        m.recordVisit("https://new.example/", "new")
        m.clearAll()
        settle()
        assertEquals(emptyList<HistoryEntry>(), m.entries.value)
        assertEquals(1, store.saves.size)
        assertEquals(emptyList<HistoryEntry>(), store.saves.last())
        advance(10_000)
        assertEquals(1, store.saves.size)
        assertEquals(emptyList<HistoryEntry>(), store.data)
    }

    @Test
    fun deletingAnUnknownUrlWritesNothing() {
        val store = MemHistoryStore(stored(2))
        val m = manager(store)
        m.delete("https://nothing.example/")
        settle()
        assertTrue(store.saves.isEmpty())
    }

    // ---- before the stored list has arrived --------------------------------------------------------------------

    @Test
    fun nothingIsWrittenBeforeTheLoadFinished() {
        val store = MemHistoryStore(stored(2))
        val m = manager(store, load = false)
        m.recordVisit("https://early.example/", "E")
        m.flush()
        m.delete("https://zzz.example/")
        advance(10_000)
        assertTrue(store.saves.isEmpty())
        m.load()
        m.flush()
        assertTrue(store.saves.isEmpty())
        settle()
        assertEquals(1, store.loads)
        advance(10_000)
        assertEquals(1, store.saves.size)
        assertEquals("https://early.example/", store.saves.last()[0].url)
        assertEquals(3, store.saves.last().size)
    }

    @Test
    fun clearBeforeLoadWipesTheStoredData() {
        val store = MemHistoryStore(stored(3))
        val m = manager(store, load = false)
        m.load()
        m.clearAll()
        settle()
        assertEquals(emptyList<HistoryEntry>(), m.entries.value)
        assertEquals(emptyList<HistoryEntry>(), store.data)
        assertEquals(1, store.saves.size)
    }

    @Test
    fun visitAfterAClearBeforeLoadSurvivesOnItsOwn() {
        val store = MemHistoryStore(stored(3))
        val m = manager(store, load = false)
        m.load()
        m.recordVisit("https://before.example/", "b")
        m.clearAll()
        m.recordVisit("https://after.example/", "a")
        settle()
        advance(5_000)
        assertEquals(listOf("https://after.example/"), urls(m))
        assertEquals(listOf("https://after.example/"), store.data.map { it.url })
    }

    @Test
    fun deleteBeforeLoadRemovesTheStoredRow() {
        val store = MemHistoryStore(stored(3))
        val m = manager(store, load = false)
        m.load()
        m.delete("https://s2.example/")
        settle()
        assertEquals(listOf("https://s1.example/", "https://s3.example/"), urls(m))
        assertEquals(2, store.data.size)
    }

    @Test
    fun visitBeforeLoadIsKeptOnTopOfTheStoredRows() {
        val store = MemHistoryStore(stored(2))
        val m = manager(store, load = false)
        m.load()
        m.recordVisit("https://s2.example/", "")
        m.recordVisit("https://fresh.example/", "Fresh")
        m.updateTitle("https://s1.example/", "Renamed")
        settle()
        advance(5_000)
        assertEquals(listOf("https://fresh.example/", "https://s2.example/", "https://s1.example/"), urls(m))
        // The blank title kept what was stored.
        assertEquals("S2", m.entries.value[1].title)
        assertEquals("Renamed", m.entries.value[2].title)
        assertEquals(urls(m), store.data.map { it.url })
    }

    @Test
    fun reloadsFromTheStoreInANewManager() {
        val file = AtomicTextFile(File(tmp.root, "history/history.json"))
        val first = manager(FileHistoryStore(file))
        first.recordVisit("https://a.example/", "A")
        clock = 2_000
        first.recordVisit("https://b.example/", "B")
        first.flush()
        settle()
        val second = manager(FileHistoryStore(file))
        assertEquals(listOf("https://b.example/", "https://a.example/"), urls(second))
        assertEquals("B", second.entries.value[0].title)
        assertEquals(2_000L, second.entries.value[0].visitedAt)
    }

    // ---- search and logging -------------------------------------------------------------------------------------

    @Test
    fun searchFiltersTitleAndUrlCaseInsensitively() {
        val list = listOf(
            HistoryEntry("https://Kotlin.example/docs", "Language", 3),
            HistoryEntry("https://b.example/", "Coffee BREWING", 2),
            HistoryEntry("https://c.example/", "", 1),
        )
        assertEquals(list, filterHistory(list, ""))
        assertEquals(list, filterHistory(list, "  "))
        assertEquals(listOf("https://Kotlin.example/docs"), filterHistory(list, "kotlin").map { it.url })
        assertEquals(listOf("https://b.example/"), filterHistory(list, " brewing ").map { it.url })
        assertEquals(3, filterHistory(list, "EXAMPLE").size)
        assertEquals(emptyList<HistoryEntry>(), filterHistory(list, "nothing"))
    }

    @Test
    fun logsNeverContainUrlsOrTitles() {
        val store = MemHistoryStore(listOf(HistoryEntry("https://secret-stored.example/", "Secret Stored", 1)))
        store.failSave = true
        val m = manager(store)
        m.recordVisit("https://secret-page.example/path?token=abc", "Secret Page Title")
        m.updateTitle("https://secret-page.example/path?token=abc", "Another Secret")
        advance(5_000)
        m.delete("https://secret-page.example/path?token=abc")
        m.clearAll()
        settle()
        assertTrue(logs.any { it.contains("IOException") })
        logs.forEach {
            assertFalse(it, it.contains("secret", ignoreCase = true))
            assertFalse(it, it.contains("example"))
            assertFalse(it, it.contains("token"))
        }
    }

    @Test
    fun aFailingLoadStartsEmptyAndLogsOnlyTheClassName() {
        val store = MemHistoryStore(stored(2))
        store.failLoad = true
        val m = manager(store)
        assertEquals(emptyList<HistoryEntry>(), m.entries.value)
        assertTrue(logs.any { it.contains("IOException") })
        m.recordVisit("https://a.example/", "A")
        assertEquals(1, m.entries.value.size)
    }
}
