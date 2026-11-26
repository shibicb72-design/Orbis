package com.shibi.orbis.tabs

import android.view.View
import com.shibi.orbis.engine.BrowserSession
import com.shibi.orbis.engine.SessionHost
import com.shibi.orbis.engine.SessionInit
import com.shibi.orbis.engine.SessionState
import com.shibi.orbis.engine.SitePrompt
import com.shibi.orbis.ui.browser.canCloseAllTabs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class MoreFakeSession(val init: SessionInit) : BrowserSession {
    override val isPrivate get() = init.isPrivate
    val _state = MutableStateFlow(SessionState(desktopMode = init.desktopMode))
    override val state: StateFlow<SessionState> = _state
    override val prompts: StateFlow<SitePrompt?> = MutableStateFlow(null)
    override var host: SessionHost? = null
    var active: Boolean? = null
    var closed = false
    var saved: String? = null

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
    override fun setActive(active: Boolean) { this.active = active }
    override fun savedState(): String? = saved
    override fun close() { closed = true }
}

private class MoreMemoryStore(var snapshot: TabsSnapshot? = null) : TabStore {
    val saves = mutableListOf<TabsSnapshot>()
    override fun load(): TabsSnapshot? = snapshot
    override fun save(snapshot: TabsSnapshot) { saves += snapshot; this.snapshot = snapshot }
}

