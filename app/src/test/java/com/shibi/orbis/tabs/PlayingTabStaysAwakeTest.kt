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
import org.junit.Assert.assertNotNull
import org.junit.Test

private class AwakeFakeSession(val init: SessionInit) : BrowserSession {
    override val isPrivate get() = init.isPrivate
    val _state = MutableStateFlow(SessionState(desktopMode = init.desktopMode))
    override val state: StateFlow<SessionState> = _state
    override val prompts: StateFlow<SitePrompt?> = MutableStateFlow(null)
    override var host: SessionHost? = null
    var active: Boolean? = null

    /** Every setActive(false) call, so "at once" and "once" can be asserted. */
    var sleepCalls = 0

    override fun attachTo(contentView: View) = Unit
    override fun detachFrom(contentView: View) = Unit
    override fun loadUrl(url: String) = Unit
    override fun loadExternalUrl(url: String) = Unit
    override fun loadImportedFile(url: String) = Unit
    override fun setDesktopMode(enabled: Boolean) = Unit
    override fun goBack() = false
    override fun goForward() = false
    override fun reload() = Unit
    override fun stop() = Unit
    override fun find(query: String, backwards: Boolean) = Unit
    override fun clearFind() = Unit
    override fun setActive(active: Boolean) {
        if (!active) sleepCalls++
        this.active = active
    }
    override fun savedState(): String? = null
    override fun close() = Unit
}

private class AwakeStore : TabStore {
    override fun load(): TabsSnapshot? = null
    override fun save(snapshot: TabsSnapshot) = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlayingTabStaysAwakeTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val created = mutableListOf<AwakeFakeSession>()
    private val importUrl =
        "file:///data/user/0/com.shibi.orbis/cache/imports/11111111-1111-1111-1111-111111111111/notes.pdf"

    @After
    fun tearDown() = scope.cancel()

    private fun manager(): TabManager {
        val m = TabManager(
            factory = { init -> AwakeFakeSession(init).also { created += it } },
            store = AwakeStore(),
            scope = scope,
            log = {},
            io = dispatcher,
        )
        m.restore()
        scheduler.advanceUntilIdle()
        m.openSelected()
        scheduler.advanceUntilIdle()
        return m
    }

    private fun AwakeFakeSession.play(playing: Boolean) {
        _state.value = _state.value.copy(media = MediaState(playing = playing))
        scheduler.runCurrent()
    }

    private fun AwakeFakeSession.navigate(url: String) {
        _state.value = _state.value.copy(url = url, title = "t")
        scheduler.runCurrent()
    }

    /** One way of leaving the selected tab (the first session, a loaded web page). Returns when the new tab is selected. */
    private fun interface Leave {
        fun run(m: TabManager, first: AwakeFakeSession)
    }

    private val ways: Map<String, Leave> =
        mapOf(
            "select" to Leave { m, _ -> m.select(m.tabs.value.last().id) },
            "newTab" to Leave { m, _ -> assertNotNull(m.newTab("https://x.example/")) },
            "openImported" to Leave { m, _ -> assertEquals(true, m.openImported(importUrl)) },
            "reopen" to Leave { m, _ ->
                assertNotNull(m.reopen(ClosedTab("https://closed.example/", "c", false, null)))
            },
            "duplicate" to Leave { m, first ->
                // The tab being left is the one duplicated: the copy opens right after it and takes the selection.
                first.navigate("https://page.example/")
                assertNotNull(m.duplicate(m.tabs.value.first().id))
            },
            "popup" to Leave { m, first ->
                m.onPopupSession(AwakeFakeSession(SessionInit()), first)
            },
            "adopt" to Leave { m, _ -> assertEquals(true, m.adoptSession(AwakeFakeSession(SessionInit()))) },
        )

    private fun leaveFirst(name: String, playing: Boolean): AwakeFakeSession {
        val m = manager()
        val first = created[0]
        if (name == "select") {
            // Switching needs a second tab: open one, then come back to the first so that leaving it is the switch.
            val firstId = m.selected.value.id
            m.newTab("https://other.example/")
            m.select(firstId)
        }
        first.navigate("https://page.example/")
        first.play(playing)
        first.sleepCalls = 0
        ways.getValue(name).run(m, first)
        scheduler.runCurrent()
        return first
    }

    @Test
    fun aPlayingTabIsNotPutToSleepByAnyWayOfLeavingIt() {
        for (name in ways.keys) {
            created.clear()
            val first = leaveFirst(name, playing = true)
            assertEquals("$name: not put to sleep", 0, first.sleepCalls)
            assertEquals("$name: still active", true, first.active)
        }
    }

