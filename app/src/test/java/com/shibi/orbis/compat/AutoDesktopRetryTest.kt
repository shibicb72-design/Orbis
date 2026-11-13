package com.shibi.orbis.compat

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoDesktopRetryTest {
    private fun AutoDesktopRetry.blocked(
        host: String = "a.test",
        desktop: Boolean = false,
        always: Boolean = false,
        declined: Boolean = false,
        chose: Boolean = false,
    ) = onBlockedPage(host, desktop, always, declined, chose)

    @Test
    fun retriesOnceThenNothingForTheSameHost() {
        val r = AutoDesktopRetry()
        assertEquals(Action.Retry, r.blocked())
        r.onCleanPage("a.test")
        assertEquals(Action.None, r.blocked())
    }

    @Test
    fun blockedAgainWhileRetryingReverts_thenNeverRetriesAgain() {
        val r = AutoDesktopRetry()
        assertEquals(Action.Retry, r.blocked())
        assertEquals(Action.Revert, r.blocked(desktop = true))
        assertEquals(Action.None, r.blocked())
    }

    @Test
    fun cleanPageWhileRetryingKeeps() {
        val r = AutoDesktopRetry()
        assertEquals(Action.Retry, r.blocked())
        assertEquals(Action.Keep, r.onCleanPage("a.test"))
        assertEquals(Action.None, r.onCleanPage("a.test"))
    }

    @Test
    fun cleanPageWithoutRetryIsNothing() {
        assertEquals(Action.None, AutoDesktopRetry().onCleanPage("a.test"))
    }

    @Test
    fun declinedHostIsNotRetried() {
        assertEquals(Action.None, AutoDesktopRetry().blocked(declined = true))
    }

    @Test
    fun alwaysDesktopIsNotRetried() {
        assertEquals(Action.None, AutoDesktopRetry().blocked(always = true))
    }

    @Test
    fun desktopChosenByUserIsLeftAlone() {
        assertEquals(Action.None, AutoDesktopRetry().blocked(desktop = true, chose = true))
    }

    @Test
    fun twoHostsInOneTabAreIndependent() {
        val r = AutoDesktopRetry()
        assertEquals(Action.Retry, r.blocked("a.test"))
        assertEquals(Action.Keep, r.onCleanPage("a.test"))
        assertEquals(Action.Retry, r.blocked("b.test"))
        assertEquals(Action.None, r.blocked("a.test"))
        assertEquals(Action.Revert, r.blocked("b.test", desktop = true))
        assertEquals(Action.None, r.blocked("b.test"))
    }

    @Test
    fun leavingDoesNotResetTheAttemptedMark() {
        val r = AutoDesktopRetry()
        assertEquals(Action.Retry, r.blocked())
        r.onLeft("a.test")
        assertEquals(Action.None, r.blocked())
        assertEquals(Action.None, r.onCleanPage("a.test"))
    }
}
