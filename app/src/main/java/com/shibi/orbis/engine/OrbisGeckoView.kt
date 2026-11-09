package com.shibi.orbis.engine

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.MotionEvent
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.PanZoomController

/** What a content view tells the screen about a pull-to-refresh gesture. */
interface PullToRefreshSource {
    /** The finger moved [distancePx] down from where it started, on a page at its top. */
    var onPull: ((distancePx: Float) -> Unit)?

    /** The pull ended (finger up, cancel, or it went back up) at [distancePx]; the screen decides whether to reload. */
    var onPullEnd: ((distancePx: Float) -> Unit)?
}

/**
 * Whether a touch may start pull-to-refresh, from GeckoView's answer for its first event: the page is at its top
 * (cannot scroll further up), allows vertical overscroll, and did not take the touch itself (a map, a carousel, a page
 * handling touchmove). Mirrors the check Firefox for Android makes.
 */
fun pullToRefreshAllowed(handledResult: Int, scrollableDirections: Int, overscrollDirections: Int): Boolean =
    handledResult != PanZoomController.INPUT_RESULT_HANDLED_CONTENT &&
        (scrollableDirections and PanZoomController.SCROLLABLE_FLAG_TOP) == 0 &&
        (overscrollDirections and PanZoomController.OVERSCROLL_FLAG_VERTICAL) != 0

/** Pull-to-refresh reloads once the finger travelled at least [thresholdPx] down. */
fun pullTriggersRefresh(distancePx: Float, thresholdPx: Float): Boolean = distancePx >= thresholdPx

/**
 * GeckoView that reports pull-to-refresh gestures. Every touch still goes to the page exactly once: the first event
 * through onTouchEventForDetailResult (which also tells us whether a pull may start), the rest through onTouchEvent.
 * A second finger cancels the pull (pinch zoom).
 */
class OrbisGeckoView(context: Context) : GeckoView(context), PullToRefreshSource {
    init {
        // GeckoView's print support opens Android's print dialog from the activity; without this it does nothing.
        setActivityContextDelegate {
            generateSequence(getContext()) { (it as? ContextWrapper)?.baseContext }.firstOrNull { it is Activity }
        }
    }

    override var onPull: ((Float) -> Unit)? = null
    override var onPullEnd: ((Float) -> Unit)? = null

    @Volatile private var mayPull = false
    private var startY = 0f
    private var pulling = false
    private var lastDistance = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = event.y
                pulling = false
                lastDistance = 0f
                mayPull = false
                onTouchEventForDetailResult(event).accept { d ->
                    mayPull = d != null && pullToRefreshAllowed(d.handledResult(), d.scrollableDirections(), d.overscrollDirections())
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - startY
                if (mayPull && event.pointerCount == 1 && dy > 0f) {
                    pulling = true
                    lastDistance = dy
                    onPull?.invoke(dy)
                } else if (pulling) {
                    endPull(if (dy > 0f) dy else 0f)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> if (pulling) endPull(0f)

            MotionEvent.ACTION_UP -> if (pulling) endPull(lastDistance)

            MotionEvent.ACTION_CANCEL -> if (pulling) endPull(0f)
        }
        return super.onTouchEvent(event)
    }

    private fun endPull(distance: Float) {
        pulling = false
        mayPull = false
        onPullEnd?.invoke(distance)
    }
}
