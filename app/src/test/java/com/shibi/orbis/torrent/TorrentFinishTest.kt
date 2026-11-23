package com.shibi.orbis.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentFinishTest {
    // ---- F2: every real file wanted ----

    @Test
    fun noPaddingAndEveryFileWantedIsAllWanted() {
        assertTrue(TorrentFinish.allRealFilesWanted(listOf(true, true, true), listOf(false, false, false)))
    }

    @Test
    fun oneRealFileNotWantedIsNotAllWanted() {
        assertFalse(TorrentFinish.allRealFilesWanted(listOf(true, false, true), listOf(false, false, false)))
    }

    @Test
    fun paddingFilesNotWantedAndEveryRealFileWantedIsAllWanted() {
        // a.bin, padding, b.bin, padding: libtorrent keeps both padding files at "do not download".
        assertTrue(TorrentFinish.allRealFilesWanted(listOf(true, false, true, false), listOf(false, true, false, true)))
    }

    @Test
    fun aPaddedTorrentBeforeApprovalIsNotAllWanted() {
        // Added with nothing wanted: the real files are not wanted either.
        assertFalse(TorrentFinish.allRealFilesWanted(listOf(false, false, false), listOf(false, true, false)))
    }

    @Test
    fun onlyPaddingFilesFollowsTheRuleForAnEmptyTorrent() {
        // No file list at all counts as all wanted (completion is then the engine's isComplete alone); a list of
        // padding files only is the same.
        assertTrue(TorrentFinish.allRealFilesWanted(emptyList(), emptyList()))
        assertEquals(
            TorrentFinish.allRealFilesWanted(emptyList(), emptyList()),
            TorrentFinish.allRealFilesWanted(listOf(false, false), listOf(true, true)),
        )
    }

    @Test
    fun aFileWithoutAPaddingFlagIsARealFile() {
        assertFalse(TorrentFinish.allRealFilesWanted(listOf(true, false), listOf(false)))
    }

    // ---- F3: the tick ----

    private val complete = TorrentFinish.TickFacts(running = true, checking = false, finishedOrSeeding = true, complete = true)
    private val downloading = TorrentFinish.TickFacts(running = true, checking = false, finishedOrSeeding = false, complete = false)

    @Test
    fun theTickNeverReportsATorrentThatIsNotApproved() {
        val r = FinishReports()
        // Nothing wanted yet: libtorrent calls it finished, and with no byte wanted nothing is missing either.
        repeat(3) { assertFalse(r.tick(1, complete)) }
        assertFalse(r.tick(1, complete.copy(complete = false)))
        assertFalse(r.tick(1, complete.copy(finishedOrSeeding = false)))
    }

    @Test
    fun theTickDoesNotReportAnApprovedTorrentStillDownloading() {
        val r = FinishReports()
        r.approve(1)
        assertFalse(r.tick(1, downloading))
    }

    @Test
    fun theTickReportsAnApprovedCompleteTorrentOnce() {
        val r = FinishReports()
        r.approve(1)
        assertTrue(r.tick(1, complete))
        assertFalse("not again at the next tick", r.tick(1, complete))
        assertFalse(r.tick(1, complete))
    }

    @Test
    fun oneSignalAloneIsNotEnough() {
        val r = FinishReports()
        r.approve(1)
        // Just approved: libtorrent's state is still FINISHED from the nothing-wanted phase, no byte is wanted yet.
        assertFalse(r.tick(1, downloading.copy(finishedOrSeeding = true)))
        // Every wanted byte counted, the state one step behind: the next tick reports it.
        assertFalse(r.tick(1, downloading.copy(complete = true)))
        assertTrue(r.tick(1, complete))
    }

    @Test
    fun theTickWaitsForAPausedOrCheckingTorrent() {
        val r = FinishReports()
        r.approve(1)
        assertFalse(r.tick(1, complete.copy(running = false)))
        assertFalse(r.tick(1, complete.copy(checking = true)))
        // Resumed: reported then, once.
        assertTrue(r.tick(1, complete))
        assertFalse(r.tick(1, complete))
    }

    @Test
    fun removedAndAddedAgainItMayBeReportedOnceMore() {
        val r = FinishReports()
        r.approve(1)
        assertTrue(r.tick(1, complete))
        r.forget(1)
        // Back in the session, not approved yet: still never.
        assertFalse(r.tick(1, complete))
        r.approve(1)
        assertTrue(r.tick(1, complete))
        assertFalse(r.tick(1, complete))
        r.clear()
        r.approve(1)
        assertTrue("a new session starts over", r.tick(1, complete))
    }

    @Test
    fun eachTorrentIsReportedOnceWhicheverRouteComesFirst() {
        val r = FinishReports()
        r.approve(1)
        r.approve(2)
        // Torrent 1: the alert first, then the tick finds it reported.
        assertTrue(r.report(1))
        assertFalse(r.tick(1, complete))
        assertFalse(r.report(1))
        // Torrent 2: the tick first, then a late alert finds it reported.
        assertTrue(r.tick(2, complete))
        assertFalse(r.report(2))
    }

    /**
     * Shibi's error log of 2026-11-22 (torrent 1, files=2): "finished alert ignored 1" at the session start (nothing
     * wanted yet), downloading, then at the end "finished alert ignored 1" and SEEDING with no finish, until a pause
     * and resume finished it by "complete at approval". With the fix the end goes differently.
     */
    @Test
    fun thePhoneLogSequenceNowFinishesByItself() {
        val r = FinishReports()
        val pad = listOf(false, true, false)
        // Session start: libtorrent's alert for a torrent with nothing wanted. Ignored, as before.
        assertFalse(TorrentFinish.allRealFilesWanted(listOf(false, false, false), pad))
        assertFalse(r.tick(1, complete))
        r.approve(1)
        // Downloading: nothing to report.
        assertFalse(r.tick(1, downloading))
        // The last piece: libtorrent keeps the padding file unwanted. The alert now counts as all wanted...
        assertTrue(TorrentFinish.allRealFilesWanted(listOf(true, false, true), pad))
        assertTrue(r.report(1))
        // ...and the tick, which would have caught it otherwise, does not report it twice.
        assertFalse(r.tick(1, complete))
    }

    @Test
    fun withoutTheAlertTheTickCatchesTheStuckTorrent() {
        val r = FinishReports()
        r.approve(1)
        assertFalse(r.tick(1, downloading))
        // The alert was lost or ignored: SEEDING, every byte on disk, nothing reported yet.
        assertTrue(r.tick(1, complete))
    }
}
