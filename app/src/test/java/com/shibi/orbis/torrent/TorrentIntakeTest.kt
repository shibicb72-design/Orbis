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

    /** android.net.Uri's authority: after `//` up to `/`, `\`, `?` or `#`, percent-decoded. */
    private fun platformAuthorityOf(uri: String?): String? {
        if (uri == null) return null
        val start = uri.indexOf("//").takeIf { it >= 0 }?.plus(2) ?: return null
        var end = start
        while (end < uri.length && uri[end] !in "/\\?#") end++
        return java.net.URLDecoder.decode(uri.substring(start, end).replace("+", "%2B"), "UTF-8")
    }

    /** The URI check() looks at: data for VIEW, the stream for SEND. MainActivity picks it the same way. */
    private fun picked(action: String?, data: String?, stream: String?) = if (action == view) data else stream

    private fun check(action: String?, data: String?, mime: String?, stream: String?, pkg: String, lookup: (String) -> String?) =
        TorrentIntake.check(action, data, mime, stream, platformAuthorityOf(picked(action, data, stream)), pkg, setOf("$pkg.downloads"), lookup)

    private fun intake(action: String?, data: String?, mime: String?, stream: String?, pkg: String, lookup: (String) -> String?) =
        TorrentIntake.intakeRequest(action, data, mime, stream, platformAuthorityOf(picked(action, data, stream)), pkg, setOf("$pkg.downloads"), lookup)

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
        val ok = intake(view, "content://com.android.providers.downloads/x/1", type, null, ours, theirs)
        assertTrue(ok is IntakeRequest.TorrentUri)
        assertEquals("content://com.android.providers.downloads/x/1", (ok as IntakeRequest.TorrentUri).uri)
        assertFalse(ok.isPrivate)
        assertNull(intake(view, "file:///sdcard/Download/a.torrent", type, null, ours, theirs))
        val shared = intake(send, null, type, "content://media/external/file/9", ours, theirs)
        assertEquals("content://media/external/file/9", (shared as IntakeRequest.TorrentUri).uri)
        assertNull(intake(send, "content://media/external/file/9", type, null, ours, theirs))
        assertNull(intake(view, "content://x/1", "application/octet-stream", null, ours, theirs))
        assertNull(intake(view, "content://x/1", null, null, ours, theirs))
        assertNull(intake("android.intent.action.MAIN", "content://x/1", type, null, ours, theirs))
        assertNull(intake(view, "content://com.shibi.orbis.downloads/a", type, null, ours) { ours })
        assertNull(intake(view, "content://0@com.shibi.orbis.downloads/a", type, null, ours, theirs))
        assertNull(intake(view, "content://x/a b", type, null, ours, theirs))
        val long = "content://x/" + "a".repeat(8_192)
        assertNull(intake(view, long, type, null, ours, theirs))
        assertTrue(intake(view, long.take(8_192), type, null, ours, theirs) is IntakeRequest.TorrentUri)
    }

    @Test
    fun refusalsNameTheirReason() {
        fun reason(c: TorrentIntake.Check) = (c as TorrentIntake.Check.Refused).reason
        assertEquals(IntakeRefusal.NOT_CONTENT, reason(check(view, "file:///a.torrent", type, null, ours, theirs)))
        assertEquals(IntakeRefusal.NOT_CONTENT, reason(check(send, null, type, null, ours, theirs)))
        assertEquals(IntakeRefusal.OWN_PROVIDER, reason(check(view, "content://com.shibi.orbis.x/a", type, null, ours) { ours }))
        assertSame(TorrentIntake.Check.NotTorrent, check(view, "content://x/a", "application/pdf", null, ours, theirs))
    }

    @Test
    fun ownProviderTricksAreRefused() {
        fun reason(c: TorrentIntake.Check) = (c as TorrentIntake.Check.Refused).reason
        val refused = listOf(
            "content://com.shibi.orbis.downloads/downloads_external/a.torrent?{",
            "content://com.shibi.orbis%2Edownloads/downloads_external/a.torrent",
            "CONTENT://com.shibi.orbis.downloads/a.torrent",
            "content://COM.SHIBI.ORBIS.DOWNLOADS/a.torrent",
            "content://0@com.shibi.orbis.downloads/a.torrent",
            "content://com.shibi.orbis.downloads\\@x/a.torrent",
            "content://x\\@com.shibi.orbis.downloads/a.torrent",
            "content://com.android.providers.downloads:1/a.torrent",
            "content:///a.torrent",
        )
        for (uri in refused) assertEquals(uri, IntakeRefusal.OWN_PROVIDER, reason(check(view, uri, type, null, ours, theirs)))
        // A platform authority that disagrees, a lookup that throws, a lookup that names us.
        val good = "content://com.android.providers.downloads/x/1"
        val disagree = TorrentIntake.check(view, good, type, null, "com.shibi.orbis.downloads", ours, setOf("$ours.downloads"), theirs)
        assertEquals(IntakeRefusal.OWN_PROVIDER, reason(disagree))
        assertEquals(IntakeRefusal.OWN_PROVIDER, reason(check(view, good, type, null, ours) { error("lookup failed") }))
        assertEquals(IntakeRefusal.OWN_PROVIDER, reason(check(view, good, type, null, ours) { ours }))
        // Another app's provider is accepted.
        assertTrue(check(view, good, type, null, ours, theirs) is TorrentIntake.Check.Ok)
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
