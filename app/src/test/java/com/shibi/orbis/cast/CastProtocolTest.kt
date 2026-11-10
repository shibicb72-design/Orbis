package com.shibi.orbis.cast

import java.io.InputStream
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CASTV2 framing, receiver and media status, the YouTube launch, and Chromecast TXT records. */
class CastProtocolTest {
    @Test
    fun castMessageRoundTripsIncludingALongUtf8Payload() {
        val payload = """{"type":"PING","title":"${"🎬".repeat(40)}"}"""
        val message = CastMessage(CAST_SENDER_ID, CAST_RECEIVER_ID, NS_HEARTBEAT, payload)
        val framed = frameCastMessage(message)
        assertTrue(framed.size > 127)
        val length = castFrameLength(framed)
        assertEquals(framed.size - 4, length)
        val decoded = decodeCastMessage(framed.copyOfRange(4, framed.size))
        assertEquals(message, decoded)
    }

    @Test
    fun anUnknownProtobufFieldIsSkippedAndABinaryFrameHasNoText() {
        val message = CastMessage(CAST_SENDER_ID, CAST_RECEIVER_ID, NS_HEARTBEAT, """{"type":"PING"}""")
        val withUnknown = encodeCastMessage(message) + byteArrayOf(64, 1)
        val decoded = decodeCastMessage(withUnknown)
        assertEquals(message.payloadUtf8, decoded?.payloadUtf8)
        assertEquals(NS_HEARTBEAT, decoded?.namespace)
        val binary = message.copy(payloadType = CastMessage.PAYLOAD_BINARY, payloadUtf8 = "ignored")
        val decodedBinary = decodeCastMessage(encodeCastMessage(binary))
        assertEquals(CastMessage.PAYLOAD_BINARY, decodedBinary?.payloadType)
        assertEquals("", decodedBinary?.payloadUtf8)
    }

    @Test
    fun anIllegalFrameLengthIsRejected() {
        val tooBig = byteArrayOf(0x00, 0x04, 0x00, 0x01)
        assertEquals(-1, castFrameLength(tooBig))
        assertNull(castFrameLength(byteArrayOf(0, 0, 0)))
    }

    @Test
    fun heartbeatTimesOutAfterFifteenSecondsOfSilence() {
        val session = CastSession(CastGoal.Media(media(), "Show", null))
        session.start(0)
        assertTrue(session.onClock(HEARTBEAT_INTERVAL_MS - 1).isEmpty())
        assertEquals("PING", typeOf(session.onClock(HEARTBEAT_INTERVAL_MS)))
        assertEquals("PING", typeOf(session.onClock(HEARTBEAT_INTERVAL_MS * 2)))
        val dead = session.onClock(HEARTBEAT_SILENCE_MS)
        assertEquals(CastFailure.SILENCE, (dead.single() as CastEffect.Failed).reason)
    }

    @Test
    fun aPongResetsTheHeartbeatSilenceTimer() {
        val session = CastSession(CastGoal.Media(media(), "Show", null))
        session.start(0)
        session.onFrame(frame(NS_HEARTBEAT, """{"type":"PONG"}"""), 14_000)
        assertFalse(session.onClock(14_999).any { it is CastEffect.Failed })
        assertTrue(session.onClock(14_000 + HEARTBEAT_SILENCE_MS).single() is CastEffect.Failed)
    }

    @Test
    fun aPingIsAnsweredWithPong() {
        val session = CastSession(CastGoal.Media(media(), "Show", null))
        session.start(0)
        val reply = session.onFrame(frame(NS_HEARTBEAT, """{"type":"PING"}"""), 1_000)
        assertEquals("PONG", typeOf(reply))
    }

    @Test
    fun receiverStatusParsesTheRunningAppAndVolume() {
        val snapshot = parseReceiverStatus(receiver("CC1AD845", "session-1", "web-5", 0.25, 3))
        assertEquals(1, snapshot?.applications?.size)
        assertEquals("CC1AD845", snapshot?.applications?.first()?.appId)
        assertEquals("web-5", snapshot?.applications?.first()?.transportId)
        assertEquals(0.25f, snapshot?.volume)
        assertEquals(3, snapshot?.requestId)
        val parsed = parseMediaStatus(mediaStatus("https://cdn.example/a.mp4", "PLAYING", 4.5, 20.0, 1))
        assertEquals("PLAYING", parsed.single().playerState)
        assertEquals(4.5, parsed.single().positionSec, 0.001)
        assertEquals(20.0, parsed.single().durationSec, 0.001)
        assertEquals(1, parsed.single().mediaSessionId)
    }