    @Test
    fun aTabThatIsNotPlayingIsPutToSleepByAnyWayOfLeavingIt() {
        for (name in ways.keys) {
            created.clear()
            val first = leaveFirst(name, playing = false)
            assertEquals("$name: put to sleep once", 1, first.sleepCalls)
            assertEquals("$name: inactive", false, first.active)
        }
    }

    @Test
    fun aKeptAwakeTabSleepsOnceWhenItStopsPlayingWhileAnotherTabIsSelected() {
        val m = manager()
        val first = created[0]
        first.play(true)
        m.newTab("https://b.example/")
        assertEquals(0, first.sleepCalls)
        first.play(false)
        assertEquals(1, first.sleepCalls)
        assertEquals(false, first.active)
        // Later state changes (a new title, playing again) do not put it to sleep a second time or wake it.
        first.play(false)
        first.navigate("https://elsewhere.example/")
        assertEquals(1, first.sleepCalls)
    }

    @Test
    fun aKeptAwakeTabThatIsSelectedAgainIsActiveAndSleepsWhenLeftWhileNotPlaying() {
        val m = manager()
        val first = created[0]
        val firstId = m.selected.value.id
        first.play(true)
        m.newTab("https://b.example/")
        assertEquals(0, first.sleepCalls)
        m.select(firstId)
        assertEquals(true, first.active)
        // Stops playing while selected: nothing puts it to sleep.
        first.play(false)
        assertEquals(0, first.sleepCalls)
        m.newTab("https://c.example/")
        assertEquals(1, first.sleepCalls)
        assertEquals(false, first.active)
    }

    @Test
    fun aTabThatStartsPlayingAfterItWasLeftStaysAsleep() {
        val m = manager()
        val first = created[0]
        m.newTab("https://b.example/")
        assertEquals(1, first.sleepCalls)
        first.play(true)
        first.play(false)
        assertEquals(1, first.sleepCalls)
    }

    @Test
    fun twoPlayingTabsBothStayAwakeAndEachSleepsWhenItStops() {
        val m = manager()
        val first = created[0]
        first.play(true)
        m.newTab("https://b.example/")
        val second = created[1]
        second.play(true)
        m.newTab("https://c.example/")
        assertEquals(0, first.sleepCalls)
        assertEquals(0, second.sleepCalls)
        second.play(false)
        assertEquals(0, first.sleepCalls)
        assertEquals(1, second.sleepCalls)
        first.play(false)
        assertEquals(1, first.sleepCalls)
    }

    @Test
    fun aKeptAwakeTabThatIsClosedIsForgotten() {
        val m = manager()
        val first = created[0]
        val firstId = m.selected.value.id
        first.play(true)
        m.newTab("https://b.example/")
        m.close(firstId)
        first.play(false)
        assertEquals(0, first.sleepCalls)
    }

    @Test
    fun aPlayingPrivateTabIsPutToSleepAtOnceBySwitchingAndByNewPrivateTab() {
        val m = manager()
        val normalId = m.selected.value.id
        val priv = m.newPrivateTab("https://p.example/")!!
        val privSession = created[1]
        privSession.play(true)
        m.select(normalId)
        assertEquals(1, privSession.sleepCalls)
        assertEquals(false, privSession.active)
        m.select(priv)
        privSession.sleepCalls = 0
        m.newPrivateTab("https://p2.example/")
        assertEquals(1, privSession.sleepCalls)
        assertEquals(false, privSession.active)
        // Stopping later changes nothing.
        privSession.play(false)
        assertEquals(1, privSession.sleepCalls)
    }

    @Test
    fun aPlayingNormalAndAPlayingPrivateTabSideBySideBehaveDifferently() {
        val m = manager()
        val normal = created[0]
        val normalId = m.selected.value.id
        normal.play(true)
        val priv = m.newPrivateTab("https://p.example/")!!
        val privSession = created[1]
        assertEquals(0, normal.sleepCalls)
        privSession.play(true)
        m.select(normalId)
        assertEquals(1, privSession.sleepCalls)
        assertEquals(false, privSession.active)
        assertEquals(true, normal.active)
        m.select(priv)
        m.select(normalId)
        m.newTab("https://c.example/")
        assertEquals(0, normal.sleepCalls)
        assertEquals(true, normal.active)
    }
}
