package com.shibi.orbis.cast

import java.net.URI
import java.net.URLDecoder
import org.json.JSONArray
import org.json.JSONObject

/** What the user can send to a TV or speaker from the current page. */
internal sealed class CastTarget {
    data class Direct(val media: CastableMedia) : CastTarget()
    data class YouTube(val video: YouTubeVideo) : CastTarget()
    data object None : CastTarget()
}

/** A file the Default Media Receiver or a DLNA renderer can play itself. */
internal data class CastableMedia(
    val url: String,
    val mime: String,
    val video: Boolean,
    val title: String,
    val poster: String?,
    val positionSec: Double,
    val durationSec: Double,
)

/** A YouTube watch page. [startSec] is the later of the URL timestamp and the player position. */
internal data class YouTubeVideo(
    val videoId: String,
    val watchUrl: String,
    val startSec: Double,
    val title: String,
)

private const val MAX_URL_CHARS = 4096
private const val MAX_TITLE_CHARS = 300
private const val MAX_PROBE_CHARS = 100_000
private const val MAX_START_SEC = 100.0 * 60 * 60

private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "www.youtu.be")
private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")
private val YOUTUBE_CLOCK = Regex("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$", RegexOption.IGNORE_CASE)

private val EXTENSION_MIME =
    mapOf(
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "webm" to "video/webm",
        "m3u8" to "application/vnd.apple.mpegurl",
        "mpd" to "application/dash+xml",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/ogg",
        "wav" to "audio/wav",
        "flac" to "audio/flac",
    )

/** MIME types the Default Media Receiver and a typical DLNA renderer can open from a URL. */
private val CASTABLE_MIME =
    setOf(
        "video/mp4",
        "video/webm",
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "application/vnd.mpeg.dash.mpd",
        "application/dash+xml",
        "audio/mpeg",
        "audio/mp3",
        "audio/mp4",
        "audio/webm",
        "audio/aac",
        "audio/ogg",
        "audio/wav",
        "audio/wave",
        "audio/x-wav",
        "audio/flac",
        "audio/x-flac",
    )

/** YouTube receiver (the TV's YouTube app). Local Cast channel only; the phone does not call YouTube's lounge API. */
internal const val YOUTUBE_RECEIVER_APP_ID = "233637DE"
internal const val YOUTUBE_NAMESPACE = "urn:x-cast:com.google.youtube.mdx"

internal fun youtubeFlingPayload(videoId: String, startSec: Double): String =
    JSONObject()
        .put("type", "flingVideo")
        .put(
            "data",
            JSONObject()
                .put("videoId", videoId)
                .put("currentTime", startSec.coerceIn(0.0, MAX_START_SEC))
                .put("listId", ""),
        )
        .toString()

/** Body of a local DIAL launch (`POST /apps/YouTube` on the device). */
internal fun youtubeDialBody(videoId: String, startSec: Double): String {
    val seconds = startSec.toLong().coerceIn(0L, MAX_START_SEC.toLong())
    return "v=$videoId&t=$seconds"
}

/**
 * Decides what, if anything, can be cast. A YouTube watch page wins over a blob player on that page. Anywhere else,
 * only a direct http(s) media address (mp4, webm, HLS, DASH, or a common audio type) is castable.
 * [probeJson] is the page script's result, or null when the page could not be read.
 */
internal fun classifyPageMedia(pageUrl: String, probeJson: String?): CastTarget {
    val probe = parseProbe(probeJson)
    val page = probe?.href?.takeIf { it.isNotEmpty() } ?: pageUrl
    val youtube = parseYouTubeVideo(page)
    if (youtube != null) {
        val played = probe?.elements?.filter { it.tag == "video" }?.maxOfOrNull { it.currentTimeSec } ?: 0.0
        val start = maxOf(youtube.startSec, played.coerceIn(0.0, MAX_START_SEC))
        val title = cleanTitle(probe?.title?.ifBlank { youtube.title } ?: youtube.title)
        return CastTarget.YouTube(youtube.copy(startSec = start, title = title))
    }
    val elements = probe?.elements.orEmpty()
    val ranked =
        elements.mapNotNull { element ->
            val source = element.sources.firstNotNullOfOrNull { classifyMediaUrl(it.url, it.mime) } ?: return@mapNotNull null
            element to source
        }.sortedWith(
            compareByDescending<Pair<ProbeElement, ClassifiedUrl>> { it.first.tag == "video" }
                .thenByDescending { !it.first.paused }
                .thenByDescending { it.first.currentTimeSec > 0.5 }
                .thenByDescending { it.first.tag == "video" && !it.first.paused },
        )
    val (element, source) = ranked.firstOrNull() ?: return CastTarget.None
    val title = cleanTitle(element.title.ifBlank { probe?.title.orEmpty() }.ifBlank { fileTitle(source.url) })
    val poster = httpUrl(element.poster) ?: httpUrl(probe?.poster)
    val video = if (element.tag == "audio") false else source.video
    return CastTarget.Direct(
        CastableMedia(
            url = source.url,
            mime = source.mime,
            video = video,
            title = title.ifBlank { "Media" },
            poster = poster,
            positionSec = element.currentTimeSec.coerceIn(0.0, MAX_START_SEC),
            durationSec = element.durationSec.coerceIn(0.0, MAX_START_SEC),
        ),
    )
}

