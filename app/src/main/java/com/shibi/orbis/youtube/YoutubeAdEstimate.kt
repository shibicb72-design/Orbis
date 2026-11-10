package com.shibi.orbis.youtube

import java.net.URI
import java.net.URLDecoder
import org.json.JSONObject

/**
 * Average length of one blocked YouTube ad, in seconds.
 *
 * In-stream YouTube ads are often between about 6 and 30 seconds. Fifteen is the midpoint used for the estimate.
 * [estimatedSecondsSaved] takes this as its default and accepts another value, so the figure stays in one place.
 * The dashboard states the same number. This is an estimate, not a measurement of the ad that was blocked.
 */
const val AVERAGE_YOUTUBE_AD_SECONDS = 15

/**
 * Pings for one ad (a stats beacon, `/pagead/`, a doubleclick request) arrive together. A later ping at least this long
 * after the previous one, and with no ad id already seen for this video, is a new ad break (a mid-roll after a pre-roll).
 */
internal const val AD_BREAK_GAP_MILLIS = 20_000L

internal const val TITLE_MAX_CHARS = 120
internal const val MAX_AD_KEYS = 24

/** Error string recorded for a GeckoView content-blocking event. It counts as blocked. */
internal const val CONTENT_BLOCKING_ERROR = "NS_ERROR_BLOCKED_BY_CONTENT_BLOCKING"

private val AD_ID_KEYS = listOf("ad_id", "adid", "ad_v", "ad_docid", "ad_cpn", "ad_break_id", "break_id", "breakid")
private val VIDEO_ID_KEYS = listOf("docid", "video_id", "content_v")

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

    data class Title(val pageUrl: String, val title: String, override val isPrivate: Boolean) : YoutubeAdEvent
}

/** Saved shape of one video, including the ids used to collapse repeat pings. */
internal data class VideoAdMemory(
    val videoId: String,
    val title: String?,
    val adCount: Int,
    val estimatedSeconds: Long,
    val timestampMillis: Long,
    val adKeys: Set<String>,
    val pendingBurst: Boolean,
    val lastBreakAtMillis: Long,
)

internal data class AdPing(val videoId: String, val title: String?, val explicitAdId: String?, val atMillis: Long)

internal data class AdPingResult(val memory: VideoAdMemory, val countedNewAd: Boolean)

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
 * A YouTube ad request: `/pagead/`, `/api/stats/ads`, `get_midroll` or `ad_break` on a YouTube host, or a doubleclick /
 * googleads / googleadservices / googlesyndication host. Ordinary watch and videoplayback URLs are not ads.
 */
