package com.shibi.orbis.ui.browser

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import com.shibi.orbis.engine.SessionState
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressBarPolicyTest {
    private val privateUrl = "https://secret.example/private-page"

    @Test
    fun blankAddressesShowNothing() {
        assertEquals("", addressBarDisplayUrl(""))
        assertEquals("", addressBarDisplayUrl("about:blank"))
        assertEquals("https://a.example/", addressBarDisplayUrl("https://a.example/"))
    }

    @Test
    fun focusedBarShowsTheEditBuffer() {
        assertEquals("typ", addressBarText(true, "typ", collectedUrl = privateUrl, selectedTabUrl = "https://a.example/"))
    }

    @Test
    fun unfocusedBarRendersTheSelectedTabNotTheStaleCollectedState() {
        // A frame after switching from a private tab to a new normal tab: the collected session state still holds the
        // private tab's address, the selected tab is blank.
        assertEquals("", addressBarText(false, "", collectedUrl = privateUrl, selectedTabUrl = ""))
        assertEquals("", addressBarText(false, "", collectedUrl = privateUrl, selectedTabUrl = "about:blank"))
        // Normal then new private tab.
        assertEquals("", addressBarText(false, "", collectedUrl = "https://a.example/", selectedTabUrl = ""))
        // A loaded tab shows its own address even when the collected one is another tab's.
        assertEquals("https://b.example/", addressBarText(false, "", collectedUrl = privateUrl, selectedTabUrl = "https://b.example/"))
    }

    @Test
    fun focusBufferUsesTheSessionAddressElseTheTabsOwn() {
        assertEquals("https://live.example/", addressBarTextOnFocus("https://live.example/", "https://stored.example/"))
        // The TabManager.onSessionState fallback: a fresh session reports an empty url, the tab keeps the stored one.
        assertEquals("https://stored.example/", addressBarTextOnFocus("", "https://stored.example/"))
        assertEquals("", addressBarTextOnFocus("", ""))
        assertEquals("", addressBarTextOnFocus("about:blank", ""))
    }

    @Test
    fun pendingSubmitWinsOnFocusUntilItIsCleared() {
        // OBS-ENTER-01: focus regained right after Go, before the page commits (session and tab urls still empty or old).
        assertEquals("https://new.example/", addressBarTextOnFocus("", "", pendingSubmit = "https://new.example/"))
        assertEquals("https://new.example/", addressBarTextOnFocus("https://old.example/", "https://old.example/", "https://new.example/"))
        // Private and normal tabs use the same pure rule; the tab's mode only decides where the buffer lives.
        assertEquals(privateUrl, addressBarTextOnFocus("", "", pendingSubmit = privateUrl))
        // No pending submit: exactly the old behaviour.
        assertEquals("https://old.example/", addressBarTextOnFocus("https://old.example/", "https://stored.example/", null))
        assertEquals("https://stored.example/", addressBarTextOnFocus("", "https://stored.example/", null))
        assertEquals("", addressBarTextOnFocus("", "", null))
    }

    @Test
    fun unfocusedBarShowsThePendingSubmitUntilTheTabCommits() {
        assertEquals("https://new.example/", addressBarText(false, "", collectedUrl = "", selectedTabUrl = "", pendingSubmit = "https://new.example/"))
        assertEquals(
            "https://new.example/",
            addressBarText(false, "", collectedUrl = "https://old.example/", selectedTabUrl = "https://old.example/", pendingSubmit = "https://new.example/"),
        )
        // Cleared (the tab committed): the tab's own address, as before.
        assertEquals("https://b.example/", addressBarText(false, "", collectedUrl = privateUrl, selectedTabUrl = "https://b.example/", pendingSubmit = null))
        // Focused it is still the edit buffer.
        assertEquals("typ", addressBarText(true, "typ", collectedUrl = "", selectedTabUrl = "", pendingSubmit = "https://new.example/"))
    }

    @Test
    fun pendingSubmitIsTheDisplayFormOfTheTarget() {
        assertEquals("https://a.example/x", pendingSubmitFor("https://a.example/x"))
        assertEquals("", pendingSubmitFor("about:blank"))
    }

    // ---- N1: a submitted address must not outlive a load that never commits ------------------------------------------

    private val target = "https://new.example/"
    private val idle = SessionState(url = "https://old.example/")
    private fun loading() = idle.copy(isLoading = true, progress = 10)

    /** Feeds [observations] in order; returns the pending value after each one (null = cleared). */
    private fun observe(start: PendingSubmit, vararg observations: SessionState): List<PendingSubmit?> {
        var p: PendingSubmit? = start
        return observations.map { obs ->
            p = pendingSubmitAfterSession(p, obs)
            p
        }
    }

    private fun submit(base: SessionState = idle) = pendingSubmitStart(target, base)

    @Test
    fun submitShowsTheTargetAndTheSubmitTimeSnapshotDoesNotClearItself() {
        val p = submit()
        assertEquals(target, p.text)
        // The state the submit saw, observed again, changes nothing (the collector may deliver it after the click).
        assertEquals(p, pendingSubmitAfterSession(p, idle))
        assertEquals(target, addressBarTextOnFocus("https://old.example/", "https://old.example/", p.text))
    }

    @Test
    fun failedLoadClearsThePendingText() {
        val failed = idle.copy(loadFailed = true, failedUrl = target, isLoading = false)
        assertEquals(listOf<PendingSubmit?>(null), observe(submit(), failed))
        // Through start then error, the realistic order.
        assertNull(observe(submit(), loading(), failed).last())
    }

    @Test
    fun failureAlreadyShownAtSubmitIsStaleUntilSeenClearedOrDifferent() {
        val stale = idle.copy(loadFailed = true, failedUrl = "https://broken.example/")
        val p = submit(stale)
        // The engine resets loadFailed only at onPageStart; until then the stale flag must not clear the new submit.
        assertEquals(p, pendingSubmitAfterSession(p, stale))
        // Flag reset, then it fails again: cleared.
        assertNull(observe(p, stale.copy(loadFailed = false, failedUrl = null, isLoading = true), stale).last())
        // A failure for a different url is new even without seeing the reset.
        assertNull(pendingSubmitAfterSession(p, stale.copy(failedUrl = target)))
    }

    @Test
    fun stoppedLoadClearsThePendingText() {
        // Stop: session.stop(); Gecko ends the load, url untouched. Seen as loading, then not loading.
        val r = observe(submit(), loading(), idle)
        assertNotNull(r[0])
        assertNull(r[1])
    }

    @Test
    fun finishedLoadWithoutCommitClearsThePendingText() {
        // Same shape as a Stop or any end of load that never changed the url (blocked, download without an offer, ...).
        assertNull(observe(submit(), loading(), loading().copy(progress = 80), idle.copy(progress = 100)).last())
    }

    @Test
    fun loadStillRunningKeepsThePendingText() {
        val r = observe(submit(), loading(), loading().copy(progress = 50), loading().copy(progress = 90))
        assertTrue(r.all { it != null })
        // Not started yet is not ended either.
        assertNotNull(pendingSubmitAfterSession(submit(), idle))
    }

    @Test
    fun crashedContentProcessClearsThePendingText() {
        val crashed = idle.copy(crashed = true, isLoading = false, canGoBack = false)
        assertNull(pendingSubmitAfterSession(submit(), crashed))
        assertNull(observe(submit(), loading(), crashed).last())
    }

    @Test
    fun crashAlreadyShownAtSubmitIsStaleUntilSeenCleared() {
        val staleCrash = idle.copy(crashed = true)
        val p = submit(staleCrash)
        assertEquals(p, pendingSubmitAfterSession(p, staleCrash))
        assertNull(observe(p, staleCrash.copy(crashed = false, isLoading = true), staleCrash).last())
    }

    @Test
    fun oldLoadRunningAtSubmitMustEndBeforeTheNewLoadCounts() {
        val p = submit(loading())
        // The interrupted old load ends first (onPageStop), the new one then starts and ends: only the last clears.
        val r = observe(p, loading(), idle, loading(), idle)
        assertNotNull(r[0])
        assertNotNull("the old load ending is not the new load ending", r[1])
        assertNotNull(r[2])
        assertNull(r[3])
    }

    @Test
    fun downloadOfferedForThisTabClearsAndForAnotherTabDoesNot() {
        val p = submit()
        assertNull(pendingSubmitAfterDownload(p, sourceTabId = 7L, thisTabId = 7L))
        // The pending text belongs to one tab: another tab's offer says nothing about this tab's load.
        assertSame(p, pendingSubmitAfterDownload(p, sourceTabId = 8L, thisTabId = 7L))
        assertNull(pendingSubmitAfterDownload(null, 7L, 7L))
    }

    @Test
    fun nothingPendingStaysNothingPending() {
        assertNull(pendingSubmitAfterSession(null, loading()))
        assertNull(pendingSubmitAfterSession(null, idle.copy(loadFailed = true)))
    }

    @Test
    fun clearedPendingFallsBackToTheCommittedAddressOfTheTabItself() {
        // After any clear, the unfocused bar names the tab's committed (old) page, never the stale collected state.
        val cleared = observe(submit(), loading(), idle).last()
        assertNull(cleared)
        assertEquals(
            "https://old.example/",
            addressBarText(false, "", collectedUrl = privateUrl, selectedTabUrl = "https://old.example/", pendingSubmit = cleared?.text),
        )
    }

    @Test
    fun onlyNormalTabsMaySaveTheirTypedTextInInstanceState() {
        assertTrue(addressBarTextIsSaveable(isPrivate = false))
        assertFalse(addressBarTextIsSaveable(isPrivate = true))
    }

    @Test
    fun imeOptionsFlagIsAddedForPrivateAndLeftAloneOtherwise() {
        val base = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        assertEquals(base or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, addressBarImeOptions(base, isPrivate = true))
        assertEquals(base, addressBarImeOptions(base, isPrivate = false))
    }

    private val connection: InputConnection =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(InputConnection::class.java)) { _, _, _ -> null } as InputConnection

    /** Stands in for Compose's own request: it fills in imeOptions, replacing whatever was there. */
    private inner class FakeInner(private val written: Int) : PlatformTextInputMethodRequest {
        var calls = 0

        override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
            calls++
            outAttributes.imeOptions = written
            return connection
        }
    }

    @Test
    fun interceptorAddsTheFlagAfterTheInnerRequestAndKeepsItsValue() {
        val written = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        val inner = FakeInner(written)
        val info = EditorInfo()
        val result = NoPersonalizedLearningRequest(inner).createInputConnection(info)
        assertEquals(1, inner.calls)
        assertSame(connection, result)
        // Fails if the flag were set before the inner call (the inner call overwrites imeOptions), or not set at all.
        assertEquals(written or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, info.imeOptions)
        assertEquals(EditorInfo.IME_ACTION_GO, info.imeOptions and EditorInfo.IME_MASK_ACTION)
        assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0)
    }

    @Test
    fun interceptorFlagSurvivesWhenInnerWritesNoOtherOptions() {
        val info = EditorInfo()
        NoPersonalizedLearningRequest(FakeInner(0)).createInputConnection(info)
        assertEquals(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, info.imeOptions)
    }
}
