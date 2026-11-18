package com.shibi.orbis.history

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

private class ImportStore(var data: List<HistoryEntry> = emptyList()) : HistoryStore {
    override fun load() = data

    override fun save(items: List<HistoryEntry>) {
        data = items
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryImportTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @After
    fun tearDown() = scope.cancel()

    private fun manager(store: ImportStore, load: Boolean = true): HistoryManager {
        val m = HistoryManager(store, scope, {}, { 9_999L }, dispatcher, debounceMillis = 10L)
        if (load) {
            m.load()
            scheduler.advanceUntilIdle()
        }
        return m
    }

    @Test(timeout = 10_000)
    fun anAddressAlreadyPresentAddsNothingAndIsNotReDated() {
        val store = ImportStore(listOf(HistoryEntry("https://a.example/", "A", 500L, 7)))
        val m = manager(store)
        assertEquals(0, m.importEntries(listOf(HistoryEntry("https://a.example/", "New title", 9_000L, 1))))
        scheduler.advanceUntilIdle()
        assertEquals(listOf(HistoryEntry("https://a.example/", "A", 500L, 7)), m.entries.value)
        assertEquals(m.entries.value, store.data)
    }

    @Test(timeout = 10_000)
    fun rowsKeepTheirOwnTimeAndCountNewestFirstAndArePersisted() {
        val store = ImportStore(listOf(HistoryEntry("https://mid.example/", "M", 500L, 2)))
        val m = manager(store)
        val added =
            m.importEntries(
                listOf(
                    HistoryEntry("https://old.example/", "O", 100L, 4),
                    HistoryEntry("https://new.example/", "N‮\nline", 900L, 3),
                    HistoryEntry("javascript:alert(1)", "J", 950L, 1),
                    HistoryEntry("https://new.example/", "again", 990L, 1),
                ),
            )
        scheduler.advanceUntilIdle()
        assertEquals(2, added)
        assertEquals(
            listOf(
                HistoryEntry("https://new.example/", "N line", 900L, 3),
                HistoryEntry("https://mid.example/", "M", 500L, 2),
                HistoryEntry("https://old.example/", "O", 100L, 4),
            ),
            m.entries.value,
        )
        assertEquals(m.entries.value, store.data)
    }

    @Test(timeout = 10_000)
    fun theCapDropsTheOldestRows() {
        val existing = (1..MAX_HISTORY_ENTRIES).map { HistoryEntry("https://e$it.example/", "", 1_000L + it, 1) }
        val m = manager(ImportStore(existing.reversed()))
        val added = m.importEntries(listOf(HistoryEntry("https://newest.example/", "", 1_000_000L, 1), HistoryEntry("https://ancient.example/", "", 1L, 1)))
        assertEquals(1, added)
        assertEquals(MAX_HISTORY_ENTRIES, m.entries.value.size)
        assertEquals("https://newest.example/", m.entries.value.first().url)
        assertEquals("https://e2.example/", m.entries.value.last().url)
    }

    @Test(timeout = 10_000)
    fun beforeTheLoadTheImportIsQueuedAndReplayedOnTheStoredList() {
        val store = ImportStore(listOf(HistoryEntry("https://stored.example/", "S", 700L, 5)))
        val m = manager(store, load = false)
        m.importEntries(listOf(HistoryEntry("https://stored.example/", "file", 800L, 1), HistoryEntry("https://fresh.example/", "F", 600L, 2)))
        m.load()
        scheduler.advanceUntilIdle()
        assertEquals(
            listOf(HistoryEntry("https://stored.example/", "S", 700L, 5), HistoryEntry("https://fresh.example/", "F", 600L, 2)),
            m.entries.value,
        )
        assertEquals(m.entries.value, store.data)
    }
}