    @Test
    fun aDifferentRunningAppIsNotReused() {
        val youtube = parseReceiverStatus(receiver(YOUTUBE_RECEIVER_APP_ID, "s", "web-1", 1.0, 1))!!
        assertTrue(receiverAction(DEFAULT_MEDIA_APP_ID, youtube) is ReceiverAction.Launch)
        val reuse = receiverAction(YOUTUBE_RECEIVER_APP_ID, youtube)
        assertEquals("web-1", (reuse as ReceiverAction.Reuse).transportId)
    }

    @Test
    fun loadPayloadCarriesTheUrlTypeTitlePosterAndStartPosition() {
        val json = JSONObject(loadPayload(7, media(position = 3.5), "Show", "https://cdn.example/p.jpg"))
        assertEquals("LOAD", json.getString("type"))
        assertEquals(7, json.getInt("requestId"))
        assertTrue(json.getBoolean("autoplay"))
        assertEquals(3.5, json.getDouble("currentTime"), 0.001)
        val body = json.getJSONObject("media")
        assertEquals("https://cdn.example/a.mp4", body.getString("contentId"))
        assertEquals("video/mp4", body.getString("contentType"))
        assertEquals("BUFFERED", body.getString("streamType"))
        assertEquals("Show", body.getJSONObject("metadata").getString("title"))
        assertEquals(
            "https://cdn.example/p.jpg",
            body.getJSONObject("metadata").getJSONArray("images").getJSONObject(0).getString("url"),
        )
        val live = JSONObject(loadPayload(1, media(mime = "application/vnd.apple.mpegurl", duration = 0.0), "Live", null))
        assertEquals("LIVE", live.getJSONObject("media").getString("streamType"))
        assertFalse(live.getJSONObject("media").getJSONObject("metadata").has("images"))
    }

    @Test
    fun directMediaReusesOnlyTheDefaultReceiverThenLoads() {
        val session = CastSession(CastGoal.Media(media(), "Show", "https://cdn.example/p.jpg"))
        val opened = session.start(0)
        assertTrue(opened.none { it is CastEffect.DialPost })
        val wrong = session.onFrame(frame(NS_RECEIVER, receiver(YOUTUBE_RECEIVER_APP_ID, "s", "web-yt", 1.0, 1)), 100)
        val launch = wrong.filterIsInstance<CastEffect.Send>().map { it.frame }.single { it.namespace == NS_RECEIVER }
        assertEquals("LAUNCH", JSONObject(launch.payload).getString("type"))
        assertEquals(DEFAULT_MEDIA_APP_ID, JSONObject(launch.payload).getString("appId"))
        assertTrue(wrong.none { it is CastEffect.Send && JSONObject((it as CastEffect.Send).frame.payload).optString("type") == "LOAD" })
        val right = session.onFrame(frame(NS_RECEIVER, receiver(DEFAULT_MEDIA_APP_ID, "s", "web-9", 1.0, 2)), 200)
        val payloads = right.filterIsInstance<CastEffect.Send>().map { JSONObject(it.frame.payload).getString("type") to it.frame.destinationId }
        assertTrue(payloads.contains("CONNECT" to "web-9"))
        assertTrue(payloads.contains("LOAD" to "web-9"))
        val playing = session.onFrame(frame(NS_MEDIA, mediaStatus(media().url, "PLAYING", 3.5, 20.0, 8)), 300)
        assertTrue(playing.first() is CastEffect.Ready)
    }

