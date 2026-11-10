package com.shibi.orbis.youtube

import java.net.URI
import java.net.URLDecoder
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Average length of one YouTube ad that would have played, in seconds.
 *
 * In-stream ads are often between about 6 and 30 seconds. Fifteen is the midpoint used for the estimate.
 * [estimatedSecondsSaved] takes this as its default and accepts another value, so the figure stays in one place.
 * The dashboard states the same number. This is an estimate, not a measurement of a particular ad.
 *
 * What is counted, per video view:
 * - Primary: ad slots in that view's player response (`adPlacements`, `playerAds`, `adSlots`), deduped by slot or
 *   instream id. These are ads that were scheduled to play. The watch-page HTML and the `/youtubei/v1/player` call for
 *   the same view are one view when they arrive within [SAME_VIEW_WINDOW_MILLIS].
 * - Fallback, only when that view had no player response: one count per blocked break ping that names an ad
 *   (`/api/stats/ads`, `get_midroll_info`, `ad_break`, `/pagead/adview`, `/pagead/interaction`). Generic ad hosts do
 *   not count. A ping whose id was already counted for this view, including by the player response, does not count
 *   again.
 * - Opening the same video again counts only when a new player response lists the slots again. A time gap alone does
 *   not.
 */
const val AVERAGE_YOUTUBE_AD_SECONDS = 15

/**
 * The watch-page HTML and the player API often both carry the same slots for one view. A later response inside this
 * window only adds slot ids that were not already counted. After it, another player response is a new view.
 */
internal const val SAME_VIEW_WINDOW_MILLIS = 2_000L

internal const val TITLE_MAX_CHARS = 120
internal const val MAX_AD_KEYS = 24

/** Error string recorded for a GeckoView content-blocking event. It counts as blocked. */
internal const val CONTENT_BLOCKING_ERROR = "NS_ERROR_BLOCKED_BY_CONTENT_BLOCKING"

private val AD_ID_KEYS = listOf("ad_id", "adid", "ad_v", "ad_docid", "ad_cpn", "ad_break_id", "break_id", "breakid", "ai")

/** One blocked-ad observation, already detached from Gecko types. */
data class YoutubeAdObservation(
    val pageUrl: String?,
    val pageTitle: String?,
    val requestUrl: String,
    val isPrivate: Boolean,
    val error: String,
    val atMillis: Long,
)

/** What the built-in observer reports. Private tabs are ignored by the store. */
sealed interface YoutubeAdEvent {
    val isPrivate: Boolean

    data class Blocked(val observation: YoutubeAdObservation) : YoutubeAdEvent {
        override val isPrivate: Boolean get() = observation.isPrivate
    }

    /** Ad slots from one player response (the `/youtubei/v1/player` body, or `ytInitialPlayerResponse` in the watch HTML). */
    data class PlayerAds(
        val videoId: String,
        val title: String?,
        val pageUrl: String?,
        val slotIds: List<String>,
        override val isPrivate: Boolean,
        val atMillis: Long,
    ) : YoutubeAdEvent

    data class Title(val pageUrl: String, val title: String, override val isPrivate: Boolean) : YoutubeAdEvent
}

/** Saved shape of one video. [viewKeys] are the ids already counted for the current view, not for the lifetime of the video. */
internal data class VideoAdMemory(
    val videoId: String,
    val title: String?,
    val adCount: Int,
    val estimatedSeconds: Long,
    val timestampMillis: Long,
    val viewKeys: Set<String>,
    val viewFromPlayer: Boolean,
    val lastSlotFingerprint: String?,
    val lastPlayerAtMillis: Long,
    val provisionalAds: Int,
)

internal data class AdPing(val videoId: String, val title: String?, val explicitAdId: String?, val atMillis: Long)

internal data class AdApplyResult(val memory: VideoAdMemory, val addedAds: Int, val addedSeconds: Long)

