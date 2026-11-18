package com.shibi.orbis.ui.browser

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import com.shibi.orbis.R
import com.shibi.orbis.downloads.DownloadEntry
import com.shibi.orbis.downloads.DownloadStatus
import com.shibi.orbis.downloads.SavedTo
import com.shibi.orbis.tabs.PendingDownload
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Which message the download feedback shows. Only [SAVED], [FINISHED] and [SAVED_PRIVATE] carry the "Open" button. */
enum class DownloadFeedbackKind(val hasOpen: Boolean) {
    DOWNLOADING(false),
    SAVED(true),
    FINISHED(true),
    SAVED_PRIVATE(true),
    FAILED(false),
}

/** Which message belongs to which state of a download. Pure: the clock is passed in. */
object DownloadFeedbackState {
    /** "Downloading..." stays at least this long, even when the download is quicker. */
    const val MIN_DOWNLOADING_MS = 700L

    /** "Downloading..." is dismissed after this long if the download still runs. */
    const val MAX_DOWNLOADING_MS = 10_000L

    /** A finished file still kept only in Orbis this long after it finished counts as not copied. */
    const val COPY_GRACE_MS = 2_000L

    /**
     * The message to show now for [entry], a download started at [startedAt] (same clock as [now]), or null for none.
     * A null entry (removed from the list) shows nothing. CANCELLED and PAUSED show nothing at once.
     */
    fun kind(entry: DownloadEntry?, startedAt: Long, now: Long): DownloadFeedbackKind? {
        if (entry == null) return null
        val elapsed = now - startedAt
        return when (entry.status) {
            DownloadStatus.CANCELLED, DownloadStatus.PAUSED -> null
            DownloadStatus.IN_PROGRESS ->
                if (elapsed < MAX_DOWNLOADING_MS) DownloadFeedbackKind.DOWNLOADING else null
            DownloadStatus.FAILED ->
                if (elapsed < MIN_DOWNLOADING_MS) DownloadFeedbackKind.DOWNLOADING else DownloadFeedbackKind.FAILED
            DownloadStatus.COMPLETED -> {
                val finishedAt = entry.finishedAt
                when {
                    elapsed < MIN_DOWNLOADING_MS -> DownloadFeedbackKind.DOWNLOADING
                    entry.isPrivate -> DownloadFeedbackKind.SAVED_PRIVATE
                    entry.savedTo == SavedTo.DOWNLOADS -> DownloadFeedbackKind.SAVED
                    // The copy to Downloads happens a moment after COMPLETED: wait before calling it "not copied".
                    finishedAt == null || now - finishedAt >= COPY_GRACE_MS -> DownloadFeedbackKind.FINISHED
                    // During the wait "Downloading..." stays up (until the 10 s limit) instead of a gap with no message.
                    elapsed < MAX_DOWNLOADING_MS -> DownloadFeedbackKind.DOWNLOADING
                    else -> null
                }
            }
        }
    }

    /**
     * Milliseconds until the next change that only the clock causes (the 700 ms minimum, the 10 s limit, the end of the
     * copy wait), or null when none is due: then only a change of the entry itself can change the message.
     */
    fun nextTimedChangeIn(entry: DownloadEntry?, startedAt: Long, now: Long): Long? {
        if (entry == null) return null
        val due =
            listOfNotNull(
                startedAt + MIN_DOWNLOADING_MS,
                startedAt + MAX_DOWNLOADING_MS,
                entry.finishedAt?.let { it + COPY_GRACE_MS },
            )
        return due.filter { it > now }.minOrNull()?.let { it - now }
    }

    /** True when [a] and [b] agree in everything that can change the message (the byte counts do not count). */
    fun sameMessageState(a: DownloadEntry?, b: DownloadEntry?): Boolean =
        a?.status == b?.status && a?.savedTo == b?.savedTo && a?.isPrivate == b?.isPrivate && a?.finishedAt == b?.finishedAt

