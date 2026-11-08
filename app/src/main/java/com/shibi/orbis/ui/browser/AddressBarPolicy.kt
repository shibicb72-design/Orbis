package com.shibi.orbis.ui.browser

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

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
 * not committed yet) or the user edits again. Callers keep it in plain memory ([remember]), never saved state, and
 * inside the per-tab key, so a private tab's text never leaves its own tab or reaches instance state.
 */
internal fun pendingSubmitFor(target: String): String = addressBarDisplayUrl(target)

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
