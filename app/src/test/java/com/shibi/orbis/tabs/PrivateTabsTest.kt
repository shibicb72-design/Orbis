package com.shibi.orbis.tabs

import android.view.View
import com.shibi.orbis.engine.BrowserSession
import com.shibi.orbis.engine.ContextMenuItem
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class PrivateFakeSession(val init: SessionInit) : BrowserSession {
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
    var savedStateReads = 0

    override fun attachTo(contentView: View) = Unit
    override fun detachFrom(contentView: View) = Unit
    override fun loadUrl(url: String) { loads += url }
    val saves = mutableListOf<String>()
    override fun saveUrl(url: String) { saves += url }
    override fun loadExternalUrl(url: String) { externalLoads += url }
    override fun setDesktopMode(enabled: Boolean) { _state.value = _state.value.copy(desktopMode = enabled) }
    override fun goBack() = false
    override fun goForward() = false
    override fun reload() = Unit
    override fun stop() = Unit
    override fun find(query: String, backwards: Boolean) = Unit
    override fun clearFind() = Unit
    override fun setActive(active: Boolean) { this.active = active }
    override fun savedState(): String? { savedStateReads++; return saved }
    override fun close() { closed = true }
}

private class PrivateMemoryStore(var snapshot: TabsSnapshot? = null) : TabStore {
    val saves = mutableListOf<TabsSnapshot>()
    override fun load(): TabsSnapshot? = snapshot
    override fun save(snapshot: TabsSnapshot) { saves += snapshot; this.snapshot = snapshot }
}

private class PrivateRecordingSink : VisitSink {
    val visits = mutableListOf<Pair<String, String>>()
    val titles = mutableListOf<Pair<String, String>>()
    override fun onVisit(url: String, title: String) { visits += url to title }
    override fun onTitle(url: String, title: String) { titles += url to title }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateTabsTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val created = mutableListOf<PrivateFakeSession>()
    private val logs = mutableListOf<String>()

    @After
    fun tearDown() = scope.cancel()

    private fun manager(
        store: PrivateMemoryStore = PrivateMemoryStore(),
        visits: VisitSink = VisitSink.None,
        onEnded: () -> Unit = {},
    ): TabManager {
        val m = TabManager(
            factory = { init -> PrivateFakeSession(init).also { created += it } },
            store = store,
            scope = scope,
            log = { logs += it },
            io = dispatcher,
            visits = visits,
            onPrivateBrowsingEnded = onEnded,
        )
        m.restore()
        scheduler.advanceUntilIdle()
        return m
    }

    private fun PrivateFakeSession.navigate(url: String, title: String? = null) {
        _state.value = _state.value.copy(url = url, title = title)
        scheduler.runCurrent()
    }

    private fun TabManager.selectedIsPrivate() = selected.value.isPrivate

    private fun TabManager.flushAndSettle() {
        flush()
        scheduler.advanceUntilIdle()
    }

    // ---- creation ----

    @Test
    fun newPrivateTabCreatesAPrivateSessionAndNewTabDoesNot() {
        val m = manager()
        m.openSelected()
        val priv = m.newPrivateTab("https://p.example/")
        val normal = m.newTab("https://n.example/")
        assertNotNull(priv)
        assertNotNull(normal)
        assertFalse(created[0].init.isPrivate)
        assertTrue(created[1].init.isPrivate)
        assertEquals(listOf("https://p.example/"), created[1].loads)
        assertFalse(created[2].init.isPrivate)
        assertEquals(listOf(false, true, false), m.tabs.value.map { it.isPrivate })
        assertTrue(created[1].isPrivate)
        assertFalse(created[2].isPrivate)
    }

    @Test
    fun aPrivateTabKeepsTheTabCap() {
        val m = TabManager(
            factory = { init -> PrivateFakeSession(init).also { created += it } },
            store = PrivateMemoryStore(),
            scope = scope,
            log = { logs += it },
            io = dispatcher,
            maxTabs = 2,
        )
        m.restore()
        scheduler.advanceUntilIdle()
        assertNotNull(m.newPrivateTab())
        assertNull(m.newPrivateTab())
        assertEquals(2, m.tabs.value.size)
    }

