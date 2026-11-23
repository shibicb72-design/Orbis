package com.shibi.orbis.ui.browser

import com.shibi.orbis.engine.SessionState
import com.shibi.orbis.engine.afterLoadError
import com.shibi.orbis.engine.afterLocationChange
import com.shibi.orbis.engine.afterNavigationRequested
import com.shibi.orbis.engine.afterPageStart
import com.shibi.orbis.engine.afterPageStop
import com.shibi.orbis.engine.afterProgressChange
import com.shibi.orbis.engine.afterUserEndedLoad
import com.shibi.orbis.engine.afterContentProcessGone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The slow-load decision, driven through the same state transitions the engine applies. */
class SlowLoadNoticeTest {
    private val t0 = 1_000_000L
    private val url = "https://slow.example/article"
    private val old = SessionState(url = "https://before.example/", progress = 100)

    /** A load that started at [t0] and committed its address (headers arrived), progress 15. */
    private fun committedLoad(): SessionState =
        old.afterPageStart(url, t0).afterProgressChange(15, t0).afterLocationChange(url)

    private fun decide(
        state: SessionState,
        now: Long,
        selected: Boolean = true,
        inFront: Boolean = true,
        locked: Boolean = false,
        pip: Boolean = false,
        shown: Set<SlowLoadKind> = emptySet(),
    ): SlowLoadKind? = slowLoadNotice(slowLoadSnapshot(state, now, selected, inFront, locked, pip, shown))

    private fun unfinished(): SessionState = committedLoad().afterPageStop(success = false, now = t0 + 10_000)

    private val stopAt = t0 + 10_000
    private val settled = stopAt + UNFINISHED_SETTLE_MILLIS

    // ---- Notice A ----

    @Test
    fun notTakingLongAt29Seconds() = assertNull(decide(committedLoad(), t0 + 29_999))

    @Test
    fun takingLongAt30Seconds() = assertEquals(SlowLoadKind.TAKING_LONG, decide(committedLoad(), t0 + 30_000))

    @Test
    fun takingLongAlsoBeforeTheAddressCommitted() =
        assertEquals(SlowLoadKind.TAKING_LONG, decide(old.afterPageStart(url, t0), t0 + 30_000))

    @Test
    fun progressThatMovedAt29SecondsRestartsTheClock() {
        val moved = committedLoad().afterProgressChange(55, t0 + 29_000)
        assertNull(decide(moved, t0 + 30_000))
        assertNull(decide(moved, t0 + 58_999))
        assertEquals(SlowLoadKind.TAKING_LONG, decide(moved, t0 + 59_000))
    }

    @Test
    fun theSameProgressValueAgainDoesNotRestartTheClock() {
        val same = committedLoad().afterProgressChange(15, t0 + 29_000)
        assertEquals(SlowLoadKind.TAKING_LONG, decide(same, t0 + 30_000))
    }

    @Test
    fun takingLongOnlyOncePerLoad() {
        assertNull(decide(committedLoad(), t0 + 90_000, shown = setOf(SlowLoadKind.TAKING_LONG)))
    }

    @Test
    fun aNewLoadMayShowItAgain() {
        // The UI keys "shown" by load; a new page start is a new load with nothing shown and its own clock.
        val next = committedLoad().afterPageStart(url, t0 + 40_000)
        assertNull(decide(next, t0 + 69_999))
        assertEquals(SlowLoadKind.TAKING_LONG, decide(next, t0 + 70_000))
    }

    @Test
    fun neverAt100() = assertNull(decide(committedLoad().afterProgressChange(100, t0), t0 + 60_000))

    @Test
    fun aRedirectBackTo15AfterA100CountsAgain() {
        // Gecko's progress tracker sends 100 and then 15 again on a redirect within the same load.
        val redirected = committedLoad().afterProgressChange(100, t0 + 1_000).afterProgressChange(15, t0 + 1_000)
        assertEquals(SlowLoadKind.TAKING_LONG, decide(redirected, t0 + 31_000))
    }

