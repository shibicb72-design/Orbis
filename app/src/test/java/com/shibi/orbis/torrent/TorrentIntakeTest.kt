package com.shibi.orbis.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentIntakeTest {
    private val view = "android.intent.action.VIEW"
    private val send = "android.intent.action.SEND"
    private val type = "application/x-bittorrent"
    private val ours = "com.shibi.orbis"
    private val theirs: (String) -> String? = { "com.android.providers.downloads" }

    @Test
    fun torrentResponsesAreRecognisedByTypeOrName() {
        val plain = "https://example.com/get?id=1"
        assertTrue(TorrentIntake.isTorrentResponse("application/x-bittorrent", null, plain))
        assertTrue(TorrentIntake.isTorrentResponse("Application/X-BitTorrent; charset=binary", null, plain))
        // A .torrent name from the disposition or the URL, with a type that says nothing.
        assertTrue(TorrentIntake.isTorrentResponse("application/octet-stream", "attachment; filename=\"Sintel.torrent\"", plain))
        assertTrue(TorrentIntake.isTorrentResponse(null, null, "https://example.com/files/Sintel.TORRENT"))
        assertTrue(TorrentIntake.isTorrentResponse("", "attachment; filename*=UTF-8''sintel.torrent", plain))
        // HTML is never a torrent, whatever it is called; nor is any other type.
        assertFalse(TorrentIntake.isTorrentResponse("text/html", null, "https://example.com/Sintel.torrent"))
        assertFalse(TorrentIntake.isTorrentResponse("text/html", "attachment; filename=a.torrent", plain))
        assertFalse(TorrentIntake.isTorrentResponse("application/zip", null, "https://example.com/a.torrent"))
        assertFalse(TorrentIntake.isTorrentResponse("application/octet-stream", null, "https://example.com/a.zip"))
        assertFalse(TorrentIntake.isTorrentResponse(null, "attachment; filename=a.torrent.exe", plain))
    }

    @Test
    fun intakeRequestsMirrorTheSharedDocumentRules() {
        val ok = TorrentIntake.intakeRequest(view, "content://com.android.providers.downloads/x/1", type, null, ours, theirs)
        assertTrue(ok is IntakeRequest.TorrentUri)
        assertEquals("content://com.android.providers.downloads/x/1", (ok as IntakeRequest.TorrentUri).uri)
        assertFalse(ok.isPrivate)
        assertNull(TorrentIntake.intakeRequest(view, "file:///sdcard/Download/a.torrent", type, null, ours, theirs))
        val shared = TorrentIntake.intakeRequest(send, null, type, "content://media/external/file/9", ours, theirs)
        assertEquals("content://media/external/file/9", (shared as IntakeRequest.TorrentUri).uri)
        assertNull(TorrentIntake.intakeRequest(send, "content://media/external/file/9", type, null, ours, theirs))
        assertNull(TorrentIntake.intakeRequest(view, "content://x/1", "application/octet-stream", null, ours, theirs))
        assertNull(TorrentIntake.intakeRequest(view, "content://x/1", null, null, ours, theirs))
        assertNull(TorrentIntake.intakeRequest("android.intent.action.MAIN", "content://x/1", type, null, ours, theirs))
        assertNull(TorrentIntake.intakeRequest(view, "content://com.shibi.orbis.downloads/a", type, null, ours) { ours })
        assertNull(TorrentIntake.intakeRequest(view, "content://0@com.shibi.orbis.downloads/a", type, null, ours, theirs))
        assertNull(TorrentIntake.intakeRequest(view, "content://x/a b", type, null, ours, theirs))
        val long = "content://x/" + "a".repeat(8_192)
        assertNull(TorrentIntake.intakeRequest(view, long, type, null, ours, theirs))
        assertTrue(TorrentIntake.intakeRequest(view, long.take(8_192), type, null, ours, theirs) is IntakeRequest.TorrentUri)
    }

    @Test
    fun refusalsNameTheirReason() {
        fun reason(c: TorrentIntake.Check) = (c as TorrentIntake.Check.Refused).reason
        assertEquals(IntakeRefusal.NOT_CONTENT, reason(TorrentIntake.check(view, "file:///a.torrent", type, null, ours, theirs)))
        assertEquals(IntakeRefusal.NOT_CONTENT, reason(TorrentIntake.check(send, null, type, null, ours, theirs)))
        assertEquals(IntakeRefusal.OWN_PROVIDER, reason(TorrentIntake.check(view, "content://com.shibi.orbis.x/a", type, null, ours) { ours }))
        assertSame(TorrentIntake.Check.NotTorrent, TorrentIntake.check(view, "content://x/a", "application/pdf", null, ours, theirs))
    }

    @Test
    fun requestsPrintWithoutTheirContents() {
        val magnet = IntakeRequest.Magnet("magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Secret", isPrivate = true)
        val uri = IntakeRequest.TorrentUri("content://provider/Secret.torrent")
        val bytes = IntakeRequest.TorrentBytes("Secret".toByteArray(), isPrivate = false, suggestedName = "Secret")
        for (r in listOf(magnet, uri, bytes)) {
            assertFalse(r.toString(), r.toString().contains("Secret"))
            assertFalse(r.toString(), r.toString().contains("08ada5"))
        }
        assertEquals("intake magnet true", IntakeLog.received(magnet))
        assertEquals("intake uri false", IntakeLog.received(uri))
        assertEquals("intake refused TOO_LARGE", IntakeLog.refused(IntakeRefusal.TOO_LARGE))
    }
}
