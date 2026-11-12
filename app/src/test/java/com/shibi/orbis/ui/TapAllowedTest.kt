package com.shibi.orbis.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-15: the tap-guard rule, without Compose. */
class TapAllowedTest {
    @Test
    fun aTapIsRefusedUntilTheDelayHasPassedSinceTheButtonWasShown() {
        assertFalse(tapAllowed(shownAtMs = 1_000, focusGainedAtMs = null, windowFocused = true, nowMs = 1_000, delayMs = 500))
        assertFalse(tapAllowed(shownAtMs = 1_000, focusGainedAtMs = null, windowFocused = true, nowMs = 1_499, delayMs = 500))
        assertTrue(tapAllowed(shownAtMs = 1_000, focusGainedAtMs = null, windowFocused = true, nowMs = 1_500, delayMs = 500))
        assertTrue(tapAllowed(shownAtMs = 1_000, focusGainedAtMs = null, windowFocused = true, nowMs = 1_600, delayMs = 500))
    }

    @Test
    fun regainingWindowFocusStartsTheDelayAgain() {
        assertFalse(tapAllowed(shownAtMs = 1_000, focusGainedAtMs = 5_000, windowFocused = true, nowMs = 5_000, delayMs = 500))
        assertFalse(tapAllowed(shownAtMs = 1_000, focusGainedAtMs = 5_000, windowFocused = true, nowMs = 5_499, delayMs = 500))
        assertTrue(tapAllowed(shownAtMs = 1_000, focusGainedAtMs = 5_000, windowFocused = true, nowMs = 5_500, delayMs = 500))
    }

    @Test
    fun anEarlyFocusGainDoesNotShortenTheDelayFromShowing() {
        assertFalse(tapAllowed(shownAtMs = 1_000, focusGainedAtMs = 400, windowFocused = true, nowMs = 1_200, delayMs = 500))
    }

    @Test
    fun aClockThatWentBackwardsCountsAsTooSoon() {
        assertFalse(tapAllowed(shownAtMs = 1_000, focusGainedAtMs = null, windowFocused = true, nowMs = 900, delayMs = 500))
        assertFalse(tapAllowed(shownAtMs = 1_000, focusGainedAtMs = 3_000, windowFocused = true, nowMs = 2_000, delayMs = 500))
    }

    @Test
    fun noTapCountsWhileTheWindowHasNoFocus() {
        assertFalse(tapAllowed(shownAtMs = 1_000, focusGainedAtMs = null, windowFocused = false, nowMs = 9_000, delayMs = 500))
        assertTrue(tapAllowed(shownAtMs = 1_000, focusGainedAtMs = null, windowFocused = true, nowMs = 9_000, delayMs = 500))
    }

    @Test
    fun aZeroDelayAllowsAtOnce() {
        assertTrue(tapAllowed(shownAtMs = 1_000, focusGainedAtMs = 1_000, windowFocused = true, nowMs = 1_000, delayMs = 0))
    }
}
