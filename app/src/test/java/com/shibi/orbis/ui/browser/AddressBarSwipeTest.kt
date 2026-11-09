package com.shibi.orbis.ui.browser

import com.shibi.orbis.tabs.TabUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressBarSwipeTest {
    private fun tab(id: Long, private: Boolean = false) = TabUi(id, "t$id", "https://e$id.example/", selected = false, isPrivate = private)

    private val tabs = listOf(tab(1), tab(2, private = true), tab(3), tab(4, private = true), tab(5))

    @Test
    fun movesWithinTheSameKindOfTabs() {
        assertEquals(3L, swipeTargetTab(tabs, 1, +1))
        assertEquals(5L, swipeTargetTab(tabs, 3, +1))
        assertEquals(1L, swipeTargetTab(tabs, 3, -1))
        assertEquals(4L, swipeTargetTab(tabs, 2, +1))
    }

    @Test
    fun stopsAtTheEnds() {
        assertNull(swipeTargetTab(tabs, 5, +1))
        assertNull(swipeTargetTab(tabs, 1, -1))
        assertNull(swipeTargetTab(tabs, 99, +1))
    }

    @Test
    fun onlyFarMostlySidewaysDragsCount() {
        assertEquals(1, addressSwipeStep(dx = -200f, dy = 10f, thresholdPx = 150f))
        assertEquals(-1, addressSwipeStep(dx = 200f, dy = -10f, thresholdPx = 150f))
        assertEquals(0, addressSwipeStep(dx = -100f, dy = 0f, thresholdPx = 150f))
        assertEquals(0, addressSwipeStep(dx = -200f, dy = 150f, thresholdPx = 150f))
    }
}
