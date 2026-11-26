package com.shibi.orbis.diag

import org.junit.Assert.assertEquals
import org.junit.Test

class LoadOutcomeTrackerTest {
    private val ends = mutableListOf<LoadEnd>()
    private val t = LoadOutcomeTracker { ends += it }

    private fun kinds() = ends.map { if (it.error != null) "error-${it.error.text}" else it.kind.text }

    @Test
    fun aNormalLoad_oneCommittedLine_afterTheEmptyReport() {
        t.onLoadRequest(redirect = false, nowMs = 100)
        t.onPageStart(110)
        t.onCommit()
        t.onPageStop(true, 400)
        // Held for the page's own report.
        assertEquals(emptyList<LoadEnd>(), ends)
        t.onEmptyReport(false)
        assertEquals(listOf(LoadEnd(LoadKind.COMMITTED, 300)), ends)
        // Nothing more is written for the same load.
        t.onClosed(9_000)
        assertEquals(1, ends.size)
    }

    @Test
    fun anEmptyPage_saysSo() {
        t.onLoadRequest(false, 0)
        t.onPageStart(1)
        t.onCommit()
        t.onPageStop(true, 50)
        t.onEmptyReport(true)
        assertEquals(listOf(LoadEnd(LoadKind.COMMITTED, 50, empty = true)), ends)
    }

    @Test
    fun theReportBeforeThePageStopped_isKept() {
        t.onLoadRequest(false, 0)
        t.onPageStart(1)
        t.onCommit()
        t.onEmptyReport(true)
        assertEquals(emptyList<LoadEnd>(), ends)
        t.onPageStop(true, 70)
        assertEquals(listOf(LoadEnd(LoadKind.COMMITTED, 70, empty = true)), ends)
    }

    @Test
    fun aCommittedLine_isWrittenWhenTheNextLoadStartsEvenWithoutAReport() {
        t.onLoadRequest(false, 0)
        t.onPageStart(1)
        t.onCommit()
        t.onPageStop(true, 20)
        t.onLoadRequest(false, 5_000)
        assertEquals(listOf("committed"), kinds())
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
        t.onEmptyReport(false)
        assertEquals(listOf(LoadEnd(LoadKind.REDIRECTED, 90)), ends)
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
        t.onEmptyReport(false)
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
        t.onEmptyReport(false)
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
        t.onEmptyReport(false)
        assertEquals(listOf("unfinished", "committed"), kinds())
    }

    @Test
    fun popupsAreCountedFromTheLastCommittedLoad() {
        t.onLoadRequest(false, 0)
        t.onPageStart(1)
        t.onCommit()
        assertEquals(1, t.nextPopupCount())
        assertEquals(2, t.nextPopupCount())
        assertEquals(3, t.nextPopupCount())
        t.onLoadRequest(false, 100)
        t.onPageStart(101)
        t.onCommit()
        assertEquals(1, t.nextPopupCount())
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
