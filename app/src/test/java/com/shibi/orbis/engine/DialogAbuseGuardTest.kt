package com.shibi.orbis.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogAbuseGuardTest {
    private var clock = 1_000L
    private val guard = DialogAbuseGuard(now = { clock })

    @Test
    fun firstFivePromptsInTheWindowAreShown() {
        repeat(5) {
            assertFalse("prompt ${it + 1}", guard.shouldSuppress())
            clock += 100
        }
    }

    @Test
    fun sixthPromptTripsTheGuardAndStaysTrippedEvenAfterTheWindow() {
        repeat(5) { assertFalse(guard.shouldSuppress()) }
        assertTrue(guard.shouldSuppress())
        clock += 60_000
        assertTrue("still locked out until the next page", guard.shouldSuppress())
    }

    @Test
    fun slowPromptsNeverTrip() {
        repeat(50) {
            assertFalse(guard.shouldSuppress())
            clock += 3_000
        }
    }

    @Test
    fun promptsOlderThanTheWindowDoNotCount() {
        repeat(5) { assertFalse(guard.shouldSuppress()) }
        clock += 10_000
        assertFalse(guard.shouldSuppress())
    }

    @Test
    fun pageStartLiftsTheLockout() {
        repeat(6) { guard.shouldSuppress() }
        assertTrue(guard.shouldSuppress())
        guard.onPageStart()
        assertFalse(guard.shouldSuppress())
        repeat(4) { assertFalse(guard.shouldSuppress()) }
        assertTrue(guard.shouldSuppress())
    }
}