    @Test
    fun youtubeTriesDialFirstAndConfirmsOnlyWhenTheVideoIsPlaying() {
        val video = YouTubeVideo("dQw4w9WgXcQ", "https://youtu.be/dQw4w9WgXcQ", 12.0, "Song")
        val session = CastSession(CastGoal.YouTube(video))
        val opened = session.start(0)
        val dial = opened.filterIsInstance<CastEffect.DialPost>().single()
        assertEquals("v=dQw4w9WgXcQ&t=12", dial.body)
        assertTrue(session.onDial(201, 50).isEmpty())
        val wrongApp = session.onFrame(frame(NS_RECEIVER, receiver(DEFAULT_MEDIA_APP_ID, "s", "web-d", 1.0, 1)), 100)
        val launch = wrongApp.filterIsInstance<CastEffect.Send>().map { it.frame }.single { it.namespace == NS_RECEIVER }
        assertEquals(YOUTUBE_RECEIVER_APP_ID, JSONObject(launch.payload).getString("appId"))
        val appUp = session.onFrame(frame(NS_RECEIVER, receiver(YOUTUBE_RECEIVER_APP_ID, "ys", "web-1", 1.0, 2)), 200)
        assertTrue(appUp.any { it is CastEffect.Send && it.frame.destinationId == "web-1" && it.frame.namespace == NS_CONNECTION })
        assertTrue(appUp.none { it is CastEffect.Send && it.frame.namespace == YOUTUBE_NAMESPACE })
        assertTrue(session.onClock(200 + YOUTUBE_FLING_FALLBACK_MS - 1).none { it is CastEffect.Send && (it as CastEffect.Send).frame.namespace == YOUTUBE_NAMESPACE })
        val fling = session.onClock(200 + YOUTUBE_FLING_FALLBACK_MS).filterIsInstance<CastEffect.Send>().single { it.frame.namespace == YOUTUBE_NAMESPACE }
        val body = JSONObject(fling.frame.payload)
        assertEquals("flingVideo", body.getString("type"))
        assertEquals("dQw4w9WgXcQ", body.getJSONObject("data").getString("videoId"))
        assertEquals(12.0, body.getJSONObject("data").getDouble("currentTime"), 0.001)
        val ignored = session.onFrame(frame(YOUTUBE_NAMESPACE, """{"type":"data","data":{"videoId":"other","currentTime":1}}"""), 2_500)
        assertTrue(ignored.none { it is CastEffect.Ready })
        val ready = session.onFrame(frame(NS_MEDIA, mediaStatus("dQw4w9WgXcQ", "PLAYING", 12.0, 200.0, 4)), 2_600)
        assertTrue(ready.single() is CastEffect.Ready)
    }

    @Test
    fun youtubeDialFailureLaunchesAndAPlayingStatusConfirmsWithoutWaiting() {
        val video = YouTubeVideo("abcdefghijk", "https://www.youtube.com/watch?v=abcdefghijk", 0.0, "Clip")
        val session = CastSession(CastGoal.YouTube(video))
        session.start(0)
        val launch = session.onDial(404, 10).filterIsInstance<CastEffect.Send>().single()
        assertEquals("LAUNCH", JSONObject(launch.frame.payload).getString("type"))
        val joined = session.onFrame(frame(NS_RECEIVER, receiver(YOUTUBE_RECEIVER_APP_ID, "ys", "web-2", 1.0, 2)), 20)
        assertTrue(joined.any { it is CastEffect.Send && it.frame.namespace == YOUTUBE_NAMESPACE })
        val status = """{"type":"data","data":{"videoId":"abcdefghijk","currentTime":0,"playerState":"BUFFERING"}}"""
        assertTrue(youtubeStatusConfirms("abcdefghijk", status))
        assertTrue(session.onFrame(frame(YOUTUBE_NAMESPACE, status), 30).single() is CastEffect.Ready)
    }

    @Test
    fun youtubeFailsWhenTheLoadedVideoIsNotTheOneAskedFor() {
        val video = YouTubeVideo("abcdefghijk", "https://www.youtube.com/watch?v=abcdefghijk", 1.0, "Clip")
        val session = CastSession(CastGoal.YouTube(video))
        session.start(0)
        session.onDial(201, 10)
        session.onFrame(frame(NS_RECEIVER, receiver(YOUTUBE_RECEIVER_APP_ID, "ys", "web-2", 1.0, 1)), 20)
        session.onClock(20 + YOUTUBE_FLING_FALLBACK_MS)
        val idle = mediaStatus("abcdefghijk", "IDLE", 0.0, 0.0, 3, idle = "ERROR")
        val failed = session.onFrame(frame(NS_MEDIA, idle), 3_000)
        assertEquals(CastFailure.YOUTUBE_UNCONFIRMED, (failed.single() as CastEffect.Failed).reason)
    }

