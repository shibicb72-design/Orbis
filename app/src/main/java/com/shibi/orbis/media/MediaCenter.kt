package com.shibi.orbis.media

import com.shibi.orbis.engine.BrowserSession
import com.shibi.orbis.engine.MediaState
import com.shibi.orbis.tabs.TabManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/** The tab whose media the notification, lock screen and picture-in-picture control. */
data class MediaTab(val tabId: Long, val isPrivate: Boolean, val media: MediaState, val session: BrowserSession)

/**
 * Which tab's media is "the" media: one that is playing wins (the selected tab first), else the selected tab's paused
 * media, else any paused media. Pure, so the choice is testable.
 */
fun pickMediaTab(candidates: List<MediaTab>, selectedTabId: Long?): MediaTab? =
    candidates.firstOrNull { it.media.playing && it.tabId == selectedTabId }
        ?: candidates.firstOrNull { it.media.playing }
        ?: candidates.firstOrNull { it.tabId == selectedTabId }
        ?: candidates.firstOrNull()

/** Text the notification shows: a private tab's page title or site never reaches the notification or lock screen. */
fun mediaNotificationText(tab: MediaTab, privateLabel: String, fallbackTitle: String): Pair<String, String> =
    if (tab.isPrivate) privateLabel to "" else tab.media.title.ifBlank { fallbackTitle } to tab.media.artist

/**
 * Follows the media of every open tab and publishes [current]. Main thread. The notification service and the
 * activity (background playback, picture-in-picture) read it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaCenter(private val tabs: TabManager, scope: CoroutineScope) {
    private val _current = MutableStateFlow<MediaTab?>(null)
    val current: StateFlow<MediaTab?> = _current.asStateFlow()

    init {
        tabs.tabs
            .flatMapLatest { list ->
                val pairs = list.mapNotNull { t -> tabs.sessionOf(t.id)?.let { s -> Triple(t.id, t.isPrivate, s) } }
                if (pairs.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(pairs.map { (id, private, s) -> s.state.toMediaTab(id, private, s) }) { arr -> arr.filterNotNull() }
                }
            }
            .onEach { candidates -> _current.value = pickMediaTab(candidates, tabs.selected.value.id) }
            .launchIn(scope)
    }

    private fun StateFlow<com.shibi.orbis.engine.SessionState>.toMediaTab(id: Long, private: Boolean, s: BrowserSession) =
        kotlinx.coroutines.flow.flow {
            collect { st -> emit(st.media?.let { MediaTab(id, private, it, s) }) }
        }

    fun play() = _current.value?.session?.mediaPlay()

    fun pause() = _current.value?.session?.mediaPause()

    fun stop() = _current.value?.session?.mediaStop()
}

/**
 * The shape for picture-in-picture: media playing in the selected tab while that tab's page is fullscreen; the video's
 * size when known, else 16:9. The page, not only the video element, may be fullscreen (a site's own player container),
 * and then the engine never reports a video, so a fullscreen page playing only audio arms it too.
 */
fun pipShapeOf(tab: MediaTab?, selected: BrowserSession?, fullScreen: Boolean): Pair<Long, Long>? {
    val m = tab?.media ?: return null
    if (!fullScreen || !m.playing || tab.session !== selected) return null
    return if (m.videoWidth > 0 && m.videoHeight > 0) m.videoWidth to m.videoHeight else 16L to 9L
}

/**
 * The shape for a picture-in-picture window the user asked for: the selected tab's page is fullscreen and has media,
 * playing or paused; the video's size when known, else 16:9. Home still auto-enters only for [pipShapeOf].
 */
fun manualPipShapeOf(media: MediaState?, fullScreen: Boolean): Pair<Long, Long>? {
    if (!fullScreen || media == null) return null
    return if (media.videoWidth > 0 && media.videoHeight > 0) media.videoWidth to media.videoHeight else 16L to 9L
}

/**
 * [pipShapeOf] kept current: recomputed when the media changes and when the selected tab enters or leaves fullscreen.
 * Fullscreen must be followed too, because entering or leaving it often leaves the media itself unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun pipShapes(media: Flow<MediaTab?>, selectedSession: Flow<BrowserSession?>): Flow<Pair<Long, Long>?> {
    val selectedFullScreen =
        selectedSession.flatMapLatest { s -> s?.state?.map { s to it.fullScreen } ?: flowOf(null to false) }
            .distinctUntilChanged()
    return combine(media, selectedFullScreen) { tab, (s, fullScreen) -> pipShapeOf(tab, s, fullScreen) }
        .distinctUntilChanged()
}

/** Android accepts picture-in-picture ratios between 1:2.39 and 2.39:1; anything outside is clamped. */
fun pipAspectRatio(width: Long, height: Long): android.util.Rational {
    val r = (width.toDouble() / height.toDouble()).coerceIn(1 / 2.39, 2.39)
    return android.util.Rational((r * 1000).toInt(), 1000)
}
