package com.shibi.orbis.ui.browser

import android.os.SystemClock
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.shibi.orbis.R
import com.shibi.orbis.engine.BrowserSession
import com.shibi.orbis.engine.SessionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Notice A: the load runs and its progress value has not moved for this long. */
internal const val SLOW_LOAD_MILLIS = 30_000L

/**
 * Notice B waits this long after the stop. Gecko can report the stop of a replaced load just before the next load's start
 * (a link, a page's own redirect, Orbis's desktop-mode reload), and the error page's report can follow the stop: neither
 * may flash the notice.
 */
internal const val UNFINISHED_SETTLE_MILLIS = 2_000L

/**
 * Notice B only for a load whose progress had not moved for this long when it stopped. A page that calls window.stop()
 * itself does so while things move; a dead or silent connection stops after a silence. A connection cut while the page
 * still arrives gives no notice, on purpose.
 */
internal const val SILENT_BEFORE_STOP_MILLIS = 10_000L

/** Notice A closes itself after this long on screen, so other snackbars (Tab closed, Undo) do not wait for ever. */
internal const val TAKING_LONG_SHOWN_MILLIS = 20_000L

/** Notice B closes itself after this long on screen. */
internal const val DID_NOT_FINISH_SHOWN_MILLIS = 30_000L

internal enum class SlowLoadKind {
    /** "This page is taking a long time to load." Stop, Reload. */
    TAKING_LONG,

    /** "This page did not finish loading." Reload. */
    DID_NOT_FINISH,
}

/** Everything the decision reads. Built by [slowLoadSnapshot]; a new page start resets the load's part. */
internal data class SlowLoadSnapshot(
    val loading: Boolean,
    val progress: Int,
    val millisSinceProgress: Long,
    /** The load stopped and the engine gave its flag. */
    val ended: Boolean,
    val endedWell: Boolean,
    val millisSinceEnd: Long,
    /** How long the progress value had not moved when the load stopped (0 while it runs). */
    val millisSilentBeforeStop: Long,
    /** The engine handed a response of this load to Orbis as a download. */
    val becameDownload: Boolean,
    /** Orbis's error banner (load failed, HTTPS-only, content process gone) is up. */
    val errorPageShowing: Boolean,
    /** Stop, Reload, Back, Forward or a new address since the load started. */
    val stoppedByUser: Boolean,
    /** No new address was committed: a download, a link another app opens, a 204 answer. */
    val noNewPage: Boolean,
    val selected: Boolean,
    val inFront: Boolean,
    val hiddenByLock: Boolean,
    val inPictureInPicture: Boolean,
    /** The notices already shown for this load (closed or not). */
    val shownThisLoad: Set<SlowLoadKind>,
)

internal fun slowLoadSnapshot(
    state: SessionState,
    now: Long,
    selected: Boolean,
    inFront: Boolean,
    hiddenByLock: Boolean,
    inPictureInPicture: Boolean,
    shownThisLoad: Set<SlowLoadKind>,
): SlowLoadSnapshot {
    val watch = state.loadWatch
    return SlowLoadSnapshot(
        loading = state.isLoading,
        progress = state.progress,
        millisSinceProgress = now - watch.progressAt,
        ended = !state.isLoading && watch.endedWell != null,
        endedWell = watch.endedWell == true,
        millisSinceEnd = now - watch.endedAt,
        millisSilentBeforeStop = if (watch.endedWell != null) watch.endedAt - watch.progressAt else 0L,
        becameDownload = watch.download,
        errorPageShowing = state.loadFailed || state.httpsOnlyBlocked || state.crashed || state.contentGone,
        stoppedByUser = watch.endedByUser,
        noNewPage = !watch.committed,
        selected = selected,
        inFront = inFront,
        hiddenByLock = hiddenByLock,
        inPictureInPicture = inPictureInPicture,
        shownThisLoad = shownThisLoad,
    )
}

private fun SlowLoadSnapshot.mayShowAny(): Boolean = selected && inFront && !hiddenByLock && !inPictureInPicture && !errorPageShowing

private fun SlowLoadSnapshot.isLong(): Boolean = loading && progress < 100

