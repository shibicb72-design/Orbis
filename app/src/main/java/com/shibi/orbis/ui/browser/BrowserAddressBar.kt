package com.shibi.orbis.ui.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shibi.orbis.R
import com.shibi.orbis.engine.SearchEngine
import com.shibi.orbis.engine.SessionState
import com.shibi.orbis.tabs.PendingDownload
import kotlinx.coroutines.flow.StateFlow

internal const val ADDRESS_FIELD_TAG = "address_field"
internal const val ADDRESS_RESTING_TAG = "address_resting"
internal const val ADDRESS_STOP_TAG = "address_stop"
internal const val ADDRESS_RELOAD_TAG = "address_reload"

/**
 * The production address bar: one per selected tab (keyed by [tabId] inside), so typed text, focus, edit mode and the
 * pending submit never leak from one tab into another. BrowserScreen and the tests mount this same composable.
 *
 * Two visual states in one footprint:
 *  - resting (unfocused): the policy-selected text ([addressBarText]) drawn start-aligned on one line with an explicit end
 *    ellipsis when it does not fit ([addressBarRestingText]); no floating label over it. While a submit is pending it is
 *    announced as loading ([addressBarShowsPendingTarget]).
 *  - editing (focused): the native single-line field with the full editable string, native selection and IME.
 * The field itself always holds the full text (semantics, edit), the resting line is a decoration with no semantics.
 *
 * [tabUrl] is the selected tab's own committed url and [sessionState] its own session flow (`selectedTab.session?.state`),
 * never a flow collected outside the key (it lags a frame behind a tab switch). [focusOnStart] is true only for a tab
 * the user just created with New tab; [onFocusOnStartConsumed] is called once it was used.
 */
@Composable
internal fun BrowserAddressBar(
    tabId: Long,
    isPrivate: Boolean,
    tabUrl: String,
    sessionState: StateFlow<SessionState>?,
    downloadOffer: PendingDownload?,
    isLoading: Boolean,
    canReload: Boolean,
    searchEngine: () -> SearchEngine,
    focusOnStart: Boolean,
    onFocusOnStartConsumed: () -> Unit,
    loadUrl: (String) -> Unit,
    stop: () -> Unit,
    reload: () -> Unit,
    hideKeyboard: () -> Unit,
    moveFocusToSink: () -> Unit,
    modifier: Modifier = Modifier,
    /** Bumped by the caller (the start page's search box) to put the bar into edit mode; only a change counts. */
    focusRequests: Int = 0,
) {
    key(tabId) {
        // Saved instance state is written outside the app's control; a private tab's typed text stays in plain memory.
        // The mode of a tab id never changes, so the branch is stable inside this key.
        val typedState = rememberAddressBarTypedState(isPrivate)
        var typed by typedState
        var addressFocused by remember { mutableStateOf(false) }
        // The user typed in this focus session (reset on every focus gain). Plain memory, inside the key.
        var editedSinceFocus by remember { mutableStateOf(false) }
        // What Go just submitted, until its page commits or the load is seen to end without one (see PendingSubmit).
        // Plain memory on purpose, inside this key. Event handlers read it through `pending` when they run (DR5-01).
        val pending = rememberPendingSubmit(tabId = tabId, tabUrl = tabUrl, sessionState = sessionState, downloadOffer = downloadOffer)
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            // A tab the user just created starts with the address bar focused (once).
            if (focusOnStart) {
                onFocusOnStartConsumed()
                runCatching { focusRequester.requestFocus() }
            }
        }
        var seenFocusRequests by remember { mutableIntStateOf(focusRequests) }
        LaunchedEffect(focusRequests) {
            if (focusRequests == seenFocusRequests) return@LaunchedEffect
            seenFocusRequests = focusRequests
            runCatching { focusRequester.requestFocus() }
        }
        // A page committed in this tab while the bar was focused but untouched (e.g. a link from another app landed in
        // the blank New tab): leave edit mode so the bar names the page actually shown. Typed text is never discarded.
        // Only a CHANGE of the tab's url counts: the first composition (a tab just shown, possibly focused on start) is not
        // a commit.
        var seenTabUrl by remember { mutableStateOf(tabUrl) }
        LaunchedEffect(tabUrl) {
            if (tabUrl == seenTabUrl) return@LaunchedEffect
            seenTabUrl = tabUrl
            if (addressBarLeavesEditOnCommit(addressFocused, editedSinceFocus, tabUrl)) {
                hideKeyboard()
                moveFocusToSink()
            }
        }
        val barText = addressBarText(addressFocused, typed, collectedUrl = "", selectedTabUrl = tabUrl, pendingSubmit = pending.text)
        val resting = addressBarRestingText(addressFocused, barText)
        val loadingDescription = stringResource(R.string.address_bar_loading)
        val showsPending = addressBarShowsPendingTarget(addressFocused, pending.text, pending.failed)
        val textColor = MaterialTheme.colorScheme.onSurface
        PrivateImeScope(isPrivate) {
            Box(modifier) {
                OutlinedTextField(
                    value = barText,
                    onValueChange = {
                        typed = it
                        editedSinceFocus = true
                        onAddressBarEdited(pending)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ADDRESS_FIELD_TAG)
                        .focusRequester(focusRequester)
                        .onFocusChanged { focus ->
                            // The tab's own session, read now: never the collected state, which for a frame after a tab
                            // switch still holds the previous tab's address (least of all a private one).
                            val url = sessionState?.value?.url.orEmpty()
                            addressBarBufferOnFocusChange(focus.hasFocus, addressFocused, pending, url, tabUrl)?.let { typed = it }
                            if (focus.hasFocus && !addressFocused) editedSinceFocus = false
                            addressFocused = focus.hasFocus
                        }
                        .semantics { if (showsPending) stateDescription = loadingDescription },
                    singleLine = true,
                    placeholder = {
                        Text(stringResource(R.string.address_bar_hint), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedTextColor = textColor,
                        // At rest the resting line draws the text; the field's own copy (scrolled wherever the caret
                        // left it) must not show through underneath.
                        unfocusedTextColor = if (resting != null) Color.Transparent else textColor,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    trailingIcon = {
                        if (isLoading) {
                            IconButton(onClick = { stopFromAddressBar(stop, pending) }, modifier = Modifier.testTag(ADDRESS_STOP_TAG)) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.stop))
                            }
                        } else {
                            IconButton(onClick = reload, enabled = canReload, modifier = Modifier.testTag(ADDRESS_RELOAD_TAG)) {
                                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.reload))
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            submitFromAddressBar(
                                typed = typed,
                                searchEngine = searchEngine(),
                                // Read before the request, from the tab's own session.
                                sessionState = sessionState?.value,
                                pending = pending,
                                loadUrl = loadUrl,
                                hideKeyboard = hideKeyboard,
                                moveFocusToSink = moveFocusToSink,
                            )
                        },
                    ),
                )
                if (resting != null) {
                    // Decoration only: no semantics (the field above carries the full text) and no pointer handling, so a
                    // tap goes to the field and enters edit mode. Start-aligned, left-to-right like every address, with an
                    // explicit end ellipsis. 16dp matches the field's inner start padding; the end leaves room for the
                    // trailing button.
                    Box(
                        Modifier
                            .matchParentSize()
                            .padding(start = 16.dp, end = 52.dp)
                            .clearAndSetSemantics { testTag = ADDRESS_RESTING_TAG },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            resting,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                            color = textColor,
                        )
                    }
                }
            }
        }
    }
}
