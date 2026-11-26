package com.shibi.orbis.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoadOutcomeTrackerTest {
    private val ends = mutableListOf<LoadEnd>()
    private val t = LoadOutcomeTracker { ends += it }

    private fun kinds() = ends.map { if (it.error != null) "error-${it.error.text}" else it.kind.text }

    @Test
    fun aNormalLoad_oneCommittedLine_atThePageStop_endedYes() {
        t.onLoadRequest(redirect = false, nowMs = 100)
        t.onPageStart(110)
        t.onCommit()
        assertEquals(emptyList<LoadEnd>(), ends)
        t.onPageStop(true, 400)
        // Written at once: no report is needed.
        assertEquals(listOf(LoadEnd(LoadKind.COMMITTED, 300, ended = LoadEnded.YES)), ends)
        // Nothing more is written for the same load.
        t.onClosed(9_000)
        assertEquals(1, ends.size)
    }

    @Test
    fun aCommittedLine_isNotWaitingForTheNextLoad() {
        t.onLoadRequest(false, 0)
        t.onPageStart(1)
        t.onCommit()
        t.onPageStop(true, 20)
        assertEquals(listOf("committed"), kinds())
        t.onLoadRequest(false, 5_000)
        assertEquals(listOf("committed"), kinds())
    }

    @Test
    fun aLoadReplacedAfterItsCommit_isCommittedEndedNo_thenTheNewLoadsOwnLine() {
        t.onLoadRequest(false, 0)
        t.onPageStart(1)
        t.onCommit()
        t.onLoadRequest(false, 300)
        assertEquals(listOf(LoadEnd(LoadKind.COMMITTED, 300, ended = LoadEnded.NO)), ends)
        t.onPageStart(310)
        t.onCommit()
        t.onPageStop(true, 500)
        assertEquals(
            listOf(LoadEnd(LoadKind.COMMITTED, 300, ended = LoadEnded.NO), LoadEnd(LoadKind.COMMITTED, 200, ended = LoadEnded.YES)),
            ends,
        )
    }

    @Test
    fun aTabClosedAfterTheCommitBeforeTheStop_isEndedNo_once() {
        t.onLoadRequest(false, 0)
        t.onPageStart(1)
        t.onCommit()
        t.onClosed(250)
        t.onPageStop(true, 260)
        t.onClosed(300)
        assertEquals(listOf(LoadEnd(LoadKind.COMMITTED, 250, ended = LoadEnded.NO)), ends)
    }

    @Test
    fun aKillAfterTheCommitBeforeTheStop_isEndedNo() {
        t.onLoadRequest(false, 0)
        t.onPageStart(1)
        t.onCommit()
        t.onInterrupted(90)
        assertEquals(listOf(LoadEnd(LoadKind.COMMITTED, 90, ended = LoadEnded.NO)), ends)
    }

    @Test
    fun theUsersStopAfterTheCommit_isEndedStopped_andTheLatePageStopAddsNothing() {
        t.onLoadRequest(false, 0)
        t.onPageStart(1)
        t.onCommit()
        t.onUserStop(700)
        t.onPageStop(false, 705)
        t.onPageStop(true, 710)
        assertEquals(listOf(LoadEnd(LoadKind.COMMITTED, 700, ended = LoadEnded.STOPPED)), ends)
    }

    @Test
    fun aFailedPageStopAfterTheCommit_isEndedStopped() {
        t.onLoadRequest(false, 0)
        t.onPageStart(1)
        t.onCommit()
        t.onPageStop(false, 60)
        assertEquals(listOf(LoadEnd(LoadKind.COMMITTED, 60, ended = LoadEnded.STOPPED)), ends)
    }

    @Test
    fun aFailedPageStopBeforeAnyCommit_isStoppedWithNoEnded() {
        t.onLoadRequest(false, 0)
        t.onPageStart(1)
        t.onPageStop(false, 60)
        assertEquals(listOf(LoadEnd(LoadKind.STOPPED, 60)), ends)
    }

    @Test
    fun aRedirectChain_isOneRedirectedLine() {
        t.onLoadRequest(false, 0)
        t.onLoadRequest(true, 10)
        t.onLoadRequest(true, 20)
        t.onPageStart(25)
        // Gecko may report the start again for the target of a redirect: the same load.
        t.onPageStart(30)
        t.onCommit()
        t.onPageStop(true, 90)
        assertEquals(listOf(LoadEnd(LoadKind.REDIRECTED, 90)), ends)
    }

    @Test
    fun aRedirectChainReplacedAfterItsCommit_isRedirectedEndedNo() {
        t.onLoadRequest(false, 0)
        t.onLoadRequest(true, 10)
        t.onPageStart(25)
        t.onCommit()
        t.onLoadRequest(false, 80)
        assertEquals(listOf(LoadEnd(LoadKind.REDIRECTED, 80, ended = LoadEnded.NO)), ends)
    }

    @Test
    fun aLoadReplacedByAnother_isUnfinished_andTheNewOneHasItsOwnLine() {
        t.onLoadRequest(false, 0)
        t.onPageStart(5)
        t.onLoadRequest(false, 300)
        assertEquals(listOf(LoadEnd(LoadKind.UNFINISHED, 300)), ends)
        t.onPageStart(310)
        t.onCommit()
        t.onPageStop(true, 500)
        assertEquals(listOf("unfinished", "committed"), kinds())
    }

    @Test
    fun aPageStartWithNoRequest_replacesTheLoadBeforeIt() {
        t.onLoadRequest(false, 0)
        t.onPageStart(1)
        // Back or Forward: no load request.
        t.onPageStart(200)
        assertEquals(listOf("unfinished"), kinds())
    }

    @Test
    fun aTabClosedMidLoad_isUnfinished_once() {
        t.onLoadRequest(false, 0)
        t.onPageStart(5)
        t.onClosed(250)
        assertEquals(listOf(LoadEnd(LoadKind.UNFINISHED, 250)), ends)
        // A question answered after the close writes nothing.
        t.onRefused(300)
        t.onLoadRequest(false, 400)
        t.onClosed(500)
        assertEquals(1, ends.size)
    }

    @Test
    fun aDownload_isOneLine_andItsLateStopIsIgnored() {
        t.onLoadRequest(false, 0)
        t.onDownload(80)
        t.onPageStop(false, 90)
        assertEquals(listOf(LoadEnd(LoadKind.DOWNLOAD, 80)), ends)
    }

    @Test
    fun anAppHandOff_isOneLine() {
        t.onLoadRequest(false, 0)
        t.onAppHandOff(15)
        t.onPageStop(false, 20)
        assertEquals(listOf(LoadEnd(LoadKind.APP, 15)), ends)
    }

    @Test
    fun aRefusedLoad_isOneLine() {
        t.onLoadRequest(false, 0)
        t.onRefused(3)
        assertEquals(listOf(LoadEnd(LoadKind.REFUSED, 3)), ends)
    }

    @Test
    fun anError_isOneLine_evenWhenTheStopFollows() {
        t.onLoadRequest(false, 0)
        t.onPageStart(5)
        t.onError(ErrorCategory.NETWORK, 60)
        t.onPageStop(false, 61)
        assertEquals(listOf("error-network"), kinds())
        assertEquals(60L, ends.single().ms)
    }

    @Test
    fun theStopOfAnEarlierLoad_doesNotEndTheNewOne() {
        t.onLoadRequest(false, 0)
        t.onPageStart(1)
        t.onError(ErrorCategory.CONTENT, 10)
        t.onLoadRequest(false, 20)
        t.onPageStop(false, 21)
        assertEquals(listOf("error-content"), kinds())
        t.onPageStart(30)
        t.onCommit()
        t.onPageStop(true, 80)
        assertEquals(listOf("error-content", "committed"), kinds())
    }

    @Test
    fun theUserStopping_aSlowLoad_isStopped_once() {
        t.onLoadRequest(false, 0)
        t.onPageStart(5)
        t.onUserStop(700)
        t.onPageStop(false, 705)
        assertEquals(listOf(LoadEnd(LoadKind.STOPPED, 700)), ends)
    }

    @Test
    fun aSameDocumentCommit_isNotALoad() {
        t.onLoadRequest(false, 0)
        t.onCommit()
        t.onLoadRequest(false, 10)
        t.onClosed(20)
        // Only the second request counts, and it never started: unfinished.
        assertEquals(listOf("unfinished"), kinds())
    }

    @Test
    fun aKill_endsTheLoadInFlight_andTheTabLivesOn() {
        t.onLoadRequest(false, 0)
        t.onPageStart(1)
        t.onInterrupted(90)
        assertEquals(listOf("unfinished"), kinds())
        t.onLoadRequest(false, 100)
        t.onPageStart(101)
        t.onCommit()
        t.onPageStop(true, 150)
        assertEquals(listOf("unfinished", "committed"), kinds())
    }

    @Test
    fun popupsAreCountedFromTheLastCommittedLoad() {
        t.onLoadRequest(false, 0)
        t.onPageStart(1)
        t.onCommit()
        assertEquals(1, t.nextPopupLine())
        assertEquals(2, t.nextPopupLine())
        assertEquals(3, t.nextPopupLine())
        t.onLoadRequest(false, 100)
        t.onPageStart(101)
        t.onCommit()
        assertEquals(1, t.nextPopupLine())
    }

    @Test
    fun popupLinesStopAfterTwenty_andACommitStartsTheCountAgain() {
        t.onLoadRequest(false, 0)
        t.onPageStart(1)
        t.onCommit()
        for (n in 1..20) assertEquals(n, t.nextPopupLine())
        assertEquals(20, POPUP_LINES_PER_PAGE)
        assertNull(t.nextPopupLine())
        assertNull(t.nextPopupLine())
        t.onLoadRequest(false, 100)
        t.onPageStart(101)
        t.onCommit()
        assertEquals(1, t.nextPopupLine())
    }

    @Test
    fun everyKindIsReachableAndEachLoadWritesOneLine() {
        // committed, redirected, download, app, refused, stopped, unfinished, error: eight loads, eight lines.
        t.onLoadRequest(false, 0); t.onPageStart(1); t.onCommit(); t.onPageStop(true, 2); t.onEmptyReport(false)
        t.onLoadRequest(false, 10); t.onLoadRequest(true, 11); t.onPageStart(12); t.onCommit(); t.onPageStop(true, 13); t.onEmptyReport(false)
        t.onLoadRequest(false, 20); t.onDownload(21)
        t.onLoadRequest(false, 30); t.onAppHandOff(31)
        t.onLoadRequest(false, 40); t.onRefused(41)
        t.onLoadRequest(false, 50); t.onPageStart(51); t.onUserStop(52)
        t.onLoadRequest(false, 60); t.onPageStart(61); t.onLoadRequest(false, 62)
        t.onPageStart(63); t.onError(ErrorCategory.SECURITY, 64)
        assertEquals(
            listOf("committed", "redirected", "download", "app", "refused", "stopped", "unfinished", "error-security"),
            kinds(),
        )
    }
}
