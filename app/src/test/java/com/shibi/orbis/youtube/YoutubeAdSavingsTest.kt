package com.shibi.orbis.youtube

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class YoutubeAdSavingsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun TestScope.savings(file: File = File(tmp.root, "yt.json"), secondsPerAd: Int = AVERAGE_YOUTUBE_AD_SECONDS): YoutubeAdSavings {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return YoutubeAdSavings(file, CoroutineScope(dispatcher + SupervisorJob()), dispatcher, secondsPerAd = secondsPerAd)
    }

    private fun blocked(
        videoId: String = "aaaaaaaaaaa",
        request: String = "https://www.youtube.com/api/stats/ads?ad_v=creative1&docid=$videoId",
        title: String? = "A talk",
        error: String = "NS_ERROR_ABORT",
        isPrivate: Boolean = false,
        at: Long = 1_000,
        page: String? = "https://www.youtube.com/watch?v=$videoId",
    ) = YoutubeAdEvent.Blocked(YoutubeAdObservation(page, title, request, isPrivate, error, at))

    @Test
    fun countsDistinctAdsAndPersistsThem() = runTest {
        val file = File(tmp.root, "yt.json")
        val saved = savings(file)
        saved.load()
        advanceUntilIdle()
        saved.onEvent(blocked(at = 1_000))
        saved.onEvent(blocked(request = "https://www.youtube.com/api/stats/ads?ad_v=creative1&event=complete", at = 2_000))
        saved.onEvent(blocked(request = "https://www.youtube.com/pagead/interaction?ad_v=creative2", title = null, at = 3_000))
        val summary = saved.summary()
        assertEquals(2L, summary.totalAds)
        assertEquals(30L, summary.totalEstimatedSeconds)
        assertEquals("30 s", formatEstimatedTimeSaved(summary.totalEstimatedSeconds))
        assertEquals(1, summary.recent.size)
        assertEquals("A talk", summary.recent[0].title)
        assertEquals(2, summary.recent[0].adCount)
        assertEquals(30L, summary.recent[0].estimatedSeconds)
        advanceUntilIdle()
        val reloaded = savings(file)
        reloaded.load()
        advanceUntilIdle()
        assertEquals(2L, reloaded.summary().totalAds)
        assertEquals(30L, reloaded.summary().totalEstimatedSeconds)
        assertEquals("A talk", reloaded.summary().recent[0].title)
        reloaded.onEvent(blocked(request = "https://www.youtube.com/api/stats/ads?ad_v=creative1&event=skip", at = 50_000))
        assertEquals(2L, reloaded.summary().totalAds)
    }

    @Test
    fun privateTabsAreNotRecordedOrRemembered() = runTest {
        val saved = savings()
        saved.load()
        advanceUntilIdle()
        saved.onEvent(blocked(videoId = "secretvid11", title = "Secret", isPrivate = true, at = 1_000))
        saved.onEvent(YoutubeAdEvent.Title("https://www.youtube.com/watch?v=secretvid11", "Secret", isPrivate = true))
        saved.onEvent(blocked(videoId = "bbbbbbbbbbb", title = null, at = 2_000))
        advanceUntilIdle()
        assertEquals(1L, saved.summary().totalAds)
        assertEquals("bbbbbbbbbbb", saved.summary().recent[0].videoId)
        assertNull(saved.summary().recent[0].title)
        val text = File(tmp.root, "yt.json").readText()
        assertFalse(text.contains("secretvid11"))
        assertFalse(text.contains("Secret"))
        assertTrue(text.contains("bbbbbbbbbbb"))
    }

    @Test
    fun aTitleSeenBeforeTheAdIsKeptAndAPrivateTitleIsNot() = runTest {
        val saved = savings()
        saved.load()
        advanceUntilIdle()
        saved.onEvent(YoutubeAdEvent.Title("https://m.youtube.com/watch?v=aaaaaaaaaaa", "From the page", isPrivate = false), nowMillis = 500)
        saved.onEvent(blocked(title = null, at = 1_000))
        assertEquals("From the page", saved.summary().recent.single().title)
        val other = savings(File(tmp.root, "other.json"))
        other.load()
        advanceUntilIdle()
        other.onEvent(YoutubeAdEvent.Title("https://www.youtube.com/watch?v=ccccccccccc", "Hidden", isPrivate = true))
        other.onEvent(blocked(videoId = "ccccccccccc", title = null, at = 1_000))
        assertNull(other.summary().recent.single().title)
    }

    @Test
    fun quotesInTheTitleSurviveARestart() = runTest {
        val file = File(tmp.root, "quotes.json")
        val saved = savings(file)
        saved.load()
        advanceUntilIdle()
        saved.onEvent(blocked(title = "He said \"hi\" & <tag>", at = 5))
        advanceUntilIdle()
        val reloaded = savings(file)
        reloaded.load()
        advanceUntilIdle()
        assertEquals("He said \"hi\" & <tag>", reloaded.summary().recent.single().title)
    }

    @Test
    fun clearWipesMemoryAndTheFile() = runTest {
        val file = File(tmp.root, "clear.json")
        val saved = savings(file)
        saved.load()
        advanceUntilIdle()
        saved.onEvent(blocked())
        advanceUntilIdle()
        saved.clear()
        advanceUntilIdle()
        assertEquals(0L, saved.summary().totalAds)
        assertTrue(saved.summary().recent.isEmpty())
        assertEquals(0L, JSONObject(file.readText()).getLong("totalAds"))
        val reloaded = savings(file)
        reloaded.load()
        advanceUntilIdle()
        assertEquals(0L, reloaded.summary().totalAds)
        assertTrue(reloaded.summary().recent.isEmpty())
    }

    @Test
    fun clearBeforeLoadDropsWhatWasAlreadyStored() = runTest {
        val file = File(tmp.root, "early.json")
        val first = savings(file)
        first.load()
        advanceUntilIdle()
        first.onEvent(blocked(title = "Gone"))
        advanceUntilIdle()
        val second = savings(file)
        second.clear()
        advanceUntilIdle()
        second.load()
        advanceUntilIdle()
        assertEquals(0L, second.summary().totalAds)
        second.onEvent(blocked(videoId = "bbbbbbbbbbb", title = null, at = 9))
        advanceUntilIdle()
        val third = savings(file)
        third.load()
        advanceUntilIdle()
        assertEquals(1L, third.summary().totalAds)
        assertEquals("bbbbbbbbbbb", third.summary().recent.single().videoId)
        assertFalse(file.readText().contains("aaaaaaaaaaa"))
    }

    @Test
    fun eventsAfterClearAndBeforeLoadAreKept() = runTest {
        val file = File(tmp.root, "gap.json")
        val first = savings(file)
        first.load()
        advanceUntilIdle()
        first.onEvent(blocked(title = "Old"))
        advanceUntilIdle()
        val second = savings(file)
        second.load()
        second.onEvent(blocked(title = "Queued too early", at = 2))
        second.clear()
        second.onEvent(blocked(videoId = "bbbbbbbbbbb", title = "New", at = 9))
        advanceUntilIdle()
        assertEquals(1L, second.summary().totalAds)
        assertEquals("bbbbbbbbbbb", second.summary().recent.single().videoId)
        assertEquals("New", second.summary().recent.single().title)
        assertFalse(file.readText().contains("aaaaaaaaaaa"))
        assertFalse(file.readText().contains("Old"))
    }

    @Test
    fun playerSlotsCountAndAPingForThatViewDoesNot() = runTest {
        val saved = savings()
        saved.load()
        advanceUntilIdle()
        saved.onEvent(YoutubeAdEvent.PlayerAds("aaaaaaaaaaa", "Talk", "https://www.youtube.com/watch?v=aaaaaaaaaaa", listOf("slot:a", "slot:b"), false, 1_000))
        saved.onEvent(blocked(request = "https://www.youtube.com/api/stats/ads?ad_v=creative9", at = 1_200))
        assertEquals(2L, saved.summary().totalAds)
        assertEquals(30L, saved.summary().totalEstimatedSeconds)
        saved.onEvent(
            YoutubeAdEvent.PlayerAds(
                "aaaaaaaaaaa",
                "Talk",
                "https://www.youtube.com/watch?v=aaaaaaaaaaa",
                listOf("slot:a", "slot:b"),
                false,
                1_000 + SAME_VIEW_WINDOW_MILLIS,
            ),
        )
        assertEquals(4L, saved.summary().totalAds)
        assertEquals(60L, saved.summary().totalEstimatedSeconds)
        assertEquals("1 min", formatEstimatedTimeSaved(saved.summary().totalEstimatedSeconds))
    }

    @Test
    fun aNewViewTokenCountsAgainAndTheSameTokenDoesNot() = runTest {
        val saved = savings()
        saved.load()
        advanceUntilIdle()
        val watch = "https://www.youtube.com/watch?v=aaaaaaaaaaa"
        saved.onEvent(YoutubeAdEvent.ViewStart("aaaaaaaaaaa", watch, "Talk", "view-1", false, 1_000))
        saved.onEvent(YoutubeAdEvent.PlayerAds("aaaaaaaaaaa", "Talk", watch, listOf("slot:a"), false, 1_100, "view-1"))
        saved.onEvent(
            YoutubeAdEvent.PlayerAds(
                "aaaaaaaaaaa",
                "Talk",
                watch,
                listOf("slot:a"),
                false,
                1_100 + SAME_VIEW_WINDOW_MILLIS + 5_000,
                "view-1",
            ),
        )
        assertEquals(1L, saved.summary().totalAds)
        saved.onEvent(YoutubeAdEvent.ViewStart("aaaaaaaaaaa", watch, "Talk", "view-2", false, 20_000))
        saved.onEvent(blocked(request = "https://www.youtube.com/api/stats/ads?ad_v=creative9", at = 20_100))
        assertEquals(2L, saved.summary().totalAds)
        saved.onEvent(YoutubeAdEvent.PlayerAds("aaaaaaaaaaa", "Talk", watch, listOf("slot:a"), false, 20_200, "view-2"))
        assertEquals(2L, saved.summary().totalAds)
        assertTrue(saved.encode().contains("view-2"))
    }

    @Test
    fun anEventBeforeLoadIsKeptAndAPrivateOneIsNot() = runTest {
        val saved = savings()
        saved.onEvent(blocked(isPrivate = true))
        saved.onEvent(blocked(at = 4_000))
        saved.load()
        advanceUntilIdle()
        assertEquals(1L, saved.summary().totalAds)
        assertEquals(15L, saved.summary().totalEstimatedSeconds)
    }

    @Test
    fun networkFailuresAndOtherSitesDoNotCount() = runTest {
        val saved = savings()
        saved.load()
        advanceUntilIdle()
        saved.onEvent(blocked(error = "NS_ERROR_NET_RESET"))
        saved.onEvent(
            blocked(
                page = "https://example.com/watch?v=aaaaaaaaaaa",
                request = "https://ad.doubleclick.net/pagead/ads",
            ),
        )
        assertEquals(0L, saved.summary().totalAds)
    }

    @Test
    fun aCustomLengthIsWhatGetsStored() = runTest {
        val saved = savings(secondsPerAd = 10)
        saved.load()
        advanceUntilIdle()
        saved.onEvent(blocked())
        assertEquals(10L, saved.summary().totalEstimatedSeconds)
        assertEquals(10L, saved.summary().recent.single().estimatedSeconds)
    }

    @Test
    fun oldRowsAreDroppedButTheLifetimeTotalStays() = runTest {
        val saved = savings()
        saved.load()
        advanceUntilIdle()
        for (n in 0..VIDEOS_KEPT) {
            val id = "v" + n.toString().padStart(10, '0')
            saved.onEvent(blocked(videoId = id, title = null, at = n.toLong() + 1))
        }
        assertEquals((VIDEOS_KEPT + 1).toLong(), saved.summary().totalAds)
        assertEquals(RECENT_VIDEOS_SHOWN, saved.summary().recent.size)
        assertEquals("v" + VIDEOS_KEPT.toString().padStart(10, '0'), saved.summary().recent.first().videoId)
        val stored = JSONObject(saved.encode())
        assertEquals(VIDEOS_KEPT, stored.getJSONArray("videos").length())
        assertEquals((VIDEOS_KEPT + 1).toLong(), stored.getLong("totalAds"))
        assertFalse(saved.encode().contains("v0000000000"))
    }

    @Test
    fun twoVideosStaySeparate() = runTest {
        val saved = savings()
        saved.load()
        advanceUntilIdle()
        saved.onEvent(blocked(videoId = "aaaaaaaaaaa", at = 1))
        saved.onEvent(blocked(videoId = "bbbbbbbbbbb", title = "Second", at = 2))
        assertEquals(2L, saved.summary().totalAds)
        assertEquals(listOf("bbbbbbbbbbb", "aaaaaaaaaaa"), saved.summary().recent.map { it.videoId })
    }
}
