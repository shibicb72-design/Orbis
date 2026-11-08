package com.shibi.orbis.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.shibi.orbis.engine.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * N1: after Go the bar names the submitted target only while that load can still commit. [PendingBar] uses the real
 * [rememberPendingSubmit] (the same call BrowserScreen makes inside its per-tab key) and drives it with the engine-like
 * session state flows, tab url and download offer that the tests then change.
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
    private val flowA = MutableStateFlow(SessionState(url = old))
    private val flowB = MutableStateFlow(SessionState(url = "https://b.example/"))
    private var selected by mutableStateOf(tabA)
    private var urlA by mutableStateOf(old)
    private var downloadSource by mutableStateOf<Long?>(null)
    private lateinit var addressRequester: FocusRequester

    @Composable
    private fun PendingBar() {
        key(selected) {
            val id = selected
            val tabUrl = if (id == tabA) urlA else "https://b.example/"
            val flow = if (id == tabA) flowA else flowB
            val pending = rememberPendingSubmit(id, tabUrl, flow, downloadSource)
            var typed by remember { mutableStateOf("") }
            var focused by remember { mutableStateOf(false) }
            addressRequester = remember { FocusRequester() }
            Column {
                OutlinedTextField(
                    value = addressBarText(focused, typed, collectedUrl = flow.value.url, selectedTabUrl = tabUrl, pendingSubmit = pending.text),
                    onValueChange = {
                        typed = it
                        pending.clear()
                    },
                    modifier = Modifier
                        .testTag("address")
                        .focusRequester(addressRequester)
                        .onFocusChanged { f ->
                            if (f.hasFocus && !focused) typed = addressBarTextOnFocus(flow.value.url, tabUrl, pending.text)
                            focused = f.hasFocus
                        },
                )
                Text("go", Modifier.testTag("go").clickable { pending.submit(target, flow.value) })
                Text("stop", Modifier.testTag("stop").clickable { pending.clear() })
            }
        }
    }

    private fun addressText(): String =
        rule.onNodeWithTag("address").fetchSemanticsNode().config
            .first { it.key.name == "EditableText" }.value.toString()

    private fun go() {
        rule.onNodeWithTag("go").performClick()
        rule.waitForIdle()
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

    @Test
    fun loadedAndCommittedShowsTheNewAddress() {
        start()
        go()
        emit(flowA) { it.copy(isLoading = true, progress = 0) }
        assertEquals(target, addressText())
        rule.runOnIdle { urlA = target }
        rule.waitForIdle()
        emit(flowA) { it.copy(url = target, isLoading = false, progress = 100) }
        assertEquals(target, addressText())
    }

    @Test
    fun commitOfADifferentAddressWhileStillLoadingShowsTheCommittedAddress() {
        // A redirect: the tab's url changes to something other than the typed target while the load is still running.
        val final = "https://final.example/"
        start()
        go()
        emit(flowA) { it.copy(isLoading = true) }
        rule.runOnIdle { urlA = final }
        rule.waitForIdle()
        assertEquals(final, addressText())
    }

    @Test
    fun loadEndingWithoutACommitFallsBackToTheOldAddress() {
        start()
        go()
        emit(flowA) { it.copy(isLoading = true) }
        assertEquals("still loading: the target stays", target, addressText())
        emit(flowA) { it.copy(isLoading = false, progress = 0) }
        assertEquals(old, addressText())
    }

    @Test
    fun failedLoadFallsBackToTheOldAddress() {
        start()
        go()
        emit(flowA) { it.copy(isLoading = true) }
        emit(flowA) { it.copy(isLoading = false, loadFailed = true, failedUrl = target) }
        assertEquals(old, addressText())
    }

    @Test
    fun crashFallsBackToTheOldAddress() {
        start()
        go()
        emit(flowA) { it.copy(crashed = true, isLoading = false) }
        assertEquals(old, addressText())
    }

    @Test
    fun stopTapFallsBackToTheOldAddress() {
        start()
        go()
        rule.onNodeWithTag("stop").performClick()
        rule.waitForIdle()
        assertEquals(old, addressText())
    }

    @Test
    fun downloadOfferedForThisTabFallsBackToTheOldAddress() {
        start()
        go()
        rule.runOnIdle { downloadSource = tabA }
        rule.waitForIdle()
        assertEquals(old, addressText())
    }

    @Test
    fun downloadOfferedForAnotherTabLeavesThisTabsSubmit() {
        start()
        go()
        rule.runOnIdle { downloadSource = tabB }
        rule.waitForIdle()
        assertEquals(target, addressText())
    }

    @Test
    fun refocusRightAfterGoStillGetsTheSubmittedAddress() {
        // C3 (hardware Enter hands focus back to the bar right after Go): the buffer must be the pending target.
        start()
        go()
        rule.runOnIdle { addressRequester.requestFocus() }
        rule.onNodeWithTag("address").assertIsFocused()
        assertEquals(target, addressText())
    }

    @Test
    fun pendingSubmitStaysInItsOwnTab() {
        start()
        go()
        rule.runOnIdle { selected = tabB }
        rule.waitForIdle()
        assertEquals("https://b.example/", addressText())
        // The old tab's session ending a load must not touch (or show in) the selected tab, and coming back starts clean.
        emit(flowA) { it.copy(isLoading = true) }
        emit(flowA) { it.copy(isLoading = false) }
        rule.runOnIdle { selected = tabA }
        rule.waitForIdle()
        assertEquals(old, addressText())
    }

    @Test
    fun staleFailureFromBeforeGoDoesNotClearTheNewSubmit() {
        // The engine resets loadFailed only at onPageStart: right after Go the flag is still the old one.
        flowA.value = flowA.value.copy(loadFailed = true, failedUrl = "https://broken.example/")
        start()
        go()
        assertEquals(target, addressText())
        emit(flowA) { it.copy(loadFailed = false, failedUrl = null, isLoading = true) }
        assertEquals(target, addressText())
        emit(flowA) { it.copy(loadFailed = true, failedUrl = target, isLoading = false) }
        assertEquals(old, addressText())
    }
}
