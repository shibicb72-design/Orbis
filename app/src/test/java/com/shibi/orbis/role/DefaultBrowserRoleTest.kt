package com.shibi.orbis.role

import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultBrowserRoleTest {
    @Test
    fun heldWhenAvailableAndHeld() {
        assertEquals(BrowserRoleStatus.HELD, browserRoleStatus(available = true, held = true))
    }

    @Test
    fun availableWhenNotHeld() {
        assertEquals(BrowserRoleStatus.AVAILABLE, browserRoleStatus(available = true, held = false))
    }

    @Test
    fun unavailableWhateverHeldSays() {
        assertEquals(BrowserRoleStatus.UNAVAILABLE, browserRoleStatus(available = false, held = false))
        assertEquals(BrowserRoleStatus.UNAVAILABLE, browserRoleStatus(available = false, held = true))
    }
}
