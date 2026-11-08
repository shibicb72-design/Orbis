package com.shibi.orbis.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.input.ImeAction
import com.shibi.orbis.engine.IncomingDownload
import com.shibi.orbis.engine.SearchEngine
import com.shibi.orbis.engine.SessionState
import com.shibi.orbis.engine.afterContentProcessGone
import com.shibi.orbis.engine.afterLoadError
import com.shibi.orbis.engine.afterNavigationCancelled
import com.shibi.orbis.engine.afterPageStart
import com.shibi.orbis.engine.afterPageStop
import com.shibi.orbis.tabs.PendingDownload
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * N1: after Go the bar names the submitted target only while that load can still commit. [PendingBar] is a hand-written
 * MIRROR of the address bar's wiring in BrowserScreen, not BrowserScreen itself: it uses the real [rememberPendingSubmit]
 * inside a per-tab key and the real event bodies ([submitFromAddressBar], [stopFromAddressBar], [onAddressBarEdited],
 * [addressBarBufferOnFocusChange]), and its session is a MutableStateFlow driven with the engine's own state transitions
 * (afterPageStart and friends). What is NOT covered: that BrowserScreen passes those functions the same arguments (the
 * selected tab's own session flow, the tab url, the download offer) and wires them the same way. A change to that wiring
 * in BrowserScreen would not turn this class red; it is checked by review and on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PendingSubmitComposeTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val old = "https://old.example/"
    private val target = "https://new.example/"

    private val tabA = 1L
    private val tabB = 2L
    private val urlB = "https://b.example/"
    private val flowA = MutableStateFlow(SessionState(url = old))
    private val flowB = MutableStateFlow(SessionState(url = urlB))
    private var selected by mutableStateOf(tabA)
    private var urlA by mutableStateOf(old)
    private var downloadOffer by mutableStateOf<PendingDownload?>(null)
    private lateinit var addressRequester: FocusRequester
    private lateinit var sinkRequester: FocusRequester
    private lateinit var goNow: () -> Unit
    private val loads = mutableListOf<String>()
    private var hides = 0
    private var stops = 0

    // Every onFocusChanged invocation of the bar, by the hasFocus it reported: lets a test prove the handler really ran.
    private var focusGains = 0
    private var focusLosses = 0

    @Composable
    private fun PendingBar() {
        key(selected) {
            val id = selected
            val tabUrl = if (id == tabA) urlA else urlB
            val flow = if (id == tabA) flowA else flowB
            val pending = rememberPendingSubmit(id, tabUrl, flow, downloadOffer)
            var typed by remember { mutableStateOf("") }
            var focused by remember { mutableStateOf(false) }
            val sink = remember { FocusRequester() }
            sinkRequester = sink
            addressRequester = remember { FocusRequester() }
            goNow = {
                submitFromAddressBar(
                    typed = typed,
                    searchEngine = SearchEngine.DEFAULT,
                    sessionState = flow.value,
                    pending = pending,
                    loadUrl = { loads += it },
                    hideKeyboard = { hides++ },
                    moveFocusToSink = { sink.requestFocus() },
                )
            }
            Column(Modifier.focusRequester(sink).focusable()) {
                OutlinedTextField(
                    value = addressBarText(focused, typed, collectedUrl = flow.value.url, selectedTabUrl = tabUrl, pendingSubmit = pending.text),
                    onValueChange = {
                        typed = it
                        onAddressBarEdited(pending)
                    },
                    modifier = Modifier
                        .testTag("address")
                        .focusRequester(addressRequester)
                        .onFocusChanged { f ->
                            if (f.hasFocus) focusGains++ else focusLosses++
                            addressBarBufferOnFocusChange(f.hasFocus, focused, pending, flow.value.url, tabUrl)?.let { typed = it }
                            focused = f.hasFocus
                        },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { goNow() }),
                )
                Text("stop", Modifier.testTag("stop").clickable { stopFromAddressBar({ stops++ }, pending) })
            }
        }
    }

    private fun addressText(): String =
        rule.onNodeWithTag("address").fetchSemanticsNode().config
            .first { it.key.name == "EditableText" }.value.toString()

    /** What the user does: focus the bar, type [text] over the buffer, press the keyboard's Go. */
    private fun typeAndGo(text: String = target) {
        rule.runOnIdle { addressRequester.requestFocus() }
        rule.waitForIdle()
        rule.onNodeWithTag("address").performTextReplacement(text)
        rule.onNodeWithTag("address").performImeAction()
        rule.waitForIdle()
    }

    private fun go() {
        typeAndGo()
        assertEquals("sanity: Go shows the submitted target", target, addressText())
    }

    private fun emit(flow: MutableStateFlow<SessionState>, f: (SessionState) -> SessionState) {
        rule.runOnIdle { flow.value = f(flow.value) }
        rule.waitForIdle()
    }

    private fun start() {
        rule.setContent { PendingBar() }
        rule.waitForIdle()
    }

    private fun download(sourceTab: Long) =
        PendingDownload(
            object : IncomingDownload {
                override val url = "https://dl.example/file"
                override val contentDisposition: String? = null
                override val contentType: String? = null
                override val contentLength = -1L

                override fun openBody(): InputStream? = null

                override fun discard() = Unit
            },
            sourceTab,
        )

    // ---- Go itself -----------------------------------------------------------------------------------------------

    @Test
    fun goRequestsTheLoadHidesTheKeyboardAndMovesFocusOffTheBar() {
        start()
        go()
        assertEquals(listOf(target), loads)
        assertEquals(1, hides)
        rule.onNodeWithTag("address").assertIsNotFocused()
    }

    // ---- the load ends without a commit: back to the old address ---------------------------------------------------

    @Test
    fun loadEndingWithoutACommitFallsBackToTheOldAddress() {
        start()
        go()
        emit(flowA) { it.afterPageStart() }
        assertEquals("still loading: the target stays", target, addressText())
        emit(flowA) { it.afterPageStop(success = false) }
        assertEquals(old, addressText())
    }

    @Test
    fun failedLoadFallsBackToTheOldAddress() {
        start()
        go()
        emit(flowA) { it.afterPageStart() }
        emit(flowA) { it.afterLoadError(target) }
        assertEquals(old, addressText())
    }

    @Test
    fun crashFallsBackToTheOldAddress() {
        start()
        go()
        emit(flowA) { it.afterContentProcessGone() }
        assertEquals(old, addressText())
    }

    @Test
    fun stopTapFallsBackToTheOldAddress() {
        start()
        go()
        rule.onNodeWithTag("stop").performClick()
        rule.waitForIdle()
        assertEquals(1, stops)
        assertEquals(old, addressText())
    }

    @Test
    fun downloadOfferedForThisTabFallsBackToTheOldAddress() {
        start()
        go()
        rule.runOnIdle { downloadOffer = download(tabA) }
        rule.waitForIdle()
        assertEquals(old, addressText())
    }

    @Test
    fun downloadOfferedForAnotherTabLeavesThisTabsSubmit() {
        start()
        go()
        rule.runOnIdle { downloadOffer = download(tabB) }
        rule.waitForIdle()
        assertEquals(target, addressText())
    }

    // DR5-06: a second offer for the same tab, with no empty state in between, must clear a second submit too.
    @Test
    fun consecutiveDownloadOffersForThisTabEachClearTheirSubmit() {
        start()
        go()
        rule.runOnIdle { downloadOffer = download(tabA) }
        rule.waitForIdle()
        assertEquals(old, addressText())
        go()
        rule.runOnIdle { downloadOffer = download(tabA) }
        rule.waitForIdle()
        assertEquals(old, addressText())
    }

    @Test
    fun editingTheBarAfterGoDropsThePendingTextForGood() {
        start()
        go()
        rule.runOnIdle { addressRequester.requestFocus() }
        rule.waitForIdle()
        rule.onNodeWithTag("address").performTextReplacement("https://third.example/")
        rule.waitForIdle()
        rule.runOnIdle { sinkRequester.requestFocus() }
        rule.waitForIdle()
        assertEquals("the edit replaced the submit; unfocused, the bar names the committed page", old, addressText())
    }

    // A navigation the page refused to leave for (beforeunload: stay / dismissed): no load event ever follows.
    @Test
    fun beforeUnloadThatEndsWithoutLeavingFallsBackToTheOldAddress() {
        start()
        go()
        assertEquals(target, addressText())
        emit(flowA) { it.afterNavigationCancelled() }
        assertEquals(old, addressText())
    }

    @Test
    fun aLoadStartingAfterGoKeepsTheTargetWhileItRuns() {
        // Includes the beforeunload "leave" outcome: no cancel is counted and the new page starts as usual, which the
        // session cannot tell apart from any other start. (The prompt itself is not simulated here.)
        start()
        go()
        emit(flowA) { it.afterPageStart() }
        assertEquals(target, addressText())
    }

    // ---- commits -------------------------------------------------------------------------------------------------

    @Test
    fun commitOfADifferentAddressWhileStillLoadingShowsTheCommittedAddress() {
        // A redirect: the tab's url changes to something other than the typed target while the load is still running.
        val final = "https://final.example/"
        start()
        go()
        emit(flowA) { it.afterPageStart() }
        rule.runOnIdle { urlA = final }
        rule.waitForIdle()
        assertEquals(final, addressText())
    }

    @Test
    fun redirectedLoadEndsOnTheCommittedAddressNotTheTypedOne() {
        // End to end for a redirect (committed url != target, then the load ends): the bar ends on what the tab shows.
        // It does not isolate the commit rule (the load end would clear too); the mid-load test above does.
        val landing = "https://new.example/landing"
        start()
        go()
        emit(flowA) { it.afterPageStart() }
        rule.runOnIdle { urlA = landing }
        rule.waitForIdle()
        emit(flowA) { it.copy(url = landing).afterPageStop(success = true) }
        assertEquals(landing, addressText())
    }

    @Test
    fun typedAddressThatCommitsShowsItAfterTheLoad() {
        // Proves only that the normal case works end to end (target == committed); the clearing rules are tested above.
        start()
        go()
        emit(flowA) { it.afterPageStart() }
        rule.runOnIdle { urlA = target }
        rule.waitForIdle()
        emit(flowA) { it.copy(url = target).afterPageStop(success = true) }
        assertEquals(target, addressText())
    }

    // ---- focus ---------------------------------------------------------------------------------------------------

    @Test
    fun refocusAfterRecompositionStillGetsTheSubmittedAddress() {
        // C3 (hardware Enter hands focus back to the bar right after Go): the buffer must be the pending target.
        start()
        go()
        rule.runOnIdle { addressRequester.requestFocus() }
        rule.onNodeWithTag("address").assertIsFocused()
        assertEquals(target, addressText())
    }

    // DR5-01: Go and the view system's refocus in ONE main-thread task, so no recomposition happens in between. A handler
    // that reads a copy of the pending text made during composition sees null here and resets the buffer to the old
    // address (C3 regressed).
    @Test
    fun refocusBeforeAnyRecompositionStillGetsTheSubmittedAddress() {
        start()
        rule.runOnIdle { addressRequester.requestFocus() }
        rule.waitForIdle()
        rule.onNodeWithTag("address").performTextReplacement(target)
        rule.waitForIdle()
        rule.onNodeWithTag("address").assertIsFocused()
        val gainsBefore = focusGains
        val lossesBefore = focusLosses
        rule.runOnUiThread {
            goNow()
            addressRequester.requestFocus()
        }
        rule.waitForIdle()
        // Not vacuous: the handler ran for the loss (Go moved focus to the sink) and for the regain, inside that one task.
        assertEquals("focus was lost once", lossesBefore + 1, focusLosses)
        assertEquals("focus was regained once, running the handler", gainsBefore + 1, focusGains)
        rule.onNodeWithTag("address").assertIsFocused()
        assertEquals(target, addressText())
    }

    // ---- collapsed emissions: the state flow only shows the latest value -------------------------------------------

    // DR5-02: Go on the url whose failure is on screen. Start and error land before the collector runs, so the observed
    // state equals the submit-time state except for the counters.
    @Test
    fun sameUrlRetryWhoseStartAndErrorAreConflatedFallsBackToTheOldAddress() {
        flowA.value = SessionState(url = old).afterPageStart().afterLoadError(target)
        start()
        go()
        rule.runOnIdle {
            flowA.value = flowA.value.afterPageStart()
            flowA.value = flowA.value.afterLoadError(target)
        }
        rule.waitForIdle()
        assertEquals(old, addressText())
    }

    // DR5-04: Go during an old load; the old stop and the new start land in one emission (isLoading never shows false);
    // the new load then ends without a commit.
    @Test
    fun oldStopAndNewStartConflatedThenNewEndFallsBackToTheOldAddress() {
        flowA.value = flowA.value.afterPageStart()
        start()
        go()
        rule.runOnIdle {
            flowA.value = flowA.value.afterPageStop(success = false)
            flowA.value = flowA.value.afterPageStart()
        }
        rule.waitForIdle()
        assertEquals("the new load is running: the target stays", target, addressText())
        emit(flowA) { it.afterPageStop(success = false) }
        assertEquals(old, addressText())
    }

    @Test
    fun oldLoadEndingAfterGoDoesNotFlipTheBarBackBeforeTheNewLoadStarts() {
        flowA.value = flowA.value.afterPageStart()
        start()
        go()
        emit(flowA) { it.afterPageStop(success = false) }
        assertEquals("the old load's end is not the new load's", target, addressText())
        emit(flowA) { it.afterPageStart() }
        assertEquals(target, addressText())
        emit(flowA) { it.afterPageStop(success = false) }
        assertEquals(old, addressText())
    }

    // DR6-01: Go during an old load; the old load stops (ignored), then the new request fails with no onPageStart.
    @Test
    fun loadErrorWithoutAStartWhileAnOldLoadWasRunningFallsBackToTheOldAddress() {
        flowA.value = flowA.value.afterPageStart()
        start()
        go()
        emit(flowA) { it.afterPageStop(success = false) }
        assertEquals("the old load's end is not the new request's", target, addressText())
        emit(flowA) { it.afterLoadError(target) }
        assertEquals(old, addressText())
    }

    @Test
    fun failureOnScreenBeforeGoDoesNotClearTheNewSubmit() {
        // The engine resets loadFailed only at onPageStart: right after Go the flag is still the old one.
        flowA.value = flowA.value.afterPageStart().afterLoadError("https://broken.example/")
        start()
        go()
        assertEquals(target, addressText())
        emit(flowA) { it.afterPageStart() }
        assertEquals(target, addressText())
        emit(flowA) { it.afterLoadError(target) }
        assertEquals(old, addressText())
    }

    // ---- tabs ----------------------------------------------------------------------------------------------------

    @Test
    fun pendingSubmitNeverCrossesToAnotherTab() {
        start()
        go()
        rule.runOnIdle { selected = tabB }
        rule.waitForIdle()
        assertEquals("B shows its own address, not A's pending target", urlB, addressText())
        // Go in B: B names its own target.
        typeAndGo("https://b-target.example/")
        assertEquals("https://b-target.example/", addressText())
        // Back in A: A's pending text did not survive leaving its key, and B's target does not show in A.
        rule.runOnIdle { selected = tabA }
        rule.waitForIdle()
        assertEquals(old, addressText())
    }

    @Test
    fun otherTabsSessionEventsDoNotClearThisTabsSubmit() {
        start()
        go()
        // B's own load starts and fails: the selected tab A watches only its own session.
        emit(flowB) { it.afterPageStart() }
        emit(flowB) { it.afterLoadError("https://x.example/") }
        emit(flowB) { it.afterContentProcessGone() }
        assertEquals(target, addressText())
    }
}
