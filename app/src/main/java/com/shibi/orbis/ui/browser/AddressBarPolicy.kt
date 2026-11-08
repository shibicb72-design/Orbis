package com.shibi.orbis.ui.browser

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import com.shibi.orbis.engine.SearchEngine
import com.shibi.orbis.engine.SessionState
import com.shibi.orbis.engine.normalizeAddressInput
import com.shibi.orbis.tabs.PendingDownload
import kotlinx.coroutines.flow.Flow

internal fun isBlankAddress(url: String) = url.isEmpty() || url == "about:blank"

/** What the bar shows for a committed address: blank tabs show nothing. */
internal fun addressBarDisplayUrl(committedUrl: String): String = if (isBlankAddress(committedUrl)) "" else committedUrl

/**
 * The bar's text. Focused it is the edit buffer; unfocused it is only the committed address of the SELECTED tab itself
 * ([selectedTabUrl]). [collectedUrl] is the url of the session state collected by the screen: for a frame after a tab
 * switch it still belongs to the previously selected tab (possibly a private one), so it is deliberately never rendered.
 */
@Suppress("UNUSED_PARAMETER")
internal fun addressBarText(
    focused: Boolean,
    typed: String,
    collectedUrl: String,
    selectedTabUrl: String,
    pendingSubmit: String? = null,
): String = if (focused) typed else pendingSubmit ?: addressBarDisplayUrl(selectedTabUrl)

/**
 * The address the user just submitted with Go, in display form, until the tab's own address changes (the new page has
 * not committed yet), the user edits again, or the load demonstrably ended without committing (see [PendingSubmit]).
 * Callers keep it in plain memory ([remember]), never saved state, and inside the per-tab key, so a private tab's text
 * never leaves its own tab or reaches instance state.
 */
internal fun pendingSubmitFor(target: String): String = addressBarDisplayUrl(target)

/**
 * What Go submitted, plus the submit-time snapshot of the session counters the clearing rules compare against. Immutable;
 * every step is a pure function of the previous value and one observation, so it is unit-testable without a session.
 *
 * The engine writes the session url only when a page COMMITS (GeckoEngineImpl.onLocationChange), and the commit is
 * handled elsewhere (the tab url changes). A load that never commits (Stop, crash, download, cancel, ...) leaves the OLD
 * page displayed, so a pending text that is not cleared names a page that is not there: that is the defect (N1). Clearing
 * early is the safe direction: the bar then shows the tab's committed address, which is always true. The one exception
 * is a load ERROR: the failure banner is shown and the bar keeps the submitted address (as in Chrome and Firefox), so
 * the user can fix a typo or retry it.
 */
internal data class PendingSubmit(
    val text: String,
    /** Session counters at submit (see SessionState): everything counted after these happened after the user pressed Go. */
    val loadStartsAtSubmit: Int,
    val loadEndsAtSubmit: Int,
    val loadErrorsAtSubmit: Int,
    val navigationsCancelledAtSubmit: Int,
    /** An older load was running at submit: its end may arrive before the new load starts and must not clear the text. */
    val loadingAtSubmit: Boolean,
    /**
     * The submitted load failed (onLoadError). Like Chrome and Firefox, the bar then keeps what the user submitted, so it
     * can be corrected or retried, and the counters above are re-snapshotted at the error. A plain page stop without a
     * start after that is the failed load's own trailing stop and is ignored.
     */
    val failed: Boolean = false,
)

/** [pending] after its load failed: the text is kept and the counters are taken from [session], the state at the error. */
internal fun pendingSubmitFailed(pending: PendingSubmit, session: SessionState): PendingSubmit =
    pending.copy(
        loadStartsAtSubmit = session.loadStarts,
        loadEndsAtSubmit = session.loadEnds,
        loadErrorsAtSubmit = session.loadErrors,
        navigationsCancelledAtSubmit = session.navigationsCancelled,
        loadingAtSubmit = false,
        failed = true,
    )

/** Starts tracking a submit. [session] is the SELECTED tab's own session state read at submit time, before the load request. */
internal fun pendingSubmitStart(target: String, session: SessionState): PendingSubmit =
    PendingSubmit(
        text = pendingSubmitFor(target),
        loadStartsAtSubmit = session.loadStarts,
        loadEndsAtSubmit = session.loadEnds,
        loadErrorsAtSubmit = session.loadErrors,
        navigationsCancelledAtSubmit = session.navigationsCancelled,
        loadingAtSubmit = session.isLoading,
    )

