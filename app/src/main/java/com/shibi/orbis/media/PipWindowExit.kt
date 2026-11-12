package com.shibi.orbis.media

/**
 * Tells a closed picture-in-picture window from one expanded back to the full screen. Leaving PiP is reported by
 * onPictureInPictureModeChanged(false); closing the window also stops the activity and expanding it resumes the
 * activity, and Android may report either before or after the mode change. [onClosed] runs once for a close and never
 * for an expand. MainActivity forwards its callbacks; pure, so both orders are testable.
 */
class PipWindowExit(private val onClosed: () -> Unit) {
    /** Where the activity is when the mode change arrives. */
    enum class Activity { RESUMED, STARTED, STOPPED }

    /** Left PiP while still started: the next onResume means expanded, the next onStop means closed. */
    private var undecided = false

    fun onPipModeChanged(inPip: Boolean, activity: Activity) {
        undecided = false
        if (inPip) return
        when (activity) {
            Activity.RESUMED -> Unit // onResume came first: expanded.
            Activity.STARTED -> undecided = true
            Activity.STOPPED -> onClosed() // onStop came first: closed.
        }
    }

    fun onResume() {
        undecided = false
    }

    fun onStop() {
        if (!undecided) return
        undecided = false
        onClosed()
    }
}