/** `adCount` times [secondsPerAd]. Zero when either side is not positive. */
internal fun estimatedSecondsSaved(adCount: Int, secondsPerAd: Int = AVERAGE_YOUTUBE_AD_SECONDS): Long {
    if (adCount <= 0 || secondsPerAd <= 0) return 0L
    return adCount.toLong() * secondsPerAd.toLong()
}

/**
 * "2 h 14 min", "14 min", "45 s", "0 min". Hours omit leftover seconds. Under an hour, leftover seconds are kept.
 */
internal fun formatEstimatedTimeSaved(totalSeconds: Long): String {
    val s = if (totalSeconds <= 0L) 0L else totalSeconds
    if (s == 0L) return "0 min"
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val seconds = s % 60
    return when {
        hours > 0 && minutes > 0 -> "$hours h $minutes min"
        hours > 0 -> "$hours h"
        minutes > 0 && seconds > 0 -> "$minutes min $seconds s"
        minutes > 0 -> "$minutes min"
        else -> "$seconds s"
    }
}

/** True for the errors that mean a request was cancelled or blocked, not that the network failed. */
internal fun isBlockedAdError(error: String): Boolean {
    val e = error.trim().uppercase()
    if (e.isEmpty()) return false
    if (e.startsWith("NS_ERROR_NET_") || e.startsWith("NS_ERROR_CONNECTION_") || e.startsWith("NS_ERROR_OFFLINE") || e.startsWith("NS_ERROR_UNKNOWN_HOST")) {
        return false
    }
    return e == "NS_ERROR_ABORT" || e.startsWith("NS_ERROR_BLOCKED") || "CONTENT_BLOCKING" in e || "TRACKING_URI" in e
}

internal fun isVideoId(value: String): Boolean =
    value.length == 11 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }

internal fun isYoutubePageHost(raw: String): Boolean {
    val host = hostOf(raw) ?: return false
    return host == "youtu.be" || host.endsWith(".youtu.be") ||
        host == "youtube.com" || host.endsWith(".youtube.com") ||
        host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")
}

/**
 * A break-specific ad ping: `/api/stats/ads`, `/pagead/adview`, `/pagead/interaction`, or `get_midroll` / `ad_break` on
 * a YouTube host. `/pagead/id`, `ad_status.js`, doubleclick, googleads, googleadservices and googlesyndication do not
 * count on their own.
 */
internal fun isBreakSpecificAdRequest(raw: String): Boolean {
    val path = pathOf(raw).lowercase()
    if ("/api/stats/ads" in path) return true
    if ("/pagead/adview" in path || "/pagead/interaction" in path) return true
    if (isYoutubePageHost(raw) && ("get_midroll" in path || "ad_break" in path)) return true
    return false
}

/** Watch, shorts, embed, live, `/v/`, or a youtu.be link. Null for the YouTube homepage and for other sites. */
internal fun youtubeVideoId(raw: String): String? {
    if (!isYoutubePageHost(raw)) return null
    val host = hostOf(raw) ?: return null
    if (host == "youtu.be" || host.endsWith(".youtu.be")) {
        return pathOf(raw).trim('/').substringBefore('/').takeIf(::isVideoId)
    }
    queryMap(raw)["v"]?.takeIf(::isVideoId)?.let { return it }
    val parts = pathOf(raw).split('/').filter { it.isNotEmpty() }
    val markers = setOf("shorts", "embed", "live", "v")
    for (i in parts.indices) {
        if (parts[i].lowercase() in markers) {
            parts.getOrNull(i + 1)?.takeIf(::isVideoId)?.let { return it }
        }
    }
    return null
}

/**
 * The watch page's video id. A missing page URL is not a watch page, even when the ad request names a video.
 * Other sites are ignored.
 */
internal fun resolveYoutubeVideoId(pageUrl: String?, requestUrl: String): String? {
    if (pageUrl.isNullOrBlank() || !isYoutubePageHost(pageUrl)) return null
    return youtubeVideoId(pageUrl)
}