/**
 * The pending text after one observation of the SELECTED tab's own session state; null means cleared. The url is
 * deliberately not read here: a commit is the tab-url rule (the caller clears when the tab's url changes).
 *
 * Only the counters and isLoading are read, never the failure/crash flags: a flag can still be the old page's, and a
 * state flow conflates, so only monotonic counters tell "something happened since Go" reliably.
 *
 * A load error since Go (onLoadError, with or without an onPageStart, conflated or not) KEEPS the text, the way Chrome
 * and Firefox keep a failed address in the bar: see [pendingSubmitFailed]. It is checked before the start rule, so a
 * start and error collapsed into one emission still count as a failure. An aborted or stopped old load reports a page
 * stop, never a load error, so an error is always about a navigation the user asked for.
 *
 * Clears when
 *  - a navigation was cancelled (beforeunload refused, content process gone);
 *  - a load started after Go (or after the error) and the session is not loading now without a new error: isLoading is
 *    written by the very last start/end event, so an end came after that start. A start still loading keeps it;
 *  - no load started after Go but one ended, none was running at submit, and the submit has not failed (a stop or crash
 *    without a start).
 * When a load WAS running at submit, plain ends before the new start are that old load's and are ignored, so the bar does
 * not flip back to the old address while the new page is still on its way. Any ambiguity left (an old load's late end
 * arriving after the new start, or the old load itself genuinely failing after Go) clears early, which is the safe side.
 */
internal fun pendingSubmitAfterSession(pending: PendingSubmit?, session: SessionState): PendingSubmit? {
    if (pending == null) return null
    // != rather than >, defensively: a session whose counters started over would differ from the snapshot too, and that is
    // a reason to clear. It cannot happen with the current TabManager (a tab's session object is not replaced in place).
    if (session.navigationsCancelled != pending.navigationsCancelledAtSubmit) return null
    if (session.loadErrors != pending.loadErrorsAtSubmit) return pendingSubmitFailed(pending, session)
    if (session.loadStarts != pending.loadStartsAtSubmit) return if (session.isLoading) pending else null
    if (!pending.loadingAtSubmit && !pending.failed && session.loadEnds != pending.loadEndsAtSubmit) return null
    return pending
}

/**
 * The pending text after the download offer [sourceTabId] (TabManager.pendingDownload) appeared: a download navigation
 * does not commit a page, so it ends this tab's pending submit. An offer for another tab leaves it alone.
 */
internal fun pendingSubmitAfterDownload(pending: PendingSubmit?, sourceTabId: Long, thisTabId: Long): PendingSubmit? =
    if (sourceTabId == thisTabId) null else pending

/**
 * Compose holder of one tab's [PendingSubmit]. Plain memory only (never saved state), one per tab key. [text] is what
 * the address bar functions take as their `pendingSubmit` string. Event handlers must read it through this holder at the
 * moment they run, never from a copy taken during composition: Go and the focus change it causes can both happen before
 * the next recomposition.
 */
internal class PendingSubmitState {
    private var value by mutableStateOf<PendingSubmit?>(null)
    val text: String? get() = value?.text

    /** Go: [session] is the selected tab's own state, read before the load request. */
    fun submit(target: String, session: SessionState) {
        value = pendingSubmitStart(target, session)
    }

    /** The user edited the field, tapped Stop, or the tab's address changed (a commit). */
    fun clear() {
        value = null
    }

    fun onSession(session: SessionState) {
        value = pendingSubmitAfterSession(value, session)
    }

    fun onDownloadOffered(sourceTabId: Long, thisTabId: Long) {
        value = pendingSubmitAfterDownload(value, sourceTabId, thisTabId)
    }
}

/**
 * One tab's pending-submit state with all its clearing rules wired. Call inside the per-tab key.
 * [sessionState] must be the selected tab's OWN session flow (`selectedTab.session?.state`), not a flow collected outside
 * the key: that one still holds the previously selected tab's value for a frame after a switch. [downloadOffer] is the
 * pending download offer (TabManager.pendingDownload), if any; the effect is keyed on the offer itself, so a second offer
 * for the same tab re-runs it. The Stop tap and edits are the caller's [PendingSubmitState.clear].
 */
@Composable
internal fun rememberPendingSubmit(
    tabId: Long,
    tabUrl: String,
    sessionState: Flow<SessionState>?,
    downloadOffer: PendingDownload?,
): PendingSubmitState {
    val holder = remember { PendingSubmitState() }
    LaunchedEffect(tabUrl) { holder.clear() }
    LaunchedEffect(sessionState) { sessionState?.collect { holder.onSession(it) } }
    LaunchedEffect(downloadOffer) { downloadOffer?.let { holder.onDownloadOffered(it.sourceTabId, tabId) } }
    return holder
}

// ---- the bar's event bodies, as BrowserScreen runs them (PendingSubmitComposeTest calls the same functions) ----------------