    // ---- history ----

    @Test
    fun visitsFromAPrivateSessionNeverReachTheSinkButNormalOnesDo() {
        val sink = PrivateRecordingSink()
        val m = manager(visits = sink)
        m.openSelected()
        m.newPrivateTab()
        m.onVisit(created[1], "https://secret.example/", "Secret")
        m.onVisitTitle(created[1], "https://secret.example/", "Secret title")
        assertTrue(sink.visits.isEmpty())
        assertTrue(sink.titles.isEmpty())

        m.onVisit(created[0], "https://a.example/", "A")
        m.onVisitTitle(created[0], "https://a.example/", "A title")
        assertEquals(listOf("https://a.example/" to "A"), sink.visits)
        assertEquals(listOf("https://a.example/" to "A title"), sink.titles)
    }

    // ---- persistence ----

    @Test
    fun privateTabsNeverAppearInASavedSnapshotEvenAfterAFlush() {
        val store = PrivateMemoryStore()
        val m = manager(store)
        m.openSelected()
        created[0].saved = "NORMAL-STATE"
        created[0].navigate("https://normal.example/", "Normal")
        val normalId = m.selected.value.id
        m.newPrivateTab("https://secret.example/")
        created[1].saved = "PRIVATE-STATE"
        created[1].navigate("https://secret.example/page", "Secret")
        scheduler.advanceTimeBy(5_000)
        scheduler.runCurrent()
        m.flushAndSettle()

        assertTrue(store.saves.isNotEmpty())
        for (saved in store.saves) {
            assertEquals(listOf(normalId), saved.tabs.map { it.id })
            assertTrue(saved.tabs.none { it.url.contains("secret") || it.title == "Secret" || it.savedState == "PRIVATE-STATE" })
        }
        val last = store.saves.last()
        assertEquals("NORMAL-STATE", last.tabs.single().savedState)
        // A private tab is selected, so the stored selection is the normal tab the user came from.
        assertTrue(m.selectedIsPrivate())
        assertEquals(normalId, last.selectedId)
        assertFalse(TabCodec.encode(last).contains("secret"))
        assertFalse(TabCodec.encode(last).contains("PRIVATE-STATE"))
    }

    @Test
    fun savedStateIsNeverReadFromAPrivateSession() {
        val store = PrivateMemoryStore()
        val m = manager(store)
        m.openSelected()
        m.newPrivateTab()
        created[1].navigate("https://secret.example/", "Secret")
        created[1].saved = "PRIVATE-STATE"
        m.onSessionStateChanged(created[1])
        scheduler.advanceTimeBy(5_000)
        scheduler.runCurrent()
        m.flushAndSettle()
        m.flushAndSettle()
        assertEquals(0, created[1].savedStateReads)
        // The normal session is read as before.
        assertTrue(created[0].savedStateReads > 0)
    }

    @Test
    fun aPrivateTabsStateChangesScheduleNoWrite() {
        val store = PrivateMemoryStore()
        val m = manager(store)
        m.openSelected()
        m.newPrivateTab()
        // Let the writes caused by creating the tabs finish; only later ones are counted.
        scheduler.advanceTimeBy(5_000)
        scheduler.runCurrent()
        scheduler.advanceUntilIdle()
        val before = store.saves.size
        created[1].navigate("https://secret.example/", "Secret")
        m.onSessionStateChanged(created[1])
        scheduler.advanceTimeBy(5_000)
        scheduler.runCurrent()
        scheduler.advanceUntilIdle()
        assertEquals(before, store.saves.size)
    }

    @Test
    fun onlyPrivateTabsLeftSavesAnEmptyListThatDecodesToNothing() {
        val store = PrivateMemoryStore()
        val m = manager(store)
        m.openSelected()
        val normalId = m.selected.value.id
        m.newPrivateTab("https://secret.example/")
        m.close(normalId)
        // The last normal tab is gone but private tabs remain, so no fresh tab is made.
        assertEquals(listOf(true), m.tabs.value.map { it.isPrivate })
        m.flushAndSettle()
        val last = store.saves.last()
        assertTrue(last.tabs.isEmpty())
        assertEquals(-1L, last.selectedId)
        assertNull(TabCodec.decode(TabCodec.encode(last)))
    }

