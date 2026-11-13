package com.shibi.orbis.media

/**
 * Tells a closed picture-in-picture window from one expanded back to the full screen. Leaving PiP is reported by
 * onPictureInPictureModeChanged(false); closing the window also stops the activity and expanding it resumes the
 * activity, and Android may report either before or after the mode change. Some devices also stop and restart the
 * activity during an expand, so a stop only makes a close pending: [onClosePending] asks the caller to call
 * [confirmClose] a little later, and an [onStart] or [onResume] before then cancels it. [onClosed] runs once for a
 * confirmed close and never for an expand. MainActivity forwards its callbacks and owns the delay; pure, so every
 * order is testable.
 */
class PipWindowExit(private val onClosePending: () -> Unit = {}, private val onClosed: () -> Unit) {
    /** Where the activity is when the mode change arrives. */
    enum class Activity { RESUMED, STARTED, STOPPED }

    /** Left PiP while still started: the next onResume means expanded, the next onStop means closed. */
    private var undecided = false

    /** Looks closed; becomes a close at [confirmClose] unless the activity starts or resumes first. */
    private var pendingClose = false

    fun onPipModeChanged(inPip: Boolean, activity: Activity) {
        undecided = false
        if (inPip) {
            pendingClose = false
            return
        }
        when (activity) {
            Activity.RESUMED -> Unit // onResume came first: expanded.
            Activity.STARTED -> undecided = true
            Activity.STOPPED -> closePending() // onStop came first: closed, unless it starts again.
        }
    }

    /** True when this start cancelled a pending close. */
    fun onStart(): Boolean = clear()

    /** True when this resume cancelled a pending close. */
    fun onResume(): Boolean = clear()

    fun onStop() {
        if (!undecided) return
        undecided = false
        closePending()
    }

    /** Runs [onClosed] if a close is still pending; at most once per close. */
    fun confirmClose() {
        if (!pendingClose) return
        pendingClose = false
        onClosed()
    }

    private fun closePending() {
        pendingClose = true
        onClosePending()
    }

    private fun clear(): Boolean {
        val cancelled = pendingClose
        undecided = false
        pendingClose = false
        return cancelled
    }
}

/**
 * What closing the picture-in-picture window does: pause the media that played in it, and put the selected tab to
 * sleep only when no activity instance is started. A new instance may already be on screen (a link opened right after
 * the window closed); its page must stay active.
 */
fun onPipWindowClosed(noneStarted: Boolean, pausePipMedia: () -> Unit, sleepSelectedTab: () -> Unit) {
    pausePipMedia()
    if (noneStarted) sleepSelectedTab()
}
