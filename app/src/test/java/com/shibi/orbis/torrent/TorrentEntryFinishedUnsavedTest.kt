package com.shibi.orbis.torrent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentEntryFinishedUnsavedTest {
    private fun failed(kind: TorrentErrorKind, done: Long, total: Long = 10) =
        TorrentEntry("a", "Sintel", total, done, TorrentStatus.FAILED, 1, "/x/a", errorKind = kind, hasMetadata = true)

    @Test
    fun aFinishedRowRefusedAsV2IsFinishedUnsaved() {
        assertTrue(failed(TorrentErrorKind.UNSUPPORTED_V2, done = 10).finishedUnsaved)
    }

    @Test
    fun anUnfinishedRowRefusedAsV2IsNot() {
        assertFalse(failed(TorrentErrorKind.UNSUPPORTED_V2, done = 5).finishedUnsaved)
    }

    @Test
    fun aFinishedRowWithAnotherFailureIsNot() {
        assertFalse(failed(TorrentErrorKind.ENGINE_ERROR, done = 10).finishedUnsaved)
        assertFalse(failed(TorrentErrorKind.PUBLISH_FAILED, done = 10).finishedUnsaved)
    }
}
