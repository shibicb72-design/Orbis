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

    private val a = "https://a.example/"
    private val b = "https://b.example/"
    private val c = "https://c.example/"

    private fun tracked(vararg commits: String) = TabHistoryTracker().apply { commits.forEach { onCommit(it) } }

    @Test
    fun linksAddEntriesAndBackAndForwardMoveTheIndex() {
        val h = tracked(a, b, c)
        assertEquals(b, h.stepTarget(-1))
        assertEquals(null, h.stepTarget(1))

        h.onStepStarted(-1)
        h.onCommit(b)
        assertEquals(a, h.stepTarget(-1))
        assertEquals(c, h.stepTarget(1))

        h.onStepStarted(1)
        h.onCommit(c)
        assertEquals(b, h.stepTarget(-1))
        assertEquals(null, h.stepTarget(1))
    }

    @Test
    fun aNewAddressAfterBackDropsTheForwardEntries() {
        val h = tracked(a, b, c)
        h.onStepStarted(-1)
        h.onCommit(b)
        h.onCommit("https://d.example/")
        assertEquals(b, h.stepTarget(-1))
        assertEquals(null, h.stepTarget(1))
    }

    @Test
    fun aStepOnItsWayHasNoTargetUntilItCommits() {
        val h = tracked(a, b, c)
        h.onStepStarted(-1)
        assertEquals(null, h.stepTarget(-1))
        assertEquals(null, h.stepTarget(1))
    }

    @Test
    fun aReloadOfTheCurrentAddressChangesNothing() {
        val h = tracked(a, b)
        h.onCommit(b)
        assertEquals(a, h.stepTarget(-1))
        assertEquals(null, h.stepTarget(1))
        assertEquals(null, h.stepTarget(-2))
    }

    @Test
    fun aSameDocumentCommitAddsAnEntryOnTheSameSite() {
        // Hash links and pushState add an entry in Gecko 160 (measured); Back from there stays on the same site.
        val h = tracked(b, a, "https://a.example/#top")
        assertEquals(a, h.stepTarget(-1))
        h.onStepStarted(-1)
        h.onCommit(a)
        assertEquals(b, h.stepTarget(-1))
        assertEquals("https://a.example/#top", h.stepTarget(1))
    }

    @Test
    fun aRedirectedLinkIsOneEntryWithTheFinalAddress() {
        // Only the redirect's target commits: the entry is the final address.
        val h = tracked(a, c)
        assertEquals(a, h.stepTarget(-1))
        h.onStepStarted(-1)
        h.onCommit(a)
        assertEquals(c, h.stepTarget(1))
    }

    @Test
    fun aStepThatCommitsAnotherAddressMakesTheListUnknown() {
        val h = tracked(a, b, c)
        h.onStepStarted(-1)
        h.onCommit("https://elsewhere.example/")
        assertEquals(false, h.isKnown)
        assertEquals(null, h.stepTarget(-1))
    }

    @Test
    fun aStepThatEndsWithoutACommitMakesTheListUnknown() {
        val h = tracked(a, b)
        h.onStepStarted(-1)
        h.onLoadEndedWithoutCommit()
        assertEquals(false, h.isKnown)

        // Without a step on its way, a failed load changes nothing.
        val k = tracked(a, b)
        k.onLoadEndedWithoutCommit()
        assertEquals(a, k.stepTarget(-1))
    }

    @Test
    fun geckosListIsTakenOnlyWhenItIsCurrent() {
        val h = tracked(a, b, c)
        // Stale: its current entry is not the page shown.
        h.onReportedList(listOf(a, b), reportedIndex = 1, committedUrl = c)
        assertEquals(b, h.stepTarget(-1))
        // Current: replaces the tracked list (here one with an extra entry from replaceState counted as added).
        h.onReportedList(listOf(a, c), reportedIndex = 1, committedUrl = c)
        assertEquals(a, h.stepTarget(-1))
    }

    @Test
    fun geckosListIsNotTakenWhileAStepIsOnItsWay() {
        val h = tracked(a, b, c)
        h.onStepStarted(-1)
        h.onReportedList(listOf(a, b, c), reportedIndex = 2, committedUrl = c)
        h.onCommit(b)
        assertEquals(a, h.stepTarget(-1))
        assertEquals(c, h.stepTarget(1))
    }

    @Test
    fun aRestoredTabIsUnknownUntilItsFirstCommitThenTakesGeckosCurrentList() {
        val h = TabHistoryTracker()
        h.onRestoreStarted()
        assertEquals(false, h.isKnown)
        // Before the restored page commits, Gecko's list is not taken.
        h.onReportedList(listOf(a, b), reportedIndex = 1, committedUrl = b)
        assertEquals(false, h.isKnown)
        // The first commit does not make it known: the entries before it are not.
        h.onCommit(b)
        assertEquals(false, h.isKnown)
        assertEquals(null, h.stepTarget(-1))
        // A step while unknown is not tracked.
        h.onStepStarted(-1)
        h.onCommit(a)
        assertEquals(false, h.isKnown)
        // Gecko's current list makes it known again.
        h.onReportedList(listOf(a, b), reportedIndex = 0, committedUrl = a)
        assertEquals(b, h.stepTarget(1))
        h.onStepStarted(1)
        h.onCommit(b)
        assertEquals(a, h.stepTarget(-1))
    }

    @Test
    fun theContentProcessGoingResetsTheListAndIgnoresTheReopenedBlankDocument() {
        val h = tracked(a, b)
        // The kill, then the restore itself: both reset.
        h.onRestoreStarted()
        h.onRestoreStarted()
        assertEquals(false, h.isKnown)
        assertEquals(null, h.stepTarget(-1))
        // The reopened session's blank document and its list are not the tab's.
        h.onCommit("about:blank")
        h.onReportedList(listOf("about:blank"), reportedIndex = 0, committedUrl = "about:blank")
        assertEquals(false, h.isKnown)
        // The restored page's first commit is the page the list held: that list is back.
        h.onCommit(b)
        assertEquals(true, h.isKnown)
        assertEquals(a, h.stepTarget(-1))
        h.onStepStarted(-1)
        h.onCommit(a)
        assertEquals(b, h.stepTarget(1))
    }

    @Test
    fun aRestoreThatBringsBackAnotherPageStaysUnknownUntilGeckoReports() {
        val h = tracked(a, b)
        h.onRestoreStarted()
        // The saved state was older than the last commit: another page came back.
        h.onCommit(c)
        assertEquals(false, h.isKnown)
        h.onReportedList(listOf(a, c), reportedIndex = 1, committedUrl = c)
        assertEquals(a, h.stepTarget(-1))
    }

    @Test
    fun aRestoreUsesGeckosLastListWhenOrbisHadNone() {
        // Unknown before the kill (a restored tab), but Gecko had reported: that list, when it shows the restored page.
        val h = TabHistoryTracker()
        h.onRestoreStarted()
        h.onCommit(b)
        h.onReportedList(listOf(a, b), reportedIndex = 1, committedUrl = a) // stale at the time: not taken
        assertEquals(false, h.isKnown)
        h.onRestoreStarted()
        h.onCommit(b)
        assertEquals(a, h.stepTarget(-1))
    }

    @Test
    fun theStepUsesTheTrackedListAndFallsBackToGeckosOnlyWhenUnknown() {
        val known = tracked(a, b)
        // Gecko's list is stale (still [a] at a): the tracked list decides.
        assertEquals(a, historyStepUri(known, offset = -1, reported = listOf(a), reportedIndex = 0, committedUrl = b))
        // A step on its way: no target, even with a current Gecko list.
        known.onStepStarted(-1)
        assertEquals(null, historyStepUri(known, offset = -1, reported = listOf(a, b), reportedIndex = 1, committedUrl = b))

        val unknown = TabHistoryTracker().apply { onRestoreStarted() }
        assertEquals(a, historyStepUri(unknown, offset = -1, reported = listOf(a, b), reportedIndex = 1, committedUrl = b))
        assertEquals(null, historyStepUri(unknown, offset = -1, reported = listOf(a, b), reportedIndex = 1, committedUrl = c))
        assertEquals(null, historyStepUri(unknown, offset = -1, reported = null, reportedIndex = -1, committedUrl = b))
    }
}