    @Test
    fun takingLongNeverWhileTheErrorPageShows() {
        val failed = committedLoad().copy(isLoading = true, loadFailed = true)
        assertNull(decide(failed, t0 + 60_000))
    }

    @Test
    fun takingLongNeverWhileTheHttpsOnlyPageShows() {
        val blocked = committedLoad().copy(isLoading = true, httpsOnlyBlocked = true)
        assertNull(decide(blocked, t0 + 60_000))
    }

    @Test
    fun takingLongNeverAfterTheLoadEnded() {
        assertNull(decide(committedLoad().afterPageStop(success = true, now = t0 + 40_000), t0 + 60_000))
    }

    // ---- Notice B ----

    @Test
    fun didNotFinishAfterAnUnfinishedStop() = assertEquals(SlowLoadKind.DID_NOT_FINISH, decide(unfinished(), settled))

    @Test
    fun didNotFinishWaitsForTheSettleTime() = assertNull(decide(unfinished(), settled - 1))

    @Test
    fun didNotFinishAlsoAfterTakingLongWasShown() =
        assertEquals(SlowLoadKind.DID_NOT_FINISH, decide(unfinished(), settled, shown = setOf(SlowLoadKind.TAKING_LONG)))

    @Test
    fun didNotFinishOnlyOncePerLoad() = assertNull(decide(unfinished(), settled + 60_000, shown = setOf(SlowLoadKind.DID_NOT_FINISH)))

    @Test
    fun neverAfterALoadThatEndedWell() = assertNull(decide(committedLoad().afterPageStop(success = true, now = stopAt), settled))

    @Test
    fun didNotFinishNeverWhileTheErrorPageShows() {
        // Gecko may report the error and then the stop, or the stop and then the error.
        assertNull(decide(committedLoad().afterLoadError(url).afterPageStop(success = false, now = stopAt), settled))
        assertNull(decide(unfinished().afterLoadError(url), settled))
    }

    @Test
    fun didNotFinishNeverForTheHttpsOnlyPage() {
        assertNull(decide(unfinished().afterLoadError(url).copy(httpsOnlyBlocked = true), settled))
    }

    @Test
    fun neverAfterTheContentProcessWentAway() = assertNull(decide(unfinished().afterContentProcessGone(showBanner = true), settled))

    @Test
    fun neverAfterTheUserPressedStop() =
        assertNull(decide(committedLoad().afterUserEndedLoad().afterPageStop(success = false, now = stopAt), settled))

    @Test
    fun neverAfterTheUserPressedStopOnceItHadStopped() = assertNull(decide(unfinished().afterUserEndedLoad(), settled))

    @Test
    fun neverAfterReloadBackOrForward() {
        // GeckoBrowserSession.reload, goBack and goForward all mark the load before Gecko is told.
        assertNull(decide(committedLoad().afterUserEndedLoad().afterPageStop(false, stopAt), settled))
    }

    @Test
    fun neverAfterANewAddressWasTyped() =
        assertNull(decide(committedLoad().afterNavigationRequested().afterPageStop(false, stopAt), settled))

    @Test
    fun neverForADownload() {
        // A link that becomes a download: the page start, then a stop that is not NS_OK, and no address committed.
        assertNull(decide(old.afterPageStart(url, t0).afterProgressChange(15, t0).afterPageStop(false, stopAt), settled))
    }

    @Test
    fun neverForALinkAnotherAppOpensOrANoContentAnswer() {
        // A redirect to an app's scheme or a 204 ends the same way: no new address.
        assertNull(decide(old.afterPageStart(url, t0).afterPageStop(false, stopAt), settled + 60_000))
    }

    @Test
    fun aCommitWhileNothingLoadsDoesNotCount() {
        // A same-page address change after the stop is not this load's commit.
        val ended = old.afterPageStart(url, t0).afterPageStop(false, stopAt)
        assertNull(decide(ended.afterLocationChange("$url#part"), settled))
    }

