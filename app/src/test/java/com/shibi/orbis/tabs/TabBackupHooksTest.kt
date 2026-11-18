package com.shibi.orbis.tabs

import android.view.View
import com.shibi.orbis.engine.BrowserSession
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A session that takes the address it is told to load, as a committed navigation would, and has a saved state. */
private class HookSession(init: SessionInit) : BrowserSession {
    override val isPrivate = init.isPrivate
    val stateFlow = MutableStateFlow(SessionState(desktopMode = init.desktopMode))
    override val state: StateFlow<SessionState> = stateFlow
    override val prompts: StateFlow<SitePrompt?> = MutableStateFlow(null)
    override var host: SessionHost? = null
    val loads = mutableListOf<String>()

    override fun attachTo(contentView: View) = Unit
    override fun detachFrom(contentView: View) = Unit
    override fun loadUrl(url: String) {
        loads += url
        stateFlow.value = stateFlow.value.copy(url = url, title = "Loaded")
    }
    override fun loadExternalUrl(url: String) = loadUrl(url)
    override fun setDesktopMode(enabled: Boolean) = Unit
    override fun goBack() = false
    override fun goForward() = false
    override fun reload() = Unit
    override fun stop() = Unit
    override fun find(query: String, backwards: Boolean) = Unit
    override fun clearFind() = Unit
    override fun setActive(active: Boolean) = Unit
    override fun savedState(): String? = "LIVE-STATE"
    override fun close() = Unit
}

private class HookStore(var snapshot: TabsSnapshot? = null) : TabStore {
    override fun load() = snapshot

