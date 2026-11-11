package com.shibi.orbis.ui.browser

import com.shibi.orbis.engine.IncomingDownload
import com.shibi.orbis.torrent.IntakeRefusal
import com.shibi.orbis.torrent.IntakeRequest
import com.shibi.orbis.torrent.TorrentPreview
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentConfirmModelTest {
    private val magnet = "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel"

    private class Response(override val contentLength: Long) : IncomingDownload {
        override val url = "https://example.com/a.torrent"
        override val contentDisposition: String? = null
        override val contentType: String? = "application/x-bittorrent"
        override fun openBody(): InputStream? = null
        override fun discard() = Unit
    }

    @Test
    fun aMagnetShowsItsNameAndIsReadyAtOnce() {
        val m = TorrentConfirmModel.of(IntakeRequest.Magnet(magnet, isPrivate = false), TorrentBody.Reading, ipNoteSeen = false)
        assertEquals("Sintel", m.nameText)
        assertEquals(TorrentUnnamed.MAGNET_LINK, m.unnamed)
        assertNull(m.sizeBytes)
        assertNull(m.fileCount)
        assertTrue(m.downloadEnabled)
        assertTrue(m.showFirstUseNote)
        assertFalse(m.isPrivate)
        assertNull(m.error)
    }

    @Test
    fun aBadMagnetCannotBeDownloaded() {
        val m = TorrentConfirmModel.of(IntakeRequest.Magnet("magnet:?dn=x", isPrivate = true), TorrentBody.Reading, ipNoteSeen = true)
        assertFalse(m.downloadEnabled)
        assertEquals(IntakeRefusal.BAD_MAGNET, m.error)
        assertNull(m.nameText)
        assertTrue(m.isPrivate)
        assertFalse(m.showFirstUseNote)
    }

    @Test
    fun aResponseIsUnknownUntilItsBytesAreIn() {
        val request = IntakeRequest.TorrentResponse(Response(contentLength = 2048), isPrivate = false)
        val reading = TorrentConfirmModel.of(request, TorrentBody.Reading, ipNoteSeen = false)
        assertNull(reading.nameText)
        assertEquals(TorrentUnnamed.TORRENT_FILE, reading.unnamed)
        assertEquals(2048L, reading.sizeBytes)
        assertTrue(reading.reading)
        assertFalse(reading.downloadEnabled)

        val parsed = TorrentConfirmModel.of(request, TorrentBody.Ready(ByteArray(1), TorrentPreview.Preview("Sintel", 1_000L, 3)), ipNoteSeen = false)
        assertEquals("Sintel", parsed.nameText)
        assertEquals(1_000L, parsed.sizeBytes)
        assertEquals(3, parsed.fileCount)
        assertFalse(parsed.reading)
        assertTrue(parsed.downloadEnabled)

        // The preview reader refused the file: still downloadable (the engine decides), shown as unknown.
        val unknown = TorrentConfirmModel.of(request, TorrentBody.Ready(ByteArray(1), TorrentPreview.UNKNOWN), ipNoteSeen = false)
        assertNull(unknown.nameText)
        assertNull(unknown.sizeBytes)
        assertNull(unknown.fileCount)
        assertTrue(unknown.downloadEnabled)

        val failed = TorrentConfirmModel.of(request, TorrentBody.Failed(IntakeRefusal.TOO_LARGE), ipNoteSeen = false)
        assertEquals(IntakeRefusal.TOO_LARGE, failed.error)
        assertFalse(failed.downloadEnabled)
        assertNull(failed.sizeBytes)
    }

    @Test
    fun privateAndTheNoteFollowTheirInputs() {
        val request = IntakeRequest.TorrentUri("content://x/y", isPrivate = false)
        assertFalse(TorrentConfirmModel.of(request, TorrentBody.Reading, ipNoteSeen = false).isPrivate)
        val priv = IntakeRequest.Magnet(magnet, isPrivate = true)
        assertTrue(TorrentConfirmModel.of(priv, TorrentBody.Reading, ipNoteSeen = true).isPrivate)
        assertTrue(TorrentConfirmModel.of(priv, TorrentBody.Reading, ipNoteSeen = false).showFirstUseNote)
        assertFalse(TorrentConfirmModel.of(priv, TorrentBody.Reading, ipNoteSeen = true).showFirstUseNote)
        assertFalse(TorrentConfirmModel.of(priv, TorrentBody.Reading, ipNoteSeen = false).toString().contains("Sintel"))
    }
}