    @Test
    fun restoreBringsBackOnlyNormalTabs() {
        val store =
            PrivateMemoryStore(
                TabCodec.decode(
                    """{"version":1,"selected":5,"tabs":[
                        {"id":5,"url":"https://a.example/","title":"A","desktop":false,"private":true},
                        {"id":6,"url":"https://b.example/","title":"B","desktop":true}]}""",
                ),
            )
        val m = manager(store)
        assertEquals(listOf(5L, 6L), m.tabs.value.map { it.id })
        assertTrue(m.tabs.value.none { it.isPrivate })
        assertFalse(m.selected.value.isPrivate)
        assertTrue(created.isEmpty())
        m.openSelected()
        assertFalse(created.single().init.isPrivate)
        assertNotNull(m.newTab())
        assertFalse(created.last().init.isPrivate)
    }

    // ---- persistableSnapshot (pure) ----

    private fun tab(id: Long, isPrivate: Boolean, session: BrowserSession? = null, saved: String? = null) =
        Tab(id, session, "T$id", "https://t$id.example/", false, saved, null, isPrivate)

    @Test
    fun snapshotSelectsTheSelectedTabWhenItIsNormal() {
        val s = persistableSnapshot(listOf(tab(1, false), tab(2, true), tab(3, false)), selectedId = 3, lastNormalSelectedId = 1)
        assertEquals(listOf(1L, 3L), s.tabs.map { it.id })
        assertEquals(3L, s.selectedId)
    }

    @Test
    fun snapshotSelectsTheLastNormalTabWhileAPrivateOneIsSelected() {
        val tabs = listOf(tab(1, false), tab(2, false), tab(3, true))
        assertEquals(2L, persistableSnapshot(tabs, selectedId = 3, lastNormalSelectedId = 2).selectedId)
        // Gone: the first normal tab. Never known: the same.
        assertEquals(1L, persistableSnapshot(tabs, selectedId = 3, lastNormalSelectedId = 9).selectedId)
        assertEquals(1L, persistableSnapshot(tabs, selectedId = 3, lastNormalSelectedId = null).selectedId)
        // Pointing at a private tab is not an answer either.
        assertEquals(1L, persistableSnapshot(tabs, selectedId = 3, lastNormalSelectedId = 3).selectedId)
    }

    @Test
    fun snapshotWithNoNormalTabIsEmpty() {
        val s = persistableSnapshot(listOf(tab(2, true), tab(3, true)), selectedId = 3, lastNormalSelectedId = 1)
        assertTrue(s.tabs.isEmpty())
        assertEquals(-1L, s.selectedId)
        assertEquals(TabsSnapshot(emptyList(), -1L), persistableSnapshot(emptyList(), -1L, null))
    }

    @Test
    fun snapshotReadsNoPrivateSessionAndKeepsStoredStateForSessionlessTabs() {
        val priv = PrivateFakeSession(SessionInit(isPrivate = true)).also { it.saved = "SECRET" }
        val normal = PrivateFakeSession(SessionInit()).also { it.saved = "LIVE" }
        val s =
            persistableSnapshot(
                listOf(tab(1, false, normal, "OLD"), tab(2, true, priv, "SECRET-OLD"), tab(3, false, null, "STORED")),
                selectedId = 2,
                lastNormalSelectedId = 3,
            )
        assertEquals(0, priv.savedStateReads)
        assertEquals(listOf("LIVE", "STORED"), s.tabs.map { it.savedState })
        assertEquals(3L, s.selectedId)
    }

    // ---- inheritance ----

