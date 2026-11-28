package com.shibi.orbis.tabs

import android.view.View
import com.shibi.orbis.engine.BrowserSession
import com.shibi.orbis.engine.MediaState
import com.shibi.orbis.engine.SessionHost
import com.shibi.orbis.engine.SessionInit
import com.shibi.orbis.engine.SessionState
import com.shibi.orbis.engine.SitePrompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class LookedFakeSession(val init: SessionInit) : BrowserSession {
    override val isPrivate get() = init.isPrivate
    val _state = MutableStateFlow(SessionState())
    override val state: StateFlow<SessionState> = _state
    override val prompts: StateFlow<SitePrompt?> = MutableStateFlow(null)
    override var host: SessionHost? = null

    override fun attachTo(contentView: View) = Unit
    override fun detachFrom(contentView: View) = Unit
    override fun loadUrl(url: String) = Unit
    override fun loadExternalUrl(url: String) = Unit
    override fun setDesktopMode(enabled: Boolean) = Unit
    override fun goBack() = false
    override fun goForward() = false
    override fun reload() = Unit
    override fun stop() = Unit
    override fun find(query: String, backwards: Boolean) = Unit
    override fun clearFind() = Unit
    override fun setActive(active: Boolean) = Unit
    override fun savedState(): String? = null
    override fun close() = Unit
}

private class LookedMemoryStore(var snapshot: TabsSnapshot? = null) : TabStore {
    override fun load(): TabsSnapshot? = snapshot
    override fun save(snapshot: TabsSnapshot) { this.snapshot = snapshot }
}

