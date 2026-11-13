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
    fun aHistoryStepUsesTheListOnlyWhenItIsCurrent() {
        val uris = listOf("https://a.example/", "https://b.example/", "https://c.example/")
        assertEquals("https://a.example/", historyStepTarget(uris, currentIndex = 1, offset = -1, committedUrl = "https://b.example/"))
        assertEquals("https://c.example/", historyStepTarget(uris, currentIndex = 1, offset = 1, committedUrl = "https://b.example/"))
        // Stale: the page shown is not the list's current entry.
        assertEquals(null, historyStepTarget(uris, currentIndex = 1, offset = -1, committedUrl = "https://d.example/"))
        // Out of range.
        assertEquals(null, historyStepTarget(uris, currentIndex = 0, offset = -1, committedUrl = "https://a.example/"))
        assertEquals(null, historyStepTarget(emptyList(), currentIndex = 0, offset = 1, committedUrl = ""))
    }
}
