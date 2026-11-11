package com.shibi.orbis.ui

import java.time.Duration
import org.robolectric.shadows.ShadowSystemClock

/**
 * Moves Robolectric's uptime clock past [TAP_GUARD_DELAY_MS], so a guarded confirm button answers the next tap. The
 * Compose test rule does not move that clock by itself.
 */
fun passTapGuard(extraMs: Long = 100) {
    ShadowSystemClock.advanceBy(Duration.ofMillis(TAP_GUARD_DELAY_MS + extraMs))
}
