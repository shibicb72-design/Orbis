package com.shibi.orbis.tabs

import android.view.View
import com.shibi.orbis.engine.BrowserSession
import com.shibi.orbis.engine.IncomingDownload
import com.shibi.orbis.engine.SessionHost
import com.shibi.orbis.engine.SessionInit
import com.shibi.orbis.engine.SessionState
import com.shibi.orbis.engine.SitePrompt
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSession(val init: SessionInit) : BrowserSession {
    override val isPrivate get() = init.isPrivate
    val _state = MutableStateFlow(SessionState(desktopMode = init.desktopMode))
    override val state: StateFlow<SessionState> = _state
    override val prompts: StateFlow<SitePrompt?> = MutableStateFlow(null)
    override var host: SessionHost? = null
    var active: Boolean? = null
    var closed = false
    val loads = mutableListOf<String>()
    val externalLoads = mutableListOf<String>()
    var saved: String? = null

    override fun attachTo(contentView: View) = Unit
    override fun detachFrom(contentView: View) = Unit
    override fun loadUrl(url: String) { loads += url }
    override fun loadExternalUrl(url: String) { externalLoads += url }
    override fun setDesktopMode(enabled: Boolean) { _state.value = _state.value.copy(desktopMode = enabled) }
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

private class MemoryStore(var snapshot: TabsSnapshot? = null) : TabStore {
    val saves = mutableListOf<TabsSnapshot>()
    override fun load(): TabsSnapshot? = snapshot
    override fun save(snapshot: TabsSnapshot) { saves += snapshot; this.snapshot = snapshot }
}

private class FakeDownload : IncomingDownload {
    var discarded = false
    override val url = "https://example.com/f.zip"
    override val contentDisposition: String? = null
    override val contentType: String? = null
    override val contentLength = -1L
    override fun openBody(): InputStream? = null
    override fun discard() { discarded = true }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TabManagerTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val created = mutableListOf<FakeSession>()
    private val logs = mutableListOf<String>()

    @After
    fun tearDown() = scope.cancel()

    private fun manager(
        store: MemoryStore = MemoryStore(),
        max: Int = MAX_TABS,
        visits: VisitSink = VisitSink.None,
    ): TabManager {
        val m = TabManager(
            factory = { init -> FakeSession(init).also { created += it } },
            store = store,
            scope = scope,
            log = { logs += it },
            io = dispatcher,
            maxTabs = max,
            visits = visits,
        )
        m.restore()
        scheduler.advanceUntilIdle()
        return m
    }

    private fun FakeSession.navigate(url: String, title: String? = null) {
        _state.value = _state.value.copy(url = url, title = title)
        scheduler.runCurrent()
    }

    @Test
    fun startsWithOneBlankTabAndNoSession() {
        val m = manager()
        assertTrue(m.ready.value)
        assertEquals(1, m.tabs.value.size)
        assertNull(m.selected.value.session)
        assertTrue(created.isEmpty())
    }

    @Test
    fun openSelectedCreatesSessionWithoutLoadingAnything() {
        val m = manager()
        m.openSelected()
        assertEquals(1, created.size)
        assertTrue(created[0].loads.isEmpty() && created[0].externalLoads.isEmpty())
        assertEquals(true, created[0].active)
        m.openSelected()
        assertEquals(1, created.size)
    }

    @Test
    fun newTabSwitchesAndDeactivatesPrevious() {
        val m = manager()
        m.openSelected()
        val first = created[0]
        val id = m.newTab("https://example.com/")
        assertNotNull(id)
        assertEquals(2, m.tabs.value.size)
        assertEquals(id, m.selected.value.id)
        assertEquals(false, first.active)
        assertEquals(listOf("https://example.com/"), created[1].loads)
        m.select(m.tabs.value[0].id)
        assertEquals(true, first.active)
        assertEquals(false, created[1].active)
        assertSame(first, m.selected.value.session)
    }

    @Test
    fun closingSelectedTabSelectsOpenerThenNeighbour() {
        val m = manager()
        m.openSelected()
        val a = m.selected.value.id
        val b = m.newTab()!!
        val c = m.newTab()!!
        // d is opened from b via the link menu.
        m.select(b)
        val d = m.onOpenInNewTab("https://example.com/d", m.selected.value.session!!).let { m.selected.value.id }
        assertEquals(b, m.selected.value.openerTabId)
        m.close(d)
        assertEquals(b, m.selected.value.id)
        // b has no opener: its right neighbour (c) is selected.
        m.close(b)
        assertEquals(c, m.selected.value.id)
        m.close(c)
        assertEquals(a, m.selected.value.id)
    }

    @Test
    fun closingLastTabLeavesOneFreshBlankTab() {
        val m = manager()
        m.openSelected()
        val only = m.selected.value.id
        val old = created[0]
        m.close(only)
        assertTrue(old.closed)
        assertEquals(1, m.tabs.value.size)
        assertTrue(m.selected.value.id != only)
        assertNotNull(m.selected.value.session)
        assertEquals("", m.selected.value.url)
    }

    @Test
    fun tabCapRefusesNewTabsExternalAndPopups() = runTest(dispatcher) {
        val m = manager(max = 2)
        m.openSelected()
        assertNotNull(m.newTab())
        assertNull(m.newTab())
        assertEquals(TabEvent.TAB_LIMIT, m.events.first())
        assertFalse(m.canOpenTab())
        assertEquals(TabEvent.TAB_LIMIT, m.events.first())
        // Both tabs now hold pages, so an external link cannot reuse a blank one.
        created[1].navigate("https://loaded.example/")
        m.openExternal("https://example.com/")
        assertEquals(2, m.tabs.value.size)
        assertTrue(created[1].externalLoads.isEmpty())
        val child = FakeSession(SessionInit())
        m.onPopupSession(child, created[0])
        assertTrue(child.closed)
        assertEquals(2, m.tabs.value.size)
    }

    @Test
    fun openExternalNeverHijacksALoadedTab() {
        val m = manager()
        m.openSelected()
        created[0].navigate("https://example.com/page", "Page")
        m.openExternal("https://other.example/")
        assertEquals(2, m.tabs.value.size)
        assertEquals("https://example.com/page", m.tabs.value[0].url)
        assertEquals(listOf("https://other.example/"), created[1].externalLoads)
        assertTrue(created[0].externalLoads.isEmpty())
    }

    @Test
    fun openExternalUsesAnEmptyUntouchedTabInsteadOfLeavingItBehind() {
        val m = manager()
        m.openSelected()
        m.openExternal("https://other.example/")
        assertEquals(1, m.tabs.value.size)
        assertEquals(listOf("https://other.example/"), created[0].externalLoads)
    }

    @Test
    fun openExternalRefusesNonWebUrls() {
        val m = manager()
        m.openSelected()
        m.openExternal("javascript:alert(1)")
        m.openExternal("file:///sdcard/x.html")
        assertEquals(1, created.size)
        assertTrue(created[0].externalLoads.isEmpty())
    }

    @Test
    fun popupBecomesSelectedTabWithOpener() {
        val m = manager()
        m.openSelected()
        val openerId = m.selected.value.id
        val child = FakeSession(SessionInit())
        m.onPopupSession(child, created[0])
        assertEquals(2, m.tabs.value.size)
        assertSame(child, m.selected.value.session)
        assertEquals(openerId, m.selected.value.openerTabId)
        assertSame(m, child.host)
        assertEquals(true, child.active)
        assertEquals(false, created[0].active)
        // window.close from the popup returns to the opener.
        m.onCloseRequest(child)
        assertEquals(openerId, m.selected.value.id)
        assertTrue(child.closed)
    }

    @Test
    fun restoreIsLazyAndOnlySelectedTabOpens() {
        val store = MemoryStore(
            TabsSnapshot(
                listOf(
                    PersistedTab(5, "https://a.example/", "A", false, "STATE-A"),
                    PersistedTab(7, "https://b.example/", "B", true, "STATE-B"),
                ),
                selectedId = 7,
            ),
        )
        val m = manager(store)
        assertEquals(2, m.tabs.value.size)
        assertTrue(created.isEmpty())
        assertEquals(7L, m.selected.value.id)
        m.openSelected()
        assertEquals(1, created.size)
        val init = created[0].init
        assertEquals("STATE-B", init.restoredState)
        assertEquals("https://b.example/", init.fallbackUrl)
        assertTrue(init.desktopMode)
        // The other tab opens on first select, with its own state.
        m.select(5)
        assertEquals(2, created.size)
        assertEquals("STATE-A", created[1].init.restoredState)
        assertFalse(created[1].init.desktopMode)
        // New ids never collide with restored ones.
        assertTrue(m.newTab()!! > 7)
    }

    @Test
    fun persistsAfterDebounceAndOnFlush() {
        val store = MemoryStore()
        val m = manager(store)
        m.openSelected()
        created[0].navigate("https://example.com/", "Example")
        created[0].saved = "BLOB"
        assertTrue(store.saves.isEmpty())
        scheduler.advanceTimeBy(999)
        scheduler.runCurrent()
        assertTrue(store.saves.isEmpty())
        scheduler.advanceTimeBy(2)
        scheduler.runCurrent()
        assertEquals(1, store.saves.size)
        val saved = store.saves[0].tabs[0]
        assertEquals("https://example.com/", saved.url)
        assertEquals("Example", saved.title)
        assertEquals("BLOB", saved.savedState)
        m.newTab()
        m.flush()
        scheduler.runCurrent()
        assertEquals(2, store.saves.last().tabs.size)
    }

    @Test
    fun doesNotWriteBeforeStoredTabsHaveBeenRead() {
        val store = MemoryStore()
        val m = TabManager({ FakeSession(it) }, store, scope, {}, dispatcher)
        m.flush()
        scheduler.advanceUntilIdle()
        assertTrue(store.saves.isEmpty())
    }

    @Test
    fun downloadOnlyForSelectedTabAndOneAtATime() {
        val m = manager()
        m.openSelected()
        m.newTab()
        val background = created[0]
        val foreground = created[1]
        val d1 = FakeDownload()
        m.onDownload(d1, background)
        assertTrue(d1.discarded)
        assertNull(m.pendingDownload.value)
        val d2 = FakeDownload()
        m.onDownload(d2, foreground)
        assertFalse(d2.discarded)
        assertNotNull(m.pendingDownload.value)
        val d3 = FakeDownload()
        m.onDownload(d3, foreground)
        assertTrue(d3.discarded)
        // Closing the tab discards what it had pending.
        m.close(m.selected.value.id)
        assertTrue(d2.discarded)
        assertNull(m.pendingDownload.value)
    }

    @Test
    fun desktopModeIsMirroredIntoTheTab() {
        val m = manager()
        m.openSelected()
        created[0].setDesktopMode(true)
        scheduler.runCurrent()
        assertTrue(m.selected.value.desktopMode)
    }

    @Test
    fun backgroundedAppDeactivatesSelectedSessionAndNothingReactivatesIt() {
        val m = manager()
        m.openSelected()
        val first = created[0]
        assertEquals(true, first.active)
        m.setForeground(false)
        assertEquals(false, first.active)

        // Every operation that normally activates the selected session must keep it inactive while stopped.
        m.openSelected()
        assertEquals(false, first.active)
        m.newTab()
        assertEquals(false, created[1].active)
        m.select(m.tabs.value[0].id)
        assertEquals(false, first.active)
        val child = FakeSession(SessionInit())
        m.onPopupSession(child, first)
        assertEquals(false, child.active)
        m.close(m.selected.value.id)
        assertEquals(false, first.active)

        m.setForeground(true)
        assertEquals(true, first.active)
        assertEquals(false, created[1].active)
    }

    @Test
    fun loadInSelectedRefusesNonWebUrls() {
        val m = manager()
        m.openSelected()
        m.loadInSelected("javascript:alert(1)")
        m.loadInSelected("file:///sdcard/x.html")
        m.loadInSelected("data:text/html,x")
        m.loadInSelected("about:blank")
        m.loadInSelected("")
        assertEquals(1, created.size)
        assertTrue(created[0].loads.isEmpty())
    }

    @Test
    fun loadInSelectedCreatesTheSessionWhenAbsent() {
        val m = manager()
        assertTrue(created.isEmpty())
        m.loadInSelected("https://bookmarked.example/")
        assertEquals(1, created.size)
        assertEquals(listOf("https://bookmarked.example/"), created[0].loads)
        assertEquals(true, created[0].active)
        assertSame(created[0], m.selected.value.session)
        assertEquals(1, m.tabs.value.size)
    }

    @Test
    fun loadInSelectedUsesTheSelectedTabOnly() {
        val m = manager()
        m.openSelected()
        val first = m.selected.value.id
        m.newTab("https://second.example/")
        m.select(first)
        m.loadInSelected("https://target.example/")
        assertEquals(listOf("https://target.example/"), created[0].loads)
        assertEquals(listOf("https://second.example/"), created[1].loads)
        assertEquals(2, m.tabs.value.size)
    }

    @Test
    fun loadInSelectedOnARestoredTabOpensItsSessionFirst() {
        val store = MemoryStore(
            TabsSnapshot(listOf(PersistedTab(5, "https://a.example/", "A", false, "STATE-A")), selectedId = 5),
        )
        val m = manager(store)
        assertTrue(created.isEmpty())
        m.loadInSelected("https://target.example/")
        assertEquals(1, created.size)
        assertEquals("STATE-A", created[0].init.restoredState)
        assertEquals(listOf("https://target.example/"), created[0].loads)
    }

    @Test
    fun loadInSelectedWhileBackgroundedLeavesTheSessionInactive() {
        val m = manager()
        m.openSelected()
        m.setForeground(false)
        m.loadInSelected("https://target.example/")
        assertEquals(false, created[0].active)
        assertEquals(listOf("https://target.example/"), created[0].loads)
    }

    private class RecordingSink : VisitSink {
        val visits = mutableListOf<Pair<String, String>>()
        val titles = mutableListOf<Pair<String, String>>()
        override fun onVisit(url: String, title: String) { visits += url to title }
        override fun onTitle(url: String, title: String) { titles += url to title }
    }

    @Test
    fun visitsAreForwardedOnlyForSessionsTheManagerOwns() {
        val sink = RecordingSink()
        val m = manager(visits = sink)
        m.openSelected()
        m.onVisit(created[0], "https://a.example/", "A")
        m.onVisitTitle(created[0], "https://a.example/", "A late")
        assertEquals(listOf("https://a.example/" to "A"), sink.visits)
        assertEquals(listOf("https://a.example/" to "A late"), sink.titles)

        // A session nobody owns (never added, or a stray late callback) adds nothing.
        val stranger = FakeSession(SessionInit())
        m.onVisit(stranger, "https://stranger.example/", "S")
        m.onVisitTitle(stranger, "https://stranger.example/", "S")
        assertEquals(1, sink.visits.size)
        assertEquals(1, sink.titles.size)
    }

    @Test
    fun aClosedTabNoLongerForwardsVisits() {
        val sink = RecordingSink()
        val m = manager(visits = sink)
        m.openSelected()
        m.newTab()
        val closing = created[1]
        m.close(m.selected.value.id)
        m.onVisit(closing, "https://late.example/", "L")
        m.onVisitTitle(closing, "https://late.example/", "L")
        assertTrue(sink.visits.isEmpty())
        assertTrue(sink.titles.isEmpty())
        // The remaining tab still reports.
        m.onVisit(created[0], "https://a.example/", "A")
        assertEquals(1, sink.visits.size)
    }

    @Test
    fun popupSessionsAreForwardedOnceAdopted() {
        val sink = RecordingSink()
        val m = manager(visits = sink)
        m.openSelected()
        val child = FakeSession(SessionInit())
        m.onPopupSession(child, created[0])
        m.onVisit(child, "https://popup.example/", "P")
        assertEquals(listOf("https://popup.example/" to "P"), sink.visits)
    }

    @Test
    fun withoutASinkVisitsAreSimplyDropped() {
        val m = manager()
        m.openSelected()
        m.onVisit(created[0], "https://a.example/", "A")
        m.onVisitTitle(created[0], "https://a.example/", "A")
        assertEquals(1, m.tabs.value.size)
    }
}
