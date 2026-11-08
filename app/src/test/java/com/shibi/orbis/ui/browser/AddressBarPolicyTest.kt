package com.shibi.orbis.ui.browser

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import com.shibi.orbis.engine.SearchEngine
import com.shibi.orbis.engine.SessionState
import com.shibi.orbis.engine.afterContentProcessGone
import com.shibi.orbis.engine.afterLoadError
import com.shibi.orbis.engine.afterNavigationCancelled
import com.shibi.orbis.engine.afterPageStart
import com.shibi.orbis.engine.afterPageStop
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

    // ---- the engine's counters: the transitions the reducer relies on ------------------------------------------------

    @Test
    fun engineTransitionsMaintainTheCounters() {
        val s = SessionState(url = "https://old.example/")
        val started = s.afterPageStart()
        assertEquals(1, started.loadStarts)
        assertEquals(0, started.loadEnds)
        assertTrue(started.isLoading)
        val stopped = started.afterPageStop(success = true)
        assertEquals(1, stopped.loadStarts)
        assertEquals(1, stopped.loadEnds)
        assertEquals("a page stop (including an aborted load) is not a load error", 0, stopped.loadErrors)
        assertFalse(stopped.isLoading)
        assertEquals(100, stopped.progress)
        val failed = stopped.afterPageStart().afterLoadError("https://x.example/")
        assertEquals(2, failed.loadStarts)
        assertEquals(2, failed.loadEnds)
        assertEquals(1, failed.loadErrors)
        assertTrue(failed.loadFailed)
        assertEquals("https://x.example/", failed.failedUrl)
        assertFalse(failed.isLoading)
        assertEquals("the failure leaves the displayed url alone", "https://old.example/", failed.url)
        val gone = failed.afterContentProcessGone()
        assertEquals(3, gone.loadEnds)
        assertEquals("a crash is not a load error", 1, gone.loadErrors)
        assertEquals(1, gone.navigationsCancelled)
        assertTrue(gone.crashed)
        assertEquals(2, gone.afterNavigationCancelled().navigationsCancelled)
        assertEquals("only the cancel counter moves", gone.loadEnds, gone.afterNavigationCancelled().loadEnds)
        // A new page start resets the flags but never the counters.
        val again = gone.afterPageStart()
        assertFalse(again.crashed)
        assertFalse(again.loadFailed)
        assertEquals(3, again.loadStarts)
        assertEquals(3, again.loadEnds)
        assertEquals(1, again.loadErrors)
        assertEquals(1, again.navigationsCancelled)
    }

    // ---- N1: a submitted address must not outlive a load that never commits ------------------------------------------

    private val target = "https://new.example/"
    private val idle = SessionState(url = "https://old.example/")
    private fun loading(base: SessionState = idle) = base.afterPageStart()

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
        val failed = loading().afterLoadError(target)
        // Seen as one collapsed emission, and through start then error, the realistic order.
        assertEquals(listOf<PendingSubmit?>(null), observe(submit(), failed))
        assertNull(observe(submit(), loading(), failed).last())
    }

    @Test
    fun loadErrorThatArrivesWithoutAStartClearsWhenNothingWasLoading() {
        assertNull(pendingSubmitAfterSession(submit(), idle.afterLoadError(target)))
    }

    @Test
    fun stoppedLoadClearsThePendingText() {
        // Stop: session.stop(); Gecko ends the load, url untouched. Seen as loading, then not loading.
        val r = observe(submit(), loading(), loading().afterPageStop(success = false))
        assertNotNull(r[0])
        assertNull(r[1])
    }

    @Test
    fun finishedLoadWithoutCommitClearsThePendingText() {
        // Same shape as a Stop or any end of load that never changed the url (blocked, download without an offer, ...).
        val running = loading()
        assertNull(observe(submit(), running, running.copy(progress = 80), running.afterPageStop(success = true)).last())
    }

    @Test
    fun loadStillRunningKeepsThePendingText() {
        val running = loading()
        val r = observe(submit(), running, running.copy(progress = 50), running.copy(progress = 90))
        assertTrue(r.all { it != null })
        // Not started yet is not ended either.
        assertNotNull(pendingSubmitAfterSession(submit(), idle))
    }

    @Test
    fun crashedContentProcessClearsThePendingText() {
        val crashed = idle.afterContentProcessGone()
        assertNull(pendingSubmitAfterSession(submit(), crashed))
        assertNull(observe(submit(), loading(), loading().afterContentProcessGone()).last())
        // Even when a load was running at submit and no new start is ever seen (the request died with the process).
        assertNull(pendingSubmitAfterSession(submit(loading()), loading().afterContentProcessGone()))
    }

    @Test
    fun cancelledNavigationClearsThePendingText() {
        // A beforeunload prompt ended without leaving: no load event follows, only the cancel counter.
        assertNull(pendingSubmitAfterSession(submit(), idle.afterNavigationCancelled()))
        assertNull(pendingSubmitAfterSession(submit(loading()), loading().afterNavigationCancelled()))
    }

    @Test
    fun cancelCountedBeforeTheSubmitDoesNotClearIt() {
        val earlier = idle.afterNavigationCancelled()
        val p = submit(earlier)
        assertEquals(p, pendingSubmitAfterSession(p, earlier))
    }

    @Test
    fun flagsLeftFromBeforeGoDoNotMatter() {
        // Old failure and old crash flags are still set right after Go (the engine resets them only at onPageStart).
        val failedBefore = loading().afterLoadError("https://broken.example/")
        val p = submit(failedBefore)
        assertTrue(failedBefore.loadFailed)
        assertEquals(p, pendingSubmitAfterSession(p, failedBefore))
        val crashedBefore = idle.afterContentProcessGone()
        val q = submit(crashedBefore)
        assertEquals(q, pendingSubmitAfterSession(q, crashedBefore))
    }

    @Test
    fun oldLoadRunningAtSubmitMustEndBeforeTheNewLoadCounts() {
        val old = loading()
        val p = submit(old)
        // The interrupted old load ends first (onPageStop), the new one then starts and ends: only the last clears.
        val oldEnded = old.afterPageStop(success = false)
        val newRunning = oldEnded.afterPageStart()
        val r = observe(p, old, oldEnded, newRunning, newRunning.afterPageStop(success = false))
        assertNotNull(r[0])
        assertNotNull("the old load ending is not the new load ending", r[1])
        assertNotNull(r[2])
        assertNull(r[3])
    }

    // DR5-02: Go on the same url while its old failure is on screen. Start and error both land before the collector runs,
    // so the observed state equals the submit snapshot in every field but the counters.
    @Test
    fun sameUrlRetryWhoseStartAndErrorAreConflatedClears() {
        val failedOnScreen = loading().afterLoadError(target)
        val p = submit(failedOnScreen)
        val collapsed = failedOnScreen.afterPageStart().afterLoadError(target)
        assertEquals(
            "sanity: nothing but the counters differs, so a flag/isLoading reducer sees no change",
            failedOnScreen.copy(loadStarts = 0, loadEnds = 0, loadErrors = 0),
            collapsed.copy(loadStarts = 0, loadEnds = 0, loadErrors = 0),
        )
        assertNull(pendingSubmitAfterSession(p, collapsed))
    }

    // DR5-04: Go during an old load; the old load's stop and the new load's start land in one emission, so isLoading
    // never shows false. After the new load ends without a commit the text must still clear.
    @Test
    fun oldStopAndNewStartConflatedThenNewEndClears() {
        val old = loading()
        val p = submit(old)
        val collapsedStart = old.afterPageStop(success = false).afterPageStart()
        val r = observe(p, collapsedStart, collapsedStart.afterPageStop(success = false))
        assertNotNull("still loading: the new page is on its way", r[0])
        assertNull(r[1])
    }

    @Test
    fun oldStopNeverReportedThenNewEndClears() {
        val old = loading()
        val r = observe(submit(old), old.afterPageStart(), old.afterPageStart().afterPageStop(success = false))
        assertNotNull(r[0])
        assertNull(r[1])
    }

    // DR6-01: Go during an old load; the old load stops (ignored), then the new request fails with no onPageStart at all.
    // Nothing but loadErrors tells this apart from the old load's stop, so the target must clear on the error.
    @Test
    fun loadErrorWithoutAStartClearsEvenWhenAnOldLoadWasRunningAtSubmit() {
        val old = loading()
        val p = submit(old)
        val oldStopped = old.afterPageStop(success = false)
        val r = observe(p, oldStopped, oldStopped.afterLoadError(target))
        assertNotNull("the old load's stop alone is not the new request's end", r[0])
        assertNull(r[1])
        // The same seen as one emission (stop and error conflated).
        assertNull(pendingSubmitAfterSession(p, oldStopped.afterLoadError(target)))
        // And with the old load's stop never reported at all.
        assertNull(pendingSubmitAfterSession(p, old.afterLoadError(target)))
    }

    @Test
    fun abortedOldLoadAloneNeverClearsAGoDuringLoading() {
        // A page stop with success=false is how an interrupted old load ends; it must not look like a load error.
        val old = loading()
        val p = submit(old)
        assertEquals(p, pendingSubmitAfterSession(p, old.afterPageStop(success = false)))
    }

    @Test
    fun loadErrorCountedBeforeGoDoesNotClearTheSubmitWhileLoading() {
        val failedBefore = loading().afterLoadError("https://broken.example/")
        val p = submit(failedBefore.afterPageStart())
        assertEquals(p, pendingSubmitAfterSession(p, failedBefore.afterPageStart()))
    }

    @Test
    fun oldLoadsLateEndAfterTheNewStartClearsEarly() {
        // Documented, accepted: the bar goes back to the committed address, which is always true.
        val old = loading()
        val newRunning = old.afterPageStart()
        assertNull(pendingSubmitAfterSession(submit(old), newRunning.afterPageStop(success = false)))
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
        assertNull(pendingSubmitAfterSession(null, idle.afterLoadError(target)))
    }

    @Test
    fun clearedPendingFallsBackToTheCommittedAddressOfTheTabItself() {
        // After any clear, the unfocused bar names the tab's committed (old) page, never the stale collected state.
        val running = loading()
        val cleared = observe(submit(), running, running.afterPageStop(success = false)).last()
        assertNull(cleared)
        assertEquals(
            "https://old.example/",
            addressBarText(false, "", collectedUrl = privateUrl, selectedTabUrl = "https://old.example/", pendingSubmit = cleared?.text),
        )
    }

    // ---- the bar's event bodies --------------------------------------------------------------------------------------

    @Test
    fun goTakesTheSnapshotBeforeTheRequestAndHandsFocusOnInOrder() {
        val pending = PendingSubmitState()
        val log = mutableListOf<String>()
        val ok =
            submitFromAddressBar(
                typed = "  https://new.example/ ",
                searchEngine = SearchEngine.DEFAULT,
                sessionState = loading(),
                pending = pending,
                loadUrl = { log += "load:$it"; log += "pendingAtLoad:${pending.text}" },
                hideKeyboard = { log += "hide" },
                moveFocusToSink = { log += "sink" },
            )
        assertTrue(ok)
        assertEquals(listOf("load:https://new.example/", "pendingAtLoad:https://new.example/", "hide", "sink"), log)
        assertEquals("https://new.example/", pending.text)
    }

    @Test
    fun goWithBlankInputDoesNothing() {
        val pending = PendingSubmitState()
        var touched = false
        val ok =
            submitFromAddressBar(
                "   ", SearchEngine.DEFAULT, idle, pending,
                loadUrl = { touched = true }, hideKeyboard = { touched = true }, moveFocusToSink = { touched = true },
            )
        assertFalse(ok)
        assertFalse(touched)
        assertNull(pending.text)
    }

    @Test
    fun stopStopsAndForgetsThePendingText() {
        val pending = PendingSubmitState().also { it.submit(target, idle) }
        var stopped = false
        stopFromAddressBar({ stopped = true }, pending)
        assertTrue(stopped)
        assertNull(pending.text)
    }

    @Test
    fun editingForgetsThePendingText() {
        val pending = PendingSubmitState().also { it.submit(target, idle) }
        onAddressBarEdited(pending)
        assertNull(pending.text)
    }

    @Test
    fun focusGainTakesThePendingTextLiveFromTheHolder() {
        val pending = PendingSubmitState()
        // No pending: the session address. Then Go happens (no recomposition in a pure test) and the very next focus event sees it.
        assertEquals("https://old.example/", addressBarBufferOnFocusChange(true, false, pending, "https://old.example/", "https://old.example/"))
        pending.submit(target, idle)
        assertEquals(target, addressBarBufferOnFocusChange(true, false, pending, "https://old.example/", "https://old.example/"))
        // Not a gain: the buffer is left alone.
        assertNull(addressBarBufferOnFocusChange(true, true, pending, "", ""))
        assertNull(addressBarBufferOnFocusChange(false, true, pending, "", ""))
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
