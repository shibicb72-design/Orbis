package com.shibi.orbis.ui.browser

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shibi.orbis.engine.BrowserSession
import com.shibi.orbis.engine.SessionHost
import com.shibi.orbis.engine.SessionInit
import com.shibi.orbis.engine.SessionState
import com.shibi.orbis.engine.SitePrompt
import com.shibi.orbis.engine.afterPageStart
import com.shibi.orbis.privacy.pageHiddenByLock
import com.shibi.orbis.settings.DarkMode
import com.shibi.orbis.tabs.TabManager
import com.shibi.orbis.tabs.TabStore
import com.shibi.orbis.tabs.TabsSnapshot
import com.shibi.orbis.ui.theme.OrbisTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A session whose "live stream dropped" flag the test sets, as the stall script's "dropped" would. */
private class StreamSession(init: SessionInit) : BrowserSession {
    override val isPrivate = init.isPrivate
    val stateFlow = MutableStateFlow(SessionState(url = "https://live.example/watch"))
    override val state: StateFlow<SessionState> = stateFlow
    override val prompts: StateFlow<SitePrompt?> = MutableStateFlow(null)
    override var host: SessionHost? = null
    var reloads = 0
    var dismissals = 0

    fun drop() {
        stateFlow.value = stateFlow.value.copy(liveStreamDropped = true)
    }

    fun backAgain() {
        stateFlow.value = stateFlow.value.copy(liveStreamDropped = false)
    }

    override fun dismissLiveStreamDropped() {
        dismissals++
        stateFlow.value = stateFlow.value.copy(liveStreamDropped = false)
    }

    override fun reload() {
        reloads++
        stateFlow.value = stateFlow.value.afterPageStart(stateFlow.value.url)
    }

    override fun attachTo(contentView: View) = Unit
    override fun detachFrom(contentView: View) = Unit
    override fun loadUrl(url: String) = Unit
    override fun loadExternalUrl(url: String) = Unit
    override fun setDesktopMode(enabled: Boolean) = Unit
    override fun goBack() = false
    override fun goForward() = false
    override fun stop() = Unit
    override fun find(query: String, backwards: Boolean) = Unit
    override fun clearFind() = Unit
    override fun setActive(active: Boolean) = Unit
    override fun savedState(): String? = null
    override fun close() = Unit
}

private class NoStore : TabStore {
    override fun load(): TabsSnapshot? = null
    override fun save(snapshot: TabsSnapshot) = Unit
}

