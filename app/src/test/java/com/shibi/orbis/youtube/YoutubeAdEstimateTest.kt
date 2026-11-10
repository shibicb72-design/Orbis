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
    fun recognisesBreakPingsAndIgnoresGenericAdHosts() {
        assertTrue(isBreakSpecificAdRequest("https://www.youtube.com/pagead/interaction?ai=1"))
        assertTrue(isBreakSpecificAdRequest("https://www.youtube.com/pagead/adview?ai=1"))
        assertTrue(isBreakSpecificAdRequest("https://www.youtube.com/api/stats/ads?event=start"))
        assertTrue(isBreakSpecificAdRequest("https://m.youtube.com/get_midroll_info?ei=1"))
        assertTrue(isBreakSpecificAdRequest("https://www.youtube.com/youtubei/v1/player/ad_break"))
        assertTrue(isBreakSpecificAdRequest("https://ad.doubleclick.net/pagead/adview?ai=1"))
        assertFalse(isBreakSpecificAdRequest("https://googleads.g.doubleclick.net/pagead/id"))
        assertFalse(isBreakSpecificAdRequest("https://ad.doubleclick.net/pagead/ads"))
        assertFalse(isBreakSpecificAdRequest("https://static.doubleclick.net/instream/ad_status.js"))
        assertFalse(isBreakSpecificAdRequest("https://www.googleadservices.com/pagead/aclk"))
        assertFalse(isBreakSpecificAdRequest("https://tpc.googlesyndication.com/sodar/x"))
        assertFalse(isBreakSpecificAdRequest("https://www.youtube.com/watch?v=$video"))
        assertFalse(isBreakSpecificAdRequest("https://www.youtube.com/api/stats/watchtime?docid=$video"))
        assertFalse(isBreakSpecificAdRequest("https://rr1---sn.googlevideo.com/videoplayback?id=1"))
        assertFalse(isBreakSpecificAdRequest("https://evil.example/get_midroll_info"))
        assertFalse(isBreakSpecificAdRequest("https://evil.example/ad_break"))
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
        assertNull(classifyBlockedYoutubeAd(null, null, "https://www.youtube.com/api/stats/ads?docid=$video&ad_v=c", "NS_ERROR_ABORT", false))
        assertNull(classifyBlockedYoutubeAd(watch, null, "https://www.youtube.com/api/stats/ads?docid=$video", "NS_ERROR_ABORT", false))
        assertNull(classifyBlockedYoutubeAd(watch, null, "https://googleads.g.doubleclick.net/pagead/id?video_id=$video", "NS_ERROR_ABORT", false))
        assertNull(classifyBlockedYoutubeAd("https://www.youtube.com/", null, ad, "NS_ERROR_ABORT", false))
        assertFalse(mightReportYoutubeAd(null, ad))
        assertFalse(mightReportYoutubeAd("https://example.com/", "https://ad.doubleclick.net/pagead/ads"))
        assertFalse(mightReportYoutubeAd(watch, "https://googleads.g.doubleclick.net/pagead/id"))
        assertFalse(mightReportYoutubeAd(watch, "https://static.doubleclick.net/instream/ad_status.js"))
        assertTrue(mightReportYoutubeAd(watch, ad))
    }

    @Test
    fun aCachedTitleCountsOnlyForTheSameVideo() {
        assertEquals("Talk", titleForSameVideo("Talk", watch, watch))
        assertNull(titleForSameVideo("Old", "https://www.youtube.com/watch?v=bbbbbbbbbbb", watch))
        assertNull(titleForSameVideo("Talk", null, watch))
        assertNull(titleForSameVideo("Talk", watch, null))
    }

    @Test
    fun repeatedPingsForTheSameAdCountOnceAndAGapDoesNotAddAnother() {
        val first = AdPing(video, "Talk", "ad_v:one", 0)
        val repeat = first.copy(atMillis = 5_000)
        val hoursLater = first.copy(atMillis = 3_600_000)
        val second = AdPing(video, "Talk", "ad_v:two", 1_000)
        val untagged = AdPing(video, "Talk", null, 20_000)
        var memory = applyAdPing(null, first).also { assertEquals(1, it.addedAds) }.memory
        memory = applyAdPing(memory, repeat).also { assertEquals(0, it.addedAds) }.memory
        memory = applyAdPing(memory, hoursLater).also { assertEquals(0, it.addedAds) }.memory
        memory = applyAdPing(memory, untagged).also { assertEquals(0, it.addedAds) }.memory
        memory = applyAdPing(memory, second).also { assertEquals(1, it.addedAds) }.memory
        assertEquals(2, memory.adCount)
        assertEquals(30L, memory.estimatedSeconds)
    }

    @Test
    fun playerSlotsCountOncePerViewAndALaterResponseCountsAgain() {
        val first = applyPlayerSlots(null, video, "Talk", listOf("slot:a", "slot:a", "slot:b"), 1_000)
        assertEquals(2, first.addedAds)
        assertEquals(30L, first.memory.estimatedSeconds)
        val sameView = applyPlayerSlots(first.memory, video, "Talk", listOf("slot:a", "slot:b"), 1_000 + SAME_VIEW_WINDOW_MILLIS - 1)
        assertEquals(0, sameView.addedAds)
        assertEquals(2, sameView.memory.adCount)
        val extraInView = applyPlayerSlots(sameView.memory, video, "Talk", listOf("slot:a", "slot:c"), 1_000 + SAME_VIEW_WINDOW_MILLIS - 1)
        assertEquals(1, extraInView.addedAds)
        assertEquals(3, extraInView.memory.adCount)
        val revisit = applyPlayerSlots(extraInView.memory, video, "Talk", listOf("slot:a", "slot:b"), extraInView.memory.lastPlayerAtMillis + SAME_VIEW_WINDOW_MILLIS)
        assertEquals(2, revisit.addedAds)
        assertEquals(5, revisit.memory.adCount)
        val ping = applyAdPing(revisit.memory, AdPing(video, null, "slot:a", revisit.memory.timestampMillis + 10))
        assertEquals(0, ping.addedAds)
        assertEquals(5, ping.memory.adCount)
    }

    @Test
    fun aPlayerResponseReplacesFallbackPingsFromTheSameView() {
        val ping = applyAdPing(null, AdPing(video, "Talk", "ad_v:one", 1_000))
        assertEquals(1, ping.addedAds)
        val player = applyPlayerSlots(ping.memory, video, "Talk", listOf("slot:a", "slot:b"), 1_500)
        assertEquals(1, player.addedAds)
        assertEquals(2, player.memory.adCount)
        assertEquals(30L, player.memory.estimatedSeconds)
        assertTrue(player.memory.viewFromPlayer)
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
        val slots = org.json.JSONArray().put("slot:a").put("slot:a").put("slot:b").put("")
        val player =
            parseYoutubeAdEvent(
                JSONObject()
                    .put("type", "player-ads")
                    .put("videoId", video)
                    .put("title", "Talk")
                    .put("pageUrl", watch)
                    .put("slots", slots)
                    .put("at", 9L),
            ) as YoutubeAdEvent.PlayerAds
        assertEquals(listOf("slot:a", "slot:b"), player.slotIds)
        assertEquals("Talk", player.title)
        assertEquals(9L, player.atMillis)
        assertNull(parseYoutubeAdEvent(JSONObject().put("type", "player-ads").put("videoId", "short").put("slots", slots)))
        assertNull(parseYoutubeAdEvent(JSONObject(JSONObject().put("type", "player-ads").put("videoId", video).toString()).put("incognito", true)))
        assertEquals("ad_v:a/b", explicitAdId("https://www.youtube.com/api/stats/ads?ad_v=a%2Fb"))
    }
}
