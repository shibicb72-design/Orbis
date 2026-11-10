package com.shibi.orbis.cast

import android.content.Context
import android.net.Uri
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import java.net.InetAddress
import java.util.concurrent.Executors

internal data class GoogleCastRoute(val id: String, val name: String, val address: InetAddress?)

internal data class GoogleCastStatus(val playing: Boolean, val positionSec: Double, val durationSec: Double, val volume: Float)

/**
 * Chromecast, Google TV and Nest through the Cast SDK. Device names come from MediaRouter. The receiver application
 * id is the Default Media Receiver, switched to the YouTube receiver only while a YouTube video is launching.
 * Play Services may be missing: [available] is then false and DLNA still works.
 */
internal class GoogleCastBridge(context: Context) {
    private val appContext = context.applicationContext
    private var castContext: CastContext? = null
    private var unavailable = false
    private var listening = false
    private var receiverAppId = CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
    private var pending: Pending? = null
    private var youtubeAddress: InetAddress? = null
    private val selector =
        MediaRouteSelector.Builder()
            .addControlCategory(
                CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID),
            )
            .build()

    var onStatus: ((GoogleCastStatus) -> Unit)? = null
    var onSessionEnded: (() -> Unit)? = null
    var onRoutesChanged: (() -> Unit)? = null
    private var watching = false
    private val dials =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "orbis-cast-dial").apply { isDaemon = true }
        }
    private var dialEpoch = 0

    fun available(): Boolean {
        ensure()
        return castContext != null
    }

    fun routes(): List<GoogleCastRoute> {
        ensure()
        if (castContext == null) return emptyList()
        val router = MediaRouter.getInstance(appContext)
        return router.routes.mapNotNull { route ->
            if (route.isDefault || !route.matchesSelector(selector)) return@mapNotNull null
            val device = CastDevice.getFromBundle(route.extras)
            GoogleCastRoute(route.id, route.name.toString().ifBlank { "Cast" }, device?.inetAddress)
        }
    }

    fun startDiscovery() {
        ensure()
        if (castContext == null) return
        // Passive discovery only. An active scan can require location or nearby-Wi-Fi permission, which cast does not use.
        MediaRouter.getInstance(appContext).addCallback(
            selector,
            callback,
            MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY,
        )
    }

    fun stopDiscovery() {
        if (castContext == null) return
        runCatching { MediaRouter.getInstance(appContext).removeCallback(callback) }
    }

    fun castDirect(
        routeId: String,
        media: CastableMedia,
        displayTitle: String,
        poster: String?,
        onReady: () -> Unit,
        onFailed: () -> Unit,
    ) {
        youtubeAddress = null
        val route = findRoute(routeId)
        if (route == null || !ensureApp(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)) {
            onFailed()
            return
        }
        pending = Pending.Direct(media, displayTitle, poster, onReady, onFailed)
        val current = castContext?.sessionManager?.currentCastSession
        if (current != null && current.isConnected && sameRoute(route)) {
            loadDirect(current, pending as Pending.Direct)
        } else {
            select(route)
        }
    }

    fun castYouTube(
        routeId: String,
        video: YouTubeVideo,
        onReady: () -> Unit,
        onFailed: () -> Unit,
    ) {
        val route = findRoute(routeId) ?: run {
            onFailed()
            return
        }
        youtubeAddress = CastDevice.getFromBundle(route.extras)?.inetAddress
        if (!ensureApp(YOUTUBE_RECEIVER_APP_ID)) {
            dialYouTube(video, onReady, onFailed)
            return
        }
        pending = Pending.YouTube(video, onReady, onFailed)
        val current = castContext?.sessionManager?.currentCastSession
        if (current != null && current.isConnected && sameRoute(route)) {
            flingYouTube(current, video, onReady, onFailed)
        } else {
            select(route)
        }
    }

    fun play() {
        remote()?.play()
    }

    fun pause() {
        remote()?.pause()
    }

    fun seek(positionSec: Double) {
        val client = remote() ?: return
        val ms = (positionSec.coerceAtLeast(0.0) * 1000).toLong()
        client.seek(MediaSeekOptions.Builder().setPosition(ms).build())
    }

    fun setVolume(fraction: Float) {
        val session = castContext?.sessionManager?.currentCastSession ?: return
        runCatching { session.volume = fraction.coerceIn(0f, 1f).toDouble() }
    }

    fun volume(): Float =
        runCatching { castContext?.sessionManager?.currentCastSession?.volume?.toFloat() }.getOrNull() ?: 0f

    fun stop() {
        remote()?.stop()
    }

    fun disconnect() {
        pending = null
        dialEpoch++
        val address = youtubeAddress
        youtubeAddress = null
        runCatching { castContext?.sessionManager?.endCurrentSession(true) }
        runCatching {
            val router = MediaRouter.getInstance(appContext)
            if (!router.selectedRoute.isDefault) router.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
        }
        ensureApp(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        if (address != null) dialStop(address)
    }

    fun hasSession(): Boolean = castContext?.sessionManager?.currentCastSession?.isConnected == true

    private fun ensure() {
        if (castContext != null || unavailable) return
        try {
            val ctx = CastContext.getSharedInstance(appContext)
            castContext = ctx
            if (!listening) {
                ctx.sessionManager.addSessionManagerListener(sessions, CastSession::class.java)
                listening = true
            }
        } catch (_: Exception) {
            unavailable = true
        }
    }

    private fun ensureApp(applicationId: String): Boolean {
        ensure()
        val ctx = castContext ?: return false
        if (receiverAppId == applicationId) return true
        return try {
            ctx.setReceiverApplicationId(applicationId)
            receiverAppId = applicationId
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun select(route: MediaRouter.RouteInfo) {
        runCatching { MediaRouter.getInstance(appContext).selectRoute(route) }
    }

    private fun findRoute(id: String): MediaRouter.RouteInfo? =
        MediaRouter.getInstance(appContext).routes.firstOrNull { it.id == id }

    private fun sameRoute(route: MediaRouter.RouteInfo): Boolean {
        val selected = MediaRouter.getInstance(appContext).selectedRoute
        return selected.id == route.id
    }

    private fun remote(): RemoteMediaClient? = castContext?.sessionManager?.currentCastSession?.remoteMediaClient

    private fun loadDirect(session: CastSession, request: Pending.Direct) {
        val client = session.remoteMediaClient
        if (client == null) {
            pending = null
            request.onFailed()
            return
        }
        val metadataType = if (request.media.video) MediaMetadata.MEDIA_TYPE_MOVIE else MediaMetadata.MEDIA_TYPE_MUSIC_TRACK
        val metadata =
            MediaMetadata(metadataType).apply {
                putString(MediaMetadata.KEY_TITLE, request.displayTitle.ifBlank { "Media" })
                val poster = request.poster
                if (!poster.isNullOrBlank()) addImage(WebImage(Uri.parse(poster)))
            }
        val info =
            MediaInfo.Builder(request.media.url)
                .setStreamType(
                    if (request.media.durationSec <= 0.0 &&
                        (request.media.mime.contains("mpegurl") || request.media.mime.contains("dash"))
                    ) {
                        MediaInfo.STREAM_TYPE_LIVE
                    } else {
                        MediaInfo.STREAM_TYPE_BUFFERED
                    },
                )
                .setContentType(request.media.mime)
                .setMetadata(metadata)
                .apply {
                    if (request.media.durationSec > 0.0) setStreamDuration((request.media.durationSec * 1000).toLong())
                }
                .build()
        val load =
            MediaLoadRequestData.Builder()
                .setMediaInfo(info)
                .setAutoplay(true)
                .setCurrentTime((request.media.positionSec * 1000).toLong())
                .build()
        client.load(load).setResultCallback { result ->
            val ready = pending
            pending = null
            if (result.status.isSuccess && ready is Pending.Direct) {
                watch(client)
                ready.onReady()
            } else if (ready is Pending.Direct) {
                ready.onFailed()
            }
        }
    }

    private fun flingYouTube(session: CastSession, video: YouTubeVideo, onReady: () -> Unit, onFailed: () -> Unit) {
        val payload = youtubeFlingPayload(video.videoId, video.startSec)
        try {
            session.setMessageReceivedCallbacks(
                YOUTUBE_NAMESPACE,
                Cast.MessageReceivedCallback { _, _, _ -> },
            )
        } catch (_: Exception) {
            // Already registered for this session, or the receiver has no custom channel. The send below still runs.
        }
        try {
            session.sendMessage(YOUTUBE_NAMESPACE, payload).setResultCallback { status ->
                pending = null
                if (status.isSuccess) {
                    onReady()
                } else {
                    dialYouTube(video, onReady, onFailed)
                }
            }
        } catch (_: Exception) {
            pending = null
            dialYouTube(video, onReady, onFailed)
        }
    }

    private fun dialYouTube(video: YouTubeVideo, onReady: () -> Unit, onFailed: () -> Unit) {
        val address = youtubeAddress
        val host = dialHost(address)
        if (host == null) {
            onFailed()
            return
        }
        val epoch = dialEpoch
        dials.execute {
            if (epoch != dialEpoch) return@execute
            val response =
                lanExchange(
                    method = "POST",
                    url = "http://$host:8008/apps/YouTube",
                    headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                    body = youtubeDialBody(video.videoId, video.startSec),
                )
            if (epoch != dialEpoch) return@execute
            if (response != null && response.code in 200..299) onReady() else onFailed()
        }
    }

    private fun dialStop(address: InetAddress) {
        val host = dialHost(address) ?: return
        dials.execute { lanExchange("DELETE", "http://$host:8008/apps/YouTube") }
    }

    private fun dialHost(address: InetAddress?): String? {
        if (address == null || !isLocalLanAddress(address)) return null
        val raw = address.hostAddress ?: return null
        return if (raw.contains(':')) "[${raw.substringBefore('%')}]" else raw
    }

    private fun watch(client: RemoteMediaClient) {
        if (watching) return
        watching = true
        client.registerCallback(mediaCallback)
        runCatching { client.addProgressListener(progress, 1000L) }
    }

    private val progress =
        RemoteMediaClient.ProgressListener { progressMs, durationMs ->
            val playing = remote()?.mediaStatus?.playerState == MediaStatus.PLAYER_STATE_PLAYING
            onStatus?.invoke(
                GoogleCastStatus(
                    playing = playing,
                    positionSec = progressMs / 1000.0,
                    durationSec = if (durationMs > 0) durationMs / 1000.0 else 0.0,
                    volume = volume(),
                ),
            )
        }

    private val mediaCallback =
        object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                val status = remote()?.mediaStatus ?: return
                val duration = status.mediaInfo?.streamDuration ?: 0L
                onStatus?.invoke(
                    GoogleCastStatus(
                        playing = status.playerState == MediaStatus.PLAYER_STATE_PLAYING,
                        positionSec = status.streamPosition / 1000.0,
                        durationSec = if (duration > 0) duration / 1000.0 else 0.0,
                        volume = volume(),
                    ),
                )
            }
        }

    private val callback =
        object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = changed()
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = changed()
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = changed()
            private fun changed() {
                onRoutesChanged?.invoke()
            }
        }

    private val sessions =
        object : SessionManagerListener<CastSession> {
            override fun onSessionStarting(session: CastSession) = Unit

            override fun onSessionStarted(session: CastSession, sessionId: String) {
                val request = pending ?: return
                when (request) {
                    is Pending.Direct -> loadDirect(session, request)
                    is Pending.YouTube -> flingYouTube(session, request.video, request.onReady, request.onFailed)
                }
            }

            override fun onSessionStartFailed(session: CastSession, error: Int) {
                val request = pending
                pending = null
                when (request) {
                    is Pending.YouTube -> dialYouTube(request.video, request.onReady, request.onFailed)
                    is Pending.Direct -> request.onFailed()
                    null -> Unit
                }
            }

            override fun onSessionEnding(session: CastSession) = Unit

            override fun onSessionEnded(session: CastSession, error: Int) {
                watching = false
                if (pending != null) return
                onSessionEnded?.invoke()
            }

            override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = Unit
            override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
            override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
        }

    private sealed interface Pending {
        data class Direct(
            val media: CastableMedia,
            val displayTitle: String,
            val poster: String?,
            val onReady: () -> Unit,
            val onFailed: () -> Unit,
        ) : Pending

        data class YouTube(
            val video: YouTubeVideo,
            val onReady: () -> Unit,
            val onFailed: () -> Unit,
        ) : Pending
    }
}
