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

    // ---- empty page in a desktop mode that auto desktop kept (onEmptyPage) ----

    private fun AutoDesktopRetry.empty(host: String = "a.test", desktop: Boolean = true, byAuto: Boolean = true) =
        onEmptyPage(host, desktop, byAuto)

    @Test
    fun emptyPageInMobileModeIsNothing() {
        assertEquals(Action.None, AutoDesktopRetry().empty(desktop = false, byAuto = false))
        assertEquals(Action.None, AutoDesktopRetry().empty(desktop = false, byAuto = true))
    }

    @Test
    fun emptyPageInDesktopTheUserChoseIsNeverTouched() {
        val r = AutoDesktopRetry()
        assertEquals(Action.None, r.empty(byAuto = false))
        assertEquals(Action.None, r.empty(byAuto = false))
        assertEquals(Action.None, r.onCleanPage("a.test"))
        assertEquals(Action.None, r.blocked(desktop = true, chose = true))
    }

    @Test
    fun emptyPageInAutoDesktopIsRetriedInMobile_contentKeepsMobile() {
        val r = AutoDesktopRetry()
        assertEquals(Action.RetryMobile, r.empty())
        assertEquals(Action.KeepMobile, r.onCleanPage("a.test"))
        // Kept once: the next clean page of the host is nothing.
        assertEquals(Action.None, r.onCleanPage("a.test"))
    }

    @Test
    fun mobileRetryStillEmptyRestoresDesktop_thenNeverTriesAgain() {
        val r = AutoDesktopRetry()
        assertEquals(Action.RetryMobile, r.empty())
        assertEquals(Action.RestoreDesktop, r.empty(desktop = false))
        // Desktop is back (still by auto): the same empty page is not tried again in this tab.
        assertEquals(Action.None, r.empty())
        assertEquals(Action.None, r.onCleanPage("a.test"))
    }

    @Test
    fun mobileRetryBlockedRestoresDesktop() {
        val r = AutoDesktopRetry()
        assertEquals(Action.RetryMobile, r.empty())
        assertEquals(Action.RestoreDesktop, r.blocked(desktop = false))
        assertEquals(Action.None, r.empty())
    }

    @Test
    fun oneMobileTryPerHostPerTab() {
        val r = AutoDesktopRetry()
        assertEquals(Action.RetryMobile, r.empty())
        assertEquals(Action.KeepMobile, r.onCleanPage("a.test"))
        assertEquals(Action.None, r.empty())
        // Another tab has its own state.
        assertEquals(Action.RetryMobile, AutoDesktopRetry().empty())
    }

    @Test
    fun afterMobileKeptTheBlockedRetryDoesNotSwitchTheHostToDesktopAgain() {
        val r = AutoDesktopRetry()
        assertEquals(Action.RetryMobile, r.empty())
        assertEquals(Action.KeepMobile, r.onCleanPage("a.test"))
        assertEquals(Action.None, r.blocked())
        assertEquals(Action.Retry, r.blocked("b.test"))
    }

    @Test
    fun pingPong_blockedInMobileEmptyInDesktop_endsAfterOneRoundWithDesktopRestored() {
        val r = AutoDesktopRetry()
        // Mobile: blocked -> desktop retry.
        assertEquals(Action.Retry, r.blocked())
        // Desktop: not blocked, so the retry is kept; but empty, and desktop is now by auto -> mobile retry.
        assertEquals(Action.Keep, r.onCleanPage("a.test"))
        assertEquals(Action.RetryMobile, r.empty())
        // Mobile: blocked again -> desktop restored.
        assertEquals(Action.RestoreDesktop, r.blocked(desktop = false))
        // Desktop: still empty -> nothing more, whatever comes next.
        assertEquals(Action.None, r.empty())
        assertEquals(Action.None, r.empty())
        assertEquals(Action.None, r.blocked())
        assertEquals(Action.None, r.onCleanPage("a.test"))
    }

    @Test
    fun hostsAreIndependentForTheMobileRetry() {
        val r = AutoDesktopRetry()
        assertEquals(Action.RetryMobile, r.empty("a.test"))
        assertEquals(Action.None, r.onCleanPage("b.test"))
        assertEquals(Action.RetryMobile, r.empty("b.test"))
        assertEquals(Action.KeepMobile, r.onCleanPage("b.test"))
    }

    @Test
    fun leavingTheHostClearsThePendingMobileRetry() {
        val r = AutoDesktopRetry()
        assertEquals(Action.RetryMobile, r.empty())
        r.onLeft("a.test")
        // Back on the host: a clean page is not taken as the retry's answer, and there is no second try.
        assertEquals(Action.None, r.onCleanPage("a.test"))
        assertEquals(Action.None, r.empty())
    }

    // ---- navigations during the mobile retry (onNavigation) ----

    @Test
    fun pageStartedNavigationToTheSameHostKeepsMobileOnce_theArrivingPageChangesNothingMore() {
        val r = AutoDesktopRetry()
        assertEquals(Action.RetryMobile, r.empty())
        assertEquals(Action.KeepMobile, r.onNavigation("a.test", byPage = true))
        // The page it arrives on reports, whatever it is: nothing more happens.
        assertEquals(Action.None, r.onNavigation("a.test", byPage = true))
        assertEquals(Action.None, r.onCleanPage("a.test"))
        assertEquals(Action.None, r.empty(desktop = false))
        assertEquals(Action.None, r.empty())
        assertEquals(Action.None, r.blocked())
    }

    @Test
    fun pageStartedNavigationToAnotherHostKeepsMobileForTheRetriedHost() {
        val r = AutoDesktopRetry()
        assertEquals(Action.RetryMobile, r.empty("short.test"))
        assertEquals(Action.KeepMobile, r.onNavigation("short.test", byPage = true))
        r.onLeft("short.test")
        // The arriving host was not retried and is not affected.
        assertEquals(Action.None, r.onCleanPage("main.test"))
        assertEquals(Action.None, r.empty("main.test", byAuto = false))
        // The retried host is never switched to desktop again in this tab.
        assertEquals(Action.None, r.blocked("short.test"))
    }

    @Test
    fun userStartedNavigationDuringTheRetrySettlesNothing() {
        val r = AutoDesktopRetry()
        assertEquals(Action.RetryMobile, r.empty())
        // No KeepMobile: the caller performs nothing, so the automatic choice stays stored.
        assertEquals(Action.None, r.onNavigation("a.test", byPage = false))
        // The retry is dropped: no later report keeps or restores anything, and there is no second try.
        assertEquals(Action.None, r.onCleanPage("a.test"))
        assertEquals(Action.None, r.empty())
        assertEquals(Action.None, r.onNavigation("a.test", byPage = true))
    }

    @Test
    fun pageStartedNavigationWithNoRetryPendingIsNothing() {
        val r = AutoDesktopRetry()
        assertEquals(Action.None, r.onNavigation("a.test", byPage = true))
        assertEquals(Action.None, r.onNavigation("a.test", byPage = false))
        // A pending desktop retry is not touched either.
        assertEquals(Action.Retry, r.blocked())
        assertEquals(Action.None, r.onNavigation("a.test", byPage = true))
        assertEquals(Action.Keep, r.onCleanPage("a.test"))
        // A mobile retry of another host is not settled by this host's navigation.
        assertEquals(Action.RetryMobile, r.empty("b.test"))
        assertEquals(Action.None, r.onNavigation("a.test", byPage = true))
        assertEquals(Action.KeepMobile, r.onCleanPage("b.test"))
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
