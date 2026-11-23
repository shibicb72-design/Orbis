package com.shibi.orbis.ui.browser

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.shibi.orbis.R
import com.shibi.orbis.engine.ProtectedVideoAnswer
import com.shibi.orbis.engine.ProtectedVideoRefusal
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How long the protected-video notice stays, unless its close button ends it first. */
internal const val PROTECTED_VIDEO_NOTICE_MILLIS = 10_000L

/** While these hold, the notice may be on screen: the selected tab, Orbis in front, the page not locked, no PiP. */
internal fun protectedVideoNoticeMayStay(
    tabSelected: Boolean,
    inFront: Boolean,
    pageHidden: Boolean,
    inPip: Boolean,
): Boolean = tabSelected && inFront && !pageHidden && !inPip

/**
 * The refusal to tell the user about now, or null for no notice. Only a refused DRM request (never an allowed one) of
 * the page load that is current ([currentLoad], the session's load count), once per page load, and only where
 * [protectedVideoNoticeMayStay].
 */
internal fun protectedVideoNoticeNow(
    refusal: ProtectedVideoRefusal,
    currentLoad: Int,
    tabSelected: Boolean,
    inFront: Boolean,
    pageHidden: Boolean,
    inPip: Boolean,
): ProtectedVideoAnswer? =
    refusal.answer?.takeIf {
        it != ProtectedVideoAnswer.ALLOW &&
            refusal.refusedLoad == currentLoad &&
            refusal.noticeShownLoad != currentLoad &&
            protectedVideoNoticeMayStay(tabSelected, inFront, pageHidden, inPip)
    }

/**
 * Tells the user that Orbis refused the page's protected video (DRM), and why. [refusal] and [currentLoad] are the page
 * state of tab [tabId]; [onShown] marks the load as told as the notice appears, so it never shows twice for one page
 * load. Another snackbar is not dismissed: the notice waits for the host to be free. It ends after
 * [PROTECTED_VIDEO_NOTICE_MILLIS], on its close button, or as soon as the tab, the page load, the lock, PiP or the
 * app's place in front changes.
 */
@Composable
internal fun ProtectedVideoNotice(
    tabId: Long,
    tabSelected: Boolean,
    refusal: ProtectedVideoRefusal,
    currentLoad: Int,
    pageHidden: Boolean,
    inPip: Boolean,
    snackbars: SnackbarHostState,
    onShown: (load: Int) -> Unit,
    inFront: Boolean = rememberInFront(),
) {
    val settingOffText = stringResource(R.string.protected_video_notice_setting_off)
    val privateTabText = stringResource(R.string.protected_video_notice_private_tab)
    val due by rememberUpdatedState(protectedVideoNoticeNow(refusal, currentLoad, tabSelected, inFront, pageHidden, inPip))
    val mayStay by rememberUpdatedState(protectedVideoNoticeMayStay(tabSelected, inFront, pageHidden, inPip))
    val load by rememberUpdatedState(currentLoad)
    val shown by rememberUpdatedState(onShown)
    LaunchedEffect(tabId, snackbars) {
        val effect = this
        snapshotFlow { due }.collectLatest { answer ->
            val message =
                when (answer) {
                    ProtectedVideoAnswer.DENY_SETTING_OFF -> settingOffText
                    ProtectedVideoAnswer.DENY_PRIVATE_TAB -> privateTabText
                    ProtectedVideoAnswer.ALLOW, null -> return@collectLatest
                }
            snapshotFlow { snackbars.currentSnackbarData }.first { it == null }
            val shownLoad = load
            shown(shownLoad)
            // Not a child of this collector: marking the load as told must not cancel the notice it just started.
            effect.launch {
                val notice =
                    launch {
                        withTimeoutOrNull(PROTECTED_VIDEO_NOTICE_MILLIS) {
                            snackbars.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Indefinite)
                        }
                    }
                val leave =
                    launch {
                        snapshotFlow { mayStay && load == shownLoad }.first { !it }
                        notice.cancel()
                    }
                notice.join()
                leave.cancel()
            }
        }
    }
}

@Composable
private fun rememberInFront(): Boolean {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return state.isAtLeast(Lifecycle.State.STARTED)
}