/** The tab title counts only when it was captured for the same video as [pageUrl]. YouTube's SPA reuses the tab. */
internal fun titleForSameVideo(title: String?, titleUrl: String?, pageUrl: String?): String? {
    val pageVideo = youtubeVideoId(pageUrl.orEmpty()) ?: return null
    val titled = youtubeVideoId(titleUrl.orEmpty()) ?: return null
    if (pageVideo != titled) return null
    return cleanTitle(title)
}

/** Stable id of one ad creative, when the request carries one. Repeat pings share it. */
internal fun explicitAdId(raw: String): String? {
    val q = queryMap(raw)
    for (key in AD_ID_KEYS) {
        val value = q[key]?.trim().orEmpty()
        if (value.length in 1..200 && value != "0" && !value.equals("undefined", true) && !value.equals("null", true)) {
            return "$key:${value.take(120)}"
        }
    }
    return null
}

/**
 * Null unless this is a blocked break ping, with an ad id, on a YouTube watch page, in a normal tab. Private tabs,
 * network errors, generic ad hosts, and requests with no watch-page URL are dropped.
 */
internal fun classifyBlockedYoutubeAd(
    pageUrl: String?,
    pageTitle: String?,
    requestUrl: String,
    error: String,
    isPrivate: Boolean,
): AdPing? {
    if (isPrivate) return null
    if (!isBlockedAdError(error)) return null
    if (!isBreakSpecificAdRequest(requestUrl)) return null
    val adId = explicitAdId(requestUrl) ?: return null
    val videoId = resolveYoutubeVideoId(pageUrl, requestUrl) ?: return null
    return AdPing(videoId, cleanTitle(pageTitle), adId, atMillis = 0L)
}

/** Cheap gate so the engine does not report ordinary tracker blocks to the YouTube estimate. */
internal fun mightReportYoutubeAd(pageUrl: String?, requestUrl: String): Boolean {
    if (!isBreakSpecificAdRequest(requestUrl) || explicitAdId(requestUrl) == null) return false
    return youtubeVideoId(pageUrl.orEmpty()) != null
}

/**
 * Count a fallback ping. No ad id never counts. The same id counts once until a new player response starts a new view.
 * Pings are not added on top of a player response for the current view. A time gap does not start a new ad.
 */
internal fun applyAdPing(previous: VideoAdMemory?, ping: AdPing, secondsPerAd: Int = AVERAGE_YOUTUBE_AD_SECONDS): AdApplyResult {
    val title = cleanTitle(ping.title) ?: previous?.title
    val explicit = ping.explicitAdId?.takeIf { it.isNotBlank() }
    if (previous == null) {
        if (explicit == null) {
            return AdApplyResult(emptyMemory(ping.videoId, title, ping.atMillis), addedAds = 0, addedSeconds = 0)
        }
        val seconds = estimatedSecondsSaved(1, secondsPerAd)
        return AdApplyResult(
            VideoAdMemory(
                videoId = ping.videoId,
                title = title,
                adCount = 1,
                estimatedSeconds = seconds,
                timestampMillis = ping.atMillis,
                viewKeys = setOf(explicit),
                viewFromPlayer = false,
                lastSlotFingerprint = null,
                lastPlayerAtMillis = 0L,
                provisionalAds = 1,
            ),
            addedAds = 1,
            addedSeconds = seconds,
        )
    }
    if (explicit == null || previous.viewFromPlayer || explicit in previous.viewKeys) {
        return AdApplyResult(
            previous.copy(title = title, timestampMillis = maxOf(previous.timestampMillis, ping.atMillis)),
            addedAds = 0,
            addedSeconds = 0,
        )
    }
    val seconds = estimatedSecondsSaved(1, secondsPerAd)
    return AdApplyResult(
        previous.copy(
            title = title,
            adCount = previous.adCount + 1,
            estimatedSeconds = previous.estimatedSeconds + seconds,
            timestampMillis = ping.atMillis,
            viewKeys = rememberAdKey(previous.viewKeys, explicit),
            provisionalAds = previous.provisionalAds + 1,
        ),
        addedAds = 1,
        addedSeconds = seconds,
    )
}

