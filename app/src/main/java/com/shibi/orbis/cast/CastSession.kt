package com.shibi.orbis.cast

import com.shibi.orbis.youtube.LoungeResult
import org.json.JSONArray
import org.json.JSONObject

internal const val CAST_SENDER_ID = "sender-0"
internal const val CAST_RECEIVER_ID = "receiver-0"
internal const val DEFAULT_MEDIA_APP_ID = "CC1AD845"
internal const val CAST_PORT = 8009
internal const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
internal const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
internal const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
internal const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
internal const val HEARTBEAT_INTERVAL_MS = 5_000L
internal const val HEARTBEAT_SILENCE_MS = 15_000L

/** How long to wait, after DIAL has brought the YouTube app up, before handing the video over the Cast channel. */
internal const val YOUTUBE_FLING_FALLBACK_MS = 2_000L

/** How long the YouTube receiver has to report its lounge screen id before the DIAL fallback starts. */
internal const val MDX_STATUS_WAIT_MS = 4_000L

/** Interval between repeated `getMdxSessionStatus` requests while the receiver has not answered. */
internal const val MDX_STATUS_RETRY_MS = 1_000L

/**
 * After the lounge accepted setPlaylist, the attempt counts as started once this much time passes with no error from
 * the TV, even when no status names the video (owner decision: grace-ready).
 */
internal const val LOUNGE_READY_GRACE_MS = 4_000L

internal const val CAST_SERVICE_TYPE = "_googlecast._tcp."

/** Why a CASTV2 attempt stopped. The sheet maps these to localized text. */
internal enum class CastFailure {
    GENERIC,
    YOUTUBE_UNCONFIRMED,
    SILENCE,
    /** The TV sent LAUNCH_ERROR, INVALID_REQUEST, LOAD_FAILED, or LOAD_CANCELLED. */
    REJECTED,
}

internal data class CastFrame(
    val sourceId: String,
    val destinationId: String,
    val namespace: String,
    val payload: String,
)

internal sealed interface CastGoal {
    data class Media(val media: CastableMedia, val title: String, val poster: String?) : CastGoal
    data class YouTube(val video: YouTubeVideo) : CastGoal
}

internal sealed interface CastEffect {
    data class Send(val frame: CastFrame) : CastEffect
    data class DialPost(val body: String) : CastEffect
    /** Hand [videoId] to the TV through the YouTube lounge API. Reported back through [CastSession.onLounge]. */
    data class LoungePlay(val screenId: String, val videoId: String, val startSec: Double) : CastEffect
    data object DialDelete : CastEffect
    data class Ready(val volume: Float) : CastEffect
    data class Failed(val reason: CastFailure) : CastEffect
    data class Progress(
        val playing: Boolean,
        val positionSec: Double,
        val durationSec: Double,
        val volume: Float,
    ) : CastEffect
}

internal data class ReceiverApplication(
    val appId: String,
    val sessionId: String,
    val transportId: String,
    val displayName: String,
)

internal data class ReceiverSnapshot(
    val applications: List<ReceiverApplication>,
    val volume: Float,
    val requestId: Int,
)

internal data class MediaSnapshot(
    val mediaSessionId: Int,
    val playerState: String,
    val contentId: String,
    val positionSec: Double,
    val durationSec: Double,
    val idleReason: String,
)

internal sealed interface ReceiverAction {
    data object Launch : ReceiverAction
    data class Reuse(val transportId: String, val sessionId: String) : ReceiverAction
}

private val LOADED_STATES = setOf("PLAYING", "BUFFERING", "PAUSED")

/**
 * What to do with the app the device is already running.
 * A session is reused only when that app is the one this cast needs. Anything else is launched fresh.
 */
internal fun receiverAction(desiredAppId: String, snapshot: ReceiverSnapshot): ReceiverAction {
    val running =
        snapshot.applications.firstOrNull { it.appId.equals(desiredAppId, ignoreCase = true) && it.transportId.isNotBlank() }
    return if (running != null) ReceiverAction.Reuse(running.transportId, running.sessionId) else ReceiverAction.Launch
}

