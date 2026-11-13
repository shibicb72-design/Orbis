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
import com.shibi.orbis.engine.hostOfUrl
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    currentUrl: String,
    snackbars: SnackbarHostState,
    onShown: () -> Unit,
    onUndo: (noticeUrl: String) -> Unit,
) {
    val message = stringResource(R.string.auto_desktop_notice)
    val undoLabel = stringResource(R.string.auto_desktop_undo)
    val isMarked by rememberUpdatedState(marked)
    val url by rememberUpdatedState(currentUrl)
    val shown by rememberUpdatedState(onShown)
    val undo by rememberUpdatedState(onUndo)
    LaunchedEffect(tabId, snackbars) {
        val effect = this
        snapshotFlow { isMarked }.collectLatest { on ->
            if (!on) return@collectLatest
            // Another snackbar is never dismissed: wait for the host to be free. The mark stays set until then, and
            // if this wait is cancelled (tab change, fullscreen) the notice shows later.
            snapshotFlow { snackbars.currentSnackbarData }.first { it == null }
            shown()
            val noticeUrl = url
            // Not a child of this collector: clearing the mark must not cancel the notice it just started.
            effect.launch {
                val result =
                    withTimeoutOrNull(AUTO_DESKTOP_NOTICE_MILLIS) {
                        snackbars.showSnackbar(message, actionLabel = undoLabel, duration = SnackbarDuration.Indefinite)
                    }
                if (result == SnackbarResult.ActionPerformed) undo(noticeUrl)
            }
        }
    }
}

/**
 * Undo of an automatic Desktop site: the site the notice was shown for is never retried. The page goes back to the
 * mobile site only while it is still on that site ([currentUrl]); on another site it is left alone.
 */
internal fun undoAutoDesktop(settings: SitePolicy, session: BrowserSession, noticeUrl: String, currentUrl: String) {
    settings.declineAutoDesktop(noticeUrl, session.isPrivate)
    val noticeHost = hostOfUrl(noticeUrl)?.lowercase()
    if (noticeHost != null && noticeHost == hostOfUrl(currentUrl)?.lowercase()) session.setDesktopMode(false)
}

/** The sheet's note under the Desktop site switch: only for a site Orbis switched on by itself, while it is on. */
internal fun showsAutoDesktopNote(isAutoSite: Boolean, desktopOn: Boolean): Boolean = isAutoSite && desktopOn