    @Test
    fun aPopupOfAPrivateOpenerIsPrivateAndOfANormalOneIsNormal() {
        val m = manager()
        m.openSelected()
        val normalId = m.selected.value.id
        m.newPrivateTab()
        val privateId = m.selected.value.id

        // The engine builds the child from the opener's flag.
        val privateChild = PrivateFakeSession(SessionInit(isPrivate = created[1].isPrivate))
        m.onPopupSession(privateChild, created[1])
        assertTrue(m.selected.value.isPrivate)
        assertEquals(privateId, m.selected.value.openerTabId)
        assertEquals(true, m.tabs.value.last().isPrivate)

        m.select(normalId)
        val normalChild = PrivateFakeSession(SessionInit(isPrivate = created[0].isPrivate))
        m.onPopupSession(normalChild, created[0])
        assertFalse(m.selected.value.isPrivate)
        assertEquals(normalId, m.selected.value.openerTabId)
    }

    @Test
    fun openInNewTabFromAPrivateSessionIsPrivateEvenWhenItsTabIsGone() {
        val m = manager()
        m.openSelected()
        m.newPrivateTab()
        val opener = created[1]
        m.onOpenInNewTab("https://next.example/", opener)
        assertTrue(m.selected.value.isPrivate)
        assertTrue(created[2].init.isPrivate)

        // The opener's tab is closed before the callback arrives: the session's own flag still decides.
        val gone = m.tabs.value.first { it.isPrivate }.id
        m.close(gone)
        m.onOpenInNewTab("https://later.example/", opener)
        assertTrue(m.selected.value.isPrivate)
        assertTrue(created.last().init.isPrivate)
        assertNull(m.selected.value.openerTabId)

        // And a normal opener stays normal.
        m.onOpenInNewTab("https://normal.example/", created[0])
        assertFalse(m.selected.value.isPrivate)
        assertFalse(created.last().init.isPrivate)
    }

    @Test
    fun aTabPlayingMediaStaysActiveInTheBackgroundAndASilentOneSleeps() {
        val m = manager()
        m.openSelected()
        m.setForeground(true)
        val s = created[0]
        assertEquals(true, s.active)
        s._state.value = s._state.value.copy(media = com.shibi.orbis.engine.MediaState(playing = true))
        m.setForeground(false)
        assertEquals("playing: kept active", true, s.active)
        s._state.value = s._state.value.copy(media = com.shibi.orbis.engine.MediaState(playing = false))
        m.setForeground(true)
        m.setForeground(false)
        assertEquals("paused: put to sleep", false, s.active)
        assertEquals(s, m.sessionOf(m.selected.value.id))
    }

    @Test
    fun imageMenuOffersImageActionsInTheModeOfItsTab() {
        val m = manager()
        m.openSelected()
        m.onImageContextMenu("https://img.example/a.png", null, created[0])
        val plain = m.linkMenu.value!!
        assertEquals("https://img.example/a.png", plain.imageUrl)
        assertFalse(plain.hasLink)
        m.saveImage(plain)
        assertEquals(listOf("https://img.example/a.png"), created[0].saves)
        assertNull("saving closes the menu", m.linkMenu.value)

        m.newPrivateTab()
        val privateSession = created.last()
        m.onImageContextMenu("https://img.example/p.png", "https://link.example/q", privateSession)
        val linked = m.linkMenu.value!!
        assertTrue(linked.hasLink)
        assertEquals("https://link.example/q", linked.url)
        assertTrue(linked.isPrivate)
        m.openImageInNewTab(linked)
        assertTrue(m.selected.value.isPrivate)
        assertEquals(listOf("https://img.example/p.png"), created.last().loads)
    }

    @Test
    fun aBackgroundTabsLongPressOpensNoMenu() {
        val m = manager()
        m.openSelected()
        val first = created[0]
        m.newTab()
        m.onImageContextMenu("https://img.example/a.png", null, first)
        assertNull(m.linkMenu.value)
    }

