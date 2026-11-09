package com.shibi.orbis.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.geckoview.PanZoomController.INPUT_RESULT_HANDLED
import org.mozilla.geckoview.PanZoomController.INPUT_RESULT_HANDLED_CONTENT
import org.mozilla.geckoview.PanZoomController.INPUT_RESULT_UNHANDLED
import org.mozilla.geckoview.PanZoomController.OVERSCROLL_FLAG_NONE
import org.mozilla.geckoview.PanZoomController.OVERSCROLL_FLAG_VERTICAL
import org.mozilla.geckoview.PanZoomController.SCROLLABLE_FLAG_BOTTOM
import org.mozilla.geckoview.PanZoomController.SCROLLABLE_FLAG_TOP

class PullToRefreshTest {
    @Test
    fun onlyAtTheTopOfAPageThatAllowsOverscrollAndDidNotTakeTheTouch() {
        assertTrue("at top, can scroll down", pullToRefreshAllowed(INPUT_RESULT_HANDLED, SCROLLABLE_FLAG_BOTTOM, OVERSCROLL_FLAG_VERTICAL))
        assertTrue("short page", pullToRefreshAllowed(INPUT_RESULT_UNHANDLED, 0, OVERSCROLL_FLAG_VERTICAL))
        assertFalse("scrolled down: the drag scrolls up instead", pullToRefreshAllowed(INPUT_RESULT_HANDLED, SCROLLABLE_FLAG_TOP or SCROLLABLE_FLAG_BOTTOM, OVERSCROLL_FLAG_VERTICAL))
        assertFalse("the page handled the touch (map, carousel)", pullToRefreshAllowed(INPUT_RESULT_HANDLED_CONTENT, 0, OVERSCROLL_FLAG_VERTICAL))
        assertFalse("overscroll-behavior: none", pullToRefreshAllowed(INPUT_RESULT_HANDLED, 0, OVERSCROLL_FLAG_NONE))
    }

    @Test
    fun reloadsOnlyPastTheThreshold() {
        assertFalse(pullTriggersRefresh(100f, 250f))
        assertTrue(pullTriggersRefresh(250f, 250f))
        assertTrue(pullTriggersRefresh(400f, 250f))
    }
}