/** When each tab was last looked at (for the dashboard's tab list), its states, and the counts for the memory history. */
@OptIn(ExperimentalCoroutinesApi::class)
class TabLookedAtTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var now = 1_000_000L
    private val sessions = mutableListOf<LookedFakeSession>()

    @After
    fun tearDown() = scope.cancel()

    private fun manager(store: LookedMemoryStore = LookedMemoryStore()): TabManager {
        val m = TabManager(
            factory = { LookedFakeSession(it).also { s -> sessions += s } },
            store = store,
            scope = scope,
            log = {},
            io = dispatcher,
            clock = { now },
        )
        m.restore()
        scheduler.advanceUntilIdle()
        m.openSelected()
        return m
    }

    private fun TabManager.lookedAt(id: Long) = overview().first { it.id == id }.lastLookedAt

    private fun TabManager.save() {
        flush()
        scheduler.advanceUntilIdle()
    }

    @Test
    fun aTabIsStampedWhenItLeavesTheScreenAndTheOneOnScreenIsNow() {
        val m = manager()
        val first = m.selected.value.id
        now = 2_000_000L
        val second = m.newTab()!!
        assertEquals("the tab that left the screen keeps the time it left", 2_000_000L, m.lookedAt(first))
        now = 5_000_000L
        assertEquals("the tab on screen is looked at now", 5_000_000L, m.lookedAt(second))
        m.select(first)
        assertEquals(5_000_000L, m.lookedAt(second))
    }

    @Test
    fun nothingIsStampedWhileOrbisIsInTheBackground() {
        val m = manager()
        val first = m.selected.value.id
        val second = m.newTab()!!
        now = 2_000_000L
        m.setForeground(false)
        assertEquals("going to the background is the last look", 2_000_000L, m.lookedAt(second))
        now = 9_000_000L
        // A tab selected while in the background (a link from another app, for example) was not looked at.
        m.select(first)
        assertEquals(1_000_000L, m.lookedAt(first))
        assertEquals(2_000_000L, m.lookedAt(second))
        now = 10_000_000L
        m.setForeground(true)
        assertEquals(10_000_000L, m.lookedAt(first))
    }

    @Test
    fun theTimeSurvivesARestartForNormalTabsOnly() {
        val store = LookedMemoryStore()
        val m = manager(store)
        val normal = m.selected.value.id
        now = 3_000_000L
        val private = m.newPrivateTab()!!
        now = 4_000_000L
        m.select(normal)
        m.save()
        val stored = store.snapshot!!
        assertEquals(listOf(normal), stored.tabs.map { it.id })
        assertEquals(4_000_000L, stored.tabs.single().lastLookedAt)
        assertFalse(TabCodec.encode(stored).contains("\"looked\":3000000"))
        // Restart: a fresh manager in the background reads the stored time back.
        val again = TabManager({ LookedFakeSession(it) }, LookedMemoryStore(TabCodec.decode(TabCodec.encode(stored))), scope, {}, dispatcher, clock = { now })
        again.setForeground(false)
        again.restore()
        scheduler.advanceUntilIdle()
        assertEquals(4_000_000L, again.lookedAt(normal))
        assertTrue(again.overview().none { it.id == private })
    }

    @Test
    fun anOldStoredFileWithoutTimesStillLoadsAndItsTabsCountAsNotLookedAt() {
        val old = """{"version":2,"selected":1,"groups":[],"tabs":[""" +
            """{"id":1,"url":"https://a.example/","title":"A","desktop":false},""" +
            """{"id":2,"url":"https://b.example/","title":"B","desktop":false}]}"""
        val snapshot = TabCodec.decode(old)!!
        assertNull(snapshot.tabs[1].lastLookedAt)
        val m = TabManager({ LookedFakeSession(it) }, LookedMemoryStore(snapshot), scope, {}, dispatcher, clock = { now })
        m.restore()
        scheduler.advanceUntilIdle()
        assertEquals(listOf(1L, 2L), m.overview().map { it.id })
        assertNull(m.lookedAt(2))
        val v1 = """{"selected":1,"tabs":[{"id":1,"url":"https://a.example/","title":"A"}]}"""
        assertNull(TabCodec.decode(v1)!!.tabs.single().lastLookedAt)
    }

    @Test
    fun theCodecWritesTheTimeAndReadsItBack() {
        val snap = TabsSnapshot(listOf(PersistedTab(1, "https://a.example/", "A", false, null, lastLookedAt = 123_456L)), 1)
        assertEquals(123_456L, TabCodec.decode(TabCodec.encode(snap))!!.tabs.single().lastLookedAt)
    }

    @Test
    fun theBackupCarriesNoTime() {
        val m = manager()
        m.save()
        assertTrue(m.normalTabsForBackup().tabs.all { it.lastLookedAt == null })
    }

    @Test
    fun statesAreAsleepAwakeOrPlayingAndAPrivateTabHasNoTitle() {
        val stored = TabsSnapshot(
            listOf(PersistedTab(1, "https://a.example/", "A", false, null), PersistedTab(2, "https://b.example/", "Asleep tab", false, null)),
            1,
        )
        val m = manager(LookedMemoryStore(stored))
        val private = m.newPrivateTab()!!
        sessions.last()._state.value = SessionState(url = "https://secret.example/", title = "Secret title", media = MediaState(playing = true))
        scheduler.runCurrent()
        val rows = m.overview().associateBy { it.id }
        assertEquals(TabActivity.AWAKE, rows.getValue(1).activity)
        assertEquals(TabActivity.ASLEEP, rows.getValue(2).activity)
        assertEquals(TabActivity.PLAYING, rows.getValue(private).activity)
        assertTrue(rows.getValue(private).isPrivate)
        assertEquals("", rows.getValue(private).title)
        assertEquals("Asleep tab", rows.getValue(2).title)
    }

    @Test
    fun theCountsLeavePrivateTabsOut() {
        val stored = TabsSnapshot(
            listOf(PersistedTab(1, "https://a.example/", "A", false, null), PersistedTab(2, "https://b.example/", "B", false, null)),
            1,
        )
        val m = manager(LookedMemoryStore(stored))
        m.newPrivateTab()
        m.newPrivateTab()
        assertEquals(NormalTabCounts(open = 2, awake = 1), m.normalTabCounts())
    }

    // ---- Undo of "Close all tabs" gives back each tab's "last looked at" ----

    /** Three tabs looked at at 2 s, 3 s and (the one on screen, the third) later. Returns the manager and the ids. */
    private fun threeTabs(store: LookedMemoryStore = LookedMemoryStore()): Pair<TabManager, List<Long>> {
        val m = manager(store)
        val a = m.selected.value.id
        now = 2_000_000L
        val b = m.newTab()!!
        now = 3_000_000L
        val c = m.newTab()!!
        assertEquals(2_000_000L, m.lookedAt(a))
        assertEquals(3_000_000L, m.lookedAt(b))
        return m to listOf(a, b, c)
    }

    @Test
    fun undoGivesBackWhenEachTabWasLastLookedAt() {
        try {
            val (m, ids) = threeTabs()
            val (a, b, c) = ids
            now = 4_000_000L
            assertEquals(3, m.closeAllNormalUndoable())
            now = 4_003_000L
            scheduler.advanceTimeBy(3_000L)
            assertTrue(m.undoCloseAll())
            assertEquals(listOf(a, b, c), m.overview().map { it.id })
            assertEquals("a tab that was not on screen has the time it had", 2_000_000L, m.lookedAt(a))
            assertEquals("a tab that was not on screen has the time it had", 3_000_000L, m.lookedAt(b))
            assertTrue("the tab that was on screen has the time of the close or later", m.lookedAt(c)!! >= 4_000_000L)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun afterUndoTheStoredTabListHoldsTheTimes() {
        try {
            val store = LookedMemoryStore()
            val (m, ids) = threeTabs(store)
            val (a, b, _) = ids
            now = 4_000_000L
            assertEquals(3, m.closeAllNormalUndoable())
            now = 4_003_000L
            scheduler.advanceTimeBy(3_000L)
            assertTrue(m.undoCloseAll())
            m.save()
            val stored = store.snapshot!!.tabs.associate { it.id to it.lastLookedAt }
            assertEquals(2_000_000L, stored[a])
            assertEquals(3_000_000L, stored[b])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aTabThatDoesNotComeBackGetsNoTime() {
        try {
            val store = LookedMemoryStore()
            val m = TabManager(
                factory = { LookedFakeSession(it).also { s -> sessions += s } },
                store = store,
                scope = scope,
                log = {},
                io = dispatcher,
                clock = { now },
                maxTabs = 5,
            )
            m.restore()
            scheduler.advanceUntilIdle()
            m.openSelected()
            val a = m.selected.value.id
            now = 2_000_000L
            val b = m.newTab()!!
            now = 3_000_000L
            val c = m.newTab()!!
            m.newPrivateTab()!!
            m.newPrivateTab()!!
            now = 4_000_000L
            assertEquals(3, m.closeAllNormalUndoable())
            // Room for two of the three only: one more private tab takes a place while Undo is open.
            m.newPrivateTab()!!
            now = 4_003_000L
            scheduler.advanceTimeBy(3_000L)
            assertTrue(m.undoCloseAll())
            val back = m.overview().map { it.id }
            assertTrue(a in back && b in back)
            assertFalse(c in back)
            assertEquals(2_000_000L, m.lookedAt(a))
            assertEquals(3_000_000L, m.lookedAt(b))
            assertTrue(m.overview().none { it.id == c })
        } finally {
            scope.cancel()
        }
    }
}
