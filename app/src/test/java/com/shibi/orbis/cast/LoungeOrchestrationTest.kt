package com.shibi.orbis.cast

import com.shibi.orbis.youtube.LOUNGE_BIND_URL
import com.shibi.orbis.youtube.LOUNGE_STEP_TIMEOUT_MS
import com.shibi.orbis.youtube.LOUNGE_TOKEN_URL
import com.shibi.orbis.youtube.LoungeClient
import com.shibi.orbis.youtube.LoungeRequest
import com.shibi.orbis.youtube.LoungeResponse
import com.shibi.orbis.youtube.LoungeResult
import com.shibi.orbis.youtube.LoungeTransport
import java.net.UnknownHostException
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LoungeClient.play against a scripted transport and a fake clock. */
class LoungeOrchestrationTest {
    @Test
    fun playRunsTokenBindSetPlaylistInOrder() {
        val transport = ScriptedTransport(okScript())
        val lines = ArrayList<String>()
        val client = LoungeClient(transport, { lines += it }, Random(7), FakeClock(step = 100)::now)
        val result = client.play("SCREEN", "dQw4w9WgXcQ", 30.0) { true }
        assertEquals(LoungeResult.Ok, result)
        assertEquals(3, transport.requests.size)
        assertEquals(LOUNGE_TOKEN_URL, transport.requests[0].url)
        assertTrue(transport.requests[1].url.startsWith("$LOUNGE_BIND_URL?RID="))
        assertTrue(transport.requests[2].url.startsWith("$LOUNGE_BIND_URL?SID=SIDV&gsessionid=GSV&RID="))
        val bindRid = transport.requests[1].url.substringAfter("RID=").substringBefore('&').toInt()
        val playRid = transport.requests[2].url.substringAfter("RID=").substringBefore('&').toInt()
        assertEquals(bindRid + 1, playRid)
        assertEquals("TOKV", transport.requests[1].headers["X-YouTube-LoungeId-Token"])
        assertEquals("TOKV", transport.requests[2].headers["X-YouTube-LoungeId-Token"])
        assertTrue(transport.requests[2].body.contains("req0_videoId=dQw4w9WgXcQ"))
        assertTrue(transport.timeouts.all { it == LOUNGE_STEP_TIMEOUT_MS })
        assertEquals(
            listOf("youtube step=token", "youtube step=bind", "youtube step=setPlaylist"),
            lines.map { it.substringBefore(" http") },
        )
        assertTrue(lines[0].contains("http=200") && lines[0].contains("token=yes") && lines[0].contains("ms=100"))
        assertTrue(lines[1].contains("sid=yes gsid=yes chunks=2"))
        assertTrue(lines[2].contains("nowPlaying=yes"))
    }

    @Test
    fun abortBetweenStepsWhenEpochChanges() {
        for (abortAfter in 0..3) {
            val transport = ScriptedTransport(okScript())
            var checks = 0
            val result =
                LoungeClient(transport, {}, Random(1), FakeClock(step = 10)::now)
                    .play("SCREEN", "dQw4w9WgXcQ", 0.0) { checks++ < abortAfter }
            assertNull("abort after $abortAfter checks", result)
            assertEquals("abort after $abortAfter checks", abortAfter, transport.requests.size)
        }
        val failing = ScriptedTransport(listOf(LoungeResponse(401, "")))
        val result = LoungeClient(failing, {}, Random(1), FakeClock(step = 10)::now).play("S", "v", 0.0) { true }
        assertEquals(LoungeResult.TokenFailed(401), result)
        assertEquals(1, failing.requests.size)
    }

    @Test
    fun transportExceptionYieldsNetworkResultWithClassNameOnly() {
        val transport =
            object : LoungeTransport {
                override fun post(req: LoungeRequest, timeoutMs: Int): LoungeResponse? =
                    throw UnknownHostException("www.youtube.com SECRET-DETAIL")
            }
        val lines = ArrayList<String>()
        val result = LoungeClient(transport, { lines += it }, Random(1), FakeClock(step = 5)::now).play("S", "v", 0.0) { true }
        assertEquals(LoungeResult.Network("UnknownHostException"), result)
        assertEquals(listOf("youtube step=token err=UnknownHostException ms=5"), lines)
        val silent = object : LoungeTransport {
            override fun post(req: LoungeRequest, timeoutMs: Int): LoungeResponse? = null
        }
        assertEquals(LoungeResult.Network("NoResponse"), LoungeClient(silent, {}).play("S", "v", 0.0) { true })
    }
}

internal fun okScript(
    token: String = "TOKV",
    sid: String = "SIDV",
    gsid: String = "GSV",
    videoId: String = "dQw4w9WgXcQ",
): List<LoungeResponse> =
    listOf(
        LoungeResponse(200, """{"screens":[{"screenId":"x","loungeToken":"$token"}]}"""),
        LoungeResponse(200, "40\n[[0,[\"c\",\"$sid\",\"\",8]]]\n30\n[[1,[\"S\",\"$gsid\"]]]\n"),
        LoungeResponse(200, "60\n[[2,[\"nowPlaying\",{\"videoId\":\"$videoId\",\"currentTime\":\"0\"}]]]\n"),
    )

/** Returns the scripted responses in order and records each request. */
internal class ScriptedTransport(script: List<LoungeResponse>) : LoungeTransport {
    private val pending = ArrayDeque(script)
    val requests = ArrayList<LoungeRequest>()
    val timeouts = ArrayList<Int>()

    override fun post(req: LoungeRequest, timeoutMs: Int): LoungeResponse? {
        requests += req
        timeouts += timeoutMs
        return pending.removeFirstOrNull()
    }
}

/** Each read advances by [step], so a request appears to take exactly [step] ms. */
internal class FakeClock(private val step: Long) {
    private var value = 1_000L

    fun now(): Long {
        val current = value
        value += step
        return current
    }
}
