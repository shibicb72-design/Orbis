package com.shibi.orbis.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopCommitTest {
    @Test
    fun desktopPageThenAMobileSiteSwitchesToMobileAndReloads() {
        assertEquals(
            DesktopCommit.Apply(desktop = false, reload = true),
            desktopAfterCommit(wanted = false, current = true, reloadedForLastCommit = false, pageStarted = true),
        )
    }

    @Test
    fun mobilePageThenADesktopSiteSwitchesToDesktopAndReloads() {
        assertEquals(
            DesktopCommit.Apply(desktop = true, reload = true),
            desktopAfterCommit(wanted = true, current = false, reloadedForLastCommit = false, pageStarted = true),
        )
    }

    @Test
    fun sameModeDoesNothing() {
        assertEquals(DesktopCommit.Leave, desktopAfterCommit(wanted = true, current = true, reloadedForLastCommit = false, pageStarted = true))
        assertEquals(DesktopCommit.Leave, desktopAfterCommit(wanted = false, current = false, reloadedForLastCommit = false, pageStarted = true))
        assertEquals(DesktopCommit.Leave, desktopAfterCommit(wanted = false, current = false, reloadedForLastCommit = true, pageStarted = true))
    }

    @Test
    fun theReloadsOwnCommitNeverReloadsAgain() {
        assertEquals(
            DesktopCommit.Apply(desktop = true, reload = false),
            desktopAfterCommit(wanted = true, current = false, reloadedForLastCommit = true, pageStarted = true),
        )
        assertEquals(
            DesktopCommit.Apply(desktop = false, reload = false),
            desktopAfterCommit(wanted = false, current = true, reloadedForLastCommit = true, pageStarted = true),
        )
    }

    @Test
    fun aSameDocumentCommitLeavesTheModeAlone() {
        assertEquals(DesktopCommit.Leave, desktopAfterCommit(wanted = true, current = false, reloadedForLastCommit = false, pageStarted = false))
        assertEquals(DesktopCommit.Leave, desktopAfterCommit(wanted = false, current = true, reloadedForLastCommit = false, pageStarted = false))
        assertEquals(DesktopCommit.Leave, desktopAfterCommit(wanted = false, current = true, reloadedForLastCommit = true, pageStarted = false))
    }

    @Test
    fun noChoiceLeavesTheMode() {
        assertEquals(DesktopCommit.Leave, desktopAfterCommit(wanted = null, current = true, reloadedForLastCommit = false, pageStarted = true))
    }

    @Test
    fun onlyARedirectFromDesktopToMobileKeepsTheDesktopUserAgent() {
        assertEquals(true, redirectKeepsDesktop(isRedirect = true, desktopBefore = true, desktopAfter = false))
        assertEquals(false, redirectKeepsDesktop(isRedirect = true, desktopBefore = false, desktopAfter = true))
        assertEquals(false, redirectKeepsDesktop(isRedirect = true, desktopBefore = true, desktopAfter = true))
        assertEquals(false, redirectKeepsDesktop(isRedirect = true, desktopBefore = false, desktopAfter = false))
        assertEquals(false, redirectKeepsDesktop(isRedirect = false, desktopBefore = true, desktopAfter = false))
    }
}