    @Test
    fun aNonNumericVolumeStaysFinite() {
        val missing = parseReceiverStatus("""{"type":"RECEIVER_STATUS","requestId":1,"status":{"volume":{"level":"nope"}}}""")
        assertEquals(1f, missing?.volume)
        val nan = parseReceiverStatus("""{"type":"RECEIVER_STATUS","requestId":1,"status":{"volume":{"level":"NaN"}}}""")
        assertEquals(1f, nan?.volume)
        assertFalse(nan?.volume?.isNaN() == true)
    }

    @Test
    fun receiverAndMediaErrorsFailImmediately() {
        val types = listOf("LAUNCH_ERROR", "INVALID_REQUEST", "LOAD_FAILED", "LOAD_CANCELLED")
        for (type in types) {
            assertEquals(type, parseCastError("""{"type":"$type","reason":"NOT_FOUND"}"""))
            val session = CastSession(CastGoal.Media(media(), "Show", null))
            session.start(0)
            val failed = session.onFrame(frame(NS_RECEIVER, """{"type":"$type","requestId":2,"reason":"NOT_FOUND"}"""), 50)
            assertEquals(CastFailure.REJECTED, (failed.single() as CastEffect.Failed).reason)
            assertTrue(session.onClock(60).isEmpty())
        }
        val idle = mediaStatus("https://cdn.example/a.mp4", "IDLE", 0.0, 0.0, 3, idle = "LOAD_FAILED")
        assertEquals("LOAD_FAILED", parseCastError(idle))
        val session = CastSession(CastGoal.Media(media(), "Show", null))
        session.start(0)
        val failed = session.onFrame(frame(NS_MEDIA, idle), 80)
        assertEquals(CastFailure.REJECTED, (failed.single() as CastEffect.Failed).reason)
        assertNull(parseCastError("""{"type":"RECEIVER_STATUS"}"""))
    }

    @Test
    fun aChangedReceiverTransportGetsANewConnect() {
        val video = YouTubeVideo("dQw4w9WgXcQ", "https://youtu.be/dQw4w9WgXcQ", 0.0, "Song")
        val session = CastSession(CastGoal.YouTube(video))
        session.start(0)
        session.onDial(404, 10)
        session.onFrame(frame(NS_RECEIVER, receiver(YOUTUBE_RECEIVER_APP_ID, "ys", "web-1", 1.0, 2)), 20)
        val same = session.onFrame(frame(NS_RECEIVER, receiver(YOUTUBE_RECEIVER_APP_ID, "ys", "web-1", 1.0, 3)), 30)
        assertTrue(same.none { it is CastEffect.Send && it.frame.namespace == NS_CONNECTION })
        val relaunch = session.onFrame(frame(NS_RECEIVER, receiver(YOUTUBE_RECEIVER_APP_ID, "ys2", "web-9", 1.0, 4)), 40)
        val connects = relaunch.filterIsInstance<CastEffect.Send>().filter { it.frame.namespace == NS_CONNECTION }
        assertEquals(listOf("web-9"), connects.map { it.frame.destinationId })
        assertTrue(relaunch.none { it is CastEffect.Send && it.frame.namespace == YOUTUBE_NAMESPACE })
    }

    @Test
    fun aFrameArrivesInPiecesWithTimeoutsBetweenThem() {
        val message = CastMessage(CAST_SENDER_ID, CAST_RECEIVER_ID, NS_HEARTBEAT, """{"type":"PING"}""")
        val framed = frameCastMessage(message)
        val pieces = ArrayList<Any>(framed.size * 2)
        for (index in framed.indices) {
            pieces += framed.copyOfRange(index, index + 1)
            if (index != framed.lastIndex) pieces += SocketTimeoutException("gap")
        }
        val assembled = readCastFrame(PiecewiseInput(pieces))
        val decoded = assembled as Read.Frame
        assertEquals(message, decoded.message)

        assertTrue(readCastFrame(PiecewiseInput(listOf(SocketTimeoutException("idle")))) is Read.Tick)

        var now = 0L
        val stalled = listOf(framed.copyOfRange(0, 4), SocketTimeoutException("body"))
        val closed =
            readCastFrame(PiecewiseInput(stalled) { now += CAST_FRAME_DEADLINE_MS }) { now }
        assertTrue(closed is Read.Closed)
    }

