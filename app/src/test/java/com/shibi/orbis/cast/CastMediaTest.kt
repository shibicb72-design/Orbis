package com.shibi.orbis.cast

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastMediaTest {
    @Test
    fun directMediaByExtension() {
        assertEquals("video/mp4", classifyMediaUrl("https://cdn.example/a/movie.mp4", "")?.mime)
        assertEquals("video/mp4", classifyMediaUrl("https://cdn.example/a/movie.MP4?download=1", "")?.mime)
        assertEquals("video/webm", classifyMediaUrl("http://192.168.1.9/clip.webm", "")?.mime)
        assertEquals("application/vnd.apple.mpegurl", classifyMediaUrl("https://cdn.example/live/index.m3u8", "")?.mime)
        assertEquals("application/dash+xml", classifyMediaUrl("https://cdn.example/dash/manifest.mpd#t=1", "")?.mime)
        assertEquals("audio/mpeg", classifyMediaUrl("https://cdn.example/song.mp3", "")?.mime)
        assertFalse(classifyMediaUrl("https://cdn.example/song.mp3", "")!!.video)
        assertTrue(classifyMediaUrl("https://cdn.example/a/movie.mp4", "")!!.video)
    }

    @Test
    fun mimeHintAcceptsAUrlWithoutAnExtension() {
        val found = classifyMediaUrl("https://cdn.example/media?id=1", "video/mp4; codecs=\"avc1\"")
        assertEquals("video/mp4", found?.mime)
        assertEquals("application/vnd.apple.mpegurl", classifyMediaUrl("https://cdn.example/playlist", "application/x-mpegURL")?.mime)
        assertEquals("application/dash+xml", classifyMediaUrl("https://cdn.example/manifest", "application/dash+xml")?.mime)
        assertEquals("audio/mpeg", classifyMediaUrl("https://cdn.example/a", "audio/mp3")?.mime)
    }

    @Test
    fun blobMediaSourceAndNonMediaAreRejected() {
        val rejected =
            listOf(
                "blob:https://example.com/8b0b0b0b-0000-0000-0000-000000000001",
                "mediasource:https://example.com/abc",
                "data:video/mp4;base64,AAAA",
                "javascript:alert(1)",
                "file:///sdcard/a.mp4",
                "https://cdn.example/watch",
                "https://cdn.example/page.html",
                "https://www.youtube.com/embed/dQw4w9WgXcQ",
                "http://",
                "",
                "https://cdn.example/a b.mp4",
            )
        for (url in rejected) assertNull(url, classifyMediaUrl(url, "video/mp4".takeIf { url.startsWith("blob") || url.startsWith("mediasource") }.orEmpty()))
        assertNull(classifyMediaUrl("https://cdn.example/no-extension", ""))
        assertNull(classifyMediaUrl("https://cdn.example/page.html", "text/html"))
    }

    @Test
    fun youtubeIdAndTimestamp() {
        val watch = parseYouTubeVideo("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals("dQw4w9WgXcQ", watch?.videoId)
        assertEquals(0.0, watch?.startSec)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", watch?.watchUrl)

        assertEquals(90.0, parseYouTubeVideo("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=1m30s")?.startSec)
        assertEquals(90.0, parseYouTubeVideo("https://youtu.be/dQw4w9WgXcQ?t=90")?.startSec)
        assertEquals(43.0, parseYouTubeVideo("https://www.youtu.be/dQw4w9WgXcQ?t=43s")?.startSec)
        assertEquals(12.0, parseYouTubeVideo("https://www.youtube.com/embed/dQw4w9WgXcQ?start=12")?.startSec)
        assertEquals(45.0, parseYouTubeVideo("https://www.youtube.com/watch?v=dQw4w9WgXcQ#t=45")?.startSec)
        assertEquals(3723.0, parseYouTubeVideo("https://youtube.com/watch?v=dQw4w9WgXcQ&t=1h2m3s")?.startSec)
        assertEquals(62.0, parseYouTubeVideo("https://www.youtube.com/shorts/dQw4w9WgXcQ?t=1:02")?.startSec)
        assertEquals(3723.0, parseYouTubeVideo("https://www.youtube.com/live/dQw4w9WgXcQ?t=1:02:03")?.startSec)
        assertEquals("dQw4w9WgXcQ", parseYouTubeVideo("https://www.youtube.com/watch?list=PL123&v=dQw4w9WgXcQ")?.videoId)
    }

    @Test
    fun youtubeTimeFormats() {
        assertEquals(90.0, parseYouTubeTime("90"))
        assertEquals(90.0, parseYouTubeTime("90s"))
        assertEquals(90.0, parseYouTubeTime("1m30s"))
        assertEquals(3600.0, parseYouTubeTime("1h"))
        assertEquals(62.0, parseYouTubeTime("1:02"))
        assertEquals(3723.0, parseYouTubeTime("1:02:03"))
        assertNull(parseYouTubeTime(""))
        assertNull(parseYouTubeTime("nope"))
        assertNull(parseYouTubeTime("1m30"))
        assertEquals(60.0, parseYouTubeTime("1m"))
        assertEquals(60.0, parseYouTubeTime("1m0s"))
    }

    @Test
    fun otherHostsAreNotYoutube() {
        assertNull(parseYouTubeVideo("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(parseYouTubeVideo("https://youtube.com.evil.com/watch?v=dQw4w9WgXcQ"))
        assertNull(parseYouTubeVideo("https://notyoutube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(parseYouTubeVideo("https://www.youtube.com/feed/trending"))
        assertNull(parseYouTubeVideo("https://www.youtube.com/watch?v=short"))
        assertNull(parseYouTubeVideo("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(parseYouTubeVideo("https://www.youtube.com/watch?v=dQw4w9WgXcQ extra"))
    }

    @Test
    fun pageWithADirectFileIsCastable() {
        val json = probe(title = "Talk", href = "https://example.com/talk", media = listOf(element("video", "https://cdn.example/talk.mp4", "video/mp4", current = 12.5, duration = 90.0, paused = false)))
        val target = classifyPageMedia("https://example.com/talk", json) as CastTarget.Direct
        assertEquals("https://cdn.example/talk.mp4", target.media.url)
        assertEquals("video/mp4", target.media.mime)
        assertEquals(12.5, target.media.positionSec, 0.01)
        assertEquals("Talk", target.media.title)
        assertTrue(target.media.video)
    }

    @Test
    fun blobPlayerOnANormalPageIsNotCastable() {
        val json = probe(title = "Player", href = "https://example.com/watch", media = listOf(element("video", "blob:https://example.com/uuid", "", current = 4.0)))
        assertTrue(classifyPageMedia("https://example.com/watch", json) is CastTarget.None)
        assertTrue(classifyPageMedia("https://example.com/watch", null) is CastTarget.None)
    }

    @Test
    fun blobCurrentSrcFallsThroughToADirectSource() {
        val json =
            """{"title":"Mix","poster":"","href":"https://example.com/a","media":[{"tag":"video","sources":[{"src":"blob:https://example.com/x","type":""},{"src":"https://cdn.example/a.webm","type":"video/webm"}],"currentTime":3,"duration":10,"paused":false,"poster":"","title":""}]}"""
        val target = classifyPageMedia("https://example.com/a", json) as CastTarget.Direct
        assertEquals("https://cdn.example/a.webm", target.media.url)
    }

    @Test
    fun playingVideoBeatsAPausedAudioElement() {
        val json =
            probe(
                title = "Page",
                href = "https://example.com/a",
                media =
                    listOf(
                        element("audio", "https://cdn.example/a.mp3", "audio/mpeg", paused = false),
                        element("video", "https://cdn.example/a.mp4", "video/mp4", paused = true),
                    ),
            )
        val target = classifyPageMedia("https://example.com/a", json) as CastTarget.Direct
        assertEquals("https://cdn.example/a.mp4", target.media.url)
    }

    @Test
    fun youtubePageWithABlobPlayerUsesTheVideoIdAndTheLaterTimestamp() {
        val json = probe(title = "Song - YouTube", href = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10", media = listOf(element("video", "blob:https://www.youtube.com/1", "", current = 40.0)))
        val target = classifyPageMedia("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10", json) as CastTarget.YouTube
        assertEquals("dQw4w9WgXcQ", target.video.videoId)
        assertEquals(40.0, target.video.startSec, 0.01)
        assertEquals("Song", target.video.title)
    }

    @Test
    fun youtubeTimestampFromTheUrlWhenThePlayerHasNotMoved() {
        val json = probe(title = "Song - YouTube", href = "https://youtu.be/dQw4w9WgXcQ?t=1m30s", media = listOf(element("video", "blob:https://www.youtube.com/1", "", current = 0.0)))
        val target = classifyPageMedia("https://youtu.be/dQw4w9WgXcQ?t=1m30s", json) as CastTarget.YouTube
        assertEquals(90.0, target.video.startSec, 0.01)
    }

    @Test
    fun youtubePageIsRecognisedWithoutAProbe() {
        val target = classifyPageMedia("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=12s", null) as CastTarget.YouTube
        assertEquals("dQw4w9WgXcQ", target.video.videoId)
        assertEquals(12.0, target.video.startSec, 0.01)
    }

    @Test
    fun flingPayloadAndDialBodyCarryTheIdAndTime() {
        val payload = JSONObject(youtubeFlingPayload("dQw4w9WgXcQ", 12.5))
        assertEquals("flingVideo", payload.getString("type"))
        assertEquals("dQw4w9WgXcQ", payload.getJSONObject("data").getString("videoId"))
        assertEquals(12.5, payload.getJSONObject("data").getDouble("currentTime"), 0.01)
        assertEquals("v=dQw4w9WgXcQ&t=12", youtubeDialBody("dQw4w9WgXcQ", 12.4))
    }

    private fun probe(title: String, href: String, media: List<String>): String =
        """{"title":${JSONObject.quote(title)},"poster":"","href":${JSONObject.quote(href)},"media":[${media.joinToString()}]}"""

    private fun element(
        tag: String,
        src: String,
        type: String,
        current: Double = 0.0,
        duration: Double = 0.0,
        paused: Boolean = true,
    ): String =
        """{"tag":"$tag","sources":[{"src":${JSONObject.quote(src)},"type":${JSONObject.quote(type)}}],"currentTime":$current,"duration":$duration,"paused":$paused,"poster":"","title":""}"""
}
