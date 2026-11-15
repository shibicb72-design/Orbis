package com.shibi.orbis.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentProcessGoneTest {
    private val page = "http://10.0.2.2:8777/f1x"
    private val loaded =
        SessionState()
            .afterPageStart(page)
            .afterLocationChange(page)
            .afterTitleChange("Page f1x")
            .afterPageStop(success = true)
            .copy(canGoBack = true)

    /**
     * What the reopened session reports, in the order measured on GeckoView 160 (a16_x64, kill -9 of the tab's
     * process): onPageStart(about:blank), onLocationChange(about:blank), onCanGoBack(false), onCanGoForward(false),
     * onPageStop(true). No title change was reported; the transition drops one anyway.
     */
    private fun SessionState.freshSessionEvents(): SessionState =
        afterPageStart("about:blank")
            .afterLocationChange("about:blank")
            .copy(canGoBack = false, canGoForward = false)
            .afterPageStop(success = true)
            .afterTitleChange("")

    @Test
    fun aKilledPageKeepsItsAddressAndTitleThroughTheFreshSessionsEvents() {
        val after = loaded.afterContentProcessGone(showBanner = false).freshSessionEvents()
        assertEquals(page, after.url)
        assertEquals("Page f1x", after.title)
        assertTrue(after.contentGone)
        assertFalse(after.crashed)
        assertEquals(loaded.loadStarts, after.loadStarts)
    }

    @Test
    fun theBannerSurvivesTheFreshSessionsEvents() {
        val after = loaded.afterContentProcessGone(showBanner = true).freshSessionEvents()
        assertTrue(after.crashed)
        assertTrue(after.contentGone)
        assertEquals(page, after.url)
        assertEquals(ReloadTarget.LostPage, reloadTarget(after))
    }

    @Test
    fun onlyAboutBlankIsTheFreshSessionsDocument() {
        val gone = loaded.afterContentProcessGone(showBanner = false)
        assertTrue(gone.isFreshSessionBlank("about:blank"))
        assertFalse(gone.isFreshSessionBlank(page))
        assertFalse(loaded.isFreshSessionBlank("about:blank"))
    }

    @Test
    fun theRestoredPageCountsAgain() {
        val restoring = loaded.afterContentProcessGone(showBanner = true).freshSessionEvents().afterRestoreRequested()
        assertFalse(restoring.crashed)
        // Still gone until the restored page starts: a late blank event from the reopen is still dropped.
        assertTrue(restoring.contentGone)
        assertEquals(page, restoring.afterLocationChange("about:blank").url)

        val started = restoring.afterPageStart(page)
        assertFalse(started.contentGone)
        assertTrue(started.isLoading)
        assertEquals(restoring.loadStarts + 1, started.loadStarts)
        assertEquals("Restored", started.afterTitleChange("Restored").title)
    }

    @Test
    fun theStartPageAfterALostPageIsAPageAgain() {
        val home = loaded.afterContentProcessGone(showBanner = true).afterNavigationRequested()
        assertFalse(home.crashed)
        assertFalse(home.contentGone)
        assertEquals("about:blank", home.afterPageStart("about:blank").afterLocationChange("about:blank").url)
    }

    @Test
    fun withoutALostPageAboutBlankIsAnOrdinaryPage() {
        val home = loaded.afterPageStart("about:blank").afterLocationChange("about:blank")
        assertEquals("about:blank", home.url)
        assertEquals(loaded.loadStarts + 1, home.loadStarts)
    }

    @Test
    fun killOfTheVisibleTabRestoresNow() {
        assertEquals(
            ProcessGoneAction.RESTORE_NOW,
            processGoneAction(ContentProcessGone.KILL, isActive = true, millisSinceLastRestore = null, hasSavedState = true, hasUrl = true),
        )
    }

    @Test
    fun killOfABackgroundTabWaitsUntilItIsShown() {
        assertEquals(
            ProcessGoneAction.RESTORE_WHEN_SHOWN,
            processGoneAction(ContentProcessGone.KILL, isActive = false, millisSinceLastRestore = null, hasSavedState = true, hasUrl = true),
        )
    }

    @Test
    fun aSecondKillInsideThirtySecondsShowsTheBanner() {
        assertEquals(
            ProcessGoneAction.BANNER,
            processGoneAction(ContentProcessGone.KILL, isActive = true, millisSinceLastRestore = 29_999L, hasSavedState = true, hasUrl = true),
        )
        assertEquals(
            ProcessGoneAction.BANNER,
            processGoneAction(ContentProcessGone.KILL, isActive = false, millisSinceLastRestore = 0L, hasSavedState = true, hasUrl = true),
        )
        assertEquals(
            ProcessGoneAction.RESTORE_NOW,
            processGoneAction(ContentProcessGone.KILL, isActive = true, millisSinceLastRestore = 30_000L, hasSavedState = true, hasUrl = true),
        )
    }

    @Test
    fun aCrashNeverRestoresByItself() {
        for (active in listOf(true, false)) {
            assertEquals(
                ProcessGoneAction.BANNER,
                processGoneAction(ContentProcessGone.CRASH, isActive = active, millisSinceLastRestore = null, hasSavedState = true, hasUrl = true),
            )
        }
    }

    @Test
    fun withoutSavedStateTheAddressIsEnoughToRestore() {
        assertEquals(
            ProcessGoneAction.RESTORE_NOW,
            processGoneAction(ContentProcessGone.KILL, isActive = true, millisSinceLastRestore = null, hasSavedState = false, hasUrl = true),
        )
        assertEquals(
            ProcessGoneAction.RESTORE_WHEN_SHOWN,
            processGoneAction(ContentProcessGone.KILL, isActive = false, millisSinceLastRestore = null, hasSavedState = false, hasUrl = true),
        )
    }

    @Test
    fun withNeitherSavedStateNorAddressThereIsNothingToRestore() {
        for (kind in ContentProcessGone.entries) {
            assertEquals(
                ProcessGoneAction.NOTHING,
                processGoneAction(kind, isActive = true, millisSinceLastRestore = null, hasSavedState = false, hasUrl = false),
            )
        }
    }

    @Test
    fun reloadAfterACrashBringsTheLostPageBack() {
        assertEquals(ReloadTarget.LostPage, reloadTarget(loaded.afterContentProcessGone()))
        // A kill waiting for the tab to be shown: the toolbar's Reload does the same as the automatic restore.
        assertEquals(ReloadTarget.LostPage, reloadTarget(loaded.afterContentProcessGone(showBanner = false)))
    }

    @Test
    fun reloadAfterALoadErrorRetriesTheAddressThatFailed() {
        val failed = loaded.afterPageStart("https://broken.example/").afterLoadError("https://broken.example/")
        assertEquals(ReloadTarget.Load("https://broken.example/"), reloadTarget(failed))
    }

    @Test
    fun reloadAfterAnErrorWithoutAWebAddressIsAPlainReload() {
        assertEquals(ReloadTarget.Page, reloadTarget(loaded.afterLoadError(null)))
        assertEquals(ReloadTarget.Page, reloadTarget(loaded))
    }
}
