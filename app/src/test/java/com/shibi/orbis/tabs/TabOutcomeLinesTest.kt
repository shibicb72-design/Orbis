package com.shibi.orbis.tabs

import android.view.View
import com.shibi.orbis.diag.OutcomeLines
import com.shibi.orbis.diag.PopupResult
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
import org.junit.Assert.assertTrue
import org.junit.Test

private class OutcomeFakeSession(val init: SessionInit) : BrowserSession {
    override val isPrivate get() = init.isPrivate
    val _state = MutableStateFlow(SessionState())
    override val state: StateFlow<SessionState> = _state
    override val prompts: StateFlow<SitePrompt?> = MutableStateFlow(null)
    override var host: SessionHost? = null
    var closed = false

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
    override fun close() { closed = true }
}

private class OutcomeStore : TabStore {
    override fun load(): TabsSnapshot? = null
    override fun save(snapshot: TabsSnapshot) = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class TabOutcomeLinesTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val created = mutableListOf<OutcomeFakeSession>()
    private val logs = mutableListOf<String>()

    @After
    fun tearDown() = scope.cancel()

    private fun manager(): TabManager {
        val m = TabManager(
            factory = { init -> OutcomeFakeSession(init).also { created += it } },
            store = OutcomeStore(),
            scope = scope,
            log = { logs += it },
            io = dispatcher,
        )
        m.restore()
        scheduler.advanceUntilIdle()
        return m
    }

    @Test
    fun aLinkFromAnotherApp_isLoggedWithTheTabAndWhetherItWasNew() {
        val m = manager()
        val first = m.selected.value.id
        m.openExternal("https://link.example/a")
        // The blank tab is used: not a new tab.
        assertEquals(OutcomeLines.linkIn(com.shibi.orbis.diag.LinkVia.VIEW, false, first, newTab = false), logs.single())
        created.last()._state.value = created.last()._state.value.copy(url = "https://link.example/a")
        scheduler.runCurrent()
        m.openExternal("https://link.example/b")
        assertEquals(2, logs.size)
        assertTrue(logs[1], logs[1].startsWith("link in: via=view tab=") && logs[1].endsWith("new-tab=yes"))
        assertFalse(logs[1], logs[1].contains("tab=$first "))
        assertFalse(logs.joinToString(), logs.any { it.contains("link.example") })
    }

    @Test
    fun theHostGivesTheFactsOfATab() {
        val m = manager()
        val a = m.selected.value.id
        m.openExternal("https://x.example/")
        val sessionA = created.single()
        val facts = m.tabFacts(sessionA)
        assertEquals(a, facts.number)
        assertTrue(facts.selected)
        assertEquals(1, facts.open)
        assertEquals(1, facts.awake)
        m.newTab()
        assertEquals(2, m.tabFacts(sessionA).open)
        assertFalse(m.tabFacts(sessionA).selected)
        // A session that is in no tab has no number.
        assertEquals(0L, m.tabFacts(OutcomeFakeSession(SessionInit())).number)
    }

    @Test
    fun aPopupAtTheTabLimit_isClosed_andItsLineSaysSo() {
        val m = manager()
        m.openExternal("https://x.example/")
        val opener = created.single()
        opener.host = m
        while (m.tabs.value.size < MAX_TABS) m.newTab()
        assertEquals(MAX_TABS, m.tabs.value.size)

        val child = OutcomeFakeSession(SessionInit())
        m.onPopupSession(child, opener)
        assertTrue(child.closed)
        assertEquals(MAX_TABS, m.tabs.value.size)
        val childTab = m.tabFacts(child).number
        assertEquals(0L, childTab)
        val line = OutcomeLines.popup(OutcomeLines.popupResultOf(childTab), false, m.tabFacts(opener).number, childTab, 1)
        assertTrue(line, line.contains("result=closed-at-tab-limit") && line.contains("child=0"))
        assertFalse(line.contains("created"))
        assertEquals(PopupResult.CLOSED_AT_TAB_LIMIT, OutcomeLines.popupResultOf(childTab))
    }

    @Test
    fun aPopupBelowTheLimit_opensATab_andItsLineSaysOpened() {
        val m = manager()
        m.openExternal("https://x.example/")
        val opener = created.single()
        val child = OutcomeFakeSession(SessionInit())
        m.onPopupSession(child, opener)
        assertFalse(child.closed)
        val childTab = m.tabFacts(child).number
        assertTrue(childTab > 0L)
        assertEquals(PopupResult.OPENED, OutcomeLines.popupResultOf(childTab))
    }
}