/** A direct media URL, or null for blob, MediaSource, data, or anything that is not a known media type. */
internal fun classifyMediaUrl(url: String, mimeHint: String): ClassifiedUrl? {
    val trimmed = url.trim()
    if (trimmed.length !in 1..MAX_URL_CHARS) return null
    if (trimmed.any { it.isWhitespace() || it.code < 0x20 }) return null
    val lower = trimmed.lowercase()
    if (lower.startsWith("blob:") || lower.startsWith("mediasource:") || lower.startsWith("data:") ||
        lower.startsWith("javascript:") || lower.startsWith("about:") || lower.startsWith("file:")
    ) {
        return null
    }
    val uri =
        try {
            URI(trimmed)
        } catch (_: Exception) {
            return null
        }
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    if (uri.host.isNullOrBlank()) return null
    val mime = canonicalMime(mimeHint) ?: canonicalMime(EXTENSION_MIME[extensionOf(uri)].orEmpty())
    if (mime == null || mime !in CASTABLE_MIME) return null
    val video = !mime.startsWith("audio/")
    return ClassifiedUrl(trimmed, mime, video)
}

internal data class ClassifiedUrl(val url: String, val mime: String, val video: Boolean)

/** Video id and the timestamp written in the URL (`t`, `start`, or a `#t=` fragment). Null if this is not YouTube. */
internal fun parseYouTubeVideo(url: String): YouTubeVideo? {
    val trimmed = url.trim()
    if (trimmed.length !in 1..MAX_URL_CHARS) return null
    val uri =
        try {
            URI(trimmed)
        } catch (_: Exception) {
            return null
        }
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.lowercase() ?: return null
    if (host !in YOUTUBE_HOSTS) return null
    val query = queryParams(uri.rawQuery)
    val fragment = queryParams(uri.rawFragment)
    val path = uri.path.orEmpty().trim('/')
    val segments = path.split('/').filter { it.isNotEmpty() }
    val id =
        when {
            host == "youtu.be" || host == "www.youtu.be" -> segments.firstOrNull()
            segments.firstOrNull() == "watch" -> query["v"]
            segments.firstOrNull() in setOf("embed", "shorts", "live", "v", "e") -> segments.getOrNull(1)
            else -> query["v"]
        }?.substringBefore('?')?.substringBefore('&')
    if (id == null || !YOUTUBE_ID.matches(id)) return null
    val start =
        parseYouTubeTime(query["t"])
            ?: parseYouTubeTime(query["start"])
            ?: parseYouTubeTime(query["time_continue"])
            ?: parseYouTubeTime(fragment["t"])
            ?: parseYouTubeTime(fragment["start"])
            ?: 0.0
    val watch = "https://www.youtube.com/watch?v=$id"
    return YouTubeVideo(videoId = id, watchUrl = watch, startSec = start, title = "")
}

/** `90`, `90s`, `1m30s`, `1h2m3s`, `1:02`, `1:02:03`. */
internal fun parseYouTubeTime(value: String?): Double? {
    if (value.isNullOrBlank()) return null
    val v = value.trim()
    if (v.isEmpty() || v.length > 32) return null
    v.toDoubleOrNull()?.let { seconds ->
        if (seconds in 0.0..MAX_START_SEC) return seconds
        return null
    }
    if (':' in v) {
        if (!v.all { it.isDigit() || it == ':' }) return null
        val parts = v.split(':')
        if (parts.size !in 2..3 || parts.any { it.isEmpty() }) return null
        val nums = parts.map { it.toLongOrNull() ?: return null }
        if (nums.any { it < 0 }) return null
        val seconds =
            when (nums.size) {
                2 -> nums[0] * 60 + nums[1]
                else -> nums[0] * 3600 + nums[1] * 60 + nums[2]
            }
        return seconds.toDouble().takeIf { it in 0.0..MAX_START_SEC }
    }
    val match = YOUTUBE_CLOCK.matchEntire(v) ?: return null
    if (match.groupValues.drop(1).all { it.isEmpty() }) return null
    val hours = match.groupValues[1].toLongOrNull() ?: 0L
    val minutes = match.groupValues[2].toLongOrNull() ?: 0L
    val seconds = match.groupValues[3].toLongOrNull() ?: 0L
    val total = hours * 3600 + minutes * 60 + seconds
    return total.toDouble().takeIf { it in 0.0..MAX_START_SEC }
}