    @Test
    fun neverOnceANewerLoadStarted() {
        val newer = unfinished().afterPageStart("https://next.example/", stopAt + 500)
        assertNull(decide(newer, settled))
        assertNotEquals(SlowLoadKind.DID_NOT_FINISH, decide(newer, settled + 60_000))
    }

    // ---- Where it may show ----

    @Test
    fun neverForATabThatIsNotSelected() {
        assertNull(decide(committedLoad(), t0 + 60_000, selected = false))
        assertNull(decide(unfinished(), settled, selected = false))
    }

    @Test
    fun neverWhileOrbisIsNotInFront() {
        assertNull(decide(committedLoad(), t0 + 60_000, inFront = false))
        assertNull(decide(unfinished(), settled, inFront = false))
    }

    @Test
    fun neverOverALockedPrivatePage() {
        assertNull(decide(committedLoad(), t0 + 60_000, locked = true))
        assertNull(decide(unfinished(), settled, locked = true))
    }

    @Test
    fun neverInPictureInPicture() {
        assertNull(decide(committedLoad(), t0 + 60_000, pip = true))
        assertNull(decide(unfinished(), settled, pip = true))
    }

    // ---- Staying up, rechecking ----

    private fun snap(state: SessionState, now: Long, locked: Boolean = false, pip: Boolean = false, inFront: Boolean = true) =
        slowLoadSnapshot(state, now, true, inFront, locked, pip, emptySet())

    @Test
    fun takingLongStaysWhileTheLoadRunsAndGoesWhenItEnds() {
        assertTrue(slowLoadNoticeStillApplies(SlowLoadKind.TAKING_LONG, snap(committedLoad().afterProgressChange(55, t0 + 31_000), t0 + 31_000)))
        assertFalse(slowLoadNoticeStillApplies(SlowLoadKind.TAKING_LONG, snap(committedLoad().afterPageStop(true, t0 + 40_000), t0 + 40_000)))
        assertFalse(slowLoadNoticeStillApplies(SlowLoadKind.TAKING_LONG, snap(unfinished(), settled)))
    }

    @Test
    fun didNotFinishGoesWhenTheUserReloadsOrANewLoadStarts() {
        assertTrue(slowLoadNoticeStillApplies(SlowLoadKind.DID_NOT_FINISH, snap(unfinished(), settled)))
        assertFalse(slowLoadNoticeStillApplies(SlowLoadKind.DID_NOT_FINISH, snap(unfinished().afterUserEndedLoad(), settled)))
        assertFalse(slowLoadNoticeStillApplies(SlowLoadKind.DID_NOT_FINISH, snap(unfinished().afterPageStart(url, settled), settled)))
    }

    @Test
    fun bothGoInPictureInPictureAndUnderTheLockButNotInTheBackground() {
        val running = committedLoad()
        assertFalse(slowLoadNoticeStillApplies(SlowLoadKind.TAKING_LONG, snap(running, t0 + 31_000, pip = true)))
        assertFalse(slowLoadNoticeStillApplies(SlowLoadKind.TAKING_LONG, snap(running, t0 + 31_000, locked = true)))
        assertTrue(slowLoadNoticeStillApplies(SlowLoadKind.TAKING_LONG, snap(running, t0 + 31_000, inFront = false)))
    }

    @Test
    fun recheckTimes() {
        assertEquals(10_000L, slowLoadRecheckIn(snap(committedLoad(), t0 + 20_000)))
        assertEquals(500L, slowLoadRecheckIn(snap(unfinished(), stopAt + 1_500)))
        assertNull(slowLoadRecheckIn(snap(committedLoad().afterPageStop(true, stopAt), stopAt)))
        assertNull(slowLoadRecheckIn(snap(committedLoad(), t0 + 20_000, pip = true)))
    }
}