/**
 * Count the slots in one player response. The same ids inside [SAME_VIEW_WINDOW_MILLIS] of the previous player
 * response for this video are the same view (the HTML embed plus the player API). A later response counts again,
 * which is how a revisit is recorded. Slot ids already counted for this view are not added twice.
 */
internal fun applyPlayerSlots(
    previous: VideoAdMemory?,
    videoId: String,
    title: String?,
    slotIds: List<String>,
    atMillis: Long,
    secondsPerAd: Int = AVERAGE_YOUTUBE_AD_SECONDS,
): AdApplyResult {
    val distinct = LinkedHashSet<String>()
    for (raw in slotIds) {
        val id = raw.trim()
        if (id.isEmpty() || id.length > 120) continue
        distinct.add(id)
    }
    val titleClean = cleanTitle(title) ?: previous?.title
    val fingerprint = distinct.sorted().joinToString("\n")
    val addedCount = distinct.size
    val addedSeconds = estimatedSecondsSaved(addedCount, secondsPerAd)
    if (previous == null) {
        return AdApplyResult(
            VideoAdMemory(
                videoId = videoId,
                title = titleClean,
                adCount = addedCount,
                estimatedSeconds = addedSeconds,
                timestampMillis = atMillis,
                viewKeys = distinct,
                viewFromPlayer = true,
                lastSlotFingerprint = fingerprint,
                lastPlayerAtMillis = atMillis,
                provisionalAds = 0,
            ),
            addedAds = addedCount,
            addedSeconds = addedSeconds,
        )
    }
    val sincePlayer = abs(atMillis - previous.lastPlayerAtMillis)
    if (previous.viewFromPlayer && sincePlayer < SAME_VIEW_WINDOW_MILLIS) {
        val novel = distinct.filter { it !in previous.viewKeys }
        if (novel.isEmpty()) {
            return AdApplyResult(
                previous.copy(
                    title = titleClean,
                    timestampMillis = maxOf(previous.timestampMillis, atMillis),
                    lastPlayerAtMillis = maxOf(previous.lastPlayerAtMillis, atMillis),
                    lastSlotFingerprint = fingerprint.ifEmpty { previous.lastSlotFingerprint },
                ),
                addedAds = 0,
                addedSeconds = 0,
            )
        }
        val seconds = estimatedSecondsSaved(novel.size, secondsPerAd)
        val keys = LinkedHashSet(previous.viewKeys)
        keys.addAll(novel)
        return AdApplyResult(
            previous.copy(
                title = titleClean,
                adCount = previous.adCount + novel.size,
                estimatedSeconds = previous.estimatedSeconds + seconds,
                timestampMillis = maxOf(previous.timestampMillis, atMillis),
                viewKeys = keys,
                lastSlotFingerprint = keys.sorted().joinToString("\n"),
                lastPlayerAtMillis = maxOf(previous.lastPlayerAtMillis, atMillis),
            ),
            addedAds = novel.size,
            addedSeconds = seconds,
        )
    }
    val sincePing = abs(atMillis - previous.timestampMillis)
    if (!previous.viewFromPlayer && previous.provisionalAds > 0 && sincePing < SAME_VIEW_WINDOW_MILLIS) {
        val removed = previous.provisionalAds.coerceAtMost(previous.adCount)
        val removedSeconds = estimatedSecondsSaved(removed, secondsPerAd).coerceAtMost(previous.estimatedSeconds)
        return AdApplyResult(
            previous.copy(
                title = titleClean,
                adCount = previous.adCount - removed + addedCount,
                estimatedSeconds = previous.estimatedSeconds - removedSeconds + addedSeconds,
                timestampMillis = atMillis,
                viewKeys = distinct,
                viewFromPlayer = true,
                lastSlotFingerprint = fingerprint,
                lastPlayerAtMillis = atMillis,
                provisionalAds = 0,
            ),
            addedAds = addedCount - removed,
            addedSeconds = addedSeconds - removedSeconds,
        )
    }
    return AdApplyResult(
        previous.copy(
            title = titleClean,
            adCount = previous.adCount + addedCount,
            estimatedSeconds = previous.estimatedSeconds + addedSeconds,
            timestampMillis = atMillis,
            viewKeys = distinct,
            viewFromPlayer = true,
            lastSlotFingerprint = fingerprint,
            lastPlayerAtMillis = atMillis,
            provisionalAds = 0,
        ),
        addedAds = addedCount,
        addedSeconds = addedSeconds,
    )
}