private fun SlowLoadSnapshot.isUnfinished(): Boolean =
    ended && !endedWell && !stoppedByUser && !noNewPage && !becameDownload && millisSilentBeforeStop >= SILENT_BEFORE_STOP_MILLIS

/** The notice to show now, or null. Each at most once per load ([SlowLoadSnapshot.shownThisLoad]). */
internal fun slowLoadNotice(s: SlowLoadSnapshot): SlowLoadKind? =
    when {
        !s.mayShowAny() -> null
        s.isLong() && s.millisSinceProgress >= SLOW_LOAD_MILLIS && SlowLoadKind.TAKING_LONG !in s.shownThisLoad -> SlowLoadKind.TAKING_LONG
        s.isUnfinished() && s.millisSinceEnd >= UNFINISHED_SETTLE_MILLIS && SlowLoadKind.DID_NOT_FINISH !in s.shownThisLoad ->
            SlowLoadKind.DID_NOT_FINISH
        else -> null
    }

/**
 * A notice that is up stays while this holds for the same tab and load: A while the load runs, B while the load stays
 * stopped unfinished. Picture-in-picture, the lock or an error banner take it away. Not being in front does not.
 */
internal fun slowLoadNoticeStillApplies(kind: SlowLoadKind, s: SlowLoadSnapshot): Boolean =
    s.selected && !s.hiddenByLock && !s.inPictureInPicture && !s.errorPageShowing &&
        when (kind) {
            SlowLoadKind.TAKING_LONG -> s.isLong()
            SlowLoadKind.DID_NOT_FINISH -> s.isUnfinished()
        }

/** How long until [slowLoadNotice] can change by time alone; null when only another event can change it. */
internal fun slowLoadRecheckIn(s: SlowLoadSnapshot): Long? =
    when (slowLoadNotice(s.copy(millisSinceProgress = Long.MAX_VALUE, millisSinceEnd = Long.MAX_VALUE))) {
        SlowLoadKind.TAKING_LONG -> (SLOW_LOAD_MILLIS - s.millisSinceProgress).coerceAtLeast(1)
        SlowLoadKind.DID_NOT_FINISH -> (UNFINISHED_SETTLE_MILLIS - s.millisSinceEnd).coerceAtLeast(1)
        null -> null
    }

/** A slow-load notice in the shared snackbar host: drawn by [OrbisSnackbar] (a second action and a close button). */
internal class SlowLoadVisuals(
    override val message: String,
    override val actionLabel: String,
    val secondActionLabel: String?,
    val onSecondAction: () -> Unit,
    val closeLabel: String,
) : SnackbarVisuals {
    override val withDismissAction: Boolean get() = true
    override val duration: SnackbarDuration get() = SnackbarDuration.Indefinite
}

/** The browser screen's snackbar: Material's own, except for [SlowLoadVisuals]. */
@Composable
internal fun OrbisSnackbar(data: SnackbarData) {
    val visuals = data.visuals as? SlowLoadVisuals
    if (visuals == null) {
        Snackbar(data)
        return
    }
    val actionColors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor)
    Snackbar(
        modifier = Modifier.padding(12.dp),
        action = {
            Row {
                TextButton(onClick = { data.performAction() }, colors = actionColors) { Text(visuals.actionLabel) }
                visuals.secondActionLabel?.let { label ->
                    TextButton(
                        onClick = {
                            visuals.onSecondAction()
                            data.dismiss()
                        },
                        colors = actionColors,
                    ) { Text(label) }
                }
            }
        },
        dismissAction = {
            IconButton(onClick = { data.dismiss() }) {
                Icon(Icons.Filled.Close, contentDescription = visuals.closeLabel, tint = SnackbarDefaults.dismissActionContentColor)
            }
        },
        actionOnNewLine = visuals.secondActionLabel != null,
    ) { Text(visuals.message) }
}

private class ShownMark(val load: Int, val kinds: Set<SlowLoadKind>)

private class ActiveNotice(val tabId: Long, val load: Int, val kind: SlowLoadKind, val job: Job)

private data class SlowLoadInputs(
    val tabId: Long,
    val session: BrowserSession?,
    /** Only wakes the loop; the loop reads [session]'s own state, which is never another tab's. */
    val trigger: SessionState,
    val inFront: Boolean,
    val hiddenByLock: Boolean,
    val inPictureInPicture: Boolean,
)

