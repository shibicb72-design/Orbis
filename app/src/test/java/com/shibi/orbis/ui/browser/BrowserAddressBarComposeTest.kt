package com.shibi.orbis.ui.browser

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.shibi.orbis.engine.SearchEngine
import com.shibi.orbis.engine.SessionState
import com.shibi.orbis.engine.afterLoadError
import com.shibi.orbis.engine.afterPageStart
import com.shibi.orbis.engine.afterPageStop
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice 1: the PRODUCTION [BrowserAddressBar] (the composable BrowserScreen mounts), not a mirror. The session is a
 * MutableStateFlow driven with the engine's own transitions. BrowserScreen's argument wiring is checked on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BrowserAddressBarComposeTest {
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
    private var focusOnStart by mutableStateOf(false)
    private var consumed = 0
    private val loads = mutableListOf<String>()
    private var hides = 0
    private var stops = 0

    private fun start(barWidthDp: Int = 360) {
        rule.setContent {
            MaterialTheme {
                val sink = remember { FocusRequester() }
                val id = selected
                val flow = if (id == tabA) flowA else flowB
                val state by flow.collectAsState()
                Column(Modifier.focusRequester(sink).focusable()) {
                    BrowserAddressBar(
                        tabId = id,
                        isPrivate = false,
                        tabUrl = if (id == tabA) urlA else "https://b.example/",
                        sessionState = flow,
                        downloadOffer = null,
                        isLoading = state.isLoading,
                        canReload = true,
                        searchEngine = { SearchEngine.DEFAULT },
                        focusOnStart = focusOnStart,
                        onFocusOnStartConsumed = {
                            consumed++
                            focusOnStart = false
                        },
                        loadUrl = { loads += it },
                        stop = { stops++ },
                        reload = {},
                        hideKeyboard = { hides++ },
                        moveFocusToSink = { sink.requestFocus() },
                        modifier = Modifier.width(barWidthDp.dp),
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun field() = rule.onNodeWithTag(ADDRESS_FIELD_TAG)

    private fun fieldText(): String =
        field().fetchSemanticsNode().config.first { it.key.name == "EditableText" }.value.toString()

    private fun stateDescription(): String? = field().fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun restingShown(): Boolean =
        rule.onAllNodesWithTag(ADDRESS_RESTING_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun emit(f: (SessionState) -> SessionState) {
        rule.runOnIdle { flowA.value = f(flowA.value) }
        rule.waitForIdle()
    }

    private fun typeAndGo(text: String = target) {
        field().performClick()
        rule.waitForIdle()
        field().performTextReplacement(text)
        field().performImeAction()
        rule.waitForIdle()
    }

    @Test
    fun restingShowsTheFullCommittedAddressWithoutAFloatingLabel() {
        start()
        assertEquals(old, fieldText())
        assertTrue("resting line drawn", restingShown())
        assertEquals("no hint text over a committed address", 0, rule.onAllNodesWithText("Search or enter address").fetchSemanticsNodes().size)
        assertNull(stateDescription())
    }

    @Test
    fun aBlankTabShowsThePlaceholderAndNoRestingLine() {
        urlA = ""
        flowA.value = SessionState(url = "")
        start()
        assertFalse(restingShown())
        assertEquals(1, rule.onAllNodesWithText("Search or enter address").fetchSemanticsNodes().size)
    }

    @Test
    fun anOverlongAddressEllipsizesAtRestButKeepsTheFullStringForScreenReadersAndEdit() {
        val long = "https://" + "very-long-subdomain-".repeat(10) + "example.com/path"
        urlA = long
        flowA.value = SessionState(url = long)
        start(barWidthDp = 300)
        assertEquals(long, fieldText())
        field().performClick()
        rule.waitForIdle()
        field().assertIsFocused()
        assertFalse("edit mode drops the resting line", restingShown())
        assertEquals(long, fieldText())
    }

    @Test
    fun goSubmitsOnceAndThePendingTargetIsAnnouncedAsLoadingUntilStop() {
        start()
        typeAndGo()
        assertEquals(listOf(target), loads)
        assertEquals(1, hides)
        field().assertIsNotFocused()
        emit { it.afterPageStart() }
        assertEquals(target, fieldText())
        assertEquals("Loading", stateDescription())
        rule.onNodeWithTag(ADDRESS_STOP_TAG).performClick()
        rule.waitForIdle()
        assertEquals(1, stops)
        assertEquals("Stop forgets the target: back to the committed page", old, fieldText())
        assertNull(stateDescription())
    }

    @Test
    fun aFailedLoadKeepsTheTypedAddressWithoutClaimingItIsLoading() {
        start()
        typeAndGo()
        emit { it.afterPageStart() }
        emit { it.afterLoadError(target) }
        emit { it.afterPageStop(success = false) }
        assertEquals(target, fieldText())
        assertNull("failed, not loading", stateDescription())
    }

    @Test
    fun focusOnStartFocusesOnceAndOnlyWhenAsked() {
        start()
        field().assertIsNotFocused()
        assertEquals(0, consumed)
        rule.runOnIdle {
            focusOnStart = true
            selected = tabB
        }
        rule.waitForIdle()
        field().assertIsFocused()
        assertEquals(1, consumed)
        assertFalse(focusOnStart)
    }

    @Test
    fun aPageCommittingWhileTheBarIsFocusedButUntouchedLeavesEditMode() {
        // New tab, then a link from another app lands in that blank tab.
        urlA = ""
        flowA.value = SessionState(url = "")
        start()
        field().performClick()
        rule.waitForIdle()
        field().assertIsFocused()
        rule.runOnIdle { urlA = "https://linked.example/" }
        rule.waitForIdle()
        field().assertIsNotFocused()
        assertEquals(1, hides)
        assertEquals("https://linked.example/", fieldText())
    }

    @Test
    fun aPageCommittingWhileTheUserIsTypingKeepsTheirText() {
        start()
        field().performClick()
        rule.waitForIdle()
        field().performTextReplacement("half typed")
        rule.runOnIdle { urlA = "https://redirected.example/" }
        rule.waitForIdle()
        field().assertIsFocused()
        assertEquals(0, hides)
        assertEquals("half typed", fieldText())
    }

    @Test
    fun aTabSwitchNeverCarriesTypedTextOrThePendingTarget() {
        start()
        typeAndGo()
        emit { it.afterPageStart() }
        rule.runOnIdle { selected = tabB }
        rule.waitForIdle()
        assertEquals("https://b.example/", fieldText())
        assertNull(stateDescription())
        rule.runOnIdle { selected = tabA }
        rule.waitForIdle()
        assertEquals("A's pending text did not survive leaving its key", old, fieldText())
    }
}
