package com.shibi.orbis.ui.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shibi.orbis.customtabs.CustomTabDesktopItem
import com.shibi.orbis.engine.SessionInit
import com.shibi.orbis.engine.SitePolicy
import com.shibi.orbis.settings.DarkMode
import com.shibi.orbis.tabs.NoticeSession
import com.shibi.orbis.tabs.NoticeStore
import com.shibi.orbis.tabs.TabManager
import com.shibi.orbis.ui.theme.OrbisTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.compose.material3.SnackbarResult
import org.junit.Assert.assertTrue
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The auto-desktop notice: exact text, once per mark, background tabs wait, Undo, the sheet note, the Custom Tab item. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AutoDesktopNoticeComposeTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val created = mutableListOf<NoticeSession>()
    private val text = "Showing desktop site because this site blocked Orbis"

    @After
    fun tearDown() = scope.cancel()

    private fun manager(): TabManager {
        val m = TabManager(
            factory = { init -> NoticeSession(init).also { created += it } },
            store = NoticeStore(),
            scope = scope,
            log = {},
            io = dispatcher,
        )
        m.restore()
        return m
    }

    private class RecordingPolicy : SitePolicy {
        val declined = mutableListOf<Pair<String, Boolean>>()
        override fun desktopFor(url: String, isPrivate: Boolean) = false
        override fun javaScriptFor(url: String, isPrivate: Boolean) = true
        override fun declineAutoDesktop(url: String, isPrivate: Boolean) {
            declined += url to isPrivate
        }
    }

    private val policy = RecordingPolicy()
    private val fullScreen = mutableStateOf(false)
    private val pip = mutableStateOf(false)
    private var host: SnackbarHostState? = null

    /** The wiring BrowserScreen uses, over a real TabManager. */
    private fun show(m: TabManager) {
        rule.setContent {
            OrbisTheme(DarkMode.LIGHT) {
                val selected by m.selected.collectAsState()
                val marks by m.autoDesktopNotices.collectAsState()
                val snackbars = remember { SnackbarHostState() }
                val session = selected.session
                Box {
                    host = snackbars
                    SnackbarHost(snackbars)
                    AutoDesktopNotice(
                        tabId = selected.id,
                        marked = session != null && !fullScreen.value && !pip.value && selected.id in marks,
                        currentUrl = session?.state?.collectAsState()?.value?.url.orEmpty(),
                        snackbars = snackbars,
                        onShown = { m.clearAutoDesktopNotice(selected.id) },
                        onUndo = { noticeUrl -> session?.let { undoAutoDesktop(policy, it, noticeUrl, it.state.value.url) } },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun noEventShowsNothing() {
        val m = manager()
        m.newTab("https://a.example/")
        show(m)
        rule.onNodeWithText(text).assertDoesNotExist()
    }

    @Test
    fun aMarkOnTheVisibleTabShowsTheExactTextAndIsCleared() {
        val m = manager()
        m.newTab("https://a.example/")
        show(m)
        created[0].kept.tryEmit(Unit)
        rule.waitForIdle()
        rule.onNodeWithText(text).assertIsDisplayed()
        rule.onNodeWithText("Undo").assertIsDisplayed()
        assertEquals(emptySet<Long>(), m.autoDesktopNotices.value)
    }

    @Test
    fun theNoticeGoesAwayAfterAboutEightSeconds() {
        val m = manager()
        m.newTab("https://a.example/")
        show(m)
        created[0].kept.tryEmit(Unit)
        rule.waitForIdle()
        rule.onNodeWithText(text).assertIsDisplayed()
        rule.mainClock.advanceTimeBy(AUTO_DESKTOP_NOTICE_MILLIS + 1_000)
        rule.waitForIdle()
        rule.onNodeWithText(text).assertDoesNotExist()
    }

    @Test
    fun aBackgroundTabWaitsUntilItIsShownThenShowsOnce() {
        val m = manager()
        val a = m.newTab("https://a.example/")!!
        val b = m.newTab("https://b.example/")!!
        show(m)
        created[0].kept.tryEmit(Unit)
        rule.waitForIdle()
        rule.onNodeWithText(text).assertDoesNotExist()
        assertEquals(setOf(a), m.autoDesktopNotices.value)

        m.select(a)
        rule.waitForIdle()
        rule.onNodeWithText(text).assertIsDisplayed()
        assertEquals(emptySet<Long>(), m.autoDesktopNotices.value)

        // Leaving and coming back does not show it again.
        rule.mainClock.advanceTimeBy(AUTO_DESKTOP_NOTICE_MILLIS + 1_000)
        rule.waitForIdle()
        m.select(b)
        rule.waitForIdle()
        m.select(a)
        rule.waitForIdle()
        rule.onNodeWithText(text).assertDoesNotExist()
    }

    @Test
    fun aClosedTabShowsNothing() {
        val m = manager()
        val a = m.newTab("https://a.example/")!!
        m.newTab("https://b.example/")
        show(m)
        created[0].kept.tryEmit(Unit)
        m.close(a)
        rule.waitForIdle()
        assertEquals(emptySet<Long>(), m.autoDesktopNotices.value)
        rule.onNodeWithText(text).assertDoesNotExist()
    }

    @Test
    fun undoDeclinesTheSiteAndTurnsDesktopOff() {
        val m = manager()
        m.newTab("https://a.example/")
        show(m)
        val session = created[0]
        session.setDesktopMode(true)
        session.desktopCalls.clear()
        session.kept.tryEmit(Unit)
        rule.waitForIdle()
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertEquals(listOf("https://blocked.example/page" to false), policy.declined)
        assertEquals(listOf(false), session.desktopCalls)
        rule.onNodeWithText(text).assertDoesNotExist()
    }

    private fun markedSession(m: TabManager, url: String = "https://a.test/x"): NoticeSession {
        m.newTab(url)
        show(m)
        val s = created[0]
        s.stateFlow.value = s.stateFlow.value.copy(url = url)
        return s
    }

    @Test
    fun undoAfterTheTabMovedToAnotherSiteDeclinesTheNoticeSiteOnly() {
        val m = manager()
        val s = markedSession(m)
        s.kept.tryEmit(Unit)
        rule.waitForIdle()
        s.stateFlow.value = s.stateFlow.value.copy(url = "https://b.test/")
        rule.waitForIdle()
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertEquals(listOf("https://a.test/x" to false), policy.declined)
        assertTrue(s.desktopCalls.isEmpty())
    }

    @Test
    fun undoOnTheSameSiteAnotherPathAlsoTurnsDesktopOff() {
        val m = manager()
        val s = markedSession(m)
        s.kept.tryEmit(Unit)
        rule.waitForIdle()
        s.stateFlow.value = s.stateFlow.value.copy(url = "https://A.test/other")
        rule.waitForIdle()
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertEquals(listOf("https://a.test/x" to false), policy.declined)
        assertEquals(listOf(false), s.desktopCalls)
    }

    private fun holdsBackWhile(flag: androidx.compose.runtime.MutableState<Boolean>) {
        val m = manager()
        val s = markedSession(m)
        val id = m.selected.value.id
        flag.value = true
        rule.waitForIdle()
        s.kept.tryEmit(Unit)
        rule.waitForIdle()
        rule.onNodeWithText(text).assertDoesNotExist()
        assertEquals(setOf(id), m.autoDesktopNotices.value)
        flag.value = false
        rule.waitForIdle()
        rule.onNodeWithText(text).assertIsDisplayed()
        assertEquals(emptySet<Long>(), m.autoDesktopNotices.value)
        rule.mainClock.advanceTimeBy(AUTO_DESKTOP_NOTICE_MILLIS + 1_000)
        rule.waitForIdle()
        rule.onNodeWithText(text).assertDoesNotExist()
    }

    @Test
    fun fullscreenHoldsTheNoticeBackUntilItEnds() = holdsBackWhile(fullScreen)

    @Test
    fun pictureInPictureHoldsTheNoticeBackUntilItEnds() = holdsBackWhile(pip)

    @Test
    fun anotherSnackbarIsKeptAndTheNoticeWaitsForItThenShowsTheFullTime() {
        val m = manager()
        val s = markedSession(m)
        val id = m.selected.value.id
        var result: SnackbarResult? = null
        scope.launch { result = host!!.showSnackbar("Tab closed", actionLabel = "Reopen", duration = androidx.compose.material3.SnackbarDuration.Indefinite) }
        rule.waitForIdle()
        rule.onNodeWithText("Tab closed").assertIsDisplayed()

        s.kept.tryEmit(Unit)
        rule.waitForIdle()
        // The other snackbar is still there and the mark has not been cleared while the notice waits.
        rule.onNodeWithText("Tab closed").assertIsDisplayed()
        rule.onNodeWithText(text).assertDoesNotExist()
        assertEquals(setOf(id), m.autoDesktopNotices.value)
        rule.mainClock.advanceTimeBy(AUTO_DESKTOP_NOTICE_MILLIS * 2)
        rule.waitForIdle()
        rule.onNodeWithText("Tab closed").assertIsDisplayed()
        assertEquals(setOf(id), m.autoDesktopNotices.value)

        // Its action still works; then the notice appears and stays for the whole time.
        rule.onNodeWithText("Reopen").performClick()
        rule.waitForIdle()
        assertEquals(SnackbarResult.ActionPerformed, result)
        rule.onNodeWithText(text).assertIsDisplayed()
        assertEquals(emptySet<Long>(), m.autoDesktopNotices.value)
        rule.mainClock.advanceTimeBy(AUTO_DESKTOP_NOTICE_MILLIS - 1_000)
        rule.waitForIdle()
        rule.onNodeWithText(text).assertIsDisplayed()
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        rule.onNodeWithText(text).assertDoesNotExist()
    }

    @Test
    fun aTabSwitchWhileWaitingKeepsTheMarkSoTheNoticeShowsLater() {
        val m = manager()
        val a = m.newTab("https://a.test/x")!!
        val b = m.newTab("https://b.test/")!!
        show(m)
        scope.launch { host!!.showSnackbar("Tab closed", actionLabel = "Reopen", duration = androidx.compose.material3.SnackbarDuration.Indefinite) }
        rule.waitForIdle()
        created[1].kept.tryEmit(Unit)
        rule.waitForIdle()
        m.select(a)
        rule.waitForIdle()
        assertEquals(setOf(b), m.autoDesktopNotices.value)
        host!!.currentSnackbarData?.dismiss()
        rule.waitForIdle()
        rule.onNodeWithText(text).assertDoesNotExist()
        m.select(b)
        rule.waitForIdle()
        rule.onNodeWithText(text).assertIsDisplayed()
        assertEquals(emptySet<Long>(), m.autoDesktopNotices.value)
    }

    @Test
    fun undoOnAPrivateTabPassesIsPrivate() {
        val s = NoticeSession(SessionInit(isPrivate = true))
        undoAutoDesktop(policy, s, "https://p.example/", "https://p.example/")
        assertEquals(listOf("https://p.example/" to true), policy.declined)
        assertEquals(listOf(false), s.desktopCalls)
    }

    private fun sheet(auto: Boolean, desktop: Boolean) {
        rule.setContent {
            OrbisTheme(DarkMode.LIGHT) {
                SiteSettingsSheet(
                    host = "blocked.example",
                    secure = true,
                    isPrivate = false,
                    trackingOn = true,
                    javaScriptOn = true,
                    desktop = desktop,
                    desktopAutoNote = showsAutoDesktopNote(auto, desktop),
                    permissions = emptyMap(),
                    onTracking = {},
                    onJavaScript = {},
                    onDesktop = {},
                    onPermission = { _, _ -> },
                    onClearData = {},
                    onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
    }

    private val note = "Turned on automatically because this site blocked Orbis"

    @Test
    fun sheetNoteShowsForAnAutoSiteWithDesktopOn() {
        sheet(auto = true, desktop = true)
        rule.onNodeWithText(note).assertIsDisplayed()
    }

    @Test
    fun sheetNoteHiddenForAnAutoSiteWithDesktopOff() {
        sheet(auto = true, desktop = false)
        rule.onNodeWithText(note).assertDoesNotExist()
    }

    @Test
    fun sheetNoteHiddenForANormalDesktopSite() {
        sheet(auto = false, desktop = true)
        rule.onNodeWithText(note).assertDoesNotExist()
    }

    private fun customTabItem(desktop: Boolean) {
        rule.setContent {
            OrbisTheme(DarkMode.LIGHT) {
                DropdownMenu(expanded = true, onDismissRequest = {}) {
                    CustomTabDesktopItem(desktop) {}
                }
            }
        }
        rule.waitForIdle()
    }

    private fun checkbox(state: ToggleableState) =
        SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state)

    @Test
    fun customTabItemShowsACheckedBoxWhenDesktopIsOn() {
        customTabItem(true)
        rule.onNodeWithText("Desktop site").assertIsDisplayed()
        rule.onNode(checkbox(ToggleableState.On)).assertExists()
        rule.onNode(checkbox(ToggleableState.Off)).assertDoesNotExist()
    }

    @Test
    fun customTabItemShowsAnUncheckedBoxWhenDesktopIsOff() {
        customTabItem(false)
        rule.onNode(checkbox(ToggleableState.Off)).assertExists()
        rule.onNode(checkbox(ToggleableState.On)).assertDoesNotExist()
    }
}