private val NO_SESSION_STATE = MutableStateFlow(SessionState())

/**
 * The two slow-load notices for the selected tab [tabId] and its [session]. Shown in [snackbars] only while Orbis is in
 * front, never over a locked private page or in picture-in-picture, each at most once per load (a closed one does not come
 * back, also not after a tab switch). Another snackbar is never replaced: a notice waits for the host to be free. Nothing
 * here stops or reloads by itself: Stop and Reload are the tab's own, and only on the user's tap.
 */
@Composable
internal fun SlowLoadNotice(
    tabId: Long,
    session: BrowserSession?,
    snackbars: SnackbarHostState,
    hiddenByLock: Boolean,
    inPictureInPicture: Boolean,
    log: (String) -> Unit,
    clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val inFront = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val takingLong = stringResource(R.string.slow_load_taking_long)
    val didNotFinish = stringResource(R.string.slow_load_did_not_finish)
    val stopLabel = stringResource(R.string.slow_load_stop)
    val reloadLabel = stringResource(R.string.slow_load_reload)
    val closeLabel = stringResource(R.string.slow_load_close)
    val trigger by (session?.state ?: NO_SESSION_STATE).collectAsState()
    val logLine by rememberUpdatedState(log)
    val now by rememberUpdatedState(clock)
    val inputs by rememberUpdatedState(SlowLoadInputs(tabId, session, trigger, inFront, hiddenByLock, inPictureInPicture))
    // Per tab, for its current load only: which notices were shown. Memory only.
    val marks = remember { HashMap<Long, ShownMark>() }
    LaunchedEffect(snackbars) {
        val effect = this
        var active: ActiveNotice? = null
        snapshotFlow { inputs }.collectLatest { input ->
            while (true) {
                val tab = input.session
                val s = tab?.state?.value
                val load = s?.loadStarts ?: -1
                val shown = marks[input.tabId]?.takeIf { it.load == load }?.kinds.orEmpty()
                val snapshot =
                    s?.let { slowLoadSnapshot(it, now(), true, input.inFront, input.hiddenByLock, input.inPictureInPicture, shown) }
                active?.let { a ->
                    val keep = a.job.isActive && a.tabId == input.tabId && a.load == load &&
                        snapshot != null && slowLoadNoticeStillApplies(a.kind, snapshot)
                    if (!keep) {
                        a.job.cancel()
                        active = null
                    }
                }
                if (active != null || tab == null || snapshot == null) awaitCancellation()
                val kind = slowLoadNotice(snapshot)
                if (kind == null) {
                    delay(slowLoadRecheckIn(snapshot) ?: awaitCancellation())
                    continue
                }
                if (snackbars.currentSnackbarData != null) {
                    snapshotFlow { snackbars.currentSnackbarData }.first { it == null }
                    continue
                }
                marks[input.tabId] = ShownMark(load, shown + kind)
                val visuals =
                    when (kind) {
                        SlowLoadKind.TAKING_LONG -> SlowLoadVisuals(takingLong, stopLabel, reloadLabel, { tab.reload() }, closeLabel)
                        SlowLoadKind.DID_NOT_FINISH -> SlowLoadVisuals(didNotFinish, reloadLabel, null, {}, closeLabel)
                    }
                logLine(if (kind == SlowLoadKind.TAKING_LONG) "slow load notice shown" else "unfinished load notice shown")
                // Not a child of this collector: the next input must not take the notice away unless it no longer applies.
                // It closes itself at its limit and stays marked as shown for this load.
                val limit = if (kind == SlowLoadKind.TAKING_LONG) TAKING_LONG_SHOWN_MILLIS else DID_NOT_FINISH_SHOWN_MILLIS
                val job =
                    effect.launch {
                        if (withTimeoutOrNull(limit) { snackbars.showSnackbar(visuals) } == SnackbarResult.ActionPerformed) {
                            if (kind == SlowLoadKind.TAKING_LONG) tab.stop() else tab.reload()
                        }
                    }
                active = ActiveNotice(input.tabId, load, kind, job)
            }
        }
    }
}
