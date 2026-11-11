package com.shibi.orbis.youtube

import com.shibi.orbis.cast.parseJsonArray
import com.shibi.orbis.cast.parseJsonObject
import java.net.URLEncoder
import java.util.Random
import org.json.JSONArray

/*
 * YouTube lounge (MDX) client: hands a video to a TV's YouTube app through YouTube's own servers, the way the YouTube
 * phone app does. The TV reports its screen id over the local Cast channel; this exchanges it for a lounge token,
 * binds a remote-control session and sends setPlaylist. Pure: no Android imports, the HTTP transport is injected.
 * Log lines never carry the screen id, token, SID, gsessionid, video id, URLs or bodies.
 */

internal const val LOUNGE_TOKEN_URL = "https://www.youtube.com/api/lounge/pairing/get_lounge_token_batch"
internal const val LOUNGE_BIND_URL = "https://www.youtube.com/api/lounge/bc/bind"
internal const val LOUNGE_ORIGIN = "https://www.youtube.com"

/** Largest lounge response that is parsed. Anything longer is rejected. */
internal const val LOUNGE_MAX_RESPONSE_CHARS = 256 * 1024

/** Connect plus read budget for each of the three lounge requests. */
internal const val LOUNGE_STEP_TIMEOUT_MS = 5_000

private const val FORM = "application/x-www-form-urlencoded"

internal data class LoungeRequest(val url: String, val headers: Map<String, String>, val body: String)

internal data class LoungeResponse(val code: Int, val body: String)

internal data class LoungeBind(val sid: String, val gsessionid: String, val lastEventId: Int)

/** One lounge event: `[id, ["type", arg...]]`. */
internal data class LoungeEvent(val id: Int, val type: String, val args: JSONArray)

internal sealed interface LoungeResult {
    data object Ok : LoungeResult
    data class TokenFailed(val http: Int) : LoungeResult
    data class BindFailed(val http: Int) : LoungeResult
    data class PlayFailed(val http: Int) : LoungeResult
    data class Network(val errName: String) : LoungeResult
}

internal enum class LoungeStep { TOKEN, BIND, SET_PLAYLIST }

/** Posts one lounge request. Throws on network failure; null when there was no usable response. */
internal interface LoungeTransport {
    fun post(req: LoungeRequest, timeoutMs: Int): LoungeResponse?
}

private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun form(fields: List<Pair<String, String>>): String = fields.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

internal fun loungeTokenRequest(screenId: String): LoungeRequest =
    LoungeRequest(
        url = LOUNGE_TOKEN_URL,
        headers = mapOf("Content-Type" to FORM, "Origin" to LOUNGE_ORIGIN),
        body = form(listOf("screen_ids" to screenId)),
    )

/** `screens[0].loungeToken`, or null when the response has no token. */
internal fun parseLoungeToken(body: String): String? {
    if (body.length > LOUNGE_MAX_RESPONSE_CHARS) return null
    val root = parseJsonObject(body) ?: return null
    val screen = root.optJSONArray("screens")?.optJSONObject(0) ?: return null
    return screen.optString("loungeToken").trim().takeIf { it.isNotEmpty() }
}

private fun loungeHeaders(token: String): Map<String, String> =
    mapOf(
        "X-YouTube-LoungeId-Token" to token,
        "Origin" to LOUNGE_ORIGIN,
        "Content-Type" to FORM,
    )

/** First bind of a remote-control session. [deviceId] is 32 random hex characters, new for every attempt. */
internal fun loungeBindRequest(token: String, rid: Int, deviceId: String): LoungeRequest =
    LoungeRequest(
        url = "$LOUNGE_BIND_URL?RID=$rid&VER=8&CVER=1",
        headers = loungeHeaders(token),
        body =
            form(
                listOf(
                    "device" to "REMOTE_CONTROL",
                    "id" to deviceId,
                    "name" to "Orbis",
                    "mdx-version" to "3",
                    "pairing_type" to "cast",
                    "app" to "android-phone-13.14.55",
                    "count" to "0",
                ),
            ),
    )