    @Test
    fun linkMenuOpenInNewTabFollowsTheModeOfTheTabItCameFrom() {
        val m = manager()
        m.openSelected()
        m.onLinkContextMenu("https://link.example/", created[0])
        assertEquals(false, m.linkMenu.value?.isPrivate)
        m.openLinkInNewTab(m.linkMenu.value!!)
        assertFalse(m.selected.value.isPrivate)

        m.newPrivateTab()
        val session = created.last()
        m.onLinkContextMenu("https://link.example/p", session)
        val request = m.linkMenu.value!!
        assertTrue(request.isPrivate)
        m.openLinkInNewTab(request)
        assertTrue(m.selected.value.isPrivate)
        assertTrue(created.last().init.isPrivate)
        assertEquals(listOf("https://link.example/p"), created.last().loads)
        assertNotNull(m.selected.value.openerTabId)
    }

    @Test
    fun magnetLongPressKeepsTheAddressAndDoesNotOpenItAsAPage() {
        val m = manager()
        m.openSelected()
        val magnet = "magnet:?xt=urn:btih:abc"
        m.onLinkContextMenu(magnet, created[0])
        val request = m.linkMenu.value!!
        assertEquals(magnet, request.url)
        val items = request.pageMenu()!!.items
        assertTrue(items.contains(ContextMenuItem.COPY_LINK_ADDRESS))
        assertTrue(items.contains(ContextMenuItem.OPEN_EXTERNAL))
        assertFalse(items.contains(ContextMenuItem.OPEN_NEW_TAB))
        assertFalse(items.contains(ContextMenuItem.DOWNLOAD_LINK))
        m.openLinkInNewTab(request)
        m.downloadLink(LinkMenuRequest(magnet, m.selected.value.id))
        assertEquals("magnet is not a page or a file download", 1, m.tabs.value.size)
        assertTrue(created[0].loads.isEmpty())
        assertTrue(created[0].saves.isEmpty())
        m.onLinkContextMenu(magnet, created[0])
        m.openExternalLink(m.linkMenu.value!!)
        assertNull("opening externally closes the menu", m.linkMenu.value)
    }

    @Test
    fun openInNewPrivateTabAndDownloadLinkReuseTheTabAndSavePaths() {
        val m = manager()
        m.openSelected()
        m.onLinkContextMenu("https://example.com/file.zip", created[0])
        m.downloadLink(m.linkMenu.value!!)
        assertEquals(listOf("https://example.com/file.zip"), created[0].saves)
        assertNull(m.linkMenu.value)

        m.onLinkContextMenu("https://link.example/p", created[0])
        m.openLinkInPrivateTab(m.linkMenu.value!!)
        assertTrue(m.selected.value.isPrivate)
        assertFalse(created[0].init.isPrivate)
        assertEquals(listOf("https://link.example/p"), created.last().loads)
    }

    @Test
    fun aDownloadFromAPrivateTabIsMarkedPrivate() {
        val m = manager()
        m.openSelected()
        m.newPrivateTab()
        val download = object : com.shibi.orbis.engine.IncomingDownload {
            override val url = "https://example.com/f.zip"
            override val contentDisposition: String? = null
            override val contentType: String? = null
            override val contentLength = -1L
            override fun openBody(): java.io.InputStream? = null
            override fun discard() = Unit
        }
        m.onDownload(download, created[1])
        assertEquals(true, m.pendingDownload.value?.isPrivate)
        m.resolveDownload(m.pendingDownload.value!!)
        m.select(m.tabs.value.first().id)
        m.onDownload(download, created[0])
        assertEquals(false, m.pendingDownload.value?.isPrivate)
    }

    @Test
    fun openExternalNeverReusesABlankPrivateTab() {
        val m = manager()
        m.newPrivateTab()
        val priv = created[0]
        val privateId = m.selected.value.id
        m.openExternal("https://ext.example/")

        assertEquals(3, m.tabs.value.size)
        assertFalse(m.selected.value.isPrivate)
        assertTrue(m.selected.value.id != privateId)
        assertEquals(listOf("https://ext.example/"), created.last().externalLoads)
        assertFalse(created.last().init.isPrivate)
        // The private tab was left alone.
        assertTrue(priv.externalLoads.isEmpty())
        assertTrue(priv.loads.isEmpty())
        assertFalse(priv.closed)
        assertEquals(1, m.tabs.value.count { it.isPrivate })
    }

