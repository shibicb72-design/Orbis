package com.shibi.orbis.ui.browser

import android.view.View
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * OBS-ENTER-01: submitting the address bar with a hardware Enter (outside touch mode) used to leave it focused and empty.
 * [SubmitBar] repeats BrowserScreen's address field wiring (typed buffer, onFocusChanged reset, pendingSubmit, onGo)
 * on top of the same pure helpers; [useSink] false is the old onGo (clearFocus only), true is the fixed one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AddressBarSubmitFocusComposeTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val submitted = "https://example.org/page"

    /** The tab's committed address (what BrowserScreen reads from selectedTab.url); empty = the new page has not committed. */
    private var tabUrl by mutableStateOf("")

    /** The live session's address (what the focus reset reads); lags behind the submit like the real one. */
    private var sessionUrl by mutableStateOf("")
    private var loads = mutableListOf<String>()
    private lateinit var composeView: View
    private lateinit var focusSink: FocusRequester
    private lateinit var addressRequester: FocusRequester

    @Composable
    private fun SubmitBar(isPrivate: Boolean, useSink: Boolean) {
        composeView = LocalView.current
        focusSink = remember { FocusRequester() }
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        Column(Modifier.focusRequester(focusSink).focusable()) {
            key("tab-1") {
                val typedState = rememberAddressBarTypedState(isPrivate)
                var typed by typedState
                var addressFocused by remember { mutableStateOf(false) }
                var pendingSubmit by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(tabUrl) { pendingSubmit = null }
                addressRequester = remember { FocusRequester() }
                OutlinedTextField(
                    value = addressBarText(addressFocused, typed, collectedUrl = sessionUrl, selectedTabUrl = tabUrl, pendingSubmit = pendingSubmit),
                    onValueChange = {
                        typed = it
                        pendingSubmit = null
                    },
                    modifier = Modifier
                        .testTag("address")
                        .focusRequester(addressRequester)
                        .onFocusChanged { focus ->
                            if (focus.hasFocus && !addressFocused) typed = addressBarTextOnFocus(sessionUrl, tabUrl, pendingSubmit)
                            addressFocused = focus.hasFocus
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            val target = typed.trim()
                            if (target.isNotEmpty()) {
                                loads += target
                                if (useSink) {
                                    pendingSubmit = pendingSubmitFor(target)
                                    focusSink.requestFocus()
                                } else {
                                    focusManager.clearFocus()
                                }
                            }
                        },
                    ),
                )
            }
        }
    }

    private fun setBar(isPrivate: Boolean, useSink: Boolean, touchMode: Boolean) {
        rule.setContent { SubmitBar(isPrivate, useSink) }
        rule.runOnIdle { setTouchMode(composeView, touchMode) }
        rule.waitForIdle()
    }

    /** Robolectric starts in touch mode; a hardware key event is what takes the real app out of it. */
    private fun setTouchMode(view: View, touchMode: Boolean) {
        val root = View::class.java.getMethod("getViewRootImpl").invoke(view)!!
        val method = root.javaClass.getDeclaredMethod("ensureTouchModeLocally", Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        method.invoke(root, touchMode)
        assertEquals("sanity: touch mode really switched", touchMode, view.isInTouchMode)
    }

    private fun focusTypeAndGo() {
        rule.onNodeWithTag("address").performClick()
        rule.runOnIdle { addressRequester.requestFocus() }
        rule.onNodeWithTag("address").assertIsFocused()
        rule.onNodeWithTag("address").performTextInput(submitted)
        rule.onNodeWithTag("address").performImeAction()
        rule.waitForIdle()
    }

    private fun addressText(): String =
        rule.onNodeWithTag("address").fetchSemanticsNode().config
            .first { it.key.name == "EditableText" }.value.toString()

    @Test
    fun controlOldOnGoRefocusesAndEmptiesTheBarOutsideTouchMode() {
        // Reproduces OBS-ENTER-01 with the old wiring (clearFocus, no pendingSubmit), so the passing tests below are not
        // vacuous. Robolectric's view tree does not do the real device's refocus after clearFocus() (it ends unfocused
        // here), so that refocus is replayed by hand: the first focusable, the address field, takes focus again.
        setBar(isPrivate = false, useSink = false, touchMode = false)
        focusTypeAndGo()
        assertEquals(listOf(submitted), loads)
        rule.runOnIdle { addressRequester.requestFocus() }
        rule.onNodeWithTag("address").assertIsFocused()
        assertEquals("", addressText())
    }

    @Test
    fun controlOldOnGoIsFineInTouchMode() {
        setBar(isPrivate = false, useSink = false, touchMode = true)
        focusTypeAndGo()
        rule.onNodeWithTag("address").assertIsNotFocused()
    }

    private fun assertSubmitEndsUnfocusedAndShowsTheAddress(isPrivate: Boolean, touchMode: Boolean) {
        setBar(isPrivate, useSink = true, touchMode = touchMode)
        focusTypeAndGo()
        assertEquals(listOf(submitted), loads)
        rule.onNodeWithTag("address").assertIsNotFocused()
        // The page has not committed yet (tabUrl and sessionUrl still empty): the bar must not be empty.
        assertEquals(submitted, addressText())
        // The commit lands: the bar shows the committed address and the pending text is gone.
        rule.runOnIdle { tabUrl = submitted; sessionUrl = submitted }
        rule.waitForIdle()
        rule.onNodeWithTag("address").assertIsNotFocused()
        assertEquals(submitted, addressText())
    }

    @Test
    fun hardwareEnterOnNormalTabEndsUnfocusedWithTheAddress() = assertSubmitEndsUnfocusedAndShowsTheAddress(false, touchMode = false)

    @Test
    fun hardwareEnterOnPrivateTabEndsUnfocusedWithTheAddress() = assertSubmitEndsUnfocusedAndShowsTheAddress(true, touchMode = false)

    @Test
    fun touchGoOnNormalTabEndsUnfocusedWithTheAddress() = assertSubmitEndsUnfocusedAndShowsTheAddress(false, touchMode = true)

    @Test
    fun touchGoOnPrivateTabEndsUnfocusedWithTheAddress() = assertSubmitEndsUnfocusedAndShowsTheAddress(true, touchMode = true)

    @Test
    fun focusRegainedBeforeTheCommitKeepsTheSubmittedAddress() {
        // The view system's refocus (or the user tapping the bar again) before the page commits must not clobber it.
        setBar(isPrivate = true, useSink = true, touchMode = false)
        focusTypeAndGo()
        rule.runOnIdle { addressRequester.requestFocus() }
        rule.onNodeWithTag("address").assertIsFocused()
        assertEquals(submitted, addressText())
    }

    @Test
    fun editingAfterGoDropsThePendingAddress() {
        setBar(isPrivate = false, useSink = true, touchMode = true)
        focusTypeAndGo()
        rule.runOnIdle { addressRequester.requestFocus() }
        rule.onNodeWithTag("address").performTextInput("x")
        rule.runOnIdle { focusSink.requestFocus() }
        rule.onNodeWithTag("address").assertIsNotFocused()
        // Edited, so the pending text is gone: the unfocused bar shows only the (still empty) committed address.
        assertEquals("", addressText())
    }
}
