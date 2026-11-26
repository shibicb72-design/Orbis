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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A session whose page state, prompts and holds the test sets; [onSavedState] runs while the state is read. */
private class SleepFakeSession(val init: SessionInit) : BrowserSession {
    override val isPrivate get() = init.isPrivate
    val stateFlow = MutableStateFlow(SessionState())
    override val state: StateFlow<SessionState> = stateFlow
    val promptFlow = MutableStateFlow<SitePrompt?>(null)
    override val prompts: StateFlow<SitePrompt?> = promptFlow
    override var host: SessionHost? = null
    var saved: String? = null
    var holds = SleepHolds.NONE
    var onSavedState: () -> Unit = {}
    var closed = false

    override fun attachTo(contentView: View) = Unit
    override fun detachFrom(contentView: View) = Unit
    override fun loadUrl(url: String) {
        stateFlow.value = stateFlow.value.copy(url = url, title = "Page $url")
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
    override fun savedState(): String? {
        onSavedState()
        return saved
    }
    override fun sleepHolds(): SleepHolds = holds
    override fun close() {
        closed = true
    }
}

private class SleepMemoryStore(var snapshot: TabsSnapshot? = null) : TabStore {
    override fun load(): TabsSnapshot? = snapshot
    override fun save(snapshot: TabsSnapshot) { this.snapshot = snapshot }
}

/** The sleep pass of [TabManager]: what it puts to sleep, what it never does, and how a slept tab comes back. */
@OptIn(ExperimentalCoroutinesApi::class)
class TabSleepPassTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val minute = 60_000L
    private val hour = 60 * minute
    private var now = 1_000_000_000L
    private val sessions = mutableListOf<SleepFakeSession>()
    private val logs = mutableListOf<String>()
    private val store = SleepMemoryStore()
    private var downloading = false
    private var casting = false

    @After
    fun tearDown() = scope.cancel()

    private fun manager(): TabManager {
        val m = TabManager(
            factory = { SleepFakeSession(it).also { s -> sessions += s } },
            store = store,
            scope = scope,
            log = { logs += it },
            io = dispatcher,
            clock = { now },
            downloadRunning = { downloading },
            castActive = { casting },
        )
        m.restore()
        scheduler.advanceUntilIdle()
        m.openSelected()
        return m
    }

    private fun TabManager.sessionFor(id: Long) = sessionOf(id) as SleepFakeSession?

    /** Tab A (left in the background, with a page state) and tab B (selected). Returns their ids. */
    private fun TabManager.twoTabs(): Pair<Long, Long> {
        val a = selected.value.id
        loadInSelected("https://a.example/")
        sessionFor(a)!!.saved = "STATE-A"
        val b = newTab("https://b.example/")!!
        sessionFor(b)!!.saved = "STATE-B"
        scheduler.runCurrent()
        return a to b
    }

    private fun TabManager.sleep(setting: TabSleepAfter = TabSleepAfter.AFTER_1_HOUR, lowMemory: Boolean = false) =
        sleepUnusedTabs(setting, lowMemory)

    @Test
    fun theSelectedTabNeverSleepsEvenWhenItWasLastLookedAtLongAgo() {
        val m = manager()
        val (a, b) = m.twoTabs()
        now += 2 * hour
        m.setForeground(false)
        now += 2 * hour
        assertEquals(1, m.sleep())
        assertNull(m.sessionOf(a))
        assertNotNull("the selected tab keeps its session", m.sessionOf(b))
        assertFalse(m.sessionFor(b)!!.closed)
        assertEquals(0, m.sleep(TabSleepAfter.AFTER_15_MINUTES, lowMemory = true))
        assertNotNull(m.sessionOf(b))
    }

    @Test
    fun aSleptTabHasNoSessionKeepsItsSavedStateAndWakesWithARestore() {
        val m = manager()
        val (a, _) = m.twoTabs()
        val first = m.sessionFor(a)!!
        now += hour + minute
        assertEquals(1, m.sleep())
        assertTrue(first.closed)
        assertNull(first.host)
        assertNull(m.sessionOf(a))
        val ui = m.tabs.value.single { it.id == a }
        assertTrue("the switcher marks it asleep", ui.asleep)
        assertEquals("https://a.example/", ui.url)
        assertEquals(TabActivity.ASLEEP, m.overview().single { it.id == a }.activity)
        assertEquals(listOf("tabs put to sleep: 1 (awake now: 1)"), logs.filter { it.startsWith("tabs put to sleep") })
        m.flush()
        scheduler.advanceUntilIdle()
        assertEquals("the stored tab list keeps the saved state", "STATE-A", store.snapshot!!.tabs.single { it.id == a }.savedState)
        // Waking: showing the tab opens a new session that is asked to restore the saved state.
        m.select(a)
        val woken = m.sessionFor(a)!!
        assertTrue(woken !== first)
        assertEquals("STATE-A", woken.init.restoredState)
        assertEquals("https://a.example/", woken.init.fallbackUrl)
        assertEquals(m, woken.host)
        assertFalse(m.tabs.value.single { it.id == a }.asleep)
        assertTrue(logs.contains("tab woken"))
        assertTrue("no address in any log line", logs.none { it.contains("example") })
    }