    /** True once nothing more will change: the final message is up, or the download is gone, cancelled or paused. */
    fun isFinal(kind: DownloadFeedbackKind?, entry: DownloadEntry?): Boolean =
        entry == null || entry.status == DownloadStatus.CANCELLED || entry.status == DownloadStatus.PAUSED ||
            (kind != null && kind != DownloadFeedbackKind.DOWNLOADING)
}

/**
 * Shows the messages for download [downloadId] on [host]: "Downloading...", then the final message. A new download id
 * restarts it, so the messages follow the newest download. Each message replaces whatever is showing; a snackbar from
 * something else replaces ours, and ours is not shown again. Leaving the composition removes the message.
 * "Open" calls [onOpen] with the id and does nothing else.
 */
@Composable
fun DownloadFeedback(
    host: SnackbarHostState,
    entries: StateFlow<List<DownloadEntry>>,
    downloadId: Long?,
    onOpen: (Long) -> Unit,
    now: () -> Long = System::currentTimeMillis,
) {
    if (downloadId == null) return
    val downloading = stringResource(R.string.download_feedback_downloading)
    val saved = stringResource(R.string.download_feedback_saved)
    val finished = stringResource(R.string.download_feedback_finished)
    val savedPrivate = stringResource(R.string.download_feedback_saved_private)
    val failed = stringResource(R.string.download_feedback_failed)
    val openLabel = stringResource(R.string.download_feedback_open)
    val open by rememberUpdatedState(onOpen)
    val clock by rememberUpdatedState(now)
    LaunchedEffect(downloadId, entries) {
        val startedAt = clock()
        var shown: DownloadFeedbackKind? = null
        var job: Job? = null
        while (true) {
            val entry = entries.value.firstOrNull { it.id == downloadId }
            val kind = DownloadFeedbackState.kind(entry, startedAt, clock())
            if (kind != shown) {
                shown = kind
                job?.cancel()
                job = null
                if (kind != null) {
                    val text =
                        when (kind) {
                            DownloadFeedbackKind.DOWNLOADING -> downloading
                            DownloadFeedbackKind.SAVED -> saved
                            DownloadFeedbackKind.FINISHED -> finished
                            DownloadFeedbackKind.SAVED_PRIVATE -> savedPrivate
                            DownloadFeedbackKind.FAILED -> failed
                        }
                    job =
                        launch {
                            host.currentSnackbarData?.dismiss()
                            val result =
                                host.showSnackbar(
                                    message = text,
                                    actionLabel = if (kind.hasOpen) openLabel else null,
                                    duration =
                                        if (kind == DownloadFeedbackKind.DOWNLOADING) SnackbarDuration.Indefinite else SnackbarDuration.Long,
                                )
                            if (result == SnackbarResult.ActionPerformed) open(downloadId)
                        }
                }
            }
            if (DownloadFeedbackState.isFinal(kind, entry)) break
            // Sleep until the next clock-driven change, or until the entry changes, whichever comes first.
            // With no change due (a download that runs on) only the entry can change anything, so just wait for it.
            val wait = DownloadFeedbackState.nextTimedChangeIn(entry, startedAt, clock())
            val changed: suspend () -> Unit = {
                entries.first { list -> !DownloadFeedbackState.sameMessageState(entry, list.firstOrNull { it.id == downloadId }) }
            }
            if (wait == null) changed() else withTimeoutOrNull(wait) { changed() }
        }
    }
}

/**
 * The "Download file?" question for [pending], or nothing for a download the user asked for in the page itself (the
 * PDF viewer's own Download button): that one goes to [onStart] at once. [dialog] draws the question for the others.
 */
@Composable
fun DownloadOffer(
    pending: PendingDownload?,
    onStart: (PendingDownload) -> Unit,
    dialog: @Composable (PendingDownload) -> Unit,
) {
    if (pending == null) return
    if (pending.download.userRequested) {
        val start by rememberUpdatedState(onStart)
        LaunchedEffect(pending) { start(pending) }
    } else {
        dialog(pending)
    }
}
