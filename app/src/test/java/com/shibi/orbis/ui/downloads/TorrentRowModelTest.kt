package com.shibi.orbis.ui.downloads

import com.shibi.orbis.torrent.PauseReason
import com.shibi.orbis.torrent.TorrentEntry
import com.shibi.orbis.torrent.TorrentErrorKind
import com.shibi.orbis.torrent.TorrentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentRowModelTest {
    private fun e(
        status: TorrentStatus,
        reason: PauseReason? = null,
        kind: TorrentErrorKind? = null,
        total: Long = 100,
        done: Long = 25,
        private: Boolean = false,
        eta: Long? = 42,
    ) = TorrentEntry(
        id = "0123456789abcdef0123456789abcdef",
        displayName = "Sintel",
        totalBytes = total,
        doneBytes = done,
        status = status,
        addedAt = 1,
        saveDir = "/x",
        isPrivate = private,
        errorKind = kind,
        pauseReason = reason,
        hasMetadata = total > 0,
        downBps = 2048,
        upBps = 512,
        peers = 7,
        seeds = 2,
        etaSeconds = eta,
    )

    private fun model(entry: TorrentEntry) = TorrentRowModel.from(entry)!!

    @Test
    fun everyStatusMapsToItsStateAndActions() {
        val pauseRemove = setOf(RowAction.PAUSE, RowAction.REMOVE)
        val resumeRemove = setOf(RowAction.RESUME, RowAction.REMOVE)
        val cases =
            listOf(
                e(TorrentStatus.FETCHING_METADATA, total = -1) to (RowState.Fetching to pauseRemove),
                e(TorrentStatus.DOWNLOADING) to (RowState.Downloading to pauseRemove),
                e(TorrentStatus.CHECKING) to (RowState.Checking to pauseRemove),
                e(TorrentStatus.SEEDING) to (RowState.Seeding(ratio = 0f, limit = null) to setOf(RowAction.PAUSE, RowAction.STOP_SEEDING, RowAction.REMOVE)),
                e(TorrentStatus.WAITING_FOR_WIFI) to (RowState.WaitingWifi to pauseRemove),
                e(TorrentStatus.PAUSED, PauseReason.USER) to (RowState.PausedUser to resumeRemove),
                e(TorrentStatus.PAUSED, null) to (RowState.PausedUser to resumeRemove),
                e(TorrentStatus.PAUSED, PauseReason.QUEUED) to (RowState.PausedQueued to resumeRemove),
                e(TorrentStatus.PAUSED, PauseReason.TIMEOUT) to (RowState.PausedTimeout to resumeRemove),
                e(TorrentStatus.PUBLISHING) to (RowState.Publishing to emptySet()),
                e(TorrentStatus.COMPLETED) to (RowState.Completed to setOf(RowAction.OPEN, RowAction.REMOVE)),
            )
        for ((entry, expected) in cases) {
            val m = model(entry)
            assertEquals(entry.status.name, expected.first, m.state)
            assertEquals(entry.status.name, expected.second, m.actions)
        }
        assertNull(TorrentRowModel.from(e(TorrentStatus.REMOVED)))
    }

    @Test
    fun onlyFailuresWithSomethingLeftCanBeRetried() {
        val retry = setOf(RowAction.RETRY, RowAction.REMOVE)
        val expected =
            mapOf(
                TorrentErrorKind.NO_SPACE to retry,
                TorrentErrorKind.FILE_ERROR to retry,
                TorrentErrorKind.ENGINE_ERROR to retry,
                TorrentErrorKind.PUBLISH_FAILED to retry,
                TorrentErrorKind.INVALID_SOURCE to setOf(RowAction.REMOVE),
                TorrentErrorKind.UNSAFE_METADATA to setOf(RowAction.REMOVE),
                TorrentErrorKind.MISSING_DATA to setOf(RowAction.REMOVE),
            )
        assertEquals(TorrentErrorKind.entries.toSet(), expected.keys)
        for ((kind, actions) in expected) {
            val m = model(e(TorrentStatus.FAILED, kind = kind))
            assertEquals(RowState.Failed(kind), m.state)
            assertEquals(kind.name, actions, m.actions)
        }
    }

    @Test
    fun aPrivateTorrentHasNoTitleAndPrintsNoName() {
        val m = model(e(TorrentStatus.DOWNLOADING, private = true))
        assertNull(m.title)
        assertTrue(m.isPrivate)
        assertFalse(m.toString().contains("Sintel"))
        assertEquals("Sintel", model(e(TorrentStatus.DOWNLOADING)).title)
        assertFalse(model(e(TorrentStatus.DOWNLOADING)).toString().contains("Sintel"))
    }

    @Test
    fun percentIsUnknownWithoutATotalAndWhileCheckingOrSaving() {
        assertEquals(25, model(e(TorrentStatus.DOWNLOADING)).percent)
        assertEquals(25, model(e(TorrentStatus.PAUSED, PauseReason.USER)).percent)
        assertEquals(100, model(e(TorrentStatus.COMPLETED)).percent)
        assertNull(model(e(TorrentStatus.DOWNLOADING, total = -1)).percent)
        assertNull(model(e(TorrentStatus.FETCHING_METADATA, total = -1)).percent)
        assertNull(model(e(TorrentStatus.CHECKING)).percent)
        assertNull(model(e(TorrentStatus.PUBLISHING)).percent)
        assertTrue(model(e(TorrentStatus.CHECKING)).indeterminate)
        assertTrue(model(e(TorrentStatus.PUBLISHING)).indeterminate)
        assertTrue(model(e(TorrentStatus.FETCHING_METADATA, total = -1)).indeterminate)
        assertFalse(model(e(TorrentStatus.PAUSED, PauseReason.USER, total = -1)).indeterminate)
        // More bytes than announced never shows more than 100%.
        assertEquals(100, model(e(TorrentStatus.DOWNLOADING, done = 500)).percent)
    }

    @Test
    fun liveNumbersOnlyWhileRunning() {
        val d = model(e(TorrentStatus.DOWNLOADING))
        assertEquals(2048L, d.speedBps)
        assertEquals(7, d.peers)
        assertEquals(2, d.seeds)
        assertEquals(42L, d.etaSeconds)
        assertEquals(512L, model(e(TorrentStatus.SEEDING)).speedBps)
        assertNull(model(e(TorrentStatus.SEEDING)).etaSeconds)
        assertNull(model(e(TorrentStatus.DOWNLOADING, eta = null)).etaSeconds)
        val p = model(e(TorrentStatus.PAUSED, PauseReason.USER))
        assertEquals(0L, p.speedBps)
        assertNull(p.peers)
        assertNull(p.etaSeconds)
        assertEquals(-1L, model(e(TorrentStatus.FETCHING_METADATA, total = -1)).totalBytes)
    }

    @Test
    fun aSeedingRowCarriesItsRatioAndCanStopSeeding() {
        val seeding = e(TorrentStatus.SEEDING, done = 100).copy(ratio = 1.257f)
        val m = TorrentRowModel.from(seeding, seedRatioLimit = 2f)!!
        assertEquals(RowState.Seeding(ratio = 1.257f, limit = 2f), m.state)
        assertTrue(RowAction.STOP_SEEDING in m.actions)
        assertTrue(RowAction.PAUSE in m.actions)
        assertEquals("1.26", TorrentRowModel.ratioText((m.state as RowState.Seeding).ratio))
        assertEquals("2.0", TorrentRowModel.limitText(2f))
        // No limit: the row says plain "Seeding".
        assertNull((model(seeding).state as RowState.Seeding).limit)
        // A broken ratio from the engine never shows as NaN.
        assertEquals(0f, (model(seeding.copy(ratio = Float.NaN)).state as RowState.Seeding).ratio)
        // Only seeding rows offer it.
        assertFalse(RowAction.STOP_SEEDING in model(e(TorrentStatus.DOWNLOADING)).actions)
        assertFalse(RowAction.STOP_SEEDING in model(e(TorrentStatus.COMPLETED)).actions)
    }
}
