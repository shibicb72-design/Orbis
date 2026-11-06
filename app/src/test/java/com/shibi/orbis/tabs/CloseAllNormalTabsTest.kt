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

private class ClearFakeSession(val init: SessionInit) : BrowserSession {
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

private class ClearMemoryStore(var snapshot: TabsSnapshot? = null) : TabStore {
    val saves = mutableListOf<TabsSnapshot>()
    override fun load(): TabsSnapshot? = snapshot
    override fun save(snapshot: TabsSnapshot) { saves += snapshot; this.snapshot = snapshot }
}

private class ClearFakeDownload : IncomingDownload {
    var discarded = 0
    override val url = "https://example.com/f.zip"
    override val contentDisposition: String? = null
    override val contentType: String? = null
    override val contentLength = -1L
    override fun openBody(): InputStream? = null
    override fun discard() { discarded++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CloseAllNormalTabsTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val created = mutableListOf<ClearFakeSession>()
    private var ended = 0

    @After
    fun tearDown() = scope.cancel()

    private fun manager(
        store: ClearMemoryStore = ClearMemoryStore(),
        restore: Boolean = true,
        maxTabs: Int = MAX_TABS,
    ): TabManager {
        val m = TabManager(
            factory = { init -> ClearFakeSession(init).also { created += it } },
            store = store,
            scope = scope,
            log = {},
            io = dispatcher,
            maxTabs = maxTabs,
            onPrivateBrowsingEnded = { ended++ },
        )
        if (restore) {
            m.restore()
            scheduler.advanceUntilIdle()
        }
        return m
    }

    private fun settle() = scheduler.advanceUntilIdle()

    private fun ClearFakeSession.navigate(url: String, title: String) {
        _state.value = _state.value.copy(url = url, title = title)
        scheduler.runCurrent()
    }

    private fun storedTabs(json: String) = ClearMemoryStore(TabCodec.decode(json))

    // ---- only normal tabs ----

    @Test
    fun onlyNormalTabsAreReplacedByOneFreshBlankTabWithASession() {
        val m = manager()
        m.openSelected()
        created[0].navigate("https://a.example/", "A")
        m.newTab("https://b.example/")
        m.newTab("https://c.example/")
        val oldIds = m.tabs.value.map { it.id }
        val oldSessions = created.toList()
        assertEquals(3, oldSessions.size)

        m.closeAllNormal()

        assertEquals(1, m.tabs.value.size)
        val tab = m.tabs.value.single()
        assertTrue(tab.id !in oldIds)
        assertEquals("", tab.url)
        assertEquals("", tab.title)
        assertFalse(tab.isPrivate)
        assertTrue(tab.selected)
        assertEquals(tab.id, m.selected.value.id)
        assertTrue(oldSessions.all { it.closed && it.host == null })
        // The fresh tab got its own session, active.
        assertEquals(4, created.size)
        assertFalse(created.last().closed)
        assertEquals(true, created.last().active)
        assertTrue(m.selected.value.session === created.last())
        assertEquals(0, ended)
        assertFalse(m.hasPrivateTabs)
    }

    @Test
    fun theFreshTabIsInactiveWhileTheAppIsInTheBackground() {
        val m = manager()
        m.openSelected()
        m.setForeground(false)
        m.closeAllNormal()
        assertEquals(false, created.last().active)
        assertEquals(2, created.size)
    }

    @Test
    fun aSingleBlankTabIsReplacedByAnotherSingleBlankTab() {
        val m = manager()
        val before = m.selected.value.id
        m.closeAllNormal()
        assertEquals(1, m.tabs.value.size)
        assertTrue(m.tabs.value.single().id != before)
        m.closeAllNormal()
        assertEquals(1, m.tabs.value.size)
    }

    @Test
    fun itWorksAtTheTabCap() {
        val m = manager(maxTabs = 3)
        m.openSelected()
        m.newTab()
        m.newTab()
        assertEquals(3, m.tabs.value.size)
        m.closeAllNormal()
        assertEquals(1, m.tabs.value.size)
        assertTrue(m.newTab() != null)
    }

    // ---- with private tabs ----

    @Test
    fun aSelectedPrivateTabStaysSelectedAndTheFreshTabHasNoSessionYet() {
        val m = manager()
        m.openSelected()
        m.newTab("https://n.example/")
        m.newPrivateTab("https://p.example/")
        val privateId = m.selected.value.id
        val privateSession = created.last()
        val normalSessions = created.dropLast(1)
        val countBefore = created.size

        m.closeAllNormal()

        assertEquals(listOf(true, false), m.tabs.value.map { it.isPrivate })
        assertEquals(privateId, m.selected.value.id)
        assertTrue(m.selected.value.session === privateSession)
        assertFalse(privateSession.closed)
        assertTrue(privateSession.host != null)
        assertTrue(normalSessions.all { it.closed })
        // No session for the blank tab: sessions appear only when a tab is selected.
        assertEquals(countBefore, created.size)
        val fresh = m.tabs.value.last()
        assertFalse(fresh.selected)
        assertEquals(0, ended)
        assertTrue(m.hasPrivateTabs)

        // Selecting it opens it, and it is where closing the last private tab returns to.
        m.select(privateId)
        m.select(fresh.id)
        assertEquals(countBefore + 1, created.size)
        assertEquals(true, created.last().active)
        m.select(privateId)
        m.close(privateId)
        assertEquals(1, ended)
        assertEquals(fresh.id, m.selected.value.id)
        assertTrue(privateSession.closed)
    }

    @Test
    fun theLastNormalTabPointsAtTheFreshTabWhileAPrivateTabIsSelected() {
        val m = manager()
        m.openSelected()
        m.newPrivateTab()
        val privateId = m.selected.value.id
        m.closeAllNormal()
        val fresh = m.tabs.value.first { !it.isPrivate }.id

        // Closing the last private tab returns to the fresh tab, not to the closed one.
        m.close(privateId)

        assertEquals(1, ended)
        assertEquals(fresh, m.selected.value.id)
        assertEquals(listOf(fresh), m.tabs.value.map { it.id })
    }

    @Test
    fun aSelectedNormalTabIsReplacedByTheFreshOneAndPrivateTabsAreUntouched() {
        val m = manager()
        m.openSelected()
        val normalSession = created[0]
        m.newPrivateTab("https://p.example/")
        val privateId = m.tabs.value.first { it.isPrivate }.id
        val privateSession = created[1]
        m.newPrivateTab()
        m.select(m.tabs.value.first { !it.isPrivate }.id)
        val privateActiveBefore = privateSession.active

        m.closeAllNormal()

        assertEquals(listOf(true, true, false), m.tabs.value.map { it.isPrivate })
        assertFalse(m.selected.value.isPrivate)
        assertTrue(m.selected.value.session === created.last())
        assertEquals(true, created.last().active)
        assertTrue(normalSession.closed)
        assertFalse(privateSession.closed)
        assertTrue(privateSession.host != null)
        assertEquals(privateActiveBefore, privateSession.active)
        assertTrue(m.tabs.value.any { it.id == privateId })
        assertEquals(0, ended)

        // Both private tabs close later: the callback fires once, with the last.
        m.closeAllPrivate()
        assertEquals(1, ended)
        assertEquals(1, m.tabs.value.size)
    }

    @Test
    fun aLaterCloseOfTheLastPrivateTabFiresTheCallbackOnce() {
        val m = manager()
        m.openSelected()
        m.newPrivateTab()
        val p1 = m.selected.value.id
        m.newPrivateTab()
        val p2 = m.selected.value.id
        m.closeAllNormal()
        assertEquals(0, ended)
        m.close(p1)
        assertEquals(0, ended)
        m.close(p2)
        assertEquals(1, ended)
        m.closeAllNormal()
        assertEquals(1, ended)
    }

    // ---- sessionless restored tabs ----

    @Test
    fun restoredTabsWithoutSessionsAreDroppedAndOnlyTheFreshOneGetsASession() {
        val store =
            storedTabs(
                """{"version":1,"selected":2,"tabs":[
                    {"id":1,"url":"https://a.example/","title":"A","desktop":false,"state":"STATE-A"},
                    {"id":2,"url":"https://b.example/","title":"B","desktop":true,"state":"STATE-B"}]}""",
            )
        val m = manager(store)
        assertEquals(listOf(1L, 2L), m.tabs.value.map { it.id })
        assertTrue(created.isEmpty())

        m.closeAllNormal()

        assertEquals(1, m.tabs.value.size)
        val fresh = m.tabs.value.single()
        assertTrue(fresh.id > 2)
        assertEquals("", fresh.url)
        assertEquals(1, created.size)
        assertEquals(null, created.single().init.restoredState)
        assertEquals(null, created.single().init.fallbackUrl)
        assertFalse(created.single().init.desktopMode)
        settle()
        val last = store.saves.last()
        assertEquals(listOf(fresh.id), last.tabs.map { it.id })
        assertNull(last.tabs.single().savedState)
    }

    // ---- what is written ----

    @Test
    fun theStoredTabListHoldsNoOldAddressOrStateAfterwards() {
        val store = ClearMemoryStore()
        val m = manager(store)
        m.openSelected()
        created[0].saved = "NORMAL-STATE"
        created[0].navigate("https://secret-a.example/", "Secret A")
        m.newTab("https://secret-b.example/")
        created[1].saved = "NORMAL-STATE-2"
        created[1].navigate("https://secret-b.example/", "Secret B")
        scheduler.advanceTimeBy(5_000)
        scheduler.runCurrent()
        settle()
        assertTrue(store.saves.last().tabs.any { it.url.contains("secret-a") })

        m.closeAllNormal()
        // No flush() by the test: closeAllNormal has to write by itself.
        settle()

        val last = store.saves.last()
        val fresh = m.selected.value.id
        assertEquals(listOf(fresh), last.tabs.map { it.id })
        assertEquals(fresh, last.selectedId)
        assertEquals("", last.tabs.single().url)
        assertNull(last.tabs.single().savedState)
        val text = TabCodec.encode(last)
        assertFalse(text, text.contains("secret"))
        assertFalse(text, text.contains("STATE"))
        // Nothing later brings the old tabs back either.
        scheduler.advanceTimeBy(5_000)
        scheduler.runCurrent()
        settle()
        assertEquals(listOf(fresh), store.saves.last().tabs.map { it.id })
    }

    @Test
    fun withAPrivateTabSelectedTheStoredListSelectsTheFreshTabAndHoldsNoPrivateData() {
        val store = ClearMemoryStore()
        val m = manager(store)
        m.openSelected()
        created[0].navigate("https://old.example/", "Old")
        m.newPrivateTab("https://secret.example/")
        created[1].saved = "PRIVATE-STATE"
        created[1].navigate("https://secret.example/page", "Secret")

        m.closeAllNormal()
        settle()

        val fresh = m.tabs.value.first { !it.isPrivate }.id
        val last = store.saves.last()
        assertEquals(listOf(fresh), last.tabs.map { it.id })
        assertEquals(fresh, last.selectedId)
        val text = TabCodec.encode(last)
        assertFalse(text, text.contains("old.example"))
        assertFalse(text, text.contains("secret"))
        assertFalse(text, text.contains("PRIVATE"))
    }

    @Test
    fun closingBeforeTheStoredListWasReadReplacesItInsteadOfRestoringIt() {
        val store =
            storedTabs(
                """{"version":1,"selected":1,"tabs":[{"id":1,"url":"https://old.example/","title":"Old","desktop":false}]}""",
            )
        val m = manager(store, restore = false)
        m.closeAllNormal()
        // Not ready: nothing may be written yet, and the stored list is still the old one.
        settle()
        m.restore()
        settle()

        assertEquals(1, m.tabs.value.size)
        assertTrue(m.tabs.value.single().id != 1L)
        assertEquals("", m.tabs.value.single().url)
        val last = store.saves.last()
        assertEquals(listOf(m.selected.value.id), last.tabs.map { it.id })
        assertFalse(TabCodec.encode(last).contains("old.example"))
    }

    // ---- cleanup of what belonged to closed tabs ----

    @Test
    fun aPendingDownloadAndLinkMenuOfAClosedTabAreDropped() {
        val m = manager()
        m.openSelected()
        val download = ClearFakeDownload()
        m.onDownload(download, created[0])
        m.onLinkContextMenu("https://link.example/", created[0])
        assertTrue(m.pendingDownload.value != null)
        assertTrue(m.linkMenu.value != null)

        m.closeAllNormal()

        assertNull(m.pendingDownload.value)
        assertNull(m.linkMenu.value)
        assertEquals(1, download.discarded)
    }

    @Test
    fun aPendingDownloadAndLinkMenuOfAPrivateTabSurvive() {
        val m = manager()
        m.openSelected()
        m.newPrivateTab()
        val download = ClearFakeDownload()
        m.onDownload(download, created[1])
        m.onLinkContextMenu("https://link.example/", created[1])

        m.closeAllNormal()

        assertTrue(m.pendingDownload.value?.isPrivate == true)
        assertTrue(m.linkMenu.value != null)
        assertEquals(0, download.discarded)
    }

    @Test
    fun aClosedTabsLateStateAndVisitsReachNeitherTheListNorHistory() {
        val visits = mutableListOf<String>()
        val m = TabManager(
            factory = { init -> ClearFakeSession(init).also { created += it } },
            store = ClearMemoryStore(),
            scope = scope,
            log = {},
            io = dispatcher,
            visits = object : VisitSink {
                override fun onVisit(url: String, title: String) { visits += url }
                override fun onTitle(url: String, title: String) { visits += url }
            },
        )
        m.restore()
        settle()
        m.openSelected()
        val old = created[0]
        m.closeAllNormal()

        old.navigate("https://late.example/", "Late")
        m.onVisit(old, "https://late.example/", "Late")
        m.onVisitTitle(old, "https://late.example/", "Late")
        m.onCloseRequest(old)

        assertTrue(visits.isEmpty())
        assertEquals(1, m.tabs.value.size)
        assertEquals("", m.tabs.value.single().url)
    }

    // ---- hasPrivateTabs ----

    @Test
    fun hasPrivateTabsFollowsThePrivateTabsOpen() {
        val m = manager()
        assertFalse(m.hasPrivateTabs)
        m.newPrivateTab()
        assertTrue(m.hasPrivateTabs)
        m.newPrivateTab()
        m.close(m.selected.value.id)
        assertTrue(m.hasPrivateTabs)
        m.closeAllPrivate()
        assertFalse(m.hasPrivateTabs)
    }
}