/** The live stream message: exact words, selected tab only, in front, never over a locked private page, once per drop. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LiveStreamDroppedNoticeComposeTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val sessions = mutableListOf<StreamSession>()
    private val text = "The live stream stopped and Orbis could not reconnect. Check your connection or reload the page."
    private val inFront = mutableStateOf(true)
    private val locked = mutableStateOf(false)
    private val pip = mutableStateOf(false)
    private var host: SnackbarHostState? = null

    @After
    fun tearDown() = scope.cancel()

    private fun manager(): TabManager {
        val m = TabManager(
            factory = { init -> StreamSession(init).also { sessions += it } },
            store = NoStore(),
            scope = scope,
            log = {},
            io = dispatcher,
        )
        m.restore()
        return m
    }

    /** The wiring BrowserScreen uses, over a real TabManager. */
    private fun show(m: TabManager) {
        rule.setContent {
            OrbisTheme(DarkMode.LIGHT) {
                val snackbars = remember { SnackbarHostState() }
                host = snackbars
                val selected by m.selected.collectAsState()
                Box {
                    SnackbarHost(snackbars) { OrbisSnackbar(it) }
                    LiveStreamDroppedNotice(
                        tabs = m,
                        inFront = inFront.value,
                        pageHidden = pageHiddenByLock(locked.value, selected.isPrivate),
                        inPip = pip.value,
                        snackbars = snackbars,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun shown() = rule.onNodeWithText(text).assertIsDisplayed()

    private fun notShown() = rule.onNodeWithText(text).assertDoesNotExist()

    private fun idle() = rule.waitForIdle()

    @Test
    fun noFlagShowsNothing() {
        val m = manager()
        m.newTab("https://live.example/watch")
        show(m)
        notShown()
    }

    @Test
    fun aDropOnTheSelectedTabShowsTheExactWordsWithReload() {
        val m = manager()
        m.newTab("https://live.example/watch")
        show(m)
        sessions[0].drop()
        idle()
        shown()
        rule.onNodeWithText("Reload").assertIsDisplayed()
        // It stays: no timeout.
        rule.mainClock.advanceTimeBy(120_000)
        idle()
        shown()
    }

    @Test
    fun aDropOnABackgroundTabShowsNothingUntilThatTabIsSelected() {
        val m = manager()
        val a = m.newTab("https://live.example/watch")!!
        val b = m.newTab("https://other.example/")!!
        assertEquals(b, m.selected.value.id)
        show(m)
        sessions[0].drop()
        idle()
        notShown()
        m.select(a)
        idle()
        shown()
        // Away: gone. Back while the flag is still set: shown again (it was not dismissed).
        m.select(b)
        idle()
        notShown()
        m.select(a)
        idle()
        shown()
        assertEquals(0, sessions[0].dismissals)
    }

    @Test
    fun reloadReloadsThatTabOnceAndTheMessageDoesNotComeBack() {
        val m = manager()
        val a = m.newTab("https://live.example/watch")!!
        val b = m.newTab("https://other.example/")!!
        m.select(a)
        show(m)
        sessions[0].drop()
        idle()
        rule.onNodeWithText("Reload").performClick()
        idle()
        notShown()
        assertEquals(1, sessions[0].reloads)
        assertEquals(0, sessions[1].reloads)
        assertFalse(sessions[0].state.value.liveStreamDropped)
        m.select(b)
        idle()
        m.select(a)
        idle()
        notShown()
    }

    @Test
    fun closingItClearsTheFlagUntilANewDrop() {
        val m = manager()
        val a = m.newTab("https://live.example/watch")!!
        val b = m.newTab("https://other.example/")!!
        m.select(a)
        show(m)
        sessions[0].drop()
        idle()
        rule.onNodeWithContentDescription("Dismiss").performClick()
        idle()
        notShown()
        assertEquals(1, sessions[0].dismissals)
        assertEquals(0, sessions[0].reloads)
        m.select(b)
        idle()
        m.select(a)
        idle()
        notShown()
        sessions[0].drop()
        idle()
        shown()
    }

    @Test
    fun theStreamComingBackTakesTheMessageAway() {
        val m = manager()
        m.newTab("https://live.example/watch")
        show(m)
        sessions[0].drop()
        idle()
        shown()
        sessions[0].backAgain()
        idle()
        notShown()
        assertEquals(0, sessions[0].dismissals)
    }

    @Test
    fun closingTheTabTakesTheMessageAway() {
        val m = manager()
        m.newTab("https://other.example/")
        val a = m.newTab("https://live.example/watch")!!
        show(m)
        sessions[1].drop()
        idle()
        shown()
        m.close(a)
        idle()
        notShown()
    }

    private fun holdsBackWhile(flag: MutableState<Boolean>, holdWhen: Boolean, private: Boolean = false) {
        val m = manager()
        if (private) m.newPrivateTab("https://live.example/watch") else m.newTab("https://live.example/watch")
        show(m)
        flag.value = holdWhen
        idle()
        sessions[0].drop()
        idle()
        notShown()
        assertTrue(sessions[0].state.value.liveStreamDropped)
        flag.value = !holdWhen
        idle()
        shown()
    }

    @Test
    fun notInFrontItWaitsUntilOrbisIsBack() = holdsBackWhile(inFront, holdWhen = false)

    @Test
    fun pictureInPictureHoldsItBack() = holdsBackWhile(pip, holdWhen = true)

    @Test
    fun aLockedPrivateTabSetsTheFlagButShowsNothingUntilUnlocked() = holdsBackWhile(locked, holdWhen = true, private = true)

    @Test
    fun theLockDoesNotHideANormalTabsMessage() {
        val m = manager()
        m.newTab("https://live.example/watch")
        show(m)
        locked.value = true
        sessions[0].drop()
        idle()
        shown()
    }

    @Test
    fun anotherSnackbarPushingItAsideDoesNotCountAsClosingIt() {
        val m = manager()
        m.newTab("https://live.example/watch")
        show(m)
        sessions[0].drop()
        idle()
        shown()
        var other: SnackbarResult? = null
        scope.launch {
            host!!.currentSnackbarData?.dismiss()
            other = host!!.showSnackbar("Tab closed", actionLabel = "Reopen", duration = SnackbarDuration.Indefinite)
        }
        idle()
        rule.onNodeWithText("Tab closed").assertIsDisplayed()
        notShown()
        assertEquals(0, sessions[0].dismissals)
        rule.onNodeWithText("Reopen").performClick()
        idle()
        assertEquals(SnackbarResult.ActionPerformed, other)
        shown()
        assertTrue(sessions[0].state.value.liveStreamDropped)
    }

    @Test
    fun dismissedByOtherCodeWithNothingAfterItComesBack() {
        val m = manager()
        m.newTab("https://live.example/watch")
        show(m)
        sessions[0].drop()
        idle()
        host!!.currentSnackbarData?.dismiss()
        rule.mainClock.advanceTimeBy(2_000)
        idle()
        shown()
        assertEquals(0, sessions[0].dismissals)
    }

    @Test
    fun theDecision() {
        assertTrue(showsLiveStreamDropped(dropped = true, inFront = true, pageHidden = false, inPip = false))
        assertFalse(showsLiveStreamDropped(dropped = false, inFront = true, pageHidden = false, inPip = false))
        assertFalse(showsLiveStreamDropped(dropped = true, inFront = false, pageHidden = false, inPip = false))
        assertFalse(showsLiveStreamDropped(dropped = true, inFront = true, pageHidden = true, inPip = false))
        assertFalse(showsLiveStreamDropped(dropped = true, inFront = true, pageHidden = false, inPip = true))
        // A private tab while locked is hidden; a normal tab while locked is not.
        assertFalse(showsLiveStreamDropped(true, true, pageHiddenByLock(locked = true, selectedTabIsPrivate = true), false))
        assertTrue(showsLiveStreamDropped(true, true, pageHiddenByLock(locked = true, selectedTabIsPrivate = false), false))
    }
}
