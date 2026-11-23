package com.shibi.orbis.ui.browser

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shibi.orbis.engine.BrowserSession
import com.shibi.orbis.engine.SessionHost
import com.shibi.orbis.engine.SessionState
import com.shibi.orbis.engine.SitePrompt
import com.shibi.orbis.engine.afterLocationChange
import com.shibi.orbis.engine.afterPageStart
import com.shibi.orbis.engine.afterPageStop
import com.shibi.orbis.engine.afterProgressChange
import com.shibi.orbis.settings.DarkMode
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The two slow-load notices as the browser screen wires them: words, actions, once per load, tab switches. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SlowLoadNoticeComposeTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    @After
    fun tearDown() = scope.cancel()

    private val takingLong = "This page is taking a long time to load."
    private val didNotFinish = "This page did not finish loading."

    private class SlowSession : BrowserSession {
        override val isPrivate = false
        val stateFlow = MutableStateFlow(SessionState(url = "https://before.example/", progress = 100))
        override val state: StateFlow<SessionState> = stateFlow
        override val prompts: StateFlow<SitePrompt?> = MutableStateFlow(null)
        override var host: SessionHost? = null
        var stops = 0
        var reloads = 0

        override fun attachTo(contentView: View) = Unit
        override fun detachFrom(contentView: View) = Unit
        override fun loadUrl(url: String) = Unit
        override fun loadExternalUrl(url: String) = Unit
        override fun setDesktopMode(enabled: Boolean) = Unit
        override fun goBack() = false
        override fun goForward() = false
        override fun reload() {
            reloads++
        }
        override fun stop() {
            stops++
        }
        override fun find(query: String, backwards: Boolean) = Unit
        override fun clearFind() = Unit
        override fun setActive(active: Boolean) = Unit
        override fun savedState(): String? = null
        override fun close() = Unit
    }

    private val a = SlowSession()
    private val b = SlowSession()
    private val selected = mutableStateOf(1L)
    private val locked = mutableStateOf(false)
    private val pip = mutableStateOf(false)
    private val logged = mutableListOf<String>()
    private lateinit var host: SnackbarHostState

    private fun now() = rule.mainClock.currentTime

    private fun show() {
        rule.setContent {
            OrbisTheme(DarkMode.LIGHT) {
                val snackbars = androidx.compose.runtime.remember { SnackbarHostState() }
                host = snackbars
                Box {
                    SnackbarHost(snackbars) { OrbisSnackbar(it) }
                    SlowLoadNotice(
                        tabId = selected.value,
                        session = if (selected.value == 1L) a else b,
                        snackbars = snackbars,
                        hiddenByLock = locked.value,
                        inPictureInPicture = pip.value,
                        log = { logged += it },
                        clock = ::now,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    /** A load of [s] that started now and committed its address, then sits at 15. */
    private fun startLoad(s: SlowSession) {
        val t = now()
        s.stateFlow.value = s.stateFlow.value.afterPageStart("https://slow.example/", t).afterProgressChange(15, t)
            .afterLocationChange("https://slow.example/")
        rule.waitForIdle()
    }

    private fun advance(ms: Long) {
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    @Test
    fun takingLongShowsAt30SecondsWithTheExactWordsAndStopCallsStop() {
        show()
        startLoad(a)
        advance(29_000)
        rule.onNodeWithText(takingLong).assertDoesNotExist()
        advance(2_000)
        rule.onNodeWithText(takingLong).assertIsDisplayed()
        rule.onNodeWithText("Stop").assertIsDisplayed()
        rule.onNodeWithText("Reload").assertIsDisplayed()
        assertEquals(listOf("slow load notice shown"), logged)
        rule.onNodeWithText("Stop").performClick()
        rule.waitForIdle()
        assertEquals(1, a.stops)
        assertEquals(0, a.reloads)
        rule.onNodeWithText(takingLong).assertDoesNotExist()
    }

    @Test
    fun takingLongReloadCallsReload() {
        show()
        startLoad(a)
        advance(31_000)
        rule.onNodeWithText("Reload").performClick()
        rule.waitForIdle()
        assertEquals(1, a.reloads)
        assertEquals(0, a.stops)
        rule.onNodeWithText(takingLong).assertDoesNotExist()
    }

    @Test
    fun closingTakingLongDoesNotBringItBackForThatLoad() {
        show()
        startLoad(a)
        advance(31_000)
        rule.onNodeWithContentDescription("Close").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(takingLong).assertDoesNotExist()
        // The progress moves and stalls again, and the tab is left and shown again: still the same load.
        a.stateFlow.value = a.stateFlow.value.afterProgressChange(55, now())
        advance(31_000)
        selected.value = 2L
        rule.waitForIdle()
        selected.value = 1L
        advance(31_000)
        rule.onNodeWithText(takingLong).assertDoesNotExist()
        assertEquals(0, a.stops + a.reloads)
    }

    @Test
    fun theNextLoadMayShowItAgain() {
        show()
        startLoad(a)
        advance(31_000)
        rule.onNodeWithContentDescription("Close").performClick()
        rule.waitForIdle()
        startLoad(a)
        advance(31_000)
        rule.onNodeWithText(takingLong).assertIsDisplayed()
    }

    @Test
    fun takingLongGoesWhenTheLoadFinishesWell() {
        show()
        startLoad(a)
        advance(31_000)
        rule.onNodeWithText(takingLong).assertIsDisplayed()
        a.stateFlow.value = a.stateFlow.value.afterPageStop(success = true, now = now())
        advance(5_000)
        rule.onNodeWithText(takingLong).assertDoesNotExist()
        rule.onNodeWithText(didNotFinish).assertDoesNotExist()
    }

    @Test
    fun didNotFinishShowsWithReloadAndReloadCallsReload() {
        show()
        startLoad(a)
        advance(SILENT_BEFORE_STOP_MILLIS)
        a.stateFlow.value = a.stateFlow.value.afterPageStop(success = false, now = now())
        rule.waitForIdle()
        rule.onNodeWithText(didNotFinish).assertDoesNotExist()
        advance(UNFINISHED_SETTLE_MILLIS + 100)
        rule.onNodeWithText(didNotFinish).assertIsDisplayed()
        rule.onNodeWithText("Stop").assertDoesNotExist()
        assertEquals(listOf("unfinished load notice shown"), logged)
        rule.onNodeWithText("Reload").performClick()
        rule.waitForIdle()
        assertEquals(1, a.reloads)
        rule.onNodeWithText(didNotFinish).assertDoesNotExist()
    }

    @Test
    fun anUnfinishedStopFollowedByANewLoadShowsNothing() {
        show()
        startLoad(a)
        advance(SILENT_BEFORE_STOP_MILLIS)
        a.stateFlow.value = a.stateFlow.value.afterPageStop(success = false, now = now())
        advance(500)
        a.stateFlow.value = a.stateFlow.value.afterPageStart("https://next.example/", now())
        advance(10_000)
        rule.onNodeWithText(didNotFinish).assertDoesNotExist()
    }

    @Test
    fun aBackgroundTabShowsNothingAndLeavingTheTabTakesTheNoticeAway() {
        show()
        startLoad(b)
        advance(31_000)
        rule.onNodeWithText(takingLong).assertDoesNotExist()
        startLoad(a)
        advance(31_000)
        rule.onNodeWithText(takingLong).assertIsDisplayed()
        selected.value = 2L
        advance(1_000)
        // Tab 2 has been slow all along: its own notice, once.
        rule.onNodeWithText(takingLong).assertIsDisplayed()
        rule.onNodeWithContentDescription("Close").performClick()
        rule.waitForIdle()
        selected.value = 1L
        advance(1_000)
        // Tab 1's notice was taken away when it was left; it does not come back.
        rule.onNodeWithText(takingLong).assertDoesNotExist()
        assertEquals(listOf("slow load notice shown", "slow load notice shown"), logged)
    }

    @Test
    fun nothingOverALockedPrivatePageOrInPictureInPicture() {
        locked.value = true
        show()
        startLoad(a)
        advance(31_000)
        rule.onNodeWithText(takingLong).assertDoesNotExist()
        locked.value = false
        pip.value = true
        advance(1_000)
        rule.onNodeWithText(takingLong).assertDoesNotExist()
        pip.value = false
        advance(1_000)
        rule.onNodeWithText(takingLong).assertIsDisplayed()
        pip.value = true
        advance(1_000)
        rule.onNodeWithText(takingLong).assertDoesNotExist()
    }

    @Test
    fun anotherSnackbarIsKeptAndTheNoticeWaits() {
        show()
        scope.launch { host.showSnackbar("Tab closed", actionLabel = "Undo", duration = SnackbarDuration.Indefinite) }
        rule.waitForIdle()
        startLoad(a)
        advance(31_000)
        rule.onNodeWithText("Tab closed").assertIsDisplayed()
        rule.onNodeWithText(takingLong).assertDoesNotExist()
        host.currentSnackbarData?.dismiss()
        rule.waitForIdle()
        rule.onNodeWithText(takingLong).assertIsDisplayed()
    }

    // ---- X3: each notice closes itself ----

    @Test
    fun takingLongClosesItselfAfter20SecondsAndAWaitingSnackbarThenShows() {
        show()
        startLoad(a)
        advance(31_000)
        rule.onNodeWithText(takingLong).assertIsDisplayed()
        // "Tab closed" is asked for while the notice is up: it waits behind it.
        scope.launch { host.showSnackbar("Tab closed", actionLabel = "Undo", duration = SnackbarDuration.Indefinite) }
        advance(TAKING_LONG_SHOWN_MILLIS - 2_000)
        rule.onNodeWithText(takingLong).assertIsDisplayed()
        rule.onNodeWithText("Tab closed").assertDoesNotExist()
        advance(2_000)
        rule.onNodeWithText(takingLong).assertDoesNotExist()
        rule.onNodeWithText("Tab closed").assertIsDisplayed()
        host.currentSnackbarData?.dismiss()
        // The load still runs: the notice counts as shown and does not come back.
        advance(120_000)
        rule.onNodeWithText(takingLong).assertDoesNotExist()
        assertEquals(0, a.stops + a.reloads)
        assertEquals(listOf("slow load notice shown"), logged)
    }

    @Test
    fun didNotFinishClosesItselfAfter30SecondsAndDoesNotComeBack() {
        show()
        startLoad(a)
        advance(SILENT_BEFORE_STOP_MILLIS)
        a.stateFlow.value = a.stateFlow.value.afterPageStop(success = false, now = now())
        advance(UNFINISHED_SETTLE_MILLIS + 100)
        rule.onNodeWithText(didNotFinish).assertIsDisplayed()
        advance(DID_NOT_FINISH_SHOWN_MILLIS - 1_000)
        rule.onNodeWithText(didNotFinish).assertIsDisplayed()
        advance(2_000)
        rule.onNodeWithText(didNotFinish).assertDoesNotExist()
        selected.value = 2L
        rule.waitForIdle()
        selected.value = 1L
        advance(60_000)
        rule.onNodeWithText(didNotFinish).assertDoesNotExist()
        assertEquals(0, a.reloads)
    }
}
