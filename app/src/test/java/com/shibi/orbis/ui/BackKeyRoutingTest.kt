package com.shibi.orbis.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BackKeyRoutingTest {
    private var backs = 0

    @Test
    fun backIsTakenOnBothEdgesAndRunsOnceOnRelease() {
        assertTrue(routeBackKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)) { backs++ })
        assertEquals(0, backs)
        assertTrue(routeBackKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)) { backs++ })
        assertEquals(1, backs)
    }

    @Test
    fun aCanceledBackDoesNothing() {
        val up = KeyEvent.changeFlags(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), KeyEvent.FLAG_CANCELED)
        assertTrue(routeBackKey(up) { backs++ })
        assertEquals(0, backs)
    }

    @Test
    fun otherKeysPassThrough() {
        assertFalse(routeBackKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)) { backs++ })
        assertFalse(routeBackKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE)) { backs++ })
        assertEquals(0, backs)
    }
}