    @Test
    fun aTabThatStartsPlayingWhileItsStateIsReadIsNotClosed() {
        val m = manager()
        val (a, _) = m.twoTabs()
        val session = m.sessionFor(a)!!
        // The check is made again at the moment of closing: here the page starts playing while its state is read (the
        // first check has already taken the page's state, so only the second one can see it).
        session.onSavedState = { session.stateFlow.value = session.stateFlow.value.copy(media = MediaState(playing = true)) }
        now += 2 * hour
        assertEquals(0, m.sleep())
        assertFalse(session.closed)
        assertNotNull(m.sessionOf(a))
    }

    @Test
    fun aPlayingTabIsNotSleptAndOnceItStopsTheTimeCountsFromWhenItWasLastLookedAt() {
        val m = manager()
        val (a, _) = m.twoTabs()
        val session = m.sessionFor(a)!!
        session.stateFlow.value = session.stateFlow.value.copy(media = MediaState(playing = true))
        scheduler.runCurrent()
        now += 50 * minute
        assertEquals(0, m.sleep(TabSleepAfter.AFTER_15_MINUTES, lowMemory = true))
        assertFalse(session.closed)
        session.stateFlow.value = session.stateFlow.value.copy(media = MediaState(playing = false))
        scheduler.runCurrent()
        assertEquals("50 minutes since it was looked at: not yet an hour", 0, m.sleep(TabSleepAfter.AFTER_1_HOUR))
        now += 11 * minute
        assertEquals(1, m.sleep(TabSleepAfter.AFTER_1_HOUR))
        assertTrue(session.closed)
    }

    @Test
    fun aPrivateTabIsNeverSlept() {
        val m = manager()
        val normal = m.selected.value.id
        m.loadInSelected("https://a.example/")
        val private = m.newPrivateTab("https://secret.example/")!!
        m.sessionFor(private)!!.saved = "PRIVATE-STATE"
        m.select(normal)
        now += 2 * 24 * hour
        assertEquals(0, m.sleep(TabSleepAfter.AFTER_15_MINUTES, lowMemory = true))
        assertNotNull(m.sessionOf(private))
        assertFalse(m.sessionFor(private)!!.closed)
    }

    @Test
    fun neverSleepsNothingEvenWhenMemoryIsLow() {
        val m = manager()
        val (a, _) = m.twoTabs()
        now += 365 * 24 * hour
        assertEquals(0, m.sleep(TabSleepAfter.NEVER))
        assertEquals(0, m.sleep(TabSleepAfter.NEVER, lowMemory = true))
        assertNotNull(m.sessionOf(a))
    }

    @Test
    fun aTabGrantedTheMicrophoneNeverSleeps() {
        val m = manager()
        val (a, _) = m.twoTabs()
        m.sessionFor(a)!!.holds = SleepHolds(captureGranted = true, fileHanded = false)
        now += 2 * 24 * hour
        assertEquals(0, m.sleep(TabSleepAfter.AFTER_15_MINUTES))
        assertEquals(0, m.sleep(TabSleepAfter.AFTER_15_MINUTES, lowMemory = true))
        assertFalse(m.sessionFor(a)!!.closed)
    }

    @Test
    fun aTabThatWasHandedAFileNeverSleeps() {
        val m = manager()
        val (a, _) = m.twoTabs()
        m.sessionFor(a)!!.holds = SleepHolds(captureGranted = false, fileHanded = true)
        now += 2 * 24 * hour
        assertEquals(0, m.sleep(TabSleepAfter.AFTER_15_MINUTES, lowMemory = true))
        assertNotNull(m.sessionOf(a))
    }

    @Test
    fun anyDownloadInProgressHoldsEveryTab() {
        val m = manager()
        val (a, _) = m.twoTabs()
        val c = m.newTab("https://c.example/")!!
        m.sessionFor(c)!!.saved = "STATE-C"
        m.select(a)
        val b = m.tabs.value.first { it.url == "https://b.example/" }.id
        now += 2 * 24 * hour
        downloading = true
        assertEquals(0, m.sleep(TabSleepAfter.AFTER_15_MINUTES))
        assertEquals("not on low memory either", 0, m.sleep(TabSleepAfter.AFTER_15_MINUTES, lowMemory = true))
        assertNotNull(m.sessionOf(b))
        assertNotNull(m.sessionOf(c))
        // Paused or finished: no hold.
        downloading = false
        assertEquals(2, m.sleep(TabSleepAfter.AFTER_15_MINUTES))
    }