    @Test
    fun castTxtReadsNameModelAndIdAndRejectsLoopback() {
        val attributes =
            mapOf(
                "fn" to "Living Room".toByteArray(),
                "md" to "Chromecast".toByteArray(),
                "id" to "abc\u0000".toByteArray(),
            )
        val parsed = parseCastTxt(attributes, "Living-Room")
        assertEquals("Living Room", parsed.name)
        assertEquals("Chromecast", parsed.model)
        assertEquals("abc", parsed.id)
        assertNull(castDeviceFromTxt(attributes, "Living-Room", listOf(InetAddress.getByName("127.0.0.1"))))
        assertNull(castDeviceFromTxt(attributes, "Living-Room", listOf(InetAddress.getByName("8.8.8.8"))))
        val device = castDeviceFromTxt(attributes, "Living-Room", listOf(InetAddress.getByName("192.168.1.40")))
        assertEquals("abc", device?.id)
        assertEquals("192.168.1.40", device?.address?.hostAddress)
        val unnamed = castDeviceFromTxt(emptyMap(), "Nest-Hub", listOf(InetAddress.getByName("10.0.0.8")))
        assertEquals("Nest-Hub", unnamed?.name)
        assertEquals("10.0.0.8", unnamed?.id)
    }

    private fun media(
        position: Double = 0.0,
        mime: String = "video/mp4",
        duration: Double = 20.0,
    ) = CastableMedia(
        url = "https://cdn.example/a.mp4",
        mime = mime,
        video = true,
        title = "Show",
        poster = null,
        positionSec = position,
        durationSec = duration,
    )

    private fun frame(namespace: String, payload: String) =
        CastFrame(CAST_RECEIVER_ID, CAST_SENDER_ID, namespace, payload)

    private fun typeOf(effects: List<CastEffect>): String =
        JSONObject(effects.filterIsInstance<CastEffect.Send>().single().frame.payload).getString("type")

    private fun receiver(appId: String, sessionId: String, transportId: String, volume: Double, requestId: Int) =
        """{"type":"RECEIVER_STATUS","requestId":$requestId,"status":{"applications":[{"appId":"$appId","displayName":"Receiver","sessionId":"$sessionId","transportId":"$transportId"}],"volume":{"level":$volume,"muted":false}}}"""

    private fun mediaStatus(
        contentId: String,
        state: String,
        position: Double,
        duration: Double,
        session: Int,
        idle: String = "",
    ) =
        """{"type":"MEDIA_STATUS","requestId":4,"status":[{"mediaSessionId":$session,"playerState":"$state","currentTime":$position,"idleReason":"$idle","media":{"contentId":"$contentId","contentType":"video/mp4","streamType":"BUFFERED","duration":$duration}}]}"""
}

/** Serves scripted byte chunks. A [SocketTimeoutException] step is thrown instead of returning bytes. */
private class PiecewiseInput(
    steps: List<Any>,
    private val onTimeout: () -> Unit = {},
) : InputStream() {
    private val pending = ArrayDeque(steps)

    override fun read(): Int {
        val one = ByteArray(1)
        val count = read(one, 0, 1)
        return if (count < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        while (pending.isNotEmpty() && pending.first() is ByteArray && (pending.first() as ByteArray).isEmpty()) {
            pending.removeFirst()
        }
        if (pending.isEmpty()) return -1
        val next = pending.removeFirst()
        if (next is SocketTimeoutException) {
            onTimeout()
            throw next
        }
        val data = next as ByteArray
        val count = minOf(len, data.size)
        System.arraycopy(data, 0, b, off, count)
        if (count < data.size) pending.addFirst(data.copyOfRange(count, data.size))
        return count
    }
}
