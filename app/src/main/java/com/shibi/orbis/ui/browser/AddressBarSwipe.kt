package com.shibi.orbis.ui.browser

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import com.shibi.orbis.tabs.TabUi
import kotlin.math.abs

/**
 * The tab a swipe on the address bar switches to: [step] +1 is the next tab, -1 the previous one, among the tabs of the
 * same kind (normal or private) as the current one, in tab order. Null at either end (no wrap) or when [currentId] is
 * not in the list.
 */
internal fun swipeTargetTab(tabs: List<TabUi>, currentId: Long, step: Int): Long? {
    val current = tabs.firstOrNull { it.id == currentId } ?: return null
    val group = tabs.filter { it.isPrivate == current.isPrivate }
    val i = group.indexOfFirst { it.id == currentId }
    return group.getOrNull(i + step)?.id
}

/**
 * Whether a drag of ([dx], [dy]) px is a sideways swipe: far enough ([thresholdPx]) and mostly horizontal. Returns the
 * step (+1 swiped left = next tab, -1 swiped right = previous tab) or 0.
 */
internal fun addressSwipeStep(dx: Float, dy: Float, thresholdPx: Float): Int =
    when {
        abs(dx) < thresholdPx || abs(dx) < 2 * abs(dy) -> 0
        dx < 0 -> 1
        else -> -1
    }

/**
 * Sideways swipes on the resting address bar switch tabs. Watches touches before the field does (Initial pass) and takes
 * over only once a swipe is recognised; the rest of that gesture, its release included, is then consumed, so the field
 * sees no tap and does not start editing. Off while [enabled] is false (editing: drags select text there).
 */
@Composable
internal fun Modifier.addressBarSwipe(enabled: Boolean, onSwipe: (Int) -> Unit): Modifier {
    val thresholdPx = with(LocalDensity.current) { 56.dp.toPx() }
    val isEnabled by rememberUpdatedState(enabled)
    val swipe by rememberUpdatedState(onSwipe)
    return pointerInput(thresholdPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!isEnabled) return@awaitEachGesture
            var fired = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.size > 1) return@awaitEachGesture
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (fired) {
                    change.consume()
                    if (!change.pressed) return@awaitEachGesture
                    continue
                }
                if (!change.pressed) return@awaitEachGesture
                val step = addressSwipeStep(change.position.x - down.position.x, change.position.y - down.position.y, thresholdPx)
                if (step != 0) {
                    fired = true
                    change.consume()
                    swipe(step)
                }
            }
        }
    }
}
