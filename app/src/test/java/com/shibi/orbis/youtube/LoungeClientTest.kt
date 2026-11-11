package com.shibi.orbis.youtube

import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lounge request builders and response parsers. */
class LoungeClientTest {
    @Test
    fun tokenRequestPostsScreenIdFormEncoded() {
        val request = loungeTokenRequest("ab c&d=1")
        assertEquals(LOUNGE_TOKEN_URL, request.url)
        assertEquals("application/x-www-form-urlencoded", request.headers["Content-Type"])
        assertEquals("screen_ids=ab+c%26d%3D1", request.body)
        assertEquals(mapOf("screen_ids" to "ab c&d=1"), form(request.body))
    }

    @Test
    fun tokenResponseYieldsLoungeTokenFromFirstScreen() {
        val body =
            """{"screens":[{"screenId":"s1","loungeToken":"TOKEN-ONE","expiration":1},{"screenId":"s2","loungeToken":"TOKEN-TWO"}]}"""
        assertEquals("TOKEN-ONE", parseLoungeToken(body))
    }

    @Test
    fun tokenResponseWithoutScreensIsNull() {
        assertNull(parseLoungeToken("""{"screens":[]}"""))
        assertNull(parseLoungeToken("""{}"""))
        assertNull(parseLoungeToken("""{"screens":[{"screenId":"s1"}]}"""))
        assertNull(parseLoungeToken("<html>"))
    }

    @Test
    fun bindRequestCarriesTokenHeaderOriginAndRidVerCver() {
        val request = loungeBindRequest("TOK", 12345, "0123456789abcdef0123456789abcdef")
        assertEquals("$LOUNGE_BIND_URL?RID=12345&VER=8&CVER=1", request.url)
        assertEquals("TOK", request.headers["X-YouTube-LoungeId-Token"])
        assertEquals("https://www.youtube.com", request.headers["Origin"])
        assertEquals("application/x-www-form-urlencoded", request.headers["Content-Type"])
        val fields = form(request.body)
        assertEquals("REMOTE_CONTROL", fields["device"])
        assertEquals("0123456789abcdef0123456789abcdef", fields["id"])
        assertEquals("Orbis", fields["name"])
        assertEquals("3", fields["mdx-version"])
        assertEquals("cast", fields["pairing_type"])
        assertEquals("android-phone-13.14.55", fields["app"])
        assertEquals("0", fields["count"])
    }

    @Test
    fun bindChunksYieldSidAndGsessionid() {
        val body = "41\n[[0,[\"c\",\"SIDVALUE\",\"\",8]]]\n52\n[[1,[\"S\",\"GSESSIONVALUE\"]]]\n"
        assertEquals(LoungeBind("SIDVALUE", "GSESSIONVALUE", 1), parseLoungeBind(body))
        assertEquals(2, parseLoungeChunks(body)?.chunks)
    }

    @Test
    fun bindResponseAsOneChunkParses() {
        val body =
            "180\n[[0,[\"c\",\"SID1\",\"\",8]],[1,[\"S\",\"GS1\"]],[2,[\"loungeStatus\",{\"devices\":\"[{\\\"name\\\":\\\"x]\\\"}]\"}]]," +
                "[3,[\"playlistModified\",{}]]]\n"
        assertEquals(LoungeBind("SID1", "GS1", 3), parseLoungeBind(body))
        assertEquals(1, parseLoungeChunks(body)?.chunks)
    }

    @Test
    fun bindResponseWithoutSIsNull() {
        assertNull(parseLoungeBind("41\n[[0,[\"c\",\"SIDVALUE\",\"\",8]]]\n"))
        assertNull(parseLoungeBind("52\n[[1,[\"S\",\"GSESSIONVALUE\"]]]\n"))
        assertNull(parseLoungeBind(""))
        assertNull(parseLoungeBind("not chunks"))
    }

    @Test
    fun oversizedBindResponseIsRejected() {
        val valid = "41\n[[0,[\"c\",\"SIDVALUE\",\"\",8]]]\n52\n[[1,[\"S\",\"GSESSIONVALUE\"]]]\n"
        val padded = valid + " ".repeat(LOUNGE_MAX_RESPONSE_CHARS - valid.length)
        assertEquals("SIDVALUE", parseLoungeBind(padded)?.sid)
        assertNull(parseLoungeBind(padded + " "))
        assertNull(parseLoungeEvents(padded + " "))
    }

    @Test
    fun setPlaylistRequestUsesSidGsessionidAndReq0Fields() {
        val request = loungeSetPlaylistRequest("TOK", LoungeBind("S/D", "G+S", 4), 12346, "dQw4w9WgXcQ", 75.9)
        assertEquals("$LOUNGE_BIND_URL?SID=S%2FD&gsessionid=G%2BS&RID=12346&VER=8&CVER=1", request.url)
        assertEquals("TOK", request.headers["X-YouTube-LoungeId-Token"])
        assertEquals("https://www.youtube.com", request.headers["Origin"])
        val fields = form(request.body)
        assertEquals("1", fields["count"])
        assertEquals("0", fields["ofs"])
        assertEquals("setPlaylist", fields["req0__sc"])
        assertEquals("dQw4w9WgXcQ", fields["req0_videoId"])
        assertEquals("75", fields["req0_currentTime"])
        assertEquals("-1", fields["req0_currentIndex"])
        assertEquals("", fields["req0_listId"])
        assertEquals("false", fields["req0_audioOnly"])
        assertTrue(request.body.startsWith("count=1&ofs=0&req0__sc=setPlaylist&"))
    }

    @Test
    fun setPlaylistResponseNowPlayingIsDetected() {
        val body = "60\n[[5,[\"nowPlaying\",{\"videoId\":\"dQw4w9WgXcQ\",\"currentTime\":\"0\",\"state\":\"3\"}]]]\n"
        assertTrue(loungeNowPlaying(body, "dQw4w9WgXcQ"))
        assertFalse(loungeNowPlaying(body, "abcdefghijk"))
        assertFalse(loungeNowPlaying("20\n[[6,[\"noop\"]]]\n", "dQw4w9WgXcQ"))
        assertFalse(loungeNowPlaying("", "dQw4w9WgXcQ"))
    }

    @Test
    fun httpStatusMapsToLoungeResult() {
        assertEquals(LoungeResult.Ok, loungeSetPlaylistResult(200))
        assertEquals(LoungeResult.PlayFailed(400), loungeSetPlaylistResult(400))
        assertEquals(LoungeResult.PlayFailed(410), loungeSetPlaylistResult(410))
        assertEquals(LoungeResult.PlayFailed(401), loungeSetPlaylistResult(401))
        assertEquals(LoungeResult.TokenFailed(403), loungeFailure(LoungeStep.TOKEN, 403))
        assertEquals(LoungeResult.BindFailed(401), loungeFailure(LoungeStep.BIND, 401))
        assertEquals(LoungeResult.PlayFailed(500), loungeFailure(LoungeStep.SET_PLAYLIST, 500))
        assertEquals("sessionLost", loungeHttpMeaning(400))
        assertEquals("sessionLost", loungeHttpMeaning(410))
        assertEquals("tokenExpired", loungeHttpMeaning(401))
        assertEquals("", loungeHttpMeaning(500))
    }

    private fun form(body: String): Map<String, String> =
        body.split('&').associate { pair ->
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }
}
