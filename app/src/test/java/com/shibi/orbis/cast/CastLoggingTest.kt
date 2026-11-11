package com.shibi.orbis.cast

import com.shibi.orbis.youtube.LoungeClient
import com.shibi.orbis.youtube.LoungeResponse
import com.shibi.orbis.youtube.LoungeResult
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "Cast" log lines: stable device hashes and no secrets. */
class CastLoggingTest {
    @Test
    fun deviceTagIsStableSixHexAndNotTheId() {
        val id = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        val tag = deviceTag(id)
        assertTrue(tag, Regex("^[0-9a-f]{6}$").matches(tag))
        assertEquals(tag, deviceTag(id))
        assertFalse(id.contains(tag))
        assertNotEquals(tag, deviceTag("192.168.1.40"))
        assertFalse(deviceTag("192.168.1.40").contains("192"))
        assertEquals("UnknownHostException", errName(java.net.UnknownHostException("www.youtube.com")))
    }

    @Test
    fun loungeLogLinesContainNoSecrets() {
        val lines = ArrayList<String>()
        val log: (String) -> Unit = { lines += it }

        // Lounge client: success, then each failure kind.
        val ok = ScriptedTransport(okScript(token = "TOKSENTINEL", sid = "SIDSENTINEL", gsid = "GSESSENTINEL", videoId = "VIDSENTINEL"))
        assertEquals(LoungeResult.Ok, LoungeClient(ok, log, Random(3)).play("SCREENSENTINEL", "VIDSENTINEL", 5.0) { true })
        val script = okScript(token = "TOKSENTINEL", sid = "SIDSENTINEL", gsid = "GSESSENTINEL", videoId = "VIDSENTINEL")
        val failures =
            listOf(
                listOf(LoungeResponse(403, "TOKSENTINEL SCREENSENTINEL https://www.youtube.com/")),
                listOf(LoungeResponse(200, "{\"screens\":[{\"screenId\":\"SCREENSENTINEL\"}]}")),
                listOf(script[0], LoungeResponse(401, "SIDSENTINEL GSESSENTINEL")),
                listOf(script[0], LoungeResponse(200, "12\n[[0,[\"c\",\"SIDSENTINEL\"]]]\n")),
                listOf(script[0], script[1], LoungeResponse(410, "Unknown SID SIDSENTINEL")),
            )
        for (responses in failures) {
            LoungeClient(ScriptedTransport(responses), log, Random(3)).play("SCREENSENTINEL", "VIDSENTINEL", 5.0) { true }
        }

        // Session lines for a YouTube attempt that goes through the lounge, falls back and confirms.
        val video = YouTubeVideo("VIDSENTINEL", "https://www.youtube.com/watch?v=VIDSENTINEL", 5.0, "TITLESENTINEL")
        val session = CastSession(CastGoal.YouTube(video), log = log)
        session.start(0)
        val frames =
            listOf(
                CastFrame(CAST_RECEIVER_ID, CAST_SENDER_ID, NS_RECEIVER, receiver("TRANSPORTSENTINEL")),
                CastFrame(
                    CAST_RECEIVER_ID,
                    CAST_SENDER_ID,
                    YOUTUBE_NAMESPACE,
                    """{"type":"mdxSessionStatus","data":{"screenId":"SCREENSENTINEL","deviceId":"192.168.1.40"}}""",
                ),
            )
        frames.forEachIndexed { index, frame ->
            castRxLine(frame.namespace, frame.payload)?.let(log)
            session.onFrame(frame, 100L * (index + 1))
        }
        session.onLounge(LoungeResult.Network("UnknownHostException"), 300)
        session.onDial(201, 400)
        session.onClock(400 + YOUTUBE_FLING_FALLBACK_MS).forEach { effect ->
            if (effect is CastEffect.Send) castTxLine(effect.frame.namespace, effect.frame.payload)?.let(log)
        }
        val playing =
            """{"type":"MEDIA_STATUS","requestId":4,"status":[{"mediaSessionId":2,"playerState":"PLAYING","media":{"contentId":"VIDSENTINEL"}}]}"""
        assertTrue(session.onFrame(CastFrame(CAST_RECEIVER_ID, CAST_SENDER_ID, NS_MEDIA, playing), 3_000).single() is CastEffect.Ready)
        castRxLine(NS_RECEIVER, """{"type":"LAUNCH_ERROR","reason":"192.168.1.40 VIDSENTINEL"}""")?.let(log)
        castTxLine(NS_RECEIVER, """{"type":"LAUNCH","requestId":3,"appId":"$YOUTUBE_RECEIVER_APP_ID"}""")?.let(log)

        // A second attempt that is handed over by the lounge and becomes ready by grace.
        val graced = CastSession(CastGoal.YouTube(video), log = log)
        graced.start(0)
        frames.forEachIndexed { index, frame -> graced.onFrame(frame, 100L * (index + 1)) }
        graced.onLounge(LoungeResult.Ok, 300)
        assertTrue(graced.onClock(300 + LOUNGE_READY_GRACE_MS).any { it is CastEffect.Ready })

        val forbidden =
            listOf(
                "SCREENSENTINEL",
                "TOKSENTINEL",
                "SIDSENTINEL",
                "GSESSENTINEL",
                "VIDSENTINEL",
                "TITLESENTINEL",
                "TRANSPORTSENTINEL",
                "192.168.",
                "http:",
                "https:",
                "://",
                "youtube.com",
            )
        assertTrue(lines.size >= 20)
        for (line in lines) {
            for (secret in forbidden) assertFalse("\"$line\" contains $secret", line.contains(secret))
        }
        val expected =
            listOf(
                "youtube step=token http=200",
                "youtube step=bind http=200",
                "youtube step=setPlaylist http=200",
                "youtube step=token http=403",
                "youtube step=bind failed http=401",
                "youtube step=setPlaylist failed http=410",
                "rx receiver type=RECEIVER_STATUS apps=233637DE",
                "app joined app=233637DE",
                "youtube step=mdxStatus tx",
                "youtube step=mdxStatus rx screenId=yes",
                "youtube step=lounge result=Network err=UnknownHostException -> dial fallback",
                "youtube step=fling tx",
                "youtube confirmed via=MEDIA_STATUS state=PLAYING",
                "rx error type=LAUNCH_ERROR",
                "tx receiver type=LAUNCH app=233637DE reqId=3",
                "youtube ready via=grace",
            )
        for (prefix in expected) assertTrue("no line starts with $prefix in $lines", lines.any { it.startsWith(prefix) })
    }

    private fun receiver(transportId: String) =
        """{"type":"RECEIVER_STATUS","requestId":1,"status":{"applications":[{"appId":"$YOUTUBE_RECEIVER_APP_ID","displayName":"YouTube","sessionId":"s","transportId":"$transportId"}],"volume":{"level":1.0}}}"""
}
