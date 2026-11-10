package com.shibi.orbis.youtube

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeAdEstimateTest {
    private val watch = "https://www.youtube.com/watch?v=aaaaaaaaaaa"
    private val video = "aaaaaaaaaaa"

    @Test
    fun averageAdLengthIsFifteenSecondsAndTheMathUsesIt() {
        assertEquals(15, AVERAGE_YOUTUBE_AD_SECONDS)
        assertEquals(0L, estimatedSecondsSaved(0))
        assertEquals(0L, estimatedSecondsSaved(-2))
        assertEquals(0L, estimatedSecondsSaved(3, secondsPerAd = 0))
        assertEquals(15L, estimatedSecondsSaved(1))
        assertEquals(60L, estimatedSecondsSaved(4))
        assertEquals(20L, estimatedSecondsSaved(2, secondsPerAd = 10))
    }

    @Test
    fun formatsEstimatedTimeLikeTheDashboard() {
        assertEquals("0 min", formatEstimatedTimeSaved(0))
        assertEquals("0 min", formatEstimatedTimeSaved(-4))
        assertEquals("45 s", formatEstimatedTimeSaved(45))
        assertEquals("1 min", formatEstimatedTimeSaved(60))
        assertEquals("1 min 30 s", formatEstimatedTimeSaved(90))
        assertEquals("14 min", formatEstimatedTimeSaved(14 * 60))
        assertEquals("2 h", formatEstimatedTimeSaved(2 * 3600))
        assertEquals("2 h 14 min", formatEstimatedTimeSaved(2 * 3600 + 14 * 60))
        assertEquals("1 h 1 min", formatEstimatedTimeSaved(3600 + 60))
    }

    @Test
    fun readsTheVideoIdFromWatchShortsAndShortLinks() {
        assertEquals(video, youtubeVideoId(watch))
        assertEquals(video, youtubeVideoId("https://m.youtube.com/watch?v=$video&t=12"))
        assertEquals(video, youtubeVideoId("https://music.youtube.com/watch?list=PL1&v=$video"))
        assertEquals(video, youtubeVideoId("https://youtu.be/$video?t=3"))
        assertEquals(video, youtubeVideoId("https://www.youtube.com/shorts/$video"))
        assertEquals(video, youtubeVideoId("https://www.youtube.com/embed/$video"))
        assertEquals(video, youtubeVideoId("https://www.youtube.com/live/$video"))
        assertEquals(video, youtubeVideoId("https://www.youtube-nocookie.com/embed/$video"))
        assertNull(youtubeVideoId("https://www.youtube.com/"))
        assertNull(youtubeVideoId("https://www.youtube.com/playlist?list=PL1"))
        assertNull(youtubeVideoId("https://www.youtube.com/watch?v=short"))
        assertNull(youtubeVideoId("https://example.com/watch?v=$video"))
    }

    @Test
    fun recognisesKnownAdEndpointsAndIgnoresPlayback() {
        assertTrue(isYoutubeAdRequest("https://www.youtube.com/pagead/interaction?ai=1"))
        assertTrue(isYoutubeAdRequest("https://www.youtube.com/api/stats/ads?event=start"))
        assertTrue(isYoutubeAdRequest("https://m.youtube.com/get_midroll_info?ei=1"))
        assertTrue(isYoutubeAdRequest("https://www.youtube.com/youtubei/v1/player/ad_break"))
        assertTrue(isYoutubeAdRequest("https://googleads.g.doubleclick.net/pagead/id"))
        assertTrue(isYoutubeAdRequest("https://ad.doubleclick.net/pagead/ads"))
        assertTrue(isYoutubeAdRequest("https://www.googleadservices.com/pagead/aclk"))
        assertTrue(isYoutubeAdRequest("https://tpc.googlesyndication.com/sodar/x"))
        assertFalse(isYoutubeAdRequest("https://www.youtube.com/watch?v=$video"))
        assertFalse(isYoutubeAdRequest("https://www.youtube.com/api/stats/watchtime?docid=$video"))
        assertFalse(isYoutubeAdRequest("https://rr1---sn.googlevideo.com/videoplayback?id=1"))
        assertFalse(isYoutubeAdRequest("https://evil.example/get_midroll_info"))
        assertFalse(isYoutubeAdRequest("https://evil.example/ad_break"))
    }

    @Test
    fun onlyCancelledOrBlockedErrorsCount() {
        assertTrue(isBlockedAdError("NS_ERROR_ABORT"))
        assertTrue(isBlockedAdError("NS_ERROR_BLOCKED_BY_CONTENT_BLOCKING"))
        assertTrue(isBlockedAdError("ns_error_blocked_by_policy"))
        assertTrue(isBlockedAdError("NS_ERROR_TRACKING_URI"))
        assertFalse(isBlockedAdError(""))
        assertFalse(isBlockedAdError("NS_ERROR_NET_RESET"))
        assertFalse(isBlockedAdError("NS_ERROR_CONNECTION_REFUSED"))
        assertFalse(isBlockedAdError("NS_ERROR_OFFLINE"))
        assertFalse(isBlockedAdError("NS_ERROR_UNKNOWN_HOST"))
    }

    @Test
    fun privateTabsAndOtherSitesAreNotClassified() {
        val ad = "https://www.youtube.com/api/stats/ads?ad_v=creative1&docid=$video"
        assertNull(classifyBlockedYoutubeAd(watch, "Talk", ad, "NS_ERROR_ABORT", isPrivate = true))
        assertNull(classifyBlockedYoutubeAd("https://example.com/", null, "https://ad.doubleclick.net/pagead/ads", "NS_ERROR_ABORT", false))
        assertNull(classifyBlockedYoutubeAd(watch, null, "https://www.youtube.com/api/stats/watchtime", "NS_ERROR_ABORT", false))
        assertNull(classifyBlockedYoutubeAd(watch, null, ad, "NS_ERROR_NET_RESET", false))
        assertEquals(video, classifyBlockedYoutubeAd(watch, "Talk", ad, "NS_ERROR_ABORT", false)?.videoId)
        assertEquals(
            video,
            classifyBlockedYoutubeAd(null, null, "https://www.youtube.com/api/stats/ads?docid=$video", "NS_ERROR_ABORT", false)?.videoId,
        )
        assertNull(classifyBlockedYoutubeAd(null, null, "https://ad.doubleclick.net/pagead/ads?video_id=$video", "NS_ERROR_ABORT", false))
        assertFalse(mightReportYoutubeAd("https://example.com/", "https://ad.doubleclick.net/pagead/ads"))
        assertTrue(mightReportYoutubeAd(watch, "https://ad.doubleclick.net/pagead/ads"))
    }

    @Test
    fun repeatedPingsForTheSameAdCountOnceAndANewAdCountsAgain() {
        val first = AdPing(video, "Talk", "ad_v:one", 0)
        val repeat = first.copy(atMillis = 5_000)
        val hoursLater = first.copy(atMillis = 3_600_000)
        val second = AdPing(video, "Talk", "ad_v:two", 1_000)
        var memory = applyAdPing(null, first).also { assertTrue(it.countedNewAd) }.memory
        memory = applyAdPing(memory, repeat).also { assertFalse(it.countedNewAd) }.memory
        memory = applyAdPing(memory, hoursLater).also { assertFalse(it.countedNewAd) }.memory
        memory = applyAdPing(memory, second).also { assertTrue(it.countedNewAd) }.memory
        assertEquals(2, memory.adCount)
        assertEquals(30L, memory.estimatedSeconds)
    }

    @Test
    fun untaggedPingsInsideTheGapAreOneBreakAndALaterOneIsAnother() {
        val inside = applyAdPing(applyAdPing(null, AdPing(video, null, null, 0)).memory, AdPing(video, null, null, AD_BREAK_GAP_MILLIS - 1))
        assertFalse(inside.countedNewAd)
        assertEquals(1, inside.memory.adCount)
        // The window is measured from the previous ping, so a ping exactly one gap later is a new break.
        val later = applyAdPing(applyAdPing(null, AdPing(video, null, null, 0)).memory, AdPing(video, null, null, AD_BREAK_GAP_MILLIS))
        assertTrue(later.countedNewAd)
        assertEquals(2, later.memory.adCount)
    }

    @Test
    fun anIdThatArrivesDuringAnUntaggedBurstDoesNotDoubleCount() {
        var memory = applyAdPing(null, AdPing(video, null, null, 0)).memory
        memory = applyAdPing(memory, AdPing(video, "Named", "ad_v:one", 4_000)).also { assertFalse(it.countedNewAd) }.memory
        memory = applyAdPing(memory, AdPing(video, null, "ad_v:one", 90_000)).also { assertFalse(it.countedNewAd) }.memory
        memory = applyAdPing(memory, AdPing(video, null, "ad_v:two", 91_000)).also { assertTrue(it.countedNewAd) }.memory
        assertEquals(2, memory.adCount)
        assertEquals("Named", memory.title)
    }

    @Test
    fun aCustomAdLengthChangesOnlyTheEstimate() {
        val result = applyAdPing(null, AdPing(video, null, "ad_v:one", 0), secondsPerAd = 10)
        assertEquals(10L, result.memory.estimatedSeconds)
    }

    @Test
    fun parsesObserverMessagesAndDropsIncognito() {
        val blocked =
            JSONObject()
                .put("type", "blocked-ad")
                .put("url", "https://www.youtube.com/pagead/interaction")
                .put("documentUrl", watch)
                .put("title", "Talk")
                .put("error", "NS_ERROR_ABORT")
                .put("at", 50L)
        val event = parseYoutubeAdEvent(blocked) as YoutubeAdEvent.Blocked
        assertEquals(watch, event.observation.pageUrl)
        assertEquals("Talk", event.observation.pageTitle)
        assertEquals(50L, event.observation.atMillis)
        assertFalse(event.isPrivate)
        assertNull(parseYoutubeAdEvent(JSONObject(blocked.toString()).put("incognito", true)))
        val title = parseYoutubeAdEvent(JSONObject().put("type", "title").put("url", watch).put("title", "Talk"))
        assertTrue(title is YoutubeAdEvent.Title)
        assertNull(parseYoutubeAdEvent(JSONObject().put("type", "other")))
        assertNull(parseYoutubeAdEvent(JSONObject().put("type", "blocked-ad")))
    }
}
