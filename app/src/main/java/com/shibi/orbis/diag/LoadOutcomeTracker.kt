package com.shibi.orbis.diag

/**
 * How one page load ended. [error] is set for an engine error; otherwise [kind] says. [ended] says how a load whose page
 * committed came to its end: [LoadEnded.YES] when the page stopped normally. [ms] is the time from the load being asked
 * for to its end.
 */
data class LoadEnd(val kind: LoadKind, val ms: Long, val error: ErrorCategory? = null, val ended: LoadEnded = LoadEnded.YES)

/** The most pop-up lines written for one page (since the tab's last committed load). */
const val POPUP_LINES_PER_PAGE = 20

/**
 * Turns the engine's page callbacks of ONE tab into exactly one [LoadEnd] per load. No I/O, no clock of its own and no
 * text: callers pass the time in milliseconds and facts. Main thread only.
 *
 * A load is opened by a load request (or by a page start that had none: Back, Forward, a restore) and ends once:
 * - at an engine error, a download, a hand-off to an app or a refusal, at once;
 * - at the user's stop, or when the page stops;
 * - when another load replaces it, or the tab closes, before it ended: `unfinished`, or what it already was when
 *   its page had committed, with [LoadEnded.NO].
 * A load whose page committed is written at its page stop, or at the user's stop, at once. Redirect requests of a load
 * belong to that load.
 */
class LoadOutcomeTracker(private val emit: (LoadEnd) -> Unit) {
    private var open = false
    private var startedAt = 0L
    private var started = false
    private var redirected = false
    private var committed = false
    private var popups = 0
    private var dead = false

    /** A top-level load request (a redirect hop has [redirect] true). */
    fun onLoadRequest(redirect: Boolean, nowMs: Long) {
        if (dead) return
        if (redirect && open) {
            redirected = true
            return
        }
        begin(nowMs, started = false)
        if (redirect) redirected = true
    }

    /** The engine reported the page start. One without a load request (history, restore) opens its own load. */
    fun onPageStart(nowMs: Long) {
        if (dead) return
        when {
            !open -> begin(nowMs, started = true)
            !started -> started = true
            // A redirect target of a load that has not committed is the same load.
            redirected && !committed -> Unit
            else -> begin(nowMs, started = true)
        }
    }

    /** The engine committed a location. One with no page start is same-document (hash, pushState): not a load. */
    fun onCommit() {
        if (!open) return
        if (!started) {
            open = false
            return
        }
        if (!committed) {
            committed = true
            popups = 0
        }
    }

    /** The page stopped; [success] false for an abort or a failure the error callback did not report. */
    fun onPageStop(success: Boolean, nowMs: Long) {
        // A stop of an earlier load can arrive after a new request: the new load has not started.
        if (!open || !started) return
        if (success) {
            committed = true
            finish(LoadEnd(committedKind(), nowMs - startedAt))
        } else if (committed) {
            finish(LoadEnd(committedKind(), nowMs - startedAt, ended = LoadEnded.STOPPED))
        } else {
            finish(LoadEnd(LoadKind.STOPPED, nowMs - startedAt))
        }
    }

    fun onError(category: ErrorCategory, nowMs: Long) {
        if (dead) return
        ensureOpen(nowMs)
        finish(LoadEnd(LoadKind.UNFINISHED, nowMs - startedAt, error = category))
    }

    fun onDownload(nowMs: Long) = endNow(LoadKind.DOWNLOAD, nowMs)

    fun onAppHandOff(nowMs: Long) = endNow(LoadKind.APP, nowMs)

    fun onRefused(nowMs: Long) = endNow(LoadKind.REFUSED, nowMs)

    /** The user pressed stop. */
    fun onUserStop(nowMs: Long) {
        if (!open) return
        if (committed) {
            finish(LoadEnd(committedKind(), nowMs - startedAt, ended = LoadEnded.STOPPED))
        } else {
            finish(LoadEnd(LoadKind.STOPPED, nowMs - startedAt))
        }
    }

    /** The tab's session closed: nothing more is written for it (an answer to a dismissed question is not a load). */
    fun onClosed(nowMs: Long) {
        onInterrupted(nowMs)
        dead = true
    }

    /** The content process died: the load in flight will never end by itself. */
    fun onInterrupted(nowMs: Long) {
        if (dead) return
        endReplaced(nowMs)
    }

    /**
     * A pop-up opened from this tab's page: its number since the tab's last committed load, this one included, for the
     * first [POPUP_LINES_PER_PAGE]; null after that (the count still rises, so no line is written for a pop-up flood).
     */
    fun nextPopupLine(): Int? = if (++popups <= POPUP_LINES_PER_PAGE) popups else null

    private fun endNow(kind: LoadKind, nowMs: Long) {
        if (dead) return
        ensureOpen(nowMs)
        finish(LoadEnd(kind, nowMs - startedAt))
    }

    private fun ensureOpen(nowMs: Long) {
        if (!open) begin(nowMs, started = true)
    }

    private fun begin(nowMs: Long, started: Boolean) {
        endReplaced(nowMs)
        open = true
        startedAt = nowMs
        this.started = started
        redirected = false
        committed = false
    }

    private fun endReplaced(nowMs: Long) {
        if (!open) return
        val ms = nowMs - startedAt
        finish(if (committed) LoadEnd(committedKind(), ms, ended = LoadEnded.NO) else LoadEnd(LoadKind.UNFINISHED, ms))
    }

    private fun committedKind(): LoadKind = if (redirected) LoadKind.REDIRECTED else LoadKind.COMMITTED

    private fun finish(end: LoadEnd) {
        open = false
        emit(end)
    }
}