private data class Probe(val title: String, val poster: String?, val href: String, val elements: List<ProbeElement>)

private data class ProbeElement(
    val tag: String,
    val sources: List<SourceRef>,
    val currentTimeSec: Double,
    val durationSec: Double,
    val paused: Boolean,
    val poster: String?,
    val title: String,
)

private data class SourceRef(val url: String, val mime: String)

private fun parseProbe(json: String?): Probe? {
    if (json.isNullOrBlank() || json.length > MAX_PROBE_CHARS) return null
    val root =
        try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }
    val media = root.optJSONArray("media") ?: JSONArray()
    val elements = ArrayList<ProbeElement>(media.length().coerceAtMost(12))
    for (i in 0 until media.length().coerceAtMost(12)) {
        val item = media.optJSONObject(i) ?: continue
        val sourcesJson = item.optJSONArray("sources") ?: JSONArray()
        val sources = ArrayList<SourceRef>(sourcesJson.length().coerceAtMost(8))
        for (s in 0 until sourcesJson.length().coerceAtMost(8)) {
            val source = sourcesJson.optJSONObject(s) ?: continue
            val src = source.optString("src", "")
            if (src.isEmpty()) continue
            sources += SourceRef(src, source.optString("type", ""))
        }
        elements +=
            ProbeElement(
                tag = item.optString("tag", "").lowercase(),
                sources = sources,
                currentTimeSec = item.optDouble("currentTime", 0.0).takeIf { it.isFinite() } ?: 0.0,
                durationSec = item.optDouble("duration", 0.0).takeIf { it.isFinite() && it > 0 } ?: 0.0,
                paused = item.optBoolean("paused", true),
                poster = item.optString("poster", "").ifBlank { null },
                title = item.optString("title", ""),
            )
    }
    return Probe(
        title = root.optString("title", ""),
        poster = root.optString("poster", "").ifBlank { null },
        href = root.optString("href", ""),
        elements = elements,
    )
}

private fun canonicalMime(raw: String): String? {
    val base = raw.trim().substringBefore(';').trim().lowercase()
    if (base.isEmpty() || base !in CASTABLE_MIME) return null
    return when (base) {
        "audio/mp3" -> "audio/mpeg"
        "audio/wave", "audio/x-wav" -> "audio/wav"
        "audio/x-flac" -> "audio/flac"
        "application/x-mpegurl" -> "application/vnd.apple.mpegurl"
        "application/vnd.mpeg.dash.mpd" -> "application/dash+xml"
        else -> base
    }
}

private fun extensionOf(uri: URI): String {
    val path = uri.path ?: return ""
    val name = path.substringAfterLast('/')
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.lastIndex) return ""
    return name.substring(dot + 1).lowercase()
}

private fun queryParams(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (part in raw.split('&')) {
        if (part.isEmpty()) continue
        val eq = part.indexOf('=')
        val key = decode(if (eq >= 0) part.substring(0, eq) else part)
        val value = decode(if (eq >= 0) part.substring(eq + 1) else "")
        if (key.isNotEmpty() && key !in out) out[key] = value
    }
    return out
}

private fun decode(value: String): String =
    try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }

private fun httpUrl(value: String?): String? {
    val v = value?.trim().orEmpty()
    if (v.length !in 1..MAX_URL_CHARS) return null
    val lower = v.lowercase()
    if (!lower.startsWith("https://") && !lower.startsWith("http://")) return null
    return v
}

private fun cleanTitle(value: String): String {
    val trimmed = value.trim().replace(Regex("\\s+"), " ")
    val withoutBrand = trimmed.removeSuffix(" - YouTube").removeSuffix(" - YouTube Music").trim()
    return withoutBrand.take(MAX_TITLE_CHARS)
}

private fun fileTitle(url: String): String {
    val name =
        try {
            URI(url).path?.substringAfterLast('/').orEmpty()
        } catch (_: Exception) {
            ""
        }
    return name.substringBefore('?').take(MAX_TITLE_CHARS)
}