/**
 * "Close all tabs" with Undo (tabs24-1 part A) and the warning at 51, 101, 151 ... tabs with the ceiling of 500 (part B).
 * The existing [CloseAllNormalTabsTest] covers closeAllNormal(), which this class does not touch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloseAllTabsAndManyTabsTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val created = mutableListOf<MoreFakeSession>()
    private val warned = mutableListOf<Int>()
    private val limitEvents = mutableListOf<TabEvent>()
    private var store = MoreMemoryStore()

    @After
    fun tearDown() = scope.cancel()

    private fun manager(
        stored: MoreMemoryStore = MoreMemoryStore(),
        max: Int = MAX_TABS,
        undoMillis: Long = CLOSE_ALL_UNDO_MILLIS,
    ): TabManager {
        store = stored
        val m = TabManager(
            factory = { init -> MoreFakeSession(init).also { created += it } },
            store = stored,
            scope = scope,
            log = {},
            io = dispatcher,
            maxTabs = max,
            closeAllUndoMillis = undoMillis,
        )
        scope.launch { m.manyTabsWarnings.collect { warned += it } }
        scope.launch { m.events.collect { limitEvents += it } }
        m.restore()
        scheduler.advanceUntilIdle()
        return m
    }

    private fun TabManager.ids() = tabs.value.map { it.id }

    private fun TabManager.visit(id: Long, url: String, title: String = url) {
        val session = sessionOf(id) as? MoreFakeSession ?: error("tab $id has no session")
        session._state.value = session._state.value.copy(url = url, title = title)
        scheduler.runCurrent()
    }

    private fun TabManager.open(url: String): Long {
        val id = newTab(url)!!
        visit(id, url)
        return id
    }

    private fun TabManager.fillTo(count: Int) {
        while (tabs.value.size < count) assertNotNull(newTab())
    }

    // ---- part A: Close all tabs ----

    /** A, b, c, d normal (a and d grouped, b in its own group), two private tabs in between, c selected. */
    private class Scene(val m: TabManager, val normal: List<Long>, val privates: List<Long>, val orderBefore: List<Long>)

    private fun scene(): Scene {
        val m = manager()
        m.openSelected()
        val a = m.selected.value.id
        m.visit(a, "https://a.example/", "A")
        val b = m.open("https://b.example/")
        val p1 = m.newPrivateTab("https://p1.example/")!!
        val c = m.open("https://c.example/")
        val d = m.open("https://d.example/")
        val p2 = m.newPrivateTab()!!
        (m.sessionOf(a) as MoreFakeSession).saved = "STATE-A"
        (m.sessionOf(c) as MoreFakeSession).saved = "STATE-C"
        m.createGroup(listOf(a, d), "Work", TabGroupColor.BLUE)
        m.createGroup(listOf(b), "Solo", TabGroupColor.RED)
        m.select(c)
        scheduler.advanceUntilIdle()
        return Scene(m, listOf(a, b, c, d), listOf(p1, p2), m.ids())
    }

    @Test
    fun closeAllLeavesOneBlankNormalTabAndTouchesNoPrivateTab() {
        val s = scene()
        val m = s.m
        val privateSessions = s.privates.map { m.sessionOf(it) as MoreFakeSession }
        val normalSessions = s.normal.map { m.sessionOf(it) as MoreFakeSession }
        val closedCount = m.closeAllNormalUndoable()
        assertEquals(4, closedCount)
        val normalNow = m.tabs.value.filter { !it.isPrivate }
        assertEquals(1, normalNow.size)
        assertEquals("", normalNow.single().url)
        assertTrue(normalNow.single().id !in s.normal)
        assertTrue(normalNow.single().selected)
        assertEquals(s.privates, m.tabs.value.filter { it.isPrivate }.map { it.id })
        assertTrue(privateSessions.none { it.closed })
        // Closing the sessions is what stops a playing tab's sound at once.
        assertTrue(normalSessions.all { it.closed && it.host == null })
        assertTrue(m.groups.value.isEmpty())
        assertTrue(m.hasPrivateTabs)
    }

    @Test
    fun undoBringsBackTheSameTabsInTheSameOrderWithGroupsAndSelection() {
        val s = scene()
        val m = s.m
        val groupsBefore = m.groups.value
        val groupOfBefore = m.tabs.value.associate { it.id to it.groupId }
        assertTrue(groupsBefore.size == 2)
        val blank = run { m.closeAllNormalUndoable(); m.selected.value.id }
        assertTrue(m.undoCloseAll())
        assertEquals(s.orderBefore, m.ids())
        assertTrue(blank !in m.ids())
        assertEquals(groupsBefore, m.groups.value)
        assertEquals(groupOfBefore, m.tabs.value.associate { it.id to it.groupId })
        assertEquals(s.normal[2], m.selected.value.id)
        assertEquals(s.normal[2], m.tabs.value.single { it.selected }.id)
        assertEquals(listOf("https://a.example/", "https://b.example/", "https://c.example/", "https://d.example/"),
            s.normal.map { id -> m.tabs.value.single { it.id == id }.url })
        // Asleep: only the selected tab has a session again.
        assertTrue(s.normal.filter { it != s.normal[2] }.all { m.sessionOf(it) == null })
        val back = m.sessionOf(s.normal[2]) as MoreFakeSession
        assertEquals("STATE-C", back.init.restoredState)
        // Each other tab keeps its page state for when it is shown.
        m.select(s.normal[0])
        assertEquals("STATE-A", (created.last()).init.restoredState)
        // The list of recently closed tabs holds nothing of them any more.
        assertTrue(m.recentlyClosed.value.isEmpty())
        // The private tabs stayed where they were, untouched.
        assertTrue(s.privates.none { (m.sessionOf(it) as MoreFakeSession).closed })
    }

    @Test
    fun undoAfterTheBlankTabWasUsedKeepsTheUsedTab() {
        val s = scene()
        val m = s.m
        m.closeAllNormalUndoable()
        val blank = m.selected.value.id
        m.visit(blank, "https://new.example/")
        assertTrue(m.undoCloseAll())
        assertEquals(s.orderBefore + blank, m.ids())
        assertEquals("https://new.example/", m.tabs.value.single { it.id == blank }.url)
        assertEquals(s.normal[2], m.selected.value.id)
        assertFalse((m.sessionOf(blank) as MoreFakeSession).closed)
    }

    @Test
    fun undoWithAPrivateTabSelectedLeavesItSelected() {
        val s = scene()
        val m = s.m
        m.select(s.privates[0])
        m.closeAllNormalUndoable()
        assertEquals(s.privates[0], m.selected.value.id)
        assertTrue(m.undoCloseAll())
        assertEquals(s.privates[0], m.selected.value.id)
        assertEquals(s.orderBefore, m.ids())
    }

    @Test
    fun withoutUndoTheTwentyFiveMostRecentlyUsedGoToRecentlyClosedNewestFirst() {
        val m = manager()
        m.openSelected()
        val first = m.selected.value.id
        m.visit(first, "https://t1.example/")
        val ids = mutableListOf(first)
        for (i in 2..30) ids += m.open("https://t$i.example/")
        // t5 is the most recently used; then t30, t29, ... in the order they were last shown.
        m.select(ids[4])
        val expected = listOf("https://t5.example/") + (30 downTo 7).map { "https://t$it.example/" }
        assertEquals(25, expected.size)
        assertEquals(30, m.closeAllNormalUndoable())
        assertEquals(expected, m.recentlyClosed.value.map { it.url })
        scheduler.advanceTimeBy(CLOSE_ALL_UNDO_MILLIS + 1)
        scheduler.runCurrent()
        assertFalse(m.undoCloseAll())
        assertEquals(expected, m.recentlyClosed.value.map { it.url })
        assertEquals(1, m.tabs.value.size)
    }

    @Test
    fun undoIsOfferedForTenSecondsAndNotLonger() {
        val s = scene()
        val m = s.m
        m.closeAllNormalUndoable()
        scheduler.advanceTimeBy(CLOSE_ALL_UNDO_MILLIS - 1)
        scheduler.runCurrent()
        assertTrue(m.undoCloseAll())
        m.closeAllNormalUndoable()
        scheduler.advanceTimeBy(CLOSE_ALL_UNDO_MILLIS)
        scheduler.runCurrent()
        assertFalse(m.undoCloseAll())
    }

    @Test
    fun theStoredTabListIsRewrittenAtOnceOnCloseAndOnUndo() {
        val s = scene()
        val m = s.m
        m.flush()
        scheduler.advanceUntilIdle()
        assertEquals(4, store.snapshot!!.tabs.size)
        m.closeAllNormalUndoable()
        scheduler.runCurrent()
        // No debounce: the file already holds only the blank tab.
        assertEquals(1, store.snapshot!!.tabs.size)
        assertTrue(store.snapshot!!.tabs.single().url.isEmpty())
        m.undoCloseAll()
        scheduler.runCurrent()
        assertEquals(s.orderBefore.filter { it !in s.privates }, store.snapshot!!.tabs.map { it.id })
        assertEquals(2, store.snapshot!!.groups.size)
    }

    @Test
    fun withOneNormalTabNothingHappensAndTheEntryIsAbsent() {
        val m = manager()
        m.openSelected()
        assertEquals(0, m.closeAllNormalUndoable())
        assertEquals(1, m.tabs.value.size)
        assertFalse(m.undoCloseAll())
        val one = listOf(TabUi(1, "a", "https://a.example/", true), TabUi(2, "p", "https://p.example/", false, isPrivate = true))
        assertFalse(canCloseAllTabs(one))
        assertFalse(canCloseAllTabs(emptyList()))
        assertTrue(canCloseAllTabs(one + TabUi(3, "b", "https://b.example/", false)))
        // Two private tabs are not two normal tabs.
        assertFalse(canCloseAllTabs(listOf(TabUi(1, "a", "", true), TabUi(2, "p", "", false, true), TabUi(3, "q", "", false, true))))
    }

    @Test
    fun aSecondCloseAllRightAfterTheFirstDoesNotLoseTheUndo() {
        val s = scene()
        val m = s.m
        m.closeAllNormalUndoable()
        // A double tap: only the blank tab is left, so the second call does nothing.
        assertEquals(0, m.closeAllNormalUndoable())
        assertTrue(m.undoCloseAll())
        assertEquals(s.orderBefore, m.ids())
    }

    @Test
    fun clearingBrowsingDataOrTheRecentlyClosedListAlsoEndsTheUndo() {
        val s1 = scene()
        s1.m.closeAllNormalUndoable()
        s1.m.closeAllNormal()
        assertFalse(s1.m.undoCloseAll())
        assertTrue(s1.m.recentlyClosed.value.isEmpty())
        val s2 = scene()
        s2.m.closeAllNormalUndoable()
        s2.m.forgetClosed()
        assertFalse(s2.m.undoCloseAll())
        assertTrue(s2.m.recentlyClosed.value.isEmpty())
    }

    @Test
    fun aTabReopenedByHandFromRecentlyClosedDoesNotComeBackTwiceOnUndo() {
        val s = scene()
        val m = s.m
        m.closeAllNormalUndoable()
        val entry = m.recentlyClosed.value.single { it.url == "https://b.example/" }
        m.reopen(entry)
        assertTrue(m.undoCloseAll())
        assertEquals(1, m.tabs.value.count { it.url == "https://b.example/" })
        assertTrue(s.normal[1] !in m.ids())
        assertTrue(listOf(s.normal[0], s.normal[2], s.normal[3]).all { it in m.ids() })
    }

    @Test
    fun closeAllNormalStillForgetsTheRecentlyClosedListOnPurpose() {
        val s = scene()
        s.m.close(s.normal[1])
        assertEquals(1, s.m.recentlyClosed.value.size)
        s.m.closeAllNormal()
        assertTrue(s.m.recentlyClosed.value.isEmpty())
    }

    @Test
    fun recentlyClosedKeepsOlderEntriesBehindTheNewOnesUpToTheLimit() {
        val s = scene()
        val m = s.m
        m.close(s.normal[3])
        m.closeAllNormalUndoable()
        // d was closed alone first; the other three follow it, newest first, and the old one is behind them.
        val urls = m.recentlyClosed.value.map { it.url }
        assertEquals(4, urls.size)
        assertEquals("https://d.example/", urls.last())
    }

    @Test
    fun undoAtTheCeilingBringsBackWhatFitsAndSaysSo() {
        val m = manager(max = 6)
        m.openSelected()
        m.visit(m.selected.value.id, "https://a.example/")
        repeat(3) { m.open("https://x$it.example/") }
        m.closeAllNormalUndoable()
        repeat(5) { assertNotNull(m.newTab()) }
        limitEvents.clear()
        assertTrue(m.undoCloseAll())
        scheduler.runCurrent()
        assertEquals(6, m.tabs.value.size)
        assertEquals(listOf(TabEvent.TAB_LIMIT), limitEvents)
    }

    // ---- part B: more than 50 tabs ----

    /** The ceiling the brief asks for, written out so a changed constant cannot hide itself. */
    private val CEILING = 500

    private val openWays: List<Pair<String, (TabManager) -> Unit>> = listOf(
        "new tab" to { m -> m.newTab() },
        "new private tab" to { m -> m.newPrivateTab() },
        "link in a new tab" to { m ->
            m.openLinkInNewTab(LinkMenuRequest("https://link.example/", tabId = m.ids().first()))
        },
        "link in a private tab" to { m ->
            m.openLinkInPrivateTab(LinkMenuRequest("https://link.example/", tabId = m.ids().first()))
        },
        "session callback open in new tab" to { m -> m.onOpenInNewTab("https://cb.example/", created[0]) },
        "reopen closed tab" to { m -> m.reopen(ClosedTab("https://r.example/", "R", false, null)) },
        "duplicate" to { m -> m.duplicate(m.ids().first()) },
        "restore from backup" to { m ->
            m.addRestoredTabs(listOf(PersistedTab(1, "https://b.example/", "B", false, null)), emptyList())
        },
        "a page's own new window" to { m -> m.onPopupSession(MoreFakeSession(SessionInit()), created[0]) },
        "adopted session" to { m -> m.adoptSession(MoreFakeSession(SessionInit())) },
    )

    private fun manyReady(): TabManager {
        val m = manager()
        m.openSelected()
        m.visit(m.selected.value.id, "https://first.example/")
        return m
    }

    @Test
    fun theFiftyFirstTabOpensAndWarnsOnceForEveryWayOfOpeningOne() {
        for ((name, open) in openWays) {
            created.clear()
            warned.clear()
            val m = manyReady()
            m.fillTo(50)
            scheduler.runCurrent()
            assertEquals(name, 50, m.tabs.value.size)
            assertTrue(name, warned.isEmpty())
            open(m)
            scheduler.runCurrent()
            assertEquals(name, 51, m.tabs.value.size)
            assertEquals(name, listOf(51), warned)
        }
    }

    @Test
    fun noWarningAtFiftyTwoAgainAtOneHundredOneAndOneFiftyOne() {
        val m = manyReady()
        m.fillTo(51)
        scheduler.runCurrent()
        assertEquals(listOf(51), warned)
        m.fillTo(100)
        scheduler.runCurrent()
        assertEquals(listOf(51), warned)
        m.fillTo(101)
        scheduler.runCurrent()
        assertEquals(listOf(51, 101), warned)
        m.fillTo(151)
        scheduler.runCurrent()
        assertEquals(listOf(51, 101, 151), warned)
    }

    @Test
    fun privateTabsCountTowardsTheWarning() {
        val m = manyReady()
        m.fillTo(50)
        m.newPrivateTab()
        scheduler.runCurrent()
        assertEquals(listOf(51), warned)
    }

    @Test
    fun goingDownAndUpAgainWarnsAgainOnlyAfterTheCountWasTenBelow() {
        val m = manyReady()
        m.fillTo(51)
        scheduler.runCurrent()
        assertEquals(listOf(51), warned)
        // Down to 42 (only 9 below 51) and up: not again.
        while (m.tabs.value.size > 42) m.close(m.ids().last())
        m.fillTo(51)
        scheduler.runCurrent()
        assertEquals(listOf(51), warned)
        // Down to 41 (10 below) and up: again.
        while (m.tabs.value.size > 41) m.close(m.ids().last())
        m.fillTo(51)
        scheduler.runCurrent()
        assertEquals(listOf(51, 51), warned)
        // Staying at 51 or 52 does not repeat it.
        m.fillTo(52)
        scheduler.runCurrent()
        assertEquals(listOf(51, 51), warned)
    }

    @Test
    fun noWarningAtStartUpForRestoredTabsOrAfterThem() {
        val tabs = (1L..60L).map { PersistedTab(it, "https://t$it.example/", "T$it", false, null) }
        val m = manager(MoreMemoryStore(TabsSnapshot(tabs, selectedId = 7)))
        scheduler.runCurrent()
        assertEquals(60, m.tabs.value.size)
        assertTrue(warned.isEmpty())
        // Restored tabs stay asleep.
        assertTrue(created.isEmpty())
        m.newTab()
        m.newTab()
        scheduler.runCurrent()
        assertEquals(62, m.tabs.value.size)
        assertTrue(warned.isEmpty())
        // The next threshold still warns.
        m.fillTo(101)
        scheduler.runCurrent()
        assertEquals(listOf(101), warned)
    }

    @Test
    fun undoOfCloseAllDoesNotWarnAgainForTabsTheUserAlreadyHad() {
        val m = manyReady()
        m.fillTo(60)
        scheduler.runCurrent()
        assertEquals(listOf(51), warned)
        m.closeAllNormalUndoable()
        assertTrue(m.undoCloseAll())
        scheduler.runCurrent()
        assertEquals(60, m.tabs.value.size)
        assertEquals(listOf(51), warned)
    }

    @Test
    fun theCeilingIsFiveHundredAndTheWarningStepIsFifty() {
        assertEquals(500, MAX_TABS)
        assertEquals(50, TABS_WARN_EVERY)
        assertEquals(50, MAX_TAB_GROUPS)
    }

    @Test
    fun atFiveHundredEveryWayOfOpeningATabIsRefusedAndRaisesTheLimitEvent() {
        val m = manyReady()
        m.fillTo(CEILING)
        scheduler.runCurrent()
        assertEquals(CEILING, m.tabs.value.size)
        for ((name, open) in openWays) {
            if (name == "restore from backup") continue
            limitEvents.clear()
            open(m)
            scheduler.runCurrent()
            assertEquals(name, CEILING, m.tabs.value.size)
            assertEquals(name, listOf(TabEvent.TAB_LIMIT), limitEvents)
        }
        // A page's own window: its session is closed, not kept.
        val child = MoreFakeSession(SessionInit())
        limitEvents.clear()
        m.onPopupSession(child, created[0])
        scheduler.runCurrent()
        assertTrue(child.closed)
        assertEquals(listOf(TabEvent.TAB_LIMIT), limitEvents)
        // The engine's question before it opens a window.
        limitEvents.clear()
        assertFalse(m.canOpenTab())
        scheduler.runCurrent()
        assertEquals(listOf(TabEvent.TAB_LIMIT), limitEvents)
        // A restore from a backup adds nothing, and says nothing (its own message reports the count).
        assertEquals(0, m.addRestoredTabs(listOf(PersistedTab(1, "https://b.example/", "B", false, null)), emptyList()))
        // One below the ceiling everything opens again.
        m.close(m.ids().last())
        assertTrue(m.canOpenTab())
        assertNotNull(m.newTab())
        assertNull(m.newTab())
    }

    @Test
    fun noTabIsClosedWhenManyAreOpen() {
        val m = manyReady()
        m.fillTo(120)
        scheduler.runCurrent()
        assertEquals(120, m.tabs.value.size)
        assertTrue(created.none { it.closed })
        assertEquals(120, created.size)
    }

    @Test
    fun theGroupLimitStaysAtFifty() {
        val m = manyReady()
        m.fillTo(60)
        val ids = m.ids()
        for (i in 0 until MAX_TAB_GROUPS) assertNotNull("group $i", m.createGroup(listOf(ids[i]), "G$i", TabGroupColor.BLUE))
        assertNull(m.createGroup(listOf(ids[MAX_TAB_GROUPS]), "one more", TabGroupColor.BLUE))
        assertEquals(MAX_TAB_GROUPS, m.groups.value.size)
    }

    // ---- the pure warning rule ----

    @Test
    fun theWatchWarnsOncePerCrossingAndRearmsTenBelow() {
        val w = TabCountWatch()
        assertFalse(w.update(50))
        assertTrue(w.update(51))
        assertFalse(w.update(52))
        assertFalse(w.update(100))
        assertTrue(w.update(101))
        assertFalse(w.update(42))
        assertFalse(w.update(51))
        assertFalse(w.update(41))
        assertTrue(w.update(51))
        // One jump over two thresholds is one warning.
        assertTrue(TabCountWatch().update(120))
        assertFalse(TabCountWatch().update(1))
    }
}
