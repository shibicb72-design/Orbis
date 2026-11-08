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
import com.shibi.orbis.engine.SessionState
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

/** Where a [PendingSubmit] is in watching the load it started. See [pendingSubmitAfterSession]. */
internal enum class SubmitLoadWatch {
    /** The session was already loading at submit: wait until that old load is seen to end. */
    WAIT_OLD_LOAD_END,

    /** Nothing seen yet: wait for the submitted load to start. */
    WAIT_START,

    /** The submitted load was seen running: its end without a commit clears the pending text. */
    WAIT_END,
}

/**
 * What Go submitted, plus the submit-time snapshot of the session the clearing rules compare against. Immutable; every
 * step is a pure function of the previous value and one observation, so it is unit-testable without a session.
 *
 * The engine writes the session url only when a page COMMITS (GeckoEngineImpl.onLocationChange), and the commit is
 * handled elsewhere (the tab url changes). A load that never commits (error, Stop, crash, download, ...) leaves the OLD
 * page displayed, so without the rules below the bar would keep naming a target above the old page. Clearing early is the
 * safe direction: the bar then shows the tab's committed address.
 */
internal data class PendingSubmit(
    val text: String,
    /** The session's failedUrl at submit: a failure for a different one is a new failure even if the flag was set before. */
    val failedUrlAtSubmit: String?,
    /** False while loadFailed was already true at submit and has not been seen false since (the old failure is not ours). */
    val loadFailedArmed: Boolean,
    /** Same for crashed. */
    val crashedArmed: Boolean,
    val watch: SubmitLoadWatch,
)

/** Starts tracking a submit. [session] is the SELECTED tab's own session state read at submit time, before the load request. */
internal fun pendingSubmitStart(target: String, session: SessionState): PendingSubmit =
    PendingSubmit(
        text = pendingSubmitFor(target),
        failedUrlAtSubmit = session.failedUrl,
        loadFailedArmed = !session.loadFailed,
        crashedArmed = !session.crashed,
        watch = if (session.isLoading) SubmitLoadWatch.WAIT_OLD_LOAD_END else SubmitLoadWatch.WAIT_START,
    )

/**
 * The pending text after one observation of the SELECTED tab's own session state; null means cleared. Clears when, after
 * the submit, the load failed, the content process crashed, or the load was seen running and then stopped running. The
 * url is deliberately not read here: a commit is the tab-url rule (the caller clears when the tab's url changes).
 *
 * A failure or crash flag that was already set at submit is stale (the engine resets it only when the new page starts);
 * it only counts once seen cleared again, or, for a failure, when failedUrl differs from the one at submit. An old load
 * that was running at submit must first be seen to end before the new load's start and end count. Unreliable corners
 * (flags conflated away by the state flow) fall on the side of not clearing; they are reported, not guessed at.
 */
internal fun pendingSubmitAfterSession(pending: PendingSubmit?, session: SessionState): PendingSubmit? {
    if (pending == null) return null
    if (session.crashed && pending.crashedArmed) return null
    if (session.loadFailed && (pending.loadFailedArmed || session.failedUrl != pending.failedUrlAtSubmit)) return null
    val watch =
        when (pending.watch) {
            SubmitLoadWatch.WAIT_OLD_LOAD_END ->
                if (session.isLoading) SubmitLoadWatch.WAIT_OLD_LOAD_END else SubmitLoadWatch.WAIT_START

            SubmitLoadWatch.WAIT_START ->
                if (session.isLoading) SubmitLoadWatch.WAIT_END else SubmitLoadWatch.WAIT_START

            SubmitLoadWatch.WAIT_END ->
                if (session.isLoading) SubmitLoadWatch.WAIT_END else return null
        }
    return pending.copy(
        loadFailedArmed = pending.loadFailedArmed || !session.loadFailed,
        crashedArmed = pending.crashedArmed || !session.crashed,
        watch = watch,
    )
}

/**
 * The pending text after the download offer [sourceTabId] (TabManager.pendingDownload) appeared: a download navigation
 * does not commit a page, so it ends this tab's pending submit. An offer for another tab leaves it alone.
 */
internal fun pendingSubmitAfterDownload(pending: PendingSubmit?, sourceTabId: Long, thisTabId: Long): PendingSubmit? =
    if (sourceTabId == thisTabId) null else pending

/**
 * Compose holder of one tab's [PendingSubmit]. Plain memory only (never saved state), one per tab key. [text] is what
 * the address bar functions take as their `pendingSubmit` string.
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
 * the key: that one still holds the previously selected tab's value for a frame after a switch. [downloadSourceTabId] is
 * the tab a pending download offer came from, if any. The Stop tap and edits are the caller's [PendingSubmitState.clear].
 */
@Composable
internal fun rememberPendingSubmit(
    tabId: Long,
    tabUrl: String,
    sessionState: Flow<SessionState>?,
    downloadSourceTabId: Long?,
): PendingSubmitState {
    val holder = remember { PendingSubmitState() }
    LaunchedEffect(tabUrl) { holder.clear() }
    LaunchedEffect(sessionState) { sessionState?.collect { holder.onSession(it) } }
    LaunchedEffect(downloadSourceTabId) { downloadSourceTabId?.let { holder.onDownloadOffered(it, tabId) } }
    return holder
}

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
