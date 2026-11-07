package com.shibi.orbis.ui.browser

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

internal fun isBlankAddress(url: String) = url.isEmpty() || url == "about:blank"

/** What the bar shows for a committed address: blank tabs show nothing. */
internal fun addressBarDisplayUrl(committedUrl: String): String = if (isBlankAddress(committedUrl)) "" else committedUrl

/**
 * The bar's text. Focused it is the edit buffer; unfocused it is only the committed address of the SELECTED tab itself
 * (pass that tab's own url, never a value collected from the previously selected tab's session).
 */
internal fun addressBarText(focused: Boolean, typed: String, committedUrl: String): String =
    if (focused) typed else addressBarDisplayUrl(committedUrl)

/** The edit buffer when the bar gains focus: the committed address of the selected tab, or empty for a blank one. */
internal fun addressBarTextOnFocus(committedUrl: String): String = addressBarDisplayUrl(committedUrl)

/** Whether the edit buffer may go into saved instance state. A private tab's text stays in plain memory only. */
internal fun addressBarTextIsSaveable(isPrivate: Boolean): Boolean = !isPrivate

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
 * composes [content] untouched.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AddressBarImeScope(isPrivate: Boolean, content: @Composable () -> Unit) {
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

private class NoPersonalizedLearningRequest(private val inner: PlatformTextInputMethodRequest) : PlatformTextInputMethodRequest {
    override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
        val connection = inner.createInputConnection(outAttributes)
        // After the inner request: it fills in imeOptions, so the flag has to be added on top of its result.
        outAttributes.imeOptions = addressBarImeOptions(outAttributes.imeOptions, isPrivate = true)
        return connection
    }
}