    @Test
    fun openExternalStillReusesABlankNormalTab() {
        val m = manager()
        m.openSelected()
        m.openExternal("https://ext.example/")
        assertEquals(1, m.tabs.value.size)
        assertEquals(listOf("https://ext.example/"), created[0].externalLoads)
    }

    // ---- ending a private set ----

    @Test
    fun theEndCallbackFiresOnceWhenTheLastPrivateTabCloses() {
        var ended = 0
        val m = manager(onEnded = { ended++ })
        m.openSelected()
        m.newPrivateTab()
        val first = m.selected.value.id
        m.newPrivateTab()
        val second = m.selected.value.id
        m.close(first)
        assertEquals(0, ended)
        m.close(second)
        assertEquals(1, ended)
    }

    @Test
    fun theEndCallbackRunsAfterTheSessionIsClosed() {
        var closedWhenCalled: Boolean? = null
        val m = manager(onEnded = { closedWhenCalled = created[1].closed })
        m.openSelected()
        m.newPrivateTab()
        m.close(m.selected.value.id)
        assertEquals(true, closedWhenCalled)
    }

    @Test
    fun closingNormalTabsNeverFiresTheEndCallback() {
        var ended = 0
        val m = manager(onEnded = { ended++ })
        m.openSelected()
        m.newTab()
        m.newPrivateTab()
        m.close(m.tabs.value[0].id)
        m.close(m.tabs.value[0].id)
        assertEquals(0, ended)
        assertEquals(listOf(true), m.tabs.value.map { it.isPrivate })
        // Closing the last normal tab when none were private also fires nothing.
        val n = manager(onEnded = { ended++ })
        n.openSelected()
        n.close(n.selected.value.id)
        assertEquals(0, ended)
    }

    @Test
    fun closeAllPrivateFiresOnceAndLeavesNormalTabsAlone() {
        var ended = 0
        val m = manager(onEnded = { ended++ })
        m.openSelected()
        m.newTab()
        val normalIds = m.tabs.value.map { it.id }
        val normalSessions = created.toList()
        m.newPrivateTab()
        m.newPrivateTab()
        m.newPrivateTab()
        val privateSessions = created.drop(2)

        m.closeAllPrivate()

        assertEquals(1, ended)
        assertEquals(normalIds, m.tabs.value.map { it.id })
        assertTrue(m.tabs.value.none { it.isPrivate })
        assertTrue(normalSessions.none { it.closed })
        assertTrue(privateSessions.all { it.closed })
        // Closing again does nothing.
        m.closeAllPrivate()
        assertEquals(1, ended)
    }

    @Test
    fun closeAllPrivateReturnsToTheNormalTabTheUserCameFrom() {
        val m = manager()
        m.openSelected()
        val first = m.selected.value.id
        m.newTab()
        m.select(first)
        m.newPrivateTab()
        m.newPrivateTab()
        m.closeAllPrivate()
        assertEquals(first, m.selected.value.id)
        assertFalse(m.selected.value.isPrivate)
    }

    @Test
    fun closeAllPrivateWithOnlyPrivateTabsLeavesOneFreshNormalTab() {
        var ended = 0
        val m = manager(onEnded = { ended++ })
        m.openSelected()
        val normalId = m.selected.value.id
        m.newPrivateTab()
        m.newPrivateTab()
        m.close(normalId)
        assertTrue(m.tabs.value.all { it.isPrivate })

        m.closeAllPrivate()

        assertEquals(1, ended)
        assertEquals(1, m.tabs.value.size)
        assertFalse(m.tabs.value.single().isPrivate)
        assertFalse(m.selected.value.isPrivate)
        assertTrue(m.selected.value.id != normalId)
    }