    override fun save(snapshot: TabsSnapshot) {
        this.snapshot = snapshot
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TabBackupHooksTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val sessions = mutableListOf<HookSession>()

    @After
    fun tearDown() = scope.cancel()

    private fun manager(store: HookStore = HookStore(), restore: Boolean = true): TabManager {
        val m = TabManager(factory = { HookSession(it).also { s -> sessions += s } }, store = store, scope = scope, log = {}, io = dispatcher)
        if (restore) {
            m.restore()
            scheduler.advanceUntilIdle()
        }
        return m
    }

    private fun tab(url: String, title: String = "T", group: Long? = null, desktop: Boolean = false) =
        PersistedTab(0, url, title, desktop, savedState = null, groupId = group)

    @Test(timeout = 10_000)
    fun aPrivateTabNeverAppearsInTheBackupAndNoStateIsRead() {
        val m = manager()
        m.newTab("https://normal.example/")
        m.newPrivateTab("https://private.example/")
        scheduler.advanceUntilIdle()
        val snap = m.normalTabsForBackup()
        assertEquals(listOf("", "https://normal.example/"), snap.tabs.map { it.url })
        assertTrue(snap.tabs.all { it.savedState == null })
        assertFalse(snap.tabs.any { it.url.contains("private") })
    }

    @Test(timeout = 10_000)
    fun theBackupKeepsOrderGroupsAndDesktopFlags() {
        val store =
            HookStore(
                TabsSnapshot(
                    listOf(
                        PersistedTab(1, "https://a.example/", "A", desktop = true, savedState = "S", groupId = 5),
                        PersistedTab(2, "https://b.example/", "B", desktop = false, savedState = null, groupId = 5),
                        PersistedTab(3, "https://c.example/", "C", desktop = false, savedState = null),
                    ),
                    selectedId = 3,
                    groups = listOf(TabGroup(5, "G", TabGroupColor.RED)),
                ),
            )
        val snap = manager(store).normalTabsForBackup()
        assertEquals(listOf("https://a.example/", "https://b.example/", "https://c.example/"), snap.tabs.map { it.url })
        assertEquals(listOf(true, false, false), snap.tabs.map { it.desktop })
        assertEquals(listOf(5L, 5L, null), snap.tabs.map { it.groupId })
        assertEquals(listOf(TabGroup(5, "G", TabGroupColor.RED)), snap.groups)
        assertTrue(snap.tabs.all { it.savedState == null })
    }

    @Test(timeout = 10_000)
    fun at48OpenTabsFiveOfferedAddsTwo() {
        val m = manager()
        repeat(47) { m.newTab("https://open$it.example/") }
        assertEquals(48, m.tabs.value.size)
        val added = m.addRestoredTabs((1..5).map { tab("https://new$it.example/") }, emptyList())
        assertEquals(2, added)
        assertEquals(MAX_TABS, m.tabs.value.size)
        assertEquals(listOf("https://new1.example/", "https://new2.example/"), m.tabs.value.takeLast(2).map { it.url })
    }

    @Test(timeout = 10_000)
    fun restoredTabsAreLazyUnselectedNormalAndPersisted() {
        val store = HookStore()
        val m = manager(store)
        m.newTab("https://here.example/")
        m.newTab("https://gone.example/")
        scheduler.advanceUntilIdle()
        m.close(m.selected.value.id)
        scheduler.advanceUntilIdle()
        val selected = m.selected.value.id
        val closed = m.recentlyClosed.value
        assertEquals(listOf("https://gone.example/"), closed.map { it.url })
        val sessionsBefore = sessions.size

        val added = m.addRestoredTabs(listOf(tab("https://r1.example/", desktop = true), tab("https://r2.example/")), emptyList())
        scheduler.advanceUntilIdle()

        assertEquals(2, added)
        assertEquals(selected, m.selected.value.id)
        assertEquals(closed, m.recentlyClosed.value)
        assertEquals(sessionsBefore, sessions.size)
        assertTrue(sessions.all { s -> s.loads.none { it.startsWith("https://r") } })
        assertTrue(m.tabs.value.none { it.isPrivate })
        val expected = listOf("", "https://here.example/", "https://r1.example/", "https://r2.example/")
        assertEquals(expected, m.tabs.value.map { it.url })
        assertTrue(m.tabs.value.filter { it.url.startsWith("https://r") }.none { it.selected })
        assertEquals(expected, store.snapshot!!.tabs.map { it.url })
        assertTrue(store.snapshot!!.tabs[2].desktop)
        assertTrue(store.snapshot!!.tabs.drop(2).all { it.savedState == null })
    }

    @Test(timeout = 10_000)
    fun onlyWebAddressesAndTitlesAreCut() {
        val m = manager()
        val long = "x".repeat(400)
        val added =
            m.addRestoredTabs(
                listOf(tab("about:config"), tab("javascript:alert(1)"), tab("file:///sdcard/a"), tab("https://ok.example/", title = long)),
                emptyList(),
            )
        assertEquals(1, added)
        val restored = m.tabs.value.last()
        assertEquals("https://ok.example/", restored.url)
        assertEquals(300, restored.title.length)
    }

    @Test(timeout = 10_000)
    fun groupsGetFreshIdsStayContiguousAndABadNameUngroups() {
        val m = manager()
        m.newTab("https://mine.example/")
        scheduler.advanceUntilIdle()
        val mine = m.createGroup(listOf(m.selected.value.id), "Mine", TabGroupColor.GREEN)!!
        val fileGroups = listOf(TabGroup(mine, "Trip", TabGroupColor.BLUE, collapsed = true), TabGroup(mine + 1, "   ", TabGroupColor.RED))
        val added =
            m.addRestoredTabs(
                listOf(
                    tab("https://t1.example/", group = mine),
                    tab("https://u.example/"),
                    tab("https://t2.example/", group = mine),
                    tab("https://bad.example/", group = mine + 1),
                    tab("https://lost.example/", group = 999),
                ),
                fileGroups,
            )
        assertEquals(5, added)
        val groups = m.groups.value
        assertEquals(listOf("Mine", "Trip"), groups.map { it.name })
        val trip = groups.first { it.name == "Trip" }
        // The file's id for Trip was already in use here: the restored group must have its own.
        assertNotEquals(mine, trip.id)
        assertEquals(TabGroupColor.BLUE, trip.color)
        assertTrue(trip.collapsed)
        assertEquals(TabGroup(mine, "Mine", TabGroupColor.GREEN), groups.first { it.name == "Mine" })
        val byUrl = m.tabs.value.associate { it.url to it.groupId }
        assertEquals(mine, byUrl["https://mine.example/"])
        assertEquals(trip.id, byUrl["https://t1.example/"])
        assertEquals(trip.id, byUrl["https://t2.example/"])
        assertNull(byUrl["https://bad.example/"])
        assertNull(byUrl["https://lost.example/"])
        assertTrue(groupsAreContiguous(m.tabs.value) { it.groupId })
        assertEquals(
            listOf("", "https://mine.example/", "https://t1.example/", "https://t2.example/", "https://u.example/", "https://bad.example/", "https://lost.example/"),
            m.tabs.value.map { it.url },
        )
    }

    @Test(timeout = 10_000)
    fun nothingIsAddedBeforeTheStoredListHasBeenRead() {
        val m = manager(HookStore(TabsSnapshot(listOf(PersistedTab(1, "https://stored.example/", "S", false, null)), 1)), restore = false)
        assertEquals(0, m.addRestoredTabs(listOf(tab("https://r.example/")), emptyList()))
        m.restore()
        scheduler.advanceUntilIdle()
        assertEquals(listOf("https://stored.example/"), m.tabs.value.map { it.url })
    }
}
