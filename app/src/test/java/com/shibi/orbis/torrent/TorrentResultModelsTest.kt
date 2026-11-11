package com.shibi.orbis.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun t(
    id: String,
    status: TorrentStatus,
    private: Boolean = false,
    reason: PauseReason? = null,
) = TorrentEntry(id, "Sintel", 10, 5, status, 1, "/x/$id", isPrivate = private, pauseReason = reason)

class TorrentSettledTrackerTest {
    @Test
    fun eachTorrentReportsOnceWhenItSettles() {
        val tracker = TorrentSettledTracker()
        assertTrue(tracker.update(listOf(t("a", TorrentStatus.DOWNLOADING), t("b", TorrentStatus.PUBLISHING))).isEmpty())
        val first = tracker.update(listOf(t("a", TorrentStatus.FAILED), t("b", TorrentStatus.COMPLETED)))
        assertEquals(listOf("a", "b"), first.map { it.id })
        assertTrue(tracker.update(listOf(t("a", TorrentStatus.FAILED), t("b", TorrentStatus.COMPLETED))).isEmpty())
    }

    @Test
    fun theRestoredListAndEntriesThatAppearSettledReportNothing() {
        val tracker = TorrentSettledTracker()
        assertTrue(tracker.update(listOf(t("a", TorrentStatus.COMPLETED), t("b", TorrentStatus.FAILED))).isEmpty())
        // A link refused at once: it was never running.
        assertTrue(tracker.update(listOf(t("a", TorrentStatus.COMPLETED), t("b", TorrentStatus.FAILED), t("c", TorrentStatus.FAILED))).isEmpty())
    }

    @Test
    fun aRetryThatSucceedsReportsAgain() {
        val tracker = TorrentSettledTracker()
        tracker.update(listOf(t("a", TorrentStatus.PUBLISHING)))
        assertEquals(1, tracker.update(listOf(t("a", TorrentStatus.FAILED))).size)
        tracker.update(listOf(t("a", TorrentStatus.PUBLISHING)))
        assertEquals(TorrentStatus.COMPLETED, tracker.update(listOf(t("a", TorrentStatus.COMPLETED))).single().status)
    }
}

class TorrentResultContentTest {
    @Test
    fun aNormalTorrentIsNamed() {
        val done = TorrentResultContent.from(t("a", TorrentStatus.COMPLETED))
        assertEquals(TorrentResultTitle.DONE, done.title)
        assertEquals("Sintel", done.text)
        assertEquals(TorrentResultTitle.FAILED, TorrentResultContent.from(t("a", TorrentStatus.FAILED)).title)
        assertFalse(done.toString().contains("Sintel"))
    }

    @Test
    fun aPrivateTorrentIsNeverNamed() {
        val done = TorrentResultContent.from(t("a", TorrentStatus.COMPLETED, private = true))
        assertEquals(TorrentResultTitle.PRIVATE_DONE, done.title)
        assertNull(done.text)
        val failed = TorrentResultContent.from(t("a", TorrentStatus.FAILED, private = true))
        assertEquals(TorrentResultTitle.PRIVATE_FAILED, failed.title)
        assertNull(failed.text)
    }
}

class TorrentPausedModelTest {
    private val userPaused = t("a", TorrentStatus.PAUSED, reason = PauseReason.USER)

    @Test
    fun oneTorrentResumesByIdSeveralResumeAll() {
        val one = TorrentPausedModel.from(listOf(userPaused), setOf("a"), byTimeout = false)!!
        assertEquals("a", one.resumeId)
        assertFalse(one.byTimeout)
        assertFalse(one.isPrivate)
        val many =
            TorrentPausedModel.from(listOf(userPaused, t("b", TorrentStatus.PAUSED, reason = PauseReason.TIMEOUT)), setOf("a", "b"), byTimeout = true)!!
        assertEquals(2, many.count)
        assertNull(many.resumeId)
        assertTrue(many.byTimeout)
    }

    @Test
    fun onlyTorrentsStillPausedByTheUserOrTimeoutCount() {
        val entries =
            listOf(
                userPaused,
                t("q", TorrentStatus.PAUSED, reason = PauseReason.QUEUED),
                t("r", TorrentStatus.DOWNLOADING),
                t("other", TorrentStatus.PAUSED, reason = PauseReason.USER),
            )
        assertEquals(listOf("a"), TorrentPausedModel.from(entries, setOf("a", "q", "r", "gone"), false)!!.ids)
        // Resumed or removed: nothing left to show.
        assertNull(TorrentPausedModel.from(listOf(t("a", TorrentStatus.DOWNLOADING)), setOf("a"), false))
        assertNull(TorrentPausedModel.from(emptyList(), setOf("a"), false))
    }

    @Test
    fun anyPrivateTorrentMakesItPrivate() {
        val m = TorrentPausedModel.from(listOf(userPaused, t("p", TorrentStatus.PAUSED, private = true, reason = PauseReason.USER)), setOf("a", "p"), false)!!
        assertTrue(m.isPrivate)
        assertFalse(m.toString().contains("Sintel"))
    }
}