internal fun isYoutubeAdRequest(raw: String): Boolean {
    val host = hostOf(raw) ?: return false
    val path = pathOf(raw).lowercase()
    if ("/pagead/" in path || path.endsWith("/pagead")) return true
    if ("/api/stats/ads" in path) return true
    if (isYoutubePageHost(raw) && ("get_midroll" in path || "ad_break" in path)) return true
    if (host == "doubleclick.net" || host.endsWith(".doubleclick.net")) return true
    if ("googleads" in host) return true
    if (host == "googleadservices.com" || host.endsWith(".googleadservices.com")) return true
    if (host == "googlesyndication.com" || host.endsWith(".googlesyndication.com")) return true
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
 * The watch page's video id when [pageUrl] is a YouTube watch page. When the page URL is missing, a YouTube-hosted ad
 * request may name the video (`docid`, `video_id`, `content_v`). Other sites are ignored, even if an ad host is called.
 */
internal fun resolveYoutubeVideoId(pageUrl: String?, requestUrl: String): String? {
    if (!pageUrl.isNullOrBlank()) {
        if (!isYoutubePageHost(pageUrl)) return null
        return youtubeVideoId(pageUrl)
    }
    if (!isYoutubePageHost(requestUrl)) return null
    queryMap(requestUrl).let { q ->
        for (key in VIDEO_ID_KEYS) {
            q[key]?.takeIf(::isVideoId)?.let { return it }
        }
    }
    return null
}

/** Stable id of one ad creative, when the request carries one. Repeat pings share it. */
internal fun explicitAdId(raw: String): String? {
    val q = queryMap(raw)
    for (key in AD_ID_KEYS) {
        val value = q[key]?.trim().orEmpty()
        if (value.length in 1..80 && value != "0" && !value.equals("undefined", true) && !value.equals("null", true)) {
            return "$key:$value"
        }
    }
    return null
}

/**
 * Null unless this is a blocked ad on a YouTube watch page in a normal tab. Private tabs, network errors, ads that
 * loaded, and requests that are not YouTube ad endpoints are dropped.
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
    if (!isYoutubeAdRequest(requestUrl)) return null
    val videoId = resolveYoutubeVideoId(pageUrl, requestUrl) ?: return null
    return AdPing(videoId, cleanTitle(pageTitle), explicitAdId(requestUrl), atMillis = 0L)
}

/** Cheap gate so the engine does not report ordinary tracker blocks to the YouTube estimate. */
internal fun mightReportYoutubeAd(pageUrl: String?, requestUrl: String): Boolean {
    if (!isYoutubeAdRequest(requestUrl)) return false
    if (pageUrl.isNullOrBlank()) return isYoutubePageHost(requestUrl)
    return isYoutubePageHost(pageUrl)
}

/**
 * Collapse repeat pings. The same explicit ad id on a video counts once. Pings with no id inside [AD_BREAK_GAP_MILLIS]
 * of the previous ping are the same break. A different explicit id is another ad, even inside that window. An id that
 * arrives during an id-less burst is that burst, not a second ad.
 */
internal fun applyAdPing(previous: VideoAdMemory?, ping: AdPing, secondsPerAd: Int = AVERAGE_YOUTUBE_AD_SECONDS): AdPingResult {
    val title = cleanTitle(ping.title) ?: previous?.title
    val now = ping.atMillis
    val explicit = ping.explicitAdId?.takeIf { it.isNotBlank() }
    if (previous == null) {
        return AdPingResult(
            VideoAdMemory(
                videoId = ping.videoId,
                title = title,
                adCount = 1,
                estimatedSeconds = estimatedSecondsSaved(1, secondsPerAd),
                timestampMillis = now,
                adKeys = if (explicit != null) setOf(explicit) else emptySet(),
                pendingBurst = explicit == null,
                lastBreakAtMillis = now,
            ),
            countedNewAd = true,
        )
    }
    val within = withinGap(now, previous.lastBreakAtMillis)
    if (explicit != null && explicit in previous.adKeys) {
        return AdPingResult(
            previous.copy(title = title, timestampMillis = maxOf(previous.timestampMillis, now), lastBreakAtMillis = maxOf(previous.lastBreakAtMillis, now)),
            countedNewAd = false,
        )
    }
    if (explicit != null && within && previous.pendingBurst) {
        return AdPingResult(
            previous.copy(
                title = title,
                adKeys = rememberAdKey(previous.adKeys, explicit),
                pendingBurst = false,
                timestampMillis = maxOf(previous.timestampMillis, now),
                lastBreakAtMillis = maxOf(previous.lastBreakAtMillis, now),
            ),
            countedNewAd = false,
        )
    }
    if (explicit == null && within) {
        return AdPingResult(
            previous.copy(title = title, timestampMillis = maxOf(previous.timestampMillis, now), lastBreakAtMillis = maxOf(previous.lastBreakAtMillis, now)),
            countedNewAd = false,
        )
    }
    val count = previous.adCount + 1
    return AdPingResult(
        previous.copy(
            title = title,
            adCount = count,
            estimatedSeconds = previous.estimatedSeconds + estimatedSecondsSaved(1, secondsPerAd),
            timestampMillis = now,
            adKeys = if (explicit != null) rememberAdKey(previous.adKeys, explicit) else previous.adKeys,
            pendingBurst = explicit == null,
            lastBreakAtMillis = now,
        ),
        countedNewAd = true,
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
        "title" -> {
            val url = message.optString("url")
            val title = message.optString("title")
            if (url.isBlank() || title.isBlank()) null else YoutubeAdEvent.Title(url, title, isPrivate = false)
        }
        else -> null
    }
}

private fun withinGap(now: Long, last: Long): Boolean {
    val delta = if (now >= last) now - last else last - now
    return delta < AD_BREAK_GAP_MILLIS
}

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

private fun urlDecode(s: String): String = runCatching { URLDecoder.decode(s, Charsets.UTF_8) }.getOrDefault(s)
