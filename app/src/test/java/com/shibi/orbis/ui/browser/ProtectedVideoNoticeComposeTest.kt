package com.shibi.orbis.ui.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shibi.orbis.engine.SessionState
import com.shibi.orbis.engine.afterPageStart
import com.shibi.orbis.engine.afterProtectedVideoNoticeShown
import com.shibi.orbis.engine.ProtectedVideoAnswer
import com.shibi.orbis.engine.afterProtectedVideoAnswer
import com.shibi.orbis.settings.DarkMode
import com.shibi.orbis.ui.theme.OrbisTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The protected-video notice as BrowserScreen wires it: exact words, once per page load, where it may show, how it ends. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ProtectedVideoNoticeComposeTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
    private val text = "This video is protected (DRM). Protected video is turned off in Settings."
    private val privateText = "Protected video is not played in private tabs."

    @After
    fun tearDown() = scope.cancel()

    /** The page state of the selected tab, changed by the test the way the engine changes it. */
    private val page = mutableStateOf(SessionState().afterPageStart())
    private val tabId = mutableStateOf(1L)
    private val inFront = mutableStateOf(true)
    private val pageHidden = mutableStateOf(false)
    private val inPip = mutableStateOf(false)
    private val shownLoads = mutableListOf<Int>()
    private var host: SnackbarHostState? = null

    private fun show() {
        rule.setContent {
            OrbisTheme(DarkMode.LIGHT) {
                val snackbars = remember { SnackbarHostState() }
                host = snackbars
                Box {
                    SnackbarHost(snackbars)
                    ProtectedVideoNotice(
                        tabId = tabId.value,
                        tabSelected = true,
                        refusal = page.value.protectedVideo,
                        currentLoad = page.value.loadStarts,
                        pageHidden = pageHidden.value,
                        inPip = inPip.value,
                        snackbars = snackbars,
                        onShown = { load ->
                            shownLoads += load
                            page.value = page.value.afterProtectedVideoNoticeShown(load)
                        },
                        inFront = inFront.value,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun refuse(answer: ProtectedVideoAnswer = ProtectedVideoAnswer.DENY_SETTING_OFF) {
        page.value = page.value.afterProtectedVideoAnswer(answer)
        rule.waitForIdle()
    }

    private fun expire() {
        rule.mainClock.advanceTimeBy(PROTECTED_VIDEO_NOTICE_MILLIS + 1_000)
        rule.waitForIdle()
    }

    @Test
    fun noRefusalShowsNothing() {
        show()
        rule.onNodeWithText(text).assertDoesNotExist()
    }

    @Test
    fun aRefusalShowsTheExactWordsWithACloseButton() {
        show()
        refuse()
        rule.onNodeWithText(text).assertIsDisplayed()
        rule.onNodeWithContentDescription("Dismiss").assertIsDisplayed()
        assertEquals(listOf(1), shownLoads)
    }

    @Test
    fun aPrivateTabRefusalShowsThePrivateTabWordsOnly() {
        show()
        refuse(ProtectedVideoAnswer.DENY_PRIVATE_TAB)
        rule.onNodeWithText(privateText).assertIsDisplayed()
        rule.onNodeWithText(text).assertDoesNotExist()
        assertEquals(listOf(1), shownLoads)
    }

    @Test
    fun anAllowedRequestShowsNoNotice() {
        show()
        refuse(ProtectedVideoAnswer.ALLOW)
        rule.onNodeWithText(text).assertDoesNotExist()
        rule.onNodeWithText(privateText).assertDoesNotExist()
        assertEquals(emptyList<Int>(), shownLoads)
    }

    @Test
    fun theNoticeStaysTenSecondsThenGoes() {
        show()
        refuse()
        rule.mainClock.advanceTimeBy(PROTECTED_VIDEO_NOTICE_MILLIS - 1_000)
        rule.waitForIdle()
        rule.onNodeWithText(text).assertIsDisplayed()
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        rule.onNodeWithText(text).assertDoesNotExist()
    }

    @Test
    fun theCloseButtonEndsIt() {
        show()
        refuse()
        rule.onNodeWithContentDescription("Dismiss").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(text).assertDoesNotExist()
    }

    @Test
    fun moreRefusalsInTheSamePageLoadShowItOnce() {
        show()
        refuse()
        expire()
        refuse()
        refuse()
        rule.onNodeWithText(text).assertDoesNotExist()
        assertEquals(listOf(1), shownLoads)
    }

    @Test
    fun aReloadThatIsRefusedAgainShowsItAgain() {
        show()
        refuse()
        expire()
        page.value = page.value.afterPageStart()
        rule.waitForIdle()
        rule.onNodeWithText(text).assertDoesNotExist()
        refuse()
        rule.onNodeWithText(text).assertIsDisplayed()
        assertEquals(listOf(1, 2), shownLoads)
    }

    @Test
    fun aNewPageLoadEndsTheNotice() {
        show()
        refuse()
        rule.onNodeWithText(text).assertIsDisplayed()
        page.value = page.value.afterPageStart()
        rule.waitForIdle()
        rule.onNodeWithText(text).assertDoesNotExist()
    }

    @Test
    fun aTabSwitchEndsTheNotice() {
        show()
        refuse()
        rule.onNodeWithText(text).assertIsDisplayed()
        tabId.value = 2L
        page.value = SessionState().afterPageStart()
        rule.waitForIdle()
        rule.onNodeWithText(text).assertDoesNotExist()
    }

    private fun heldBackWhile(flag: androidx.compose.runtime.MutableState<Boolean>, value: Boolean) {
        show()
        flag.value = value
        rule.waitForIdle()
        refuse()
        rule.onNodeWithText(text).assertDoesNotExist()
        assertEquals(emptyList<Int>(), shownLoads)
        flag.value = !value
        rule.waitForIdle()
        rule.onNodeWithText(text).assertIsDisplayed()
        assertEquals(listOf(1), shownLoads)
    }

    private fun endedBy(flag: androidx.compose.runtime.MutableState<Boolean>, value: Boolean) {
        show()
        refuse()
        rule.onNodeWithText(text).assertIsDisplayed()
        flag.value = value
        rule.waitForIdle()
        rule.onNodeWithText(text).assertDoesNotExist()
        // Told once: coming back does not show it again in this page load.
        flag.value = !value
        rule.waitForIdle()
        rule.onNodeWithText(text).assertDoesNotExist()
    }

    @Test
    fun aLockedPrivatePageHoldsItBackUntilUnlocked() = heldBackWhile(pageHidden, true)

    @Test
    fun pictureInPictureHoldsItBackUntilItEnds() = heldBackWhile(inPip, true)

    @Test
    fun notInFrontHoldsItBackUntilOrbisIsInFront() = heldBackWhile(inFront, false)

    @Test
    fun theLockEndsTheNotice() = endedBy(pageHidden, true)

    @Test
    fun pictureInPictureEndsTheNotice() = endedBy(inPip, true)

    @Test
    fun leavingTheAppEndsTheNotice() = endedBy(inFront, false)

    @Test
    fun anotherSnackbarIsKeptAndTheNoticeWaitsForIt() {
        show()
        scope.launch { host!!.showSnackbar("Tab closed", actionLabel = "Reopen", duration = SnackbarDuration.Indefinite) }
        rule.waitForIdle()
        refuse()
        rule.onNodeWithText("Tab closed").assertIsDisplayed()
        rule.onNodeWithText(text).assertDoesNotExist()
        assertEquals(emptyList<Int>(), shownLoads)
        host!!.currentSnackbarData?.dismiss()
        rule.waitForIdle()
        rule.onNodeWithText(text).assertIsDisplayed()
        assertEquals(listOf(1), shownLoads)
    }
}