    @Test
    fun closingTheOnlyTabWhenItIsPrivateLeavesAFreshNormalTabAndEnds() {
        var ended = 0
        val m = manager(onEnded = { ended++ })
        val normalId = m.selected.value.id
        m.newPrivateTab()
        m.close(normalId)
        assertEquals(listOf(true), m.tabs.value.map { it.isPrivate })
        m.close(m.selected.value.id)
        assertEquals(1, ended)
        assertEquals(1, m.tabs.value.size)
        assertFalse(m.tabs.value.single().isPrivate)
        assertFalse(m.selected.value.isPrivate)
    }

    @Test
    fun aThrowingEndCallbackNeverBreaksClosing() {
        val m = manager(onEnded = { throw IllegalStateException("engine refused") })
        m.openSelected()
        m.newPrivateTab()
        val session = created[1]
        val id = m.selected.value.id
        m.close(id)
        assertTrue(session.closed)
        assertEquals(1, m.tabs.value.size)
        assertFalse(m.selected.value.isPrivate)
        // The class only: no message text in the log.
        assertTrue(logs.any { it == "private data clear failed: IllegalStateException" })
        assertTrue(logs.none { it.contains("engine refused") })
    }

    @Test
    fun aNewPrivateSetAfterTheEndFiresAgainWhenItEnds() {
        var ended = 0
        val m = manager(onEnded = { ended++ })
        m.openSelected()
        m.newPrivateTab()
        m.close(m.selected.value.id)
        assertEquals(1, ended)
        m.newPrivateTab()
        m.newPrivateTab()
        m.closeAllPrivate()
        assertEquals(2, ended)
    }

    // ---- next selection ----

    @Test
    fun closingAPrivateTabPrefersTheNextPrivateTabOverItsNeighbour() {
        val m = manager()
        m.openSelected()
        val p1 = m.newPrivateTab()!!
        m.newTab()
        val p2 = m.newPrivateTab()!!
        m.select(p1)
        // List is [normal, p1, normal, p2]: the plain rule would pick the normal tab at p1's position.
        m.close(p1)
        assertEquals(p2, m.selected.value.id)
    }

    @Test
    fun closingANormalTabPrefersTheNextNormalTabOverAPrivateNeighbour() {
        val m = manager()
        m.openSelected()
        val first = m.selected.value.id
        m.newPrivateTab()
        val last = m.newTab()!!
        m.select(first)
        m.close(first)
        assertEquals(last, m.selected.value.id)
    }

    @Test
    fun theSearchFallsBackToEarlierTabsOfTheSameMode() {
        val m = manager()
        m.openSelected()
        val p0 = m.newPrivateTab()!!
        m.newTab()
        val p1 = m.newPrivateTab()!!
        // List is [normal, p0, normal, p1]; closing p1 finds nothing after it and p0 before the normal tab.
        m.close(p1)
        assertEquals(p0, m.selected.value.id)
    }

    @Test
    fun closingTheLastPrivateTabReturnsToTheNormalTabTheUserCameFrom() {
        val m = manager()
        m.openSelected()
        val a = m.selected.value.id
        val b = m.newTab()!!
        m.select(a)
        val p = m.newPrivateTab()!!
        // List is [a, b, p]: the position rule alone would pick b.
        m.close(p)
        assertEquals(a, m.selected.value.id)
        assertTrue(b != m.selected.value.id)
    }

    @Test
    fun ifTheTabTheUserCameFromIsGoneTheLastPrivateTabFallsBackToThePositionRule() {
        val m = manager()
        m.openSelected()
        val a = m.selected.value.id
        val b = m.newTab()!!
        m.select(a)
        val p = m.newPrivateTab()!!
        m.close(a)
        // List is [b, p]; a is no longer listed.
        m.close(p)
        assertEquals(b, m.selected.value.id)
        assertEquals(1, m.tabs.value.size)
    }

    @Test
    fun withNoPrivateTabsTheNeighbourRuleIsUnchanged() {
        val m = manager()
        m.openSelected()
        val a = m.selected.value.id
        val b = m.newTab()!!
        val c = m.newTab()!!
        m.select(b)
        m.close(b)
        assertEquals(c, m.selected.value.id)
        m.close(c)
        assertEquals(a, m.selected.value.id)
    }
}
