package com.shibi.orbis.ui.browser

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.res.stringResource
import com.shibi.orbis.R
import com.shibi.orbis.engine.BrowserSession
import com.shibi.orbis.engine.SitePolicy
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withTimeoutOrNull

/** How long the "showing desktop site" notice stays. */
internal const val AUTO_DESKTOP_NOTICE_MILLIS = 8_000L

/**
 * Tells the user, once, that Desktop site was turned on automatically for the page of [tabId], with an Undo. [marked]
 * is the pending mark for that tab; [onShown] clears it as the notice appears, so it never shows twice. A mark set while
 * the tab is not shown waits until this is composed for that tab. Switching tab ends the notice.
 */
@Composable
internal fun AutoDesktopNotice(
    tabId: Long,
    marked: Boolean,
    snackbars: SnackbarHostState,
    onShown: () -> Unit,
    onUndo: () -> Unit,
) {
    val message = stringResource(R.string.auto_desktop_notice)
    val undoLabel = stringResource(R.string.auto_desktop_undo)
    val isMarked by rememberUpdatedState(marked)
    val shown by rememberUpdatedState(onShown)
    val undo by rememberUpdatedState(onUndo)
    LaunchedEffect(tabId, snackbars) {
        snapshotFlow { isMarked }.filter { it }.collectLatest {
            shown()
            snackbars.currentSnackbarData?.dismiss()
            val result =
                withTimeoutOrNull(AUTO_DESKTOP_NOTICE_MILLIS) {
                    snackbars.showSnackbar(message, actionLabel = undoLabel, duration = SnackbarDuration.Indefinite)
                }
            if (result == SnackbarResult.ActionPerformed) undo()
        }
    }
}

/** Undo of an automatic Desktop site: the site is never retried, and this page goes back to the mobile site. */
internal fun undoAutoDesktop(settings: SitePolicy, session: BrowserSession, url: String) {
    settings.declineAutoDesktop(url, session.isPrivate)
    session.setDesktopMode(false)
}

/** The sheet's note under the Desktop site switch: only for a site Orbis switched on by itself, while it is on. */
internal fun showsAutoDesktopNote(isAutoSite: Boolean, desktopOn: Boolean): Boolean = isAutoSite && desktopOn