internal fun loungeSetPlaylistRequest(
    token: String,
    bind: LoungeBind,
    rid: Int,
    videoId: String,
    startSec: Double,
): LoungeRequest {
    val start = if (startSec.isFinite()) startSec.toLong().coerceAtLeast(0L) else 0L
    return LoungeRequest(
        url = "$LOUNGE_BIND_URL?SID=${enc(bind.sid)}&gsessionid=${enc(bind.gsessionid)}&RID=$rid&VER=8&CVER=1",
        headers = loungeHeaders(token),
        body =
            form(
                listOf(
                    "count" to "1",
                    "ofs" to "0",
                    "req0__sc" to "setPlaylist",
                    "req0_videoId" to videoId,
                    "req0_currentTime" to start.toString(),
                    "req0_currentIndex" to "-1",
                    "req0_listId" to "",
                    "req0_audioOnly" to "false",
                ),
            ),
    )
}

/** Lounge events from a chunked response. Null when the input is oversized or no chunk parses. */
internal fun parseLoungeEvents(body: String): List<LoungeEvent>? = parseLoungeChunks(body)?.events

internal class LoungeChunks(val events: List<LoungeEvent>, val chunks: Int)

/**
 * Splits `<decimal length>\n<JSON array>` chunks. Length lines are dropped rather than trusted, so one chunk, many
 * chunks or a bare array all parse.
 */
internal fun parseLoungeChunks(body: String): LoungeChunks? {
    if (body.length > LOUNGE_MAX_RESPONSE_CHARS) return null
    val events = ArrayList<LoungeEvent>()
    var chunks = 0
    var index = 0
    while (index < body.length) {
        val start = body.indexOf('[', index)
        if (start < 0) break
        val end = matchingBracket(body, start)
        if (end < 0) break
        val array = parseJsonArray(body.substring(start, end + 1))
        if (array != null) {
            chunks += 1
            for (i in 0 until array.length()) {
                val event = array.optJSONArray(i) ?: continue
                val payload = event.optJSONArray(1) ?: continue
                val type = payload.optString(0)
                if (type.isEmpty()) continue
                events += LoungeEvent(event.optInt(0, -1), type, payload)
            }
        }
        index = end + 1
    }
    return if (chunks == 0) null else LoungeChunks(events, chunks)
}

/** Index of the `]` closing the array that opens at [start], skipping brackets inside JSON strings. -1 if unclosed. */
private fun matchingBracket(text: String, start: Int): Int {
    var depth = 0
    var inString = false
    var escaped = false
    var i = start
    while (i < text.length) {
        val c = text[i]
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
        } else {
            when (c) {
                '"' -> inString = true
                '[' -> depth += 1
                ']' -> {
                    depth -= 1
                    if (depth == 0) return i
                }
            }
        }
        i += 1
    }
    return -1
}

/** SID from the `c` event and gsessionid from the `S` event. Null unless both are present. */
internal fun parseLoungeBind(body: String): LoungeBind? {
    val events = parseLoungeEvents(body) ?: return null
    val sid = events.firstOrNull { it.type == "c" }?.args?.optString(1)?.takeIf { it.isNotBlank() } ?: return null
    val gsid = events.firstOrNull { it.type == "S" }?.args?.optString(1)?.takeIf { it.isNotBlank() } ?: return null
    return LoungeBind(sid, gsid, events.maxOf { it.id })
}

/** True when a `nowPlaying` event in [body] names [videoId]. Optional evidence; success does not require it. */
internal fun loungeNowPlaying(body: String, videoId: String): Boolean {
    val events = parseLoungeEvents(body) ?: return false
    return events.any { event ->
        event.type == "nowPlaying" && event.args.optJSONObject(1)?.optString("videoId") == videoId
    }
}

/** The result of a lounge step that did not return a usable 200. */
internal fun loungeFailure(step: LoungeStep, http: Int): LoungeResult =
    when (step) {
        LoungeStep.TOKEN -> LoungeResult.TokenFailed(http)
        LoungeStep.BIND -> LoungeResult.BindFailed(http)
        LoungeStep.SET_PLAYLIST -> LoungeResult.PlayFailed(http)
    }

/** setPlaylist succeeds on 200 only. 400 (unknown SID) and 410 mean the session was lost; 401 means the token expired. */
internal fun loungeSetPlaylistResult(http: Int): LoungeResult =
    if (http == 200) LoungeResult.Ok else LoungeResult.PlayFailed(http)

/** Short, secret-free meaning of a lounge HTTP status, for the log. Empty when there is nothing to add. */
internal fun loungeHttpMeaning(http: Int): String =
    when (http) {
        400, 410 -> "sessionLost"
        401 -> "tokenExpired"
        else -> ""
    }

