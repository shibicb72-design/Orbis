package com.shibi.orbis.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VisitTrackerTest {
    private var clock = 10_000L
    private val tracker = VisitTracker(now = { clock })

    private val a = "https://a.example/"
    private val b = "https://b.example/page"

    /** The callbacks of one ordinary page load, as the engine delivers them. */
    private fun load(url: String, title: String? = null, success: Boolean = true): Visit? {
        tracker.onPageStart()
        assertNull(tracker.onLocationChange(url))
        if (title != null) assertNull(tracker.onTitleChange(title))
        return tracker.onPageStop(success)
    }

    @Test
    fun aNormalLoadIsRecordedOnStopWithItsTitle() {
        tracker.onPageStart()
        assertNull(tracker.onLocationChange(a))
        assertNull(tracker.onTitleChange("Example A"))
        assertEquals(Visit(a, "Example A"), tracker.onPageStop(true))
    }

    @Test
    fun aLoadWithoutATitleHasAnEmptyTitleAndALateTitleUpdatesIt() {
        assertEquals(Visit(a, ""), load(a))
        assertEquals(TitleUpdate(a, "Arrived late"), tracker.onTitleChange("Arrived late"))
    }

    @Test
    fun locationBeforePageStartStillGivesExactlyOneVisit() {
        // Reverse order: the location arrives first, the load announces itself afterwards.
        val first = tracker.onLocationChange(a)
        tracker.onPageStart()
        assertNull(tracker.onTitleChange("Title"))
        val second = tracker.onPageStop(true)
        assertEquals(listOf(Visit(a, "")), listOfNotNull(first, second))
        // The title that arrives after the load still reaches the row.
        assertEquals(TitleUpdate(a, "Later"), tracker.onTitleChange("Later"))
    }

    @Test
    fun locationAfterPageStartGivesExactlyOneVisit() {
        tracker.onPageStart()
        val first = tracker.onLocationChange(a)
        val second = tracker.onPageStop(true)
        assertEquals(listOf(Visit(a, "")), listOfNotNull(first, second))
    }

    @Test
    fun aFailedUrlReportedAsALocationIsNeverAVisit() {
        assertEquals(Visit(a, "A"), load(a, "A"))
        // The location of the failure can arrive during the load or only after its stop.
        tracker.onPageStart()
        tracker.onLoadError()
        assertNull(tracker.onLocationChange(b))
        assertNull(tracker.onPageStop(false))
        clock += 60_000
        tracker.onPageStart()
        tracker.onLoadError()
        assertNull(tracker.onPageStop(false))
        assertNull(tracker.onLocationChange(b))
        // The next load clears it.
        assertEquals(Visit(b, ""), load(b))
    }

    @Test
    fun aFailedLoadIsNotRecordedAndTheOldPageStays() {
        assertEquals(Visit(a, "A"), load(a, "A"))
        tracker.onPageStart()
        tracker.onLoadError()
        assertNull(tracker.onPageStop(false))
        // Even a stop reported as success after an error is not a visit.
        tracker.onPageStart()
        tracker.onLoadError()
        assertNull(tracker.onPageStop(true))
        // The next good load is recorded normally.
        assertEquals(Visit(b, ""), load(b))
    }

    @Test
    fun aFailedFirstLoadIsNotRecorded() {
        tracker.onPageStart()
        tracker.onLoadError()
        assertNull(tracker.onLocationChange(a))
        assertNull(tracker.onPageStop(false))
    }

    @Test
    fun aStoppedLoadIsNotRecorded() {
        tracker.onPageStart()
        assertNull(tracker.onLocationChange(a))
        assertNull(tracker.onPageStop(false))
        // A later success for the same URL is still a first visit.
        assertEquals(Visit(a, ""), load(a))
    }

    @Test
    fun aStopWithoutAStartIsIgnored() {
        assertNull(tracker.onPageStop(true))
        assertNull(tracker.onPageStop(false))
        tracker.onLocationChange(a)
        assertNull(tracker.onPageStop(true))
    }

    @Test
    fun aReloadOfTheSameUrlAddsNothing() {
        assertEquals(Visit(a, "A"), load(a, "A"))
        assertNull(load(a, "A"))
        assertNull(load(a))
        // Going elsewhere and coming back is a visit again.
        assertEquals(Visit(b, ""), load(b))
        assertEquals(Visit(a, ""), load(a))
    }

    @Test
    fun nonWebLocationsAreNeverRecorded() {
        assertNull(load("about:blank"))
        assertNull(load("file:///sdcard/x.html"))
        assertNull(load("javascript:alert(1)"))
        assertNull(tracker.onLocationChange("about:blank"))
        assertNull(tracker.onLocationChange(null))
        tracker.onPageStart()
        assertNull(tracker.onLocationChange(null))
        assertNull(tracker.onPageStop(true))
    }

    @Test
    fun theTitleOfAnEarlierPageIsNeverInherited() {
        assertEquals(Visit(a, "Title of A"), load(a, "Title of A"))
        // The next page has no title at all: the stop reports an empty one.
        assertEquals(Visit(b, ""), load(b))
        // A title seen before the next load started does not leak into it.
        tracker.onTitleChange("stale")
        assertEquals(Visit(a, ""), load(a))
    }

    @Test
    fun aTitleChangeWhileLoadingNeverMakesAnUpdate() {
        assertEquals(Visit(a, "A"), load(a, "A"))
        tracker.onPageStart()
        assertNull(tracker.onTitleChange("Next page title"))
        assertNull(tracker.onLocationChange(b))
        assertEquals(Visit(b, "Next page title"), tracker.onPageStop(true))
    }

    @Test
    fun blankLateTitlesAreIgnored() {
        load(a)
        assertNull(tracker.onTitleChange(null))
        assertNull(tracker.onTitleChange("   "))
        assertNull(tracker.onTitleChange(""))
    }

    @Test
    fun aLateTitleBeforeAnyVisitHasNothingToUpdate() {
        assertNull(tracker.onTitleChange("Hello"))
    }

    // ---- same-document navigation ------------------------------------------------------------------------------

    @Test
    fun pushStateOutsideALoadIsRecordedWithAnEmptyTitle() {
        assertEquals(Visit(a, "A"), load(a, "A"))
        clock += 5_000
        assertEquals(Visit("https://a.example/#/inbox", ""), tracker.onLocationChange("https://a.example/#/inbox"))
        // The old page's title is not carried over, and a new title reaches the new row only.
        assertEquals(TitleUpdate("https://a.example/#/inbox", "Inbox"), tracker.onTitleChange("Inbox"))
    }

    @Test
    fun sameDocumentVisitsAreRateLimited() {
        load(a)
        clock += 5_000
        assertEquals(Visit("https://a.example/#1", ""), tracker.onLocationChange("https://a.example/#1"))
        clock += 1_999
        assertNull(tracker.onLocationChange("https://a.example/#2"))
        clock += 1
        assertEquals(Visit("https://a.example/#3", ""), tracker.onLocationChange("https://a.example/#3"))
    }

    @Test
    fun aRateLimitedChangeLeavesTheLastRecordedUntouched() {
        load(a)
        clock += 5_000
        tracker.onLocationChange("https://a.example/#1")
        clock += 100
        assertNull(tracker.onLocationChange("https://a.example/#2"))
        // Going back to the recorded URL is "the same as last", so lastRecorded is still #1.
        clock += 100
        assertNull(tracker.onLocationChange("https://a.example/#1"))
        // A late title belongs to the page on screen (#9), which is not the last recorded one while limited.
        clock += 100
        assertNull(tracker.onLocationChange("https://a.example/#9"))
        assertNull(tracker.onTitleChange("Title of #9"))
        clock += 5_000
        assertEquals(Visit("https://a.example/#9", ""), tracker.onLocationChange("https://a.example/#9"))
    }

    @Test
    fun theSameUrlAgainOutsideALoadIsNotRecorded() {
        load(a)
        clock += 5_000
        assertNull(tracker.onLocationChange(a))
    }

    @Test
    fun aPageLoadIsNotRateLimitedByEarlierSameDocumentVisits() {
        load(a)
        clock += 5_000
        tracker.onLocationChange("https://a.example/#1")
        clock += 10
        assertEquals(Visit(b, ""), load(b))
    }

    // ---- restored tabs -----------------------------------------------------------------------------------------

    @Test
    fun theRestoredPageIsNotAVisitAndAReloadOfItAddsNothing() {
        tracker.suppress(a)
        assertNull(load(a, "A"))
        assertNull(load(a, "A"))
        assertEquals(Visit(b, ""), load(b))
        // The one-shot is gone: returning to the restored URL is a visit.
        assertEquals(Visit(a, ""), load(a))
    }

    @Test
    fun navigationAfterTheRestoredPageIsRecorded() {
        tracker.suppress(a)
        assertNull(load(a))
        assertEquals(Visit(b, "B"), load(b, "B"))
    }

    @Test
    fun aDifferentFirstUrlIsRecordedAndClearsTheSuppression() {
        tracker.suppress(a)
        assertEquals(Visit(b, ""), load(b))
        assertEquals(Visit(a, ""), load(a))
    }

    @Test
    fun aRestoredPageThatReportsNoLoadIsConsumedToo() {
        tracker.suppress(a)
        assertNull(tracker.onLocationChange(a))
        assertNull(load(a))
        assertEquals(Visit(b, ""), load(b))
    }

    @Test
    fun aFailedLoadOfTheRestoredUrlKeepsTheSuppressionForTheNextTry() {
        tracker.suppress(a)
        tracker.onPageStart()
        tracker.onLoadError()
        assertNull(tracker.onPageStop(false))
        assertNull(load(a))
    }

    @Test
    fun suppressingNullOrANonWebUrlDoesNothing() {
        tracker.suppress(null)
        tracker.suppress("about:blank")
        assertEquals(Visit(a, ""), load(a))
    }
}