    @Test
    fun anActiveCastHoldsEveryTab() {
        val m = manager()
        val (a, _) = m.twoTabs()
        now += 2 * 24 * hour
        casting = true
        assertEquals(0, m.sleep(TabSleepAfter.AFTER_15_MINUTES, lowMemory = true))
        assertNotNull(m.sessionOf(a))
        casting = false
        assertEquals(1, m.sleep(TabSleepAfter.AFTER_15_MINUTES))
    }

    @Test
    fun theOpenerOfTheSelectedTabNeverSleeps() {
        val m = manager()
        val a = m.selected.value.id
        m.loadInSelected("https://shop.example/")
        m.sessionFor(a)!!.saved = "STATE-SHOP"
        // A sign-in or payment window opened from the shop: the shop waits for it.
        m.openLinkInNewTab(LinkMenuRequest("https://pay.example/", a))
        val payment = m.selected.value.id
        assertEquals(a, m.selected.value.openerTabId)
        m.sessionFor(payment)!!.saved = "STATE-PAY"
        now += 2 * 24 * hour
        assertEquals(0, m.sleep(TabSleepAfter.AFTER_15_MINUTES, lowMemory = true))
        assertNotNull(m.sessionOf(a))
        // Another tab in front: the shop is an ordinary background tab again.
        m.newTab("https://other.example/")
        now += hour
        assertEquals(2, m.sleep(TabSleepAfter.AFTER_15_MINUTES))
        assertNull(m.sessionOf(a))
        assertNull(m.sessionOf(payment))
    }

    @Test
    fun aTabWithAPageDialogIsNotSlept() {
        val m = manager()
        val (a, _) = m.twoTabs()
        val session = m.sessionFor(a)!!
        now += 2 * 24 * hour
        session.promptFlow.value = SitePrompt.Alert("a.example", "Hello") {}
        assertEquals(0, m.sleep(TabSleepAfter.AFTER_15_MINUTES, lowMemory = true))
        assertFalse(session.closed)
        session.promptFlow.value = null
        assertEquals(1, m.sleep(TabSleepAfter.AFTER_15_MINUTES))
    }

    @Test
    fun withoutASavedStateTheTabStaysAwake() {
        val m = manager()
        val (a, _) = m.twoTabs()
        m.sessionFor(a)!!.saved = null
        now += 2 * 24 * hour
        assertEquals(0, m.sleep(TabSleepAfter.AFTER_15_MINUTES, lowMemory = true))
        assertNotNull(m.sessionOf(a))
    }

    @Test
    fun lowMemoryUsesFiveMinutes() {
        val m = manager()
        val (a, _) = m.twoTabs()
        now += 6 * minute
        assertEquals(0, m.sleep(TabSleepAfter.AFTER_1_HOUR))
        assertEquals(1, m.sleep(TabSleepAfter.AFTER_1_HOUR, lowMemory = true))
        assertNull(m.sessionOf(a))
    }

    @Test
    fun aTabWokenAMinuteAgoIsNotSleptAgainAtOnce() {
        val m = manager()
        val (a, b) = m.twoTabs()
        now += 2 * hour
        assertEquals(1, m.sleep())
        m.select(a)
        m.sessionFor(a)!!.saved = "STATE-A2"
        now += minute
        m.select(b)
        now += minute
        assertEquals(0, m.sleep(TabSleepAfter.AFTER_15_MINUTES))
        assertNotNull(m.sessionOf(a))
    }

    @Test
    fun nothingSleepsBeforeTheStoredTabsAreRead() {
        val m = TabManager({ SleepFakeSession(it) }, SleepMemoryStore(), scope, {}, dispatcher, clock = { now })
        now += 365 * 24 * hour
        assertEquals(0, m.sleepUnusedTabs(TabSleepAfter.AFTER_15_MINUTES, lowMemory = true))
    }

    @Test
    fun theTabCountAndTheOrderStayTheSameAndTheMemoryCountFalls() {
        val m = manager()
        val (a, b) = m.twoTabs()
        val before = m.tabs.value.map { Triple(it.id, it.url, it.title) }
        assertEquals(NormalTabCounts(open = 2, awake = 2), m.normalTabCounts())
        now += 2 * hour
        m.sleep()
        assertEquals(before, m.tabs.value.map { Triple(it.id, it.url, it.title) })
        assertEquals(listOf(a, b), m.tabs.value.map { it.id })
        assertEquals(NormalTabCounts(open = 2, awake = 1), m.normalTabCounts())
    }
}