/**
 * Go. Normalizes [typed]; for blank input does nothing and returns false. Otherwise, in this order: the submit snapshot
 * is taken from [sessionState] (the selected tab's own state, BEFORE the request, so nothing the load does can be
 * mistaken for the submit-time state), the page is requested, the keyboard hides and focus moves to the sink. A null
 * [sessionState] (no session yet) requests nothing and records no pending text.
 */
internal fun submitFromAddressBar(
    typed: String,
    searchEngine: SearchEngine,
    sessionState: SessionState?,
    pending: PendingSubmitState,
    loadUrl: (String) -> Unit,
    hideKeyboard: () -> Unit,
    moveFocusToSink: () -> Unit,
): Boolean {
    val target = normalizeAddressInput(typed, searchEngine) ?: return false
    if (sessionState != null) pending.submit(target, sessionState)
    loadUrl(target)
    hideKeyboard()
    moveFocusToSink()
    return true
}

/** The Stop button: stops the load and forgets the pending text. */
internal fun stopFromAddressBar(stop: () -> Unit, pending: PendingSubmitState) {
    stop()
    pending.clear()
}

/** The user edited the field: what they type replaces whatever Go submitted. */
internal fun onAddressBarEdited(pending: PendingSubmitState) {
    pending.clear()
}

/**
 * The bar's focus change. Returns the new edit buffer when focus was just gained, null when the buffer stays as it is.
 * [pending] is read here, at the moment of the event, not from a value copied during composition: a hardware Enter
 * submits and the view system hands focus back before the next recomposition.
 */
internal fun addressBarBufferOnFocusChange(
    hasFocus: Boolean,
    wasFocused: Boolean,
    pending: PendingSubmitState,
    sessionUrl: String,
    selectedTabUrl: String,
): String? = if (hasFocus && !wasFocused) addressBarTextOnFocus(sessionUrl, selectedTabUrl, pending.text) else null

/**
 * The edit buffer when the bar gains focus: the address the user just submitted if its page has not committed yet
 * ([pendingSubmit]; without it a focus that comes back right after Go, e.g. view-system refocus after a hardware Enter,
 * would reset the buffer to the still-empty or old session address), else the live session's address, else the tab's own
 * (the same fallback as TabManager.onSessionState), or empty for a blank one.
 */
internal fun addressBarTextOnFocus(sessionUrl: String, selectedTabUrl: String, pendingSubmit: String? = null): String =
    pendingSubmit ?: addressBarDisplayUrl(sessionUrl.ifEmpty { selectedTabUrl })

/** Whether the edit buffer may go into saved instance state. A private tab's text stays in plain memory only. */
internal fun addressBarTextIsSaveable(isPrivate: Boolean): Boolean = !isPrivate

/**
 * The address bar's edit buffer. Saved instance state is written outside the app's control, so a private tab's typed text
 * stays in plain memory ([remember]); a normal tab's survives recreation ([rememberSaveable]). The mode of a tab id never
 * changes, so the branch is stable for as long as the caller keys this by tab.
 */
@Composable
internal fun rememberAddressBarTypedState(isPrivate: Boolean): MutableState<String> =
    if (addressBarTextIsSaveable(isPrivate)) {
        rememberSaveable { mutableStateOf("") }
    } else {
        remember { mutableStateOf("") }
    }

/**
 * The IME options for the address field's EditorInfo: for a private tab, the "no personalized learning" request flag is
 * added to whatever Compose already set. It is only a request; the keyboard app decides whether to honour it.
 */
internal fun addressBarImeOptions(current: Int, isPrivate: Boolean): Int =
    if (isPrivate) current or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING else current

/**
 * Compose's KeyboardOptions/PlatformImeOptions (ui-text 1.11.4) only expose privateImeOptions, a keyboard-specific string,
 * and have no way to set EditorInfo.imeOptions flags. The supported hook for that is intercepting the input session, so
 * for a private tab every input connection created inside [content] gets [addressBarImeOptions] applied. A normal tab
 * composes [content] untouched. Wrap every app-owned text field that a private tab can show (address bar, find bar, the
 * page's prompt dialogs).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PrivateImeScope(isPrivate: Boolean, content: @Composable () -> Unit) {
    if (!isPrivate) {
        content()
        return
    }
    InterceptPlatformTextInput(
        interceptor = PlatformTextInputInterceptor { request, nextHandler ->
            nextHandler.startInputMethod(NoPersonalizedLearningRequest(request))
        },
        content = content,
    )
}

internal class NoPersonalizedLearningRequest(private val inner: PlatformTextInputMethodRequest) : PlatformTextInputMethodRequest {
    override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
        val connection = inner.createInputConnection(outAttributes)
        // After the inner request: it fills in imeOptions, so the flag has to be added on top of its result.
        outAttributes.imeOptions = addressBarImeOptions(outAttributes.imeOptions, isPrivate = true)
        return connection
    }
}