/** Event type names only, ASCII letters and digits, for the log. */
private fun eventTypes(events: List<LoungeEvent>): String =
    events
        .map { event -> event.type.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }.take(32) }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(8)
        .joinToString(",")

/**
 * Runs token, bind and setPlaylist in order. [isCurrent] is checked before, between and after the requests; the
 * result is null when the attempt was abandoned on the way.
 */
internal class LoungeClient(
    private val transport: LoungeTransport,
    private val log: (String) -> Unit = {},
    private val random: Random = java.security.SecureRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun play(screenId: String, videoId: String, startSec: Double, isCurrent: () -> Boolean): LoungeResult? {
        if (!isCurrent()) return null
        val token =
            when (val step = exchange(LoungeStep.TOKEN, loungeTokenRequest(screenId))) {
                is Step.Failed -> return step.result
                is Step.Done -> {
                    val token = if (step.response.code == 200) parseLoungeToken(step.response.body) else null
                    log("youtube step=token http=${step.response.code} ms=${step.ms} token=${yes(token != null)}")
                    token ?: return loungeFailure(LoungeStep.TOKEN, step.response.code)
                }
            }
        if (!isCurrent()) return null
        val rid = 10_000 + random.nextInt(80_000)
        val deviceId = hex32()
        val bind =
            when (val step = exchange(LoungeStep.BIND, loungeBindRequest(token, rid, deviceId))) {
                is Step.Failed -> return step.result
                is Step.Done -> {
                    val code = step.response.code
                    val chunks = if (code == 200) parseLoungeChunks(step.response.body) else null
                    val bind = if (chunks != null) parseLoungeBind(step.response.body) else null
                    if (code != 200) {
                        log("youtube step=bind failed http=$code ms=${step.ms}${meaning(code)}")
                    } else {
                        val sid = chunks?.events?.any { it.type == "c" } == true
                        val gsid = chunks?.events?.any { it.type == "S" } == true
                        log(
                            "youtube step=bind http=$code ms=${step.ms} sid=${yes(sid)} gsid=${yes(gsid)} " +
                                "chunks=${chunks?.chunks ?: 0} bytes=${step.response.body.length}",
                        )
                    }
                    bind ?: return loungeFailure(LoungeStep.BIND, code)
                }
            }
        if (!isCurrent()) return null
        val result =
            when (val step = exchange(LoungeStep.SET_PLAYLIST, loungeSetPlaylistRequest(token, bind, rid + 1, videoId, startSec))) {
                is Step.Failed -> return step.result
                is Step.Done -> {
                    val code = step.response.code
                    if (code == 200) {
                        val events = parseLoungeEvents(step.response.body).orEmpty()
                        val nowPlaying = loungeNowPlaying(step.response.body, videoId)
                        log("youtube step=setPlaylist http=$code ms=${step.ms} nowPlaying=${yes(nowPlaying)} types=${eventTypes(events)}")
                    } else {
                        log("youtube step=setPlaylist failed http=$code ms=${step.ms}${meaning(code)}")
                    }
                    loungeSetPlaylistResult(code)
                }
            }
        if (!isCurrent()) return null
        return result
    }

    private fun exchange(step: LoungeStep, request: LoungeRequest): Step {
        val started = clock()
        val response =
            try {
                transport.post(request, LOUNGE_STEP_TIMEOUT_MS)
            } catch (e: Exception) {
                val name = e.javaClass.simpleName
                log("youtube step=${stepName(step)} err=$name ms=${clock() - started}")
                return Step.Failed(LoungeResult.Network(name))
            }
        val ms = clock() - started
        if (response == null) {
            log("youtube step=${stepName(step)} err=NoResponse ms=$ms")
            return Step.Failed(LoungeResult.Network("NoResponse"))
        }
        return Step.Done(response, ms)
    }

    private fun hex32(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun stepName(step: LoungeStep): String =
        when (step) {
            LoungeStep.TOKEN -> "token"
            LoungeStep.BIND -> "bind"
            LoungeStep.SET_PLAYLIST -> "setPlaylist"
        }

    private fun meaning(http: Int): String = loungeHttpMeaning(http).let { if (it.isEmpty()) "" else " meaning=$it" }

    private fun yes(value: Boolean): String = if (value) "yes" else "no"

    private sealed interface Step {
        data class Done(val response: LoungeResponse, val ms: Long) : Step
        data class Failed(val result: LoungeResult) : Step
    }
}