internal fun parseReceiverStatus(payload: String): ReceiverSnapshot? {
    val root = jsonObject(payload) ?: return null
    if (root.optString("type") != "RECEIVER_STATUS") return null
    val status = root.optJSONObject("status") ?: return null
    val apps = ArrayList<ReceiverApplication>()
    val array = status.optJSONArray("applications") ?: JSONArray()
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val appId = item.optString("appId")
        if (appId.isBlank()) continue
        apps +=
            ReceiverApplication(
                appId = appId,
                sessionId = item.optString("sessionId"),
                transportId = item.optString("transportId"),
                displayName = item.optString("displayName"),
            )
    }
    val level = status.optJSONObject("volume")?.optDouble("level", 1.0) ?: 1.0
    val volume = level.toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
    return ReceiverSnapshot(apps, volume, root.optInt("requestId"))
}

private val CAST_REJECTION_TYPES = setOf("LAUNCH_ERROR", "INVALID_REQUEST", "LOAD_FAILED", "LOAD_CANCELLED")

/**
 * The rejection type when [payload] is a receiver or media error, including a media status whose idle reason is
 * LOAD_FAILED or LOAD_CANCELLED. Null when the payload is not one of those failures.
 */
internal fun parseCastError(payload: String): String? {
    val root = jsonObject(payload) ?: return null
    val type = root.optString("type")
    if (type in CAST_REJECTION_TYPES) return type
    if (type == "MEDIA_STATUS") {
        return parseMediaStatus(payload).firstOrNull { it.idleReason in CAST_REJECTION_TYPES }?.idleReason
    }
    return null
}

internal fun parseMediaStatus(payload: String): List<MediaSnapshot> {
    val root = jsonObject(payload) ?: return emptyList()
    if (root.optString("type") != "MEDIA_STATUS") return emptyList()
    val array = root.optJSONArray("status") ?: return emptyList()
    val items = ArrayList<MediaSnapshot>(array.length())
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val media = item.optJSONObject("media")
        val duration = media?.optDouble("duration", item.optDouble("duration", 0.0)) ?: item.optDouble("duration", 0.0)
        items +=
            MediaSnapshot(
                mediaSessionId = item.optInt("mediaSessionId"),
                playerState = item.optString("playerState"),
                contentId = media?.optString("contentId").orEmpty(),
                positionSec = item.optDouble("currentTime", 0.0),
                durationSec = duration,
                idleReason = item.optString("idleReason"),
            )
    }
    return items
}

/** LOAD request for the Default Media Receiver. Poster is omitted when [poster] is blank. */
internal fun loadPayload(requestId: Int, media: CastableMedia, title: String, poster: String?): String {
    val metadata =
        JSONObject()
            .put("metadataType", if (media.video) 1 else 3)
            .put("title", title.ifBlank { "Media" })
    if (!poster.isNullOrBlank()) {
        metadata.put("images", JSONArray().put(JSONObject().put("url", poster)))
    }
    val body =
        JSONObject()
            .put("contentId", media.url)
            .put("contentType", media.mime)
            .put("streamType", if (media.durationSec <= 0.0 && isAdaptiveMime(media.mime)) "LIVE" else "BUFFERED")
            .put("metadata", metadata)
    if (media.durationSec > 0.0) body.put("duration", media.durationSec)
    return JSONObject()
        .put("type", "LOAD")
        .put("requestId", requestId)
        .put("media", body)
        .put("autoplay", true)
        .put("currentTime", media.positionSec.coerceAtLeast(0.0))
        .toString()
}

internal fun connectPayload(): String =
    JSONObject()
        .put("type", "CONNECT")
        .put("origin", JSONObject())
        .put("userAgent", "Orbis")
        .put(
            "senderInfo",
            JSONObject()
                .put("sdkType", 2)
                .put("version", "1")
                .put("browserVersion", "Orbis")
                .put("platform", 4)
                .put("connectionType", 1),
        )
        .toString()

/** True when a media or YouTube-namespace payload says [videoId] is loaded or playing. */
internal fun youtubeStatusConfirms(videoId: String, payload: String): Boolean {
    val root = jsonObject(payload) ?: return false
    if (root.optString("type") == "MEDIA_STATUS") {
        return parseMediaStatus(payload).any { item ->
            sameVideo(videoId, item.contentId) && item.playerState.uppercase() in LOADED_STATES
        }
    }
    if (root.optString("type").equals("flingVideo", ignoreCase = true)) return false
    val data = root.optJSONObject("data")
    val named = data?.optString("videoId").orEmpty().ifBlank { root.optString("videoId") }
    val content = root.optString("contentId")
    if (named != videoId && !sameVideo(videoId, content) && !sameVideo(videoId, named)) return false
    val state =
        root.optString("playerState").ifBlank { data?.optString("playerState").orEmpty() }.uppercase()
    if (state in LOADED_STATES) return true
    val type = root.optString("type")
    return (type == "data" || type == "status" || type == "nowPlaying") && data?.has("currentTime") == true && named == videoId
}

