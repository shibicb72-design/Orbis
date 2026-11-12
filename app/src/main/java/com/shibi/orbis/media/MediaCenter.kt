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

/** The video shape for picture-in-picture: only a playing video in page fullscreen; 16:9 when the size is unknown. */
fun pipShapeOf(tab: MediaTab?, fullScreen: Boolean): Pair<Long, Long>? {
    val m = tab?.media ?: return null
    if (!fullScreen || !m.playing || !m.hasVideo) return null
    return if (m.videoWidth > 0 && m.videoHeight > 0) m.videoWidth to m.videoHeight else 16L to 9L
}

/**
 * [pipShapeOf] kept current: recomputed when the media changes and when the selected tab enters or leaves fullscreen.
 * Fullscreen must be followed too, because entering or leaving it often leaves the media itself unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun pipShapes(media: Flow<MediaTab?>, selectedSession: Flow<BrowserSession?>): Flow<Pair<Long, Long>?> {
    val fullScreen = selectedSession.flatMapLatest { s -> s?.state?.map { it.fullScreen } ?: flowOf(false) }
    return combine(media, fullScreen.distinctUntilChanged(), ::pipShapeOf).distinctUntilChanged()
}

/** Android accepts picture-in-picture ratios between 1:2.39 and 2.39:1; anything outside is clamped. */
fun pipAspectRatio(width: Long, height: Long): android.util.Rational {
    val r = (width.toDouble() / height.toDouble()).coerceIn(1 / 2.39, 2.39)
    return android.util.Rational((r * 1000).toInt(), 1000)
}
