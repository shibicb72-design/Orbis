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
import kotlinx.coroutines.delay
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
    YOUTUBE_UNCONFIRMED,
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
    private val chromecast = ChromecastHub(appContext)
    private val dlna = DlnaClient(appContext)

    private val _phase = MutableStateFlow<CastPhase>(CastPhase.Idle)
    val phase: StateFlow<CastPhase> = _phase.asStateFlow()

    private val _devices = MutableStateFlow<List<CastDeviceItem>>(emptyList())
    val devices: StateFlow<List<CastDeviceItem>> = _devices.asStateFlow()

    private val _playback = MutableStateFlow<CastPlayback?>(null)
    val playback: StateFlow<CastPlayback?> = _playback.asStateFlow()

    private val _notice = MutableStateFlow<CastNotice?>(null)
    val notice: StateFlow<CastNotice?> = _notice.asStateFlow()

    private val _dlnaFormatNote = MutableStateFlow(false)
    /** True while a DLNA attempt is using HLS or DASH, which some TVs cannot play. */
    val dlnaFormatNote: StateFlow<Boolean> = _dlnaFormatNote.asStateFlow()

    private val flow = CastFlow()
    private var privateTab = false
    private var discovery: Job? = null
    private var connectWatch: Job? = null
    private var poll: Job? = null
    private var dlnaRenderer: DlnaRenderer? = null
    private var activeKind: CastDeviceKind? = null
    private var connectingKind: CastDeviceKind? = null
    private var deviceName: String = ""
    private var shownTitle: String = ""
    private var youtube = false
    private val dlnaById = LinkedHashMap<String, DlnaRenderer>()

    init {
        chromecast.onStatus = { status ->
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
        chromecast.onSessionEnded = {
            main.post {
                if (activeKind == CastDeviceKind.GOOGLE_CAST && _phase.value != CastPhase.Connecting) {
                    clearPlayback()
                }
            }
        }
        chromecast.onDevicesChanged = {
            if (_phase.value is CastPhase.Picker) publishDevices(discovery?.isActive == true)
        }
    }

    /**
     * Opens the picker for [pageUrl], or the controls when a session is already running.
     * A page change during Connecting is ignored so it cannot orphan the attempt. [tabId] is remembered when the tab
     * is private, and cleared if that tab is closed.
     */
    fun present(pageUrl: String, isPrivate: Boolean, probeJson: String?, tabId: Long) {
        if (!flow.present(_playback.value != null)) {
            sync()
            return
        }
        openPicker(pageUrl, isPrivate, probeJson, tabId)
    }

    /** Replaces the current choice with whatever the page can cast now. */
    fun castThisPage(pageUrl: String, isPrivate: Boolean, probeJson: String?, tabId: Long) {
        disconnect()
        openPicker(pageUrl, isPrivate, probeJson, tabId)
    }

    /**
     * The private tab that owned the current target was closed. The address is dropped from memory, and a cast that
     * came from that tab is stopped.
     */
    fun onPrivateTabClosed(tabId: Long) {
        val stop = flow.privateTabClosed(tabId) ?: return
        privateTab = false
        _dlnaFormatNote.value = false
        if (stop || _playback.value != null || activeKind != null) {
            disconnect()
        } else {
            discovery?.cancel()
            chromecast.stopDiscovery()
            flow.clearIdle()
            _devices.value = emptyList()
            sync()
        }
    }

    fun choose(deviceId: String, onLocalPause: () -> Unit) {
        if (flow.phase == CastPhase.Connecting) return
        val item = _devices.value.firstOrNull { it.id == deviceId } ?: return
        val chosen = flow.target ?: return
        if (chosen is CastTarget.YouTube && item.kind != CastDeviceKind.GOOGLE_CAST) {
            flow.showNotice(CastNotice.YOUTUBE_NEEDS_GOOGLE)
            sync()
            return
        }
        _dlnaFormatNote.value =
            chosen is CastTarget.Direct && item.kind == CastDeviceKind.DLNA && chosen.media.adaptive
        val generation = flow.beginConnect(System.currentTimeMillis())
        connectingKind = item.kind
        deviceName = item.name
        sync()
        armConnectTimeout(generation, item.kind)
        when (chosen) {
            is CastTarget.Direct ->
                when (item.kind) {
                    CastDeviceKind.GOOGLE_CAST ->
                        chromecast.cast(
                            deviceId = item.id,
                            goal = CastGoal.Media(chosen.media, shownTitle, if (privateTab) null else chosen.media.poster),
                            onReady = { main.post { started(CastDeviceKind.GOOGLE_CAST, generation, onLocalPause) } },
                            onFailed = { reason -> main.post { failAttempt(generation, reason) } },
                        )
                    CastDeviceKind.DLNA -> {
                        val renderer = dlnaById[item.id]
                        if (renderer == null) {
                            failAttempt(generation)
                        } else {
                            val outbound = if (privateTab) chosen.media.copy(title = shownTitle, poster = null) else chosen.media
                            scope.launch(Dispatchers.IO) {
                                val ok = dlna.play(renderer, outbound)
                                withContext(Dispatchers.Main) {
                                    if (flow.phase != CastPhase.Connecting) {
                                        if (ok) dlna.stop(renderer)
                                        return@withContext
                                    }
                                    if (ok) {
                                        dlnaRenderer = renderer
                                        if (started(CastDeviceKind.DLNA, generation, onLocalPause, renderer)) startDlnaPoll()
                                    } else {
                                        failAttempt(generation)
                                    }
                                }
                            }
                        }
                    }
                }
            is CastTarget.YouTube ->
                chromecast.cast(
                    deviceId = item.id,
                    goal = CastGoal.YouTube(chosen.video),
                    onReady = { main.post { started(CastDeviceKind.GOOGLE_CAST, generation, onLocalPause) } },
                    onFailed = { reason -> main.post { failAttempt(generation, reason) } },
                )
            CastTarget.None -> failAttempt(generation)
        }
    }

    fun play() {
        when (activeKind) {
            CastDeviceKind.GOOGLE_CAST -> chromecast.play()
            CastDeviceKind.DLNA -> dlnaRenderer?.let { renderer -> scope.launch(Dispatchers.IO) { dlna.resume(renderer) } }
            null -> Unit
        }
        _playback.value?.let { publish(it.copy(playing = true)) }
    }

    fun pause() {
        when (activeKind) {
            CastDeviceKind.GOOGLE_CAST -> chromecast.pause()
            CastDeviceKind.DLNA -> dlnaRenderer?.let { renderer -> scope.launch(Dispatchers.IO) { dlna.pause(renderer) } }
            null -> Unit
        }
        _playback.value?.let { publish(it.copy(playing = false)) }
    }

    fun seekTo(positionSec: Double) {
        when (activeKind) {
            CastDeviceKind.GOOGLE_CAST -> chromecast.seek(positionSec)
            CastDeviceKind.DLNA -> dlnaRenderer?.let { renderer -> scope.launch(Dispatchers.IO) { dlna.seek(renderer, positionSec) } }
            null -> Unit
        }
        _playback.value?.let { publish(it.copy(positionSec = positionSec.coerceAtLeast(0.0))) }
    }

    fun setVolume(fraction: Float) {
        val value = fraction.coerceIn(0f, 1f)
        when (activeKind) {
            CastDeviceKind.GOOGLE_CAST -> chromecast.setVolume(value)
            CastDeviceKind.DLNA -> dlnaRenderer?.let { renderer -> scope.launch(Dispatchers.IO) { dlna.setVolume(renderer, value) } }
            null -> Unit
        }
        _playback.value?.let { publish(it.copy(volume = value)) }
    }

    fun stopPlayback() {
        when (activeKind) {
            CastDeviceKind.GOOGLE_CAST -> chromecast.stop()
            CastDeviceKind.DLNA -> dlnaRenderer?.let { renderer -> scope.launch(Dispatchers.IO) { dlna.stop(renderer) } }
            null -> Unit
        }
        _playback.value?.let { publish(it.copy(playing = false, positionSec = 0.0)) }
    }

    fun disconnect() {
        connectWatch?.cancel()
        poll?.cancel()
        poll = null
        val kind = activeKind
        val connecting = connectingKind
        activeKind = null
        connectingKind = null
        dlnaRenderer?.let { renderer ->
            if (kind == CastDeviceKind.DLNA || connecting == CastDeviceKind.DLNA) {
                scope.launch(Dispatchers.IO) { dlna.stop(renderer) }
            }
        }
        dlnaRenderer = null
        if (kind == CastDeviceKind.GOOGLE_CAST || connecting == CastDeviceKind.GOOGLE_CAST || chromecast.hasSession()) {
            chromecast.disconnect()
        }
        _dlnaFormatNote.value = false
        flow.invalidateConnect()
        clearPlayback()
    }

    /** Runs discovery again after the nearby-Wi-Fi permission is granted. */
    fun rediscover() {
        if (flow.phase !is CastPhase.Picker) return
        beginDiscovery()
    }

    fun dismissNotice() {
        flow.showNotice(null)
        sync()
    }

    fun sheetClosed() {
        discovery?.cancel()
        chromecast.stopDiscovery()
        flow.dismissSheet(_playback.value != null)
        sync()
    }

    private fun openPicker(pageUrl: String, isPrivate: Boolean, probeJson: String?, tabId: Long) {
        val classified = classifyPageMedia(pageUrl, probeJson)
        flow.target = classified
        flow.rememberOrigin(tabId, isPrivate)
        privateTab = isPrivate
        _dlnaFormatNote.value = false
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
                flow.showNoMedia()
                _devices.value = emptyList()
                sync()
            }
            else -> {
                flow.showPicker(youtube = youtube, searching = true)
                sync()
                beginDiscovery()
            }
        }
    }

    private fun beginDiscovery() {
        discovery?.cancel()
        dlnaById.clear()
        chromecast.startDiscovery()
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
        val items = ArrayList<CastDeviceItem>()
        chromecast.devices().forEach { items += CastDeviceItem(it.id, it.name, CastDeviceKind.GOOGLE_CAST) }
        dlnaById.forEach { (id, renderer) -> items += CastDeviceItem(id, renderer.name, CastDeviceKind.DLNA) }
        _devices.value = items
        flow.updateSearching(searching = searching, devicesEmpty = items.isEmpty())
        sync()
    }

    /** False when the device answered after the attempt was abandoned. The caller has already been told to stop. */
    private fun started(
        kind: CastDeviceKind,
        generation: Int,
        onLocalPause: () -> Unit,
        renderer: DlnaRenderer? = null,
    ): Boolean {
        connectWatch?.cancel()
        if (!flow.acceptStart(generation)) {
            if (kind == CastDeviceKind.GOOGLE_CAST) chromecast.disconnect()
            if (renderer != null) scope.launch(Dispatchers.IO) { dlna.stop(renderer) }
            return false
        }
        connectingKind = null
        activeKind = kind
        val duration =
            when (val chosen = flow.target) {
                is CastTarget.Direct -> chosen.media.durationSec
                else -> 0.0
            }
        val position =
            when (val chosen = flow.target) {
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
                volume = if (kind == CastDeviceKind.GOOGLE_CAST) chromecast.volume() else 0.5f,
                youtube = youtube,
                kind = kind,
            ),
        )
        sync()
        onLocalPause()
        refreshNotification()
        return true
    }

    private fun armConnectTimeout(generation: Int, kind: CastDeviceKind) {
        connectWatch?.cancel()
        connectWatch =
            scope.launch {
                delay(CONNECT_TIMEOUT_MS)
                if (!flow.connectExpired(generation, System.currentTimeMillis())) return@launch
                val reason =
                    if (youtube && kind == CastDeviceKind.GOOGLE_CAST) CastFailure.YOUTUBE_UNCONFIRMED else CastFailure.GENERIC
                failAttempt(generation, reason = reason, unselect = kind == CastDeviceKind.GOOGLE_CAST)
            }
    }

    /** The attempt did not start. A Chromecast session that was opened is closed. */
    private fun failAttempt(
        generation: Int,
        reason: CastFailure = CastFailure.GENERIC,
        unselect: Boolean = connectingKind == CastDeviceKind.GOOGLE_CAST,
    ) {
        connectWatch?.cancel()
        val notice =
            when (reason) {
                CastFailure.YOUTUBE_UNCONFIRMED -> CastNotice.YOUTUBE_UNCONFIRMED
                else -> CastNotice.FAILED
            }
        if (!flow.failConnect(generation, youtube, notice)) return
        connectingKind = null
        _dlnaFormatNote.value = false
        if (unselect) chromecast.disconnect()
        sync()
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
        if (playback == null) {
            CastNotifications.cancel(appContext)
            return
        }
        val title =
            when {
                privateTab -> appContext.getString(com.shibi.orbis.R.string.cast_private_title)
                playback.title.isNotBlank() -> playback.title
                else -> appContext.getString(com.shibi.orbis.R.string.cast_notification_casting)
            }
        CastNotifications.show(
            context = appContext,
            deviceName = playback.deviceName,
            title = title,
            playing = playback.playing,
            positionMs = (playback.positionSec * 1000).toLong(),
            durationMs = (playback.durationSec * 1000).toLong(),
        )
    }

    private fun sync() {
        _phase.value = flow.phase
        _notice.value = flow.notice
    }

    private fun clearPlayback() {
        _playback.value = null
        activeKind = null
        CastNotifications.cancel(appContext)
        sync()
    }

    private fun rendererKey(renderer: DlnaRenderer): String = renderer.uuid ?: renderer.avTransportUrl
}