internal fun sameVideo(videoId: String, contentId: String): Boolean {
    if (contentId.isBlank() || videoId.isBlank()) return false
    if (contentId == videoId) return true
    if (contentId.contains("v=$videoId")) return true
    return contentId.endsWith("/$videoId") || contentId.endsWith("=$videoId")
}

internal fun sameMedia(expectedUrl: String, contentId: String): Boolean {
    if (contentId.isBlank()) return false
    return contentId == expectedUrl || contentId.trimEnd('/') == expectedUrl.trimEnd('/')
}

/**
 * CASTV2 session decisions with no socket. DIAL and the lounge are requested as effects and reported back through
 * [onDial] and [onLounge].
 * YouTube: launch or reuse the YouTube receiver, ask it for its lounge screen id, then hand the video over through the
 * lounge. When the screen id does not come or the lounge fails, DIAL and `flingVideo` are tried once.
 * Success is a receiver or media payload that names the video as loaded or playing, or, after the lounge accepted the
 * video, [LOUNGE_READY_GRACE_MS] without an error. A receiver that is already running a different app is not reused.
 * [log] receives secret-free lines (see CastLogging.kt).
 */
internal class CastSession(
    private val goal: CastGoal,
    private val senderId: String = CAST_SENDER_ID,
    private val log: (String) -> Unit = {},
) {
    private var requestId = 0
    private var lastHeard = 0L
    private var lastPing = 0L
    private var opened = false
    private var finished = false
    private var readySent = false
    private var launchSent = false
    private var launchRequest = 0
    private var joined = false
    private var handed = false
    private var dialOk: Boolean? = null
    private var dialPosted = false
    private var dialAnsweredAt = 0L
    private var startedAt = 0L
    private var mdxAskedAt: Long? = null
    private var mdxLastAsk = 0L
    private var mdxAsks = 0
    private var loungeSent = false
    private var loungeOkAt: Long? = null
    private var legacy = false
    private var transportId: String? = null
    private var appSessionId: String? = null
    private var mediaSessionId = 0
    private var volume = 1f
    private var lastMediaPoll = 0L
    private var tornDown = false

    private val appId: String
        get() = if (goal is CastGoal.YouTube) YOUTUBE_RECEIVER_APP_ID else DEFAULT_MEDIA_APP_ID

    fun volume(): Float = volume

    fun start(nowMs: Long): List<CastEffect> {
        lastHeard = nowMs
        lastPing = nowMs
        startedAt = nowMs
        opened = true
        val effects = ArrayList<CastEffect>()
        effects += send(CAST_RECEIVER_ID, NS_CONNECTION, connectPayload())
        effects += send(CAST_RECEIVER_ID, NS_RECEIVER, json("GET_STATUS", nextRequest()))
        return effects
    }

    fun onDial(code: Int?, nowMs: Long): List<CastEffect> {
        if (finished) return emptyList()
        heard(nowMs)
        dialOk = code != null && code in 200..299
        dialAnsweredAt = nowMs
        if (goal is CastGoal.YouTube && dialOk != true && transportId == null && !launchSent) return launch()
        return emptyList()
    }

    /** The lounge hand-over finished. Ok starts the wait for confirmation; any failure falls back to DIAL once. */
    fun onLounge(result: LoungeResult, nowMs: Long): List<CastEffect> {
        if (finished || goal !is CastGoal.YouTube || !loungeSent || legacy || loungeOkAt != null) return emptyList()
        if (result == LoungeResult.Ok) {
            loungeOkAt = nowMs
            handed = true
            val dest = transportId ?: return emptyList()
            lastMediaPoll = nowMs
            return listOf(send(dest, NS_MEDIA, mediaStatusRequest()))
        }
        log("youtube step=lounge result=${loungeResultName(result)} -> dial fallback")
        return fallBackToDial()
    }

    fun onFrame(frame: CastFrame, nowMs: Long): List<CastEffect> {
        if (finished) return emptyList()
        heard(nowMs)
        if (parseCastError(frame.payload) != null) return fail(CastFailure.REJECTED)
        if (frame.namespace == NS_HEARTBEAT) {
            return if (jsonType(frame.payload) == "PING") {
                listOf(send(frame.sourceId.ifBlank { CAST_RECEIVER_ID }, NS_HEARTBEAT, """{"type":"PONG"}"""))
            } else {
                emptyList()
            }
        }
        if (frame.namespace == NS_RECEIVER) return onReceiver(frame.payload, nowMs)
        if (frame.namespace == NS_MEDIA || frame.namespace == YOUTUBE_NAMESPACE) return onPayload(frame.namespace, frame.payload, nowMs)
        return emptyList()
    }

    fun onClock(nowMs: Long): List<CastEffect> {
        if (finished || !opened) return emptyList()
        if (nowMs - lastHeard >= HEARTBEAT_SILENCE_MS) return fail(CastFailure.SILENCE)
        val effects = ArrayList<CastEffect>()
        if (goal is CastGoal.YouTube) {
            val asked = mdxAskedAt
            val dest = transportId
            if (!legacy && !loungeSent && asked != null && dest != null) {
                if (nowMs - asked >= MDX_STATUS_WAIT_MS) {
                    log("youtube step=mdxStatus timeout ms=${nowMs - asked} asks=$mdxAsks -> dial fallback")
                    effects += fallBackToDial()
                } else if (nowMs - mdxLastAsk >= MDX_STATUS_RETRY_MS) {
                    effects += askMdxStatus(dest, nowMs)
                }
            }
            if (legacy && !handed && !readySent && dest != null && dialOk != null) {
                if (dialOk == false || nowMs - dialAnsweredAt >= YOUTUBE_FLING_FALLBACK_MS) effects += fling()
            }
            val okAt = loungeOkAt
            if (okAt != null && !readySent && nowMs - okAt >= LOUNGE_READY_GRACE_MS) {
                log("youtube ready via=grace ms=${nowMs - startedAt}")
                effects += succeed()
            }
        }
        val waitingForLounge = loungeOkAt != null && !readySent
        if ((waitingForLounge || (readySent && mediaSessionId > 0)) && transportId != null && nowMs - lastMediaPoll >= 1_000L) {
            lastMediaPoll = nowMs
            effects += send(transportId!!, NS_MEDIA, mediaStatusRequest())
        }
        if (nowMs - lastPing >= HEARTBEAT_INTERVAL_MS) {
            lastPing = nowMs
            effects += send(CAST_RECEIVER_ID, NS_HEARTBEAT, """{"type":"PING"}""")
        }
        return effects
    }

    fun play(): List<CastEffect> = mediaCommand("PLAY")

    fun pause(): List<CastEffect> = mediaCommand("PAUSE")

    fun seek(positionSec: Double): List<CastEffect> {
        val dest = transportId ?: return emptyList()
        if (mediaSessionId <= 0 || finished) return emptyList()
        val payload =
            JSONObject()
                .put("type", "SEEK")
                .put("requestId", nextRequest())
                .put("mediaSessionId", mediaSessionId)
                .put("currentTime", positionSec.coerceAtLeast(0.0))
                .toString()
        return listOf(send(dest, NS_MEDIA, payload))
    }

    fun setVolume(level: Float): List<CastEffect> {
        if (finished) return emptyList()
        volume = level.coerceIn(0f, 1f)
        val payload =
            JSONObject()
                .put("type", "SET_VOLUME")
                .put("requestId", nextRequest())
                .put("volume", JSONObject().put("level", volume.toDouble()).put("muted", false))
                .toString()
        return listOf(send(CAST_RECEIVER_ID, NS_RECEIVER, payload))
    }

    fun stopMedia(): List<CastEffect> = mediaCommand("STOP")

    fun close(): List<CastEffect> {
        if (tornDown) return emptyList()
        finished = true
        tornDown = true
        val effects = ArrayList<CastEffect>()
        if (mediaSessionId > 0 && transportId != null) {
            effects += send(transportId!!, NS_MEDIA, commandPayload("STOP", mediaSessionId))
        }
        val session = appSessionId
        if (!session.isNullOrBlank()) {
            effects +=
                send(
                    CAST_RECEIVER_ID,
                    NS_RECEIVER,
                    JSONObject().put("type", "STOP").put("requestId", nextRequest()).put("sessionId", session).toString(),
                )
        }
        effects += send(CAST_RECEIVER_ID, NS_CONNECTION, """{"type":"CLOSE"}""")
        val transport = transportId
        if (transport != null) effects += send(transport, NS_CONNECTION, """{"type":"CLOSE"}""")
        if (goal is CastGoal.YouTube && dialPosted) effects += CastEffect.DialDelete
        return effects
    }

    private fun onReceiver(payload: String, nowMs: Long): List<CastEffect> {
        val snapshot = parseReceiverStatus(payload) ?: return emptyList()
        volume = snapshot.volume
        when (val action = receiverAction(appId, snapshot)) {
            is ReceiverAction.Reuse -> {
                appSessionId = action.sessionId
                val previous = transportId
                val transportChanged = previous != null && previous != action.transportId
                transportId = action.transportId
                val effects = ArrayList<CastEffect>()
                if (!joined || transportChanged) {
                    joined = true
                    log("app joined app=${logToken(appId)} transport=yes")
                    effects += send(action.transportId, NS_CONNECTION, connectPayload())
                }
                if (goal is CastGoal.Media && !handed) effects += load()
                if (goal is CastGoal.YouTube && mdxAskedAt == null && !legacy) effects += askMdxStatus(action.transportId, nowMs)
                if (goal is CastGoal.YouTube && legacy && dialOk == false && !handed) effects += fling()
                return effects
            }
            ReceiverAction.Launch -> {
                if (launchSent && snapshot.requestId == launchRequest) {
                    return fail(if (goal is CastGoal.YouTube) CastFailure.YOUTUBE_UNCONFIRMED else CastFailure.GENERIC)
                }
                if (!launchSent) return launch()
                return emptyList()
            }
        }
    }

    private fun onPayload(namespace: String, payload: String, nowMs: Long): List<CastEffect> {
        val media = parseMediaStatus(payload)
        media.firstOrNull { it.mediaSessionId > 0 }?.let { mediaSessionId = it.mediaSessionId }
        when (val current = goal) {
            is CastGoal.YouTube -> {
                if (namespace == YOUTUBE_NAMESPACE && !legacy && !loungeSent && mdxAskedAt != null) {
                    val screenId = parseMdxScreenId(payload)
                    if (screenId != null) {
                        loungeSent = true
                        log("youtube step=mdxStatus rx screenId=yes")
                        return listOf(CastEffect.LoungePlay(screenId, current.video.videoId, current.video.startSec))
                    }
                }
                if (youtubeStatusConfirms(current.video.videoId, payload)) {
                    if (!readySent) {
                        val via = if (namespace == NS_MEDIA) "MEDIA_STATUS" else "mdx"
                        val state =
                            media.firstOrNull { sameVideo(current.video.videoId, it.contentId) }?.playerState.orEmpty()
                        val stateText = if (state.isEmpty()) "" else " state=${logToken(state)}"
                        log("youtube confirmed via=$via$stateText ms=${nowMs - startedAt}")
                    }
                    return succeed()
                }
                // While the lounge hand-over is pending, a leftover IDLE status from an earlier video is not a
                // failure; an ERROR is.
                val broken =
                    media.any {
                        it.playerState.equals("IDLE", true) && it.idleReason.isNotBlank() &&
                            (loungeOkAt == null || readySent || it.idleReason.equals("ERROR", true))
                    }
                if (broken && handed) return fail(CastFailure.YOUTUBE_UNCONFIRMED)
                if (legacy && !handed && transportId != null && dialOk != null && (dialOk == false || media.isNotEmpty())) {
                    return fling()
                }
                return emptyList()
            }
            is CastGoal.Media -> {
                val match = media.firstOrNull { sameMedia(current.media.url, it.contentId) }
                if (match != null && match.playerState.uppercase() in LOADED_STATES) {
                    val effects = ArrayList<CastEffect>()
                    if (!readySent) effects += succeed()
                    effects += progress(match)
                    return effects
                }
                val failed = media.any { it.playerState.equals("IDLE", true) && it.idleReason.equals("ERROR", true) }
                if (failed && handed) return fail(CastFailure.GENERIC)
                return emptyList()
            }
        }
    }

    private fun launch(): List<CastEffect> {
        launchSent = true
        launchRequest = nextRequest()
        val payload = JSONObject().put("type", "LAUNCH").put("requestId", launchRequest).put("appId", appId).toString()
        return listOf(send(CAST_RECEIVER_ID, NS_RECEIVER, payload))
    }

    private fun load(): List<CastEffect> {
        val dest = transportId ?: return emptyList()
        val media = goal as? CastGoal.Media ?: return emptyList()
        handed = true
        val payload = loadPayload(nextRequest(), media.media, media.title, media.poster)
        return listOf(send(dest, NS_MEDIA, payload))
    }

    private fun fling(): List<CastEffect> {
        val dest = transportId ?: return emptyList()
        val video = goal as? CastGoal.YouTube ?: return emptyList()
        handed = true
        log("youtube step=fling tx")
        return listOf(send(dest, YOUTUBE_NAMESPACE, youtubeFlingPayload(video.video.videoId, video.video.startSec)))
    }

    private fun askMdxStatus(dest: String, nowMs: Long): List<CastEffect> {
        if (mdxAskedAt == null) {
            mdxAskedAt = nowMs
            log("youtube step=mdxStatus tx")
        }
        mdxLastAsk = nowMs
        mdxAsks += 1
        return listOf(send(dest, YOUTUBE_NAMESPACE, youtubeMdxStatusRequest()))
    }

    /** The legacy hand-over, once per attempt: DIAL with the video id, then `flingVideo` on the Cast channel. */
    private fun fallBackToDial(): List<CastEffect> {
        val video = goal as? CastGoal.YouTube ?: return emptyList()
        if (legacy) return emptyList()
        legacy = true
        if (dialPosted) return emptyList()
        dialPosted = true
        return listOf(CastEffect.DialPost(youtubeDialBody(video.video.videoId, video.video.startSec)))
    }

    private fun succeed(): List<CastEffect> {
        if (readySent) return emptyList()
        readySent = true
        return listOf(CastEffect.Ready(volume))
    }

    private fun progress(item: MediaSnapshot): CastEffect.Progress =
        CastEffect.Progress(
            playing = item.playerState.equals("PLAYING", true) || item.playerState.equals("BUFFERING", true),
            positionSec = item.positionSec,
            durationSec = item.durationSec,
            volume = volume,
        )

    private fun fail(reason: CastFailure): List<CastEffect> {
        if (finished) return emptyList()
        finished = true
        return listOf(CastEffect.Failed(reason))
    }

    private fun mediaCommand(type: String): List<CastEffect> {
        val dest = transportId ?: return emptyList()
        if (mediaSessionId <= 0 || finished) return emptyList()
        return listOf(send(dest, NS_MEDIA, commandPayload(type, mediaSessionId)))
    }

    private fun mediaStatusRequest(): String {
        val payload = JSONObject().put("type", "GET_STATUS").put("requestId", nextRequest())
        if (mediaSessionId > 0) payload.put("mediaSessionId", mediaSessionId)
        return payload.toString()
    }

    private fun commandPayload(type: String, session: Int): String =
        JSONObject().put("type", type).put("requestId", nextRequest()).put("mediaSessionId", session).toString()

    private fun json(type: String, id: Int): String = JSONObject().put("type", type).put("requestId", id).toString()

    private fun send(destination: String, namespace: String, payload: String): CastEffect.Send =
        CastEffect.Send(CastFrame(senderId, destination, namespace, payload))

    private fun nextRequest(): Int {
        requestId += 1
        return requestId
    }

    private fun heard(nowMs: Long) {
        lastHeard = nowMs
    }
}

private fun jsonObject(payload: String): JSONObject? = parseJsonObject(payload)

private fun jsonType(payload: String): String = jsonObject(payload)?.optString("type").orEmpty()

/** The variant name of a lounge result, with its HTTP status or exception class name. No other detail. */
internal fun loungeResultName(result: LoungeResult): String =
    when (result) {
        LoungeResult.Ok -> "Ok"
        is LoungeResult.TokenFailed -> "TokenFailed http=${result.http}"
        is LoungeResult.BindFailed -> "BindFailed http=${result.http}"
        is LoungeResult.PlayFailed -> "PlayFailed http=${result.http}"
        is LoungeResult.Network -> "Network err=${logToken(result.errName)}"
    }
