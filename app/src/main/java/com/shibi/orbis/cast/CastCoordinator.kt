package com.shibi.orbis.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CastDeviceKind { GOOGLE_CAST, DLNA }

data class CastDeviceItem(val id: String, val name: String, val kind: CastDeviceKind)

data class CastPlayback(
    val deviceName: String,
    val title: String,
    val playing: Boolean,
    val positionSec: Double,
    val durationSec: Double,
    val volume: Float,
    val youtube: Boolean,
    val kind: CastDeviceKind,
)

enum class CastNotice {
    NO_MEDIA,
    NO_DEVICES,
    YOUTUBE_NEEDS_GOOGLE,
    FAILED,
    GOOGLE_UNAVAILABLE,
}

sealed interface CastPhase {
    data object Idle : CastPhase
    data object NoMedia : CastPhase
    data class Picker(val youtube: Boolean, val searching: Boolean) : CastPhase
    data object Connecting : CastPhase
    data object Controls : CastPhase
}

/**
 * In-memory cast session. Private tabs may cast: the page address is sent only to the chosen device and is not written
 * to disk, history, or the error log. The phone notification for a private tab does not include the page title.
 */
class CastCoordinator(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val google = GoogleCastBridge(appContext)
    private val dlna = DlnaClient(appContext)

    private val _phase = MutableStateFlow<CastPhase>(CastPhase.Idle)
    val phase: StateFlow<CastPhase> = _phase.asStateFlow()

    private val _devices = MutableStateFlow<List<CastDeviceItem>>(emptyList())
    val devices: StateFlow<List<CastDeviceItem>> = _devices.asStateFlow()

    private val _playback = MutableStateFlow<CastPlayback?>(null)
    val playback: StateFlow<CastPlayback?> = _playback.asStateFlow()

    private val _notice = MutableStateFlow<CastNotice?>(null)
    val notice: StateFlow<CastNotice?> = _notice.asStateFlow()

    private var target: CastTarget? = null
    private var privateTab = false
    private var discovery: Job? = null
    private var poll: Job? = null
    private var dlnaRenderer: DlnaRenderer? = null
    private var activeKind: CastDeviceKind? = null
    private var deviceName: String = ""
    private var shownTitle: String = ""
    private var youtube = false
    private val googleRoutes = LinkedHashMap<String, GoogleCastRoute>()
    private val dlnaById = LinkedHashMap<String, DlnaRenderer>()

    init {
        google.onStatus = { status ->
            main.post {
                val current = _playback.value ?: return@post
                if (current.kind != CastDeviceKind.GOOGLE_CAST) return@post
                publish(
                    current.copy(
                        playing = status.playing,
                        positionSec = status.positionSec,
                        durationSec = status.durationSec.takeIf { it > 0 } ?: current.durationSec,
                        volume = status.volume,
                    ),
                )
            }
        }
        google.onSessionEnded = {
            main.post {
                if (activeKind == CastDeviceKind.GOOGLE_CAST && _phase.value != CastPhase.Connecting) {
                    clearPlayback()
                }
            }
        }
        google.onRoutesChanged = {
            if (_phase.value is CastPhase.Picker) publishDevices(discovery?.isActive == true)
        }
    }

    /** Opens the picker for [pageUrl], or the controls when a session is already running. */
    fun present(pageUrl: String, isPrivate: Boolean, probeJson: String?) {
        if (_playback.value != null) {
            _phase.value = CastPhase.Controls
            _notice.value = null
            return
        }
        openPicker(pageUrl, isPrivate, probeJson)
    }

    /** Replaces the current choice with whatever the page can cast now. */
    fun castThisPage(pageUrl: String, isPrivate: Boolean, probeJson: String?) {
        disconnect()
        openPicker(pageUrl, isPrivate, probeJson)
    }

    fun choose(deviceId: String, onLocalPause: () -> Unit) {
        if (_phase.value == CastPhase.Connecting) return
        val item = _devices.value.firstOrNull { it.id == deviceId } ?: return
        val chosen = target ?: return
        if (chosen is CastTarget.YouTube && item.kind != CastDeviceKind.GOOGLE_CAST) {
            _notice.value = CastNotice.YOUTUBE_NEEDS_GOOGLE
            return
        }
        _notice.value = null
        _phase.value = CastPhase.Connecting
        deviceName = item.name
        when (chosen) {
            is CastTarget.Direct ->
                when (item.kind) {
                    CastDeviceKind.GOOGLE_CAST ->
                        google.castDirect(
                            routeId = item.id,
                            media = chosen.media,
                            displayTitle = shownTitle,
                            poster = if (privateTab) null else chosen.media.poster,
                            onReady = { main.post { started(CastDeviceKind.GOOGLE_CAST, onLocalPause) } },
                            onFailed = { main.post { failed() } },
                        )
                    CastDeviceKind.DLNA -> {
                        val renderer = dlnaById[item.id]
                        if (renderer == null) {
                            failed()
                        } else {
                            val outbound = if (privateTab) chosen.media.copy(title = shownTitle, poster = null) else chosen.media
                            scope.launch(Dispatchers.IO) {
                                val ok = dlna.play(renderer, outbound)
                                withContext(Dispatchers.Main) {
                                    if (_phase.value != CastPhase.Connecting) {
                                        if (ok) dlna.stop(renderer)
                                        return@withContext
                                    }
                                    if (ok) {
                                        dlnaRenderer = renderer
                                        started(CastDeviceKind.DLNA, onLocalPause)
                                        startDlnaPoll()
                                    } else {
                                        failed()
                                    }
                                }
                            }
                        }
                    }
                }
            is CastTarget.YouTube ->
                google.castYouTube(
                    routeId = item.id,
                    video = chosen.video,
                    onReady = { main.post { started(CastDeviceKind.GOOGLE_CAST, onLocalPause) } },
                    onFailed = { main.post { failed() } },
                )
            CastTarget.None -> failed()
        }
    }

    fun play() {
        when (activeKind) {
            CastDeviceKind.GOOGLE_CAST -> google.play()
            CastDeviceKind.DLNA -> dlnaRenderer?.let { renderer -> scope.launch(Dispatchers.IO) { dlna.resume(renderer) } }
            null -> Unit
        }
        _playback.value?.let { publish(it.copy(playing = true)) }
    }

    fun pause() {
        when (activeKind) {
            CastDeviceKind.GOOGLE_CAST -> google.pause()
            CastDeviceKind.DLNA -> dlnaRenderer?.let { renderer -> scope.launch(Dispatchers.IO) { dlna.pause(renderer) } }
            null -> Unit
        }
        _playback.value?.let { publish(it.copy(playing = false)) }
    }

    fun seekTo(positionSec: Double) {
        when (activeKind) {
            CastDeviceKind.GOOGLE_CAST -> google.seek(positionSec)
            CastDeviceKind.DLNA -> dlnaRenderer?.let { renderer -> scope.launch(Dispatchers.IO) { dlna.seek(renderer, positionSec) } }
            null -> Unit
        }
        _playback.value?.let { publish(it.copy(positionSec = positionSec.coerceAtLeast(0.0))) }
    }

    fun setVolume(fraction: Float) {
        val value = fraction.coerceIn(0f, 1f)
        when (activeKind) {
            CastDeviceKind.GOOGLE_CAST -> google.setVolume(value)
            CastDeviceKind.DLNA -> dlnaRenderer?.let { renderer -> scope.launch(Dispatchers.IO) { dlna.setVolume(renderer, value) } }
            null -> Unit
        }
        _playback.value?.let { publish(it.copy(volume = value)) }
    }

    fun stopPlayback() {
        when (activeKind) {
            CastDeviceKind.GOOGLE_CAST -> google.stop()
            CastDeviceKind.DLNA -> dlnaRenderer?.let { renderer -> scope.launch(Dispatchers.IO) { dlna.stop(renderer) } }
            null -> Unit
        }
        _playback.value?.let { publish(it.copy(playing = false, positionSec = 0.0)) }
    }

    fun disconnect() {
        poll?.cancel()
        poll = null
        val kind = activeKind
        activeKind = null
        dlnaRenderer?.let { renderer ->
            if (kind == CastDeviceKind.DLNA) scope.launch(Dispatchers.IO) { dlna.stop(renderer) }
        }
        dlnaRenderer = null
        if (kind == CastDeviceKind.GOOGLE_CAST || google.hasSession()) google.disconnect()
        clearPlayback()
    }

    fun dismissNotice() {
        _notice.value = null
    }

    fun sheetClosed() {
        discovery?.cancel()
        google.stopDiscovery()
        if (_playback.value == null && _phase.value != CastPhase.Connecting) _phase.value = CastPhase.Idle
    }

    private fun openPicker(pageUrl: String, isPrivate: Boolean, probeJson: String?) {
        val classified = classifyPageMedia(pageUrl, probeJson)
        target = classified
        privateTab = isPrivate
        youtube = classified is CastTarget.YouTube
        val rawTitle =
            when (classified) {
                is CastTarget.Direct -> classified.media.title
                is CastTarget.YouTube -> classified.video.title
                CastTarget.None -> ""
            }
        // A private tab's title stays off the phone (notification, Cast metadata). The media address still has to
        // reach the TV or nothing would play. It is not stored.
        shownTitle =
            if (isPrivate) {
                appContext.getString(com.shibi.orbis.R.string.cast_private_title)
            } else {
                rawTitle.ifBlank { appContext.getString(com.shibi.orbis.R.string.cast_notification_casting) }
            }
        when (classified) {
            CastTarget.None -> {
                _phase.value = CastPhase.NoMedia
                _notice.value = CastNotice.NO_MEDIA
                _devices.value = emptyList()
            }
            else -> {
                _phase.value = CastPhase.Picker(youtube = youtube, searching = true)
                _notice.value = if (google.available()) null else CastNotice.GOOGLE_UNAVAILABLE
                beginDiscovery()
            }
        }
    }

    private fun beginDiscovery() {
        discovery?.cancel()
        googleRoutes.clear()
        dlnaById.clear()
        if (google.available()) google.startDiscovery() else _notice.value = CastNotice.GOOGLE_UNAVAILABLE
        publishDevices(searching = true)
        discovery =
            scope.launch {
                val renderers = withContext(Dispatchers.IO) { runCatching { dlna.discover() }.getOrDefault(emptyList()) }
                if (_phase.value !is CastPhase.Picker && _phase.value != CastPhase.Connecting) return@launch
                dlnaById.clear()
                renderers.forEach { renderer -> dlnaById[rendererKey(renderer)] = renderer }
                publishDevices(searching = false)
            }
    }

    private fun publishDevices(searching: Boolean) {
        if (google.available()) {
            googleRoutes.clear()
            google.routes().forEach { googleRoutes[it.id] = it }
        }
        val items = ArrayList<CastDeviceItem>()
        googleRoutes.values.forEach { items += CastDeviceItem(it.id, it.name, CastDeviceKind.GOOGLE_CAST) }
        dlnaById.forEach { (id, renderer) -> items += CastDeviceItem(id, renderer.name, CastDeviceKind.DLNA) }
        _devices.value = items
        val phase = _phase.value
        if (phase is CastPhase.Picker) {
            _phase.value = phase.copy(searching = searching && items.isEmpty())
            if (!searching && items.isEmpty() && discovery?.isActive != true) _notice.value = CastNotice.NO_DEVICES
        }
    }

    private fun started(kind: CastDeviceKind, onLocalPause: () -> Unit) {
        if (_phase.value != CastPhase.Connecting) return
        activeKind = kind
        val duration =
            when (val chosen = target) {
                is CastTarget.Direct -> chosen.media.durationSec
                else -> 0.0
            }
        val position =
            when (val chosen = target) {
                is CastTarget.Direct -> chosen.media.positionSec
                is CastTarget.YouTube -> chosen.video.startSec
                else -> 0.0
            }
        publish(
            CastPlayback(
                deviceName = deviceName,
                title = shownTitle,
                playing = true,
                positionSec = position,
                durationSec = duration,
                volume = if (kind == CastDeviceKind.GOOGLE_CAST) google.volume() else 0.5f,
                youtube = youtube,
                kind = kind,
            ),
        )
        _phase.value = CastPhase.Controls
        _notice.value = null
        onLocalPause()
        refreshNotification()
    }

    private fun failed() {
        if (_phase.value != CastPhase.Connecting || _playback.value != null) return
        _phase.value = CastPhase.Picker(youtube = youtube, searching = false)
        _notice.value = CastNotice.FAILED
        publishDevices(searching = false)
    }

    private fun startDlnaPoll() {
        poll?.cancel()
        poll =
            scope.launch {
                val renderer = dlnaRenderer ?: return@launch
                while (activeKind == CastDeviceKind.DLNA) {
                    val snapshot = withContext(Dispatchers.IO) { runCatching { dlna.poll(renderer) }.getOrNull() }
                    if (snapshot != null && activeKind == CastDeviceKind.DLNA) {
                        val current = _playback.value
                        if (current != null) {
                            publish(
                                current.copy(
                                    playing = snapshot.playing,
                                    positionSec = snapshot.positionSec,
                                    durationSec = snapshot.durationSec.takeIf { it > 0 } ?: current.durationSec,
                                    volume = if (snapshot.volume >= 0f) snapshot.volume else current.volume,
                                ),
                            )
                        }
                    }
                    kotlinx.coroutines.delay(1000)
                }
            }
    }

    private fun publish(playback: CastPlayback) {
        _playback.value = playback
        refreshNotification()
    }

    private fun refreshNotification() {
        val playback = _playback.value
        // The Cast SDK posts its own notification once a Default Media Receiver item is loaded.
        val sdkCoversIt = playback?.kind == CastDeviceKind.GOOGLE_CAST && playback.youtube.not()
        if (playback == null || sdkCoversIt) {
            CastNotifications.cancel(appContext)
            return
        }
        val title =
            when {
                privateTab -> appContext.getString(com.shibi.orbis.R.string.cast_private_title)
                playback.title.isNotBlank() -> playback.title
                else -> appContext.getString(com.shibi.orbis.R.string.cast_notification_casting)
            }
        CastNotifications.show(appContext, playback.deviceName, title, playback.playing)
    }

    private fun clearPlayback() {
        _playback.value = null
        activeKind = null
        CastNotifications.cancel(appContext)
        if (_phase.value == CastPhase.Controls || _phase.value == CastPhase.Connecting) _phase.value = CastPhase.Idle
    }

    private fun rendererKey(renderer: DlnaRenderer): String = renderer.uuid ?: renderer.avTransportUrl
}
