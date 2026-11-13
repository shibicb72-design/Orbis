package com.shibi.orbis.media

import android.view.View
import com.shibi.orbis.engine.BrowserSession
import com.shibi.orbis.engine.MediaState
import com.shibi.orbis.engine.SessionHost
import com.shibi.orbis.engine.SessionState
import com.shibi.orbis.engine.SitePrompt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private object NoSession : BrowserSession {
    override val isPrivate = false
    override val state: StateFlow<SessionState> = MutableStateFlow(SessionState())
    override val prompts: StateFlow<SitePrompt?> = MutableStateFlow(null)
    override var host: SessionHost? = null
    override fun attachTo(contentView: View) = Unit
    override fun detachFrom(contentView: View) = Unit
    override fun loadUrl(url: String) = Unit
    override fun loadExternalUrl(url: String) = Unit
    override fun setDesktopMode(enabled: Boolean) = Unit
    override fun goBack() = false
    override fun goForward() = false
    override fun reload() = Unit
    override fun stop() = Unit
    override fun find(query: String, backwards: Boolean) = Unit
    override fun clearFind() = Unit
    override fun setActive(active: Boolean) = Unit
    override fun savedState(): String? = null
    override fun close() = Unit
}

/** A session whose fullscreen flag the test flips. */
private class FullScreenSession : BrowserSession by NoSession {
    override val state = MutableStateFlow(SessionState())
    fun fullScreen(on: Boolean) = state.update { it.copy(fullScreen = on) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MediaCenterTest {
    private fun tab(
        id: Long,
        playing: Boolean,
        private: Boolean = false,
        video: Boolean = false,
        w: Long = 0,
        h: Long = 0,
        session: BrowserSession = NoSession,
    ) = MediaTab(id, private, MediaState(playing, title = "Song $id", artist = "Band", hasVideo = video, videoWidth = w, videoHeight = h), session)

    @Test
    fun aPlayingTabWinsTheSelectedOneFirst() {
        assertEquals(2L, pickMediaTab(listOf(tab(1, false), tab(2, true), tab(3, true)), selectedTabId = 2)?.tabId)
        assertEquals(3L, pickMediaTab(listOf(tab(1, false), tab(3, true)), selectedTabId = 1)?.tabId)
        assertEquals("nothing playing: the selected tab's paused media", 1L, pickMediaTab(listOf(tab(4, false), tab(1, false)), selectedTabId = 1)?.tabId)
        assertNull(pickMediaTab(emptyList(), selectedTabId = 1))
    }

    /** Animated scenes pause while any tab plays, audio included: an inline video never reports hasVideo. */
    @Test
    fun anyTabPlayingAnythingCountsAsMediaPlaying() {
        assertEquals("no media", false, anyMediaPlaying(emptyList()))
        assertEquals("a paused tab", false, anyMediaPlaying(listOf(tab(1, false, video = true))))
        assertEquals("audio only", true, anyMediaPlaying(listOf(tab(1, true, video = false))))
        // The selected tab (1) is paused; a background tab (2) plays. MediaCenter.current would still pick tab 2 here,
        // but a video in tab 2 behind audio in tab 1 would be hidden from current: the flag looks at every tab.
        assertEquals("selected paused, another playing", true, anyMediaPlaying(listOf(tab(1, false), tab(2, true))))
        assertEquals("selected playing audio, another playing video", true, anyMediaPlaying(listOf(tab(1, true), tab(2, true, video = true))))
    }

    @Test
    fun aPrivateTabNeverShowsItsTitleInTheNotification() {
        assertEquals("Playing in a private tab" to "", mediaNotificationText(tab(1, true, private = true), "Playing in a private tab", "Playing in Orbis"))
        assertEquals("Song 1" to "Band", mediaNotificationText(tab(1, true), "P", "Playing in Orbis"))
        val untitled = MediaTab(1, false, MediaState(true), NoSession)
        assertEquals("Playing in Orbis" to "", mediaNotificationText(untitled, "P", "Playing in Orbis"))
    }

    @Test
    fun pictureInPictureForMediaPlayingInTheSelectedFullscreenTab() {
        assertEquals(1280L to 720L, pipShapeOf(tab(1, true, video = true, w = 1280, h = 720), NoSession, fullScreen = true))
        assertEquals("size unknown", 16L to 9L, pipShapeOf(tab(1, true, video = true), NoSession, fullScreen = true))
        assertEquals("site player container fullscreen: no video reported", 16L to 9L, pipShapeOf(tab(1, true), NoSession, fullScreen = true))
        assertNull("not fullscreen", pipShapeOf(tab(1, true, video = true), NoSession, fullScreen = false))
        assertNull("paused", pipShapeOf(tab(1, false, video = true), NoSession, fullScreen = true))
        assertNull("media of a background tab", pipShapeOf(tab(1, true, video = true), FullScreenSession(), fullScreen = true))
        assertNull("no media", pipShapeOf(null, NoSession, fullScreen = true))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pictureInPictureFollowsFullscreenEvenWhenTheMediaDoesNotChange() = runTest {
        val session = FullScreenSession()
        val media = MutableStateFlow<MediaTab?>(tab(1, true, video = true, w = 1280, h = 720, session = session))
        val shapes = mutableListOf<Pair<Long, Long>?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            pipShapes(media, MutableStateFlow(session)).collect { shapes += it }
        }
        session.fullScreen(true)
        session.fullScreen(false)
        session.fullScreen(true)
        media.value = tab(1, false, video = true, w = 1280, h = 720, session = session)
        assertEquals(listOf(null, 1280L to 720L, null, 1280L to 720L, null), shapes)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun containerFullscreenWithMediaPlayingArmsPictureInPicture() = runTest {
        // A site's own player container goes fullscreen: the engine reports page fullscreen but never a video.
        val session = FullScreenSession()
        val shapes = mutableListOf<Pair<Long, Long>?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            pipShapes(MutableStateFlow(tab(1, true, session = session)), MutableStateFlow(session)).collect { shapes += it }
        }
        session.fullScreen(true)
        session.fullScreen(false)
        assertEquals(listOf(null, 16L to 9L, null), shapes)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pausedMediaInAFullscreenPageDoesNotArmPictureInPicture() = runTest {
        val session = FullScreenSession().apply { fullScreen(true) }
        val shapes = mutableListOf<Pair<Long, Long>?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            pipShapes(MutableStateFlow(tab(1, false, session = session)), MutableStateFlow(session)).collect { shapes += it }
        }
        assertEquals(listOf<Pair<Long, Long>?>(null), shapes)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pictureInPictureFollowsTheSelectedTab() = runTest {
        val playing = FullScreenSession().apply { fullScreen(true) }
        val other = FullScreenSession().apply { fullScreen(true) }
        val selected = MutableStateFlow<BrowserSession?>(other)
        val shapes = mutableListOf<Pair<Long, Long>?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            pipShapes(MutableStateFlow(tab(1, true, video = true, session = playing)), selected).collect { shapes += it }
        }
        // The media plays in a background tab while another fullscreen tab is selected: no PiP.
        selected.value = playing
        selected.value = null
        assertEquals(listOf(null, 16L to 9L, null), shapes)
    }

    @Test
    fun aspectRatiosAreClampedToWhatAndroidAccepts() {
        assertEquals(1.778f, pipAspectRatio(16, 9).toFloat(), 0.002f)
        assertEquals(2.39f, pipAspectRatio(4000, 100).toFloat(), 0.002f)
        assertEquals(1 / 2.39f, pipAspectRatio(100, 4000).toFloat(), 0.002f)
    }
}