internal fun cleanTitle(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.take(TITLE_MAX_CHARS)

/** A message from the built-in observer. Incognito messages are dropped here as well as in the script. */
internal fun parseYoutubeAdEvent(message: JSONObject): YoutubeAdEvent? {
    if (message.optBoolean("incognito", false)) return null
    return when (message.optString("type")) {
        "blocked-ad" -> {
            val url = message.optString("url")
            if (url.isBlank()) return null
            YoutubeAdEvent.Blocked(
                YoutubeAdObservation(
                    pageUrl = message.optString("documentUrl").takeIf { it.isNotBlank() },
                    pageTitle = message.optString("title").takeIf { it.isNotBlank() },
                    requestUrl = url,
                    isPrivate = false,
                    error = message.optString("error"),
                    atMillis = message.optLong("at", 0L),
                ),
            )
        }
        "player-ads" -> {
            val videoId = message.optString("videoId")
            if (!isVideoId(videoId)) return null
            val slots = message.optJSONArray("slots") ?: JSONArray()
            val ids = ArrayList<String>(slots.length())
            val seen = HashSet<String>()
            for (i in 0 until slots.length()) {
                val id = slots.optString(i).trim()
                if (id.isEmpty() || id.length > 120 || !seen.add(id)) continue
                ids.add(id)
            }
            YoutubeAdEvent.PlayerAds(
                videoId = videoId,
                title = message.optString("title").takeIf { it.isNotBlank() },
                pageUrl = message.optString("pageUrl").takeIf { it.isNotBlank() },
                slotIds = ids,
                isPrivate = false,
                atMillis = message.optLong("at", 0L),
            )
        }
        "title" -> {
            val url = message.optString("url")
            val title = message.optString("title")
            if (url.isBlank() || title.isBlank()) null else YoutubeAdEvent.Title(url, title, isPrivate = false)
        }
        else -> null
    }
}

private fun emptyMemory(videoId: String, title: String?, atMillis: Long) =
    VideoAdMemory(
        videoId = videoId,
        title = title,
        adCount = 0,
        estimatedSeconds = 0,
        timestampMillis = atMillis,
        viewKeys = emptySet(),
        viewFromPlayer = false,
        lastSlotFingerprint = null,
        lastPlayerAtMillis = 0L,
        provisionalAds = 0,
    )

private fun rememberAdKey(existing: Set<String>, key: String): Set<String> {
    val ordered = LinkedHashSet(existing)
    ordered.remove(key)
    ordered.add(key)
    while (ordered.size > MAX_AD_KEYS) ordered.remove(ordered.first())
    return ordered
}

private fun hostOf(raw: String): String? =
    runCatching { URI(raw.trim()).host }.getOrNull()?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() }

private fun pathOf(raw: String): String = runCatching { URI(raw.trim()).path }.getOrNull().orEmpty()

private fun queryMap(raw: String): Map<String, String> {
    val query = runCatching { URI(raw.trim()).rawQuery }.getOrNull() ?: return emptyMap()
    if (query.isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (part in query.split('&')) {
        if (part.isEmpty()) continue
        val key = urlDecode(part.substringBefore('=')).lowercase()
        val value = urlDecode(part.substringAfter('=', ""))
        if (key.isNotEmpty() && key !in out) out[key] = value
    }
    return out
}

/** [URLDecoder.decode] with a [Charset] is API 33. minSdk is 29, so the charset-name overload is the one that runs. */
@Suppress("DEPRECATION")
private fun urlDecode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
