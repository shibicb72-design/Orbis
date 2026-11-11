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
    REJECTED,
    /** A Chromecast must be confirmed before Orbis casts to it. The question is [CastCoordinator.trustPrompt]. */
    CONFIRM_DEVICE,
}

/** Why a Chromecast needs the user's confirmation. */
enum class CastTrustReason {
    /** Orbis has not cast to this device before. */
    FIRST_USE,

    /** Another key is trusted under this name or this device id. */
    KEY_CHANGED,

    /** This device's key is trusted under another name. */
    RENAMED,
}

data class CastTrustPrompt(val deviceName: String, val reason: CastTrustReason)

sealed interface CastPhase {
    data object Idle : CastPhase
    data object NoMedia : CastPhase
    data class Picker(val youtube: Boolean, val searching: Boolean) : CastPhase
    data object Connecting : CastPhase
    data object Controls : CastPhase
}

/**
 * In-memory cast session. Private tabs may cast: the page address is sent only to the chosen device and is not written
 * to disk, history, or the error log. A YouTube video id also goes to YouTube's lounge API, which hands it to the TV.
 * The phone notification for a private tab does not include the page title.
 * [log] receives secret-free "Cast" lines for the error log (see CastLogging.kt).
 */
class CastCoordinator(
    context: Context,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val chromecast = ChromecastHub(appContext, log)
    private val dlna = DlnaClient(appContext, log)

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

    private val _trustPrompt = MutableStateFlow<CastTrustPrompt?>(null)
    /** Non-null while [CastNotice.CONFIRM_DEVICE] is shown: answer with [confirmTrust] or [dismissNotice]. */
    val trustPrompt: StateFlow<CastTrustPrompt?> = _trustPrompt.asStateFlow()

    private val flow = CastFlow()
    private var privateTab = false
    private var discovery: Job? = null
    private var connectWatch: Job? = null
    private var poll: Job? = null
    private var dlnaRenderer: DlnaRenderer? = null
    private var activeKind: CastDeviceKind? = null
    private var connectingKind: CastDeviceKind? = null
    private var deviceName: String = ""
    private var connectingDeviceId: String? = null
    /** The page-pause action of the attempt that is waiting for a trust answer. */
    private var trustLocalPause: (() -> Unit)? = null
    private var shownTitle: String = ""
    private var youtube = false
    private var connectStartedAt = 0L
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
        connectStartedAt = System.currentTimeMillis()
        val generation = flow.beginConnect(connectStartedAt)
        log("choose dev=${deviceTag(item.id)} kind=${item.kind} youtube=${chosen is CastTarget.YouTube} gen=$generation")
        connectingKind = item.kind
        connectingDeviceId = item.id
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
                            onNeedsTrust = { reason, ticket, name -> main.post { needsTrust(generation, item.id, name, reason, ticket, onLocalPause) } },
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
                    onNeedsTrust = { reason, ticket, name -> main.post { needsTrust(generation, item.id, name, reason, ticket, onLocalPause) } },
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
        chromecast.cancelTrust()
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

    /** Closes the notice. For [CastNotice.CONFIRM_DEVICE] this is "Cancel": nothing is trusted. */
    fun dismissNotice() {
        if (flow.takeTrust() != null) {
            chromecast.cancelTrust()
            log("trust dismissed")
        }
        flow.showNotice(null)
        sync()
    }

    /**
     * "Trust and cast". Connects again; the key is pinned only if the device proves the same key under the same name.
     * A different key on that connection asks again with its own reason.
     */
    fun confirmTrust() {
        val pending = flow.takeTrust() ?: return
        val onLocalPause = trustLocalPause ?: {}
        chromecast.approveTrust(pending.ticket)
        log("trust approved")
        sync()
        if (_devices.value.none { it.id == pending.deviceId && it.kind == CastDeviceKind.GOOGLE_CAST }) {
            chromecast.cancelTrust()
            flow.showNotice(CastNotice.FAILED)
            sync()
            return
        }
        choose(pending.deviceId, onLocalPause)
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
        log("discovery start kinds=cast,dlna")
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
        log("session ready kind=$kind gen=$generation ms=${System.currentTimeMillis() - connectStartedAt}")
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

    /**
     * The Chromecast proved a key the user has not trusted under its name. Leaves Connecting the way [failAttempt]
     * does (the hub has already closed the socket) and asks. An earlier approval is dropped: it belonged to the
     * connection that just ended, and a new question is never answered for the user.
     */
    private fun needsTrust(
        generation: Int,
        deviceId: String,
        checkedName: String,
        reason: CastPins.Reason,
        ticket: CastPins.Ticket,
        onLocalPause: () -> Unit,
    ) {
        connectWatch?.cancel()
        if (!flow.needsTrust(generation, youtube, PendingTrust(deviceId, checkedName, reason, ticket))) return
        chromecast.cancelTrust()
        trustLocalPause = onLocalPause
        connectingKind = null
        _dlnaFormatNote.value = false
        chromecast.disconnect()
        sync()
        publishDevices(searching = false)
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
                CastFailure.REJECTED -> CastNotice.REJECTED
                else -> CastNotice.FAILED
            }
        if (!flow.failConnect(generation, youtube, notice)) return
        // An approval belongs to the reconnect that just failed; it must not stay live for its full two minutes.
        chromecast.cancelTrust()
        log("session failed reason=$reason gen=$generation ms=${System.currentTimeMillis() - connectStartedAt}")
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
        _trustPrompt.value = flow.trust?.let { CastTrustPrompt(it.deviceName, it.reason.toPublic()) }
        if (flow.trust == null) trustLocalPause = null
    }

    private fun CastPins.Reason.toPublic(): CastTrustReason =
        when (this) {
            CastPins.Reason.FIRST_USE -> CastTrustReason.FIRST_USE
            CastPins.Reason.KEY_CHANGED -> CastTrustReason.KEY_CHANGED
            CastPins.Reason.RENAMED -> CastTrustReason.RENAMED
        }

    private fun clearPlayback() {
        _playback.value = null
        activeKind = null
        CastNotifications.cancel(appContext)
        sync()
    }

    private fun rendererKey(renderer: DlnaRenderer): String = renderer.uuid ?: renderer.avTransportUrl
}
