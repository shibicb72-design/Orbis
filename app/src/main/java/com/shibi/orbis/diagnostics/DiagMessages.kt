package com.shibi.orbis.diagnostics

import org.json.JSONObject

/** The kind of a media element's source, as the script reports it. Never the address itself. */
enum class SourceKind(val wire: String, val label: String) {
    BLOB("blob", "script player (blob)"),
    HLS("hls", "playlist on the element (.m3u8)"),
    FILE("file", "plain file"),
    OTHER("other", "other"),
    NONE("none", "no source yet"),
}

/** The media events the script reports. [FOUND] is an element that was already there at the script's first look. */
enum class MediaEvent(val wire: String) {
    LOADSTART("loadstart"),
    LOADEDMETADATA("loadedmetadata"),
    PLAYING("playing"),
    WAITING("waiting"),
    STALLED("stalled"),
    PAUSE("pause"),
    ENDED("ended"),
    ERROR("error"),
    EMPTIED("emptied"),
    SEEKING("seeking"),
    RESIZE("resize"),
    FOUND("found"),
}

/** One message from the page's script, checked. The page is untrusted: anything not exactly this shape is dropped. */
sealed interface DiagMessage {
    data class Media(val id: Int, val video: Boolean, val source: SourceKind, val sameSite: Boolean, val inFrame: Boolean) : DiagMessage

    /** What `canPlayType` answers for the two HLS types: "", "maybe" or "probably". */
    data class Hls(val mpegurl: String, val xMpegurl: String) : DiagMessage

    data class Event(
        val id: Int,
        val event: MediaEvent,
        val sinceAppeared: Double,
        val readyState: Int,
        val networkState: Int,
        val position: Double,
        val ahead: Double,
        val finite: Boolean,
        /** Only for [MediaEvent.LOADSTART]: the same source started again after `emptied`. */
        val again: Boolean = false,
        /** Only for [MediaEvent.ERROR]: MediaError.code (0 when none). */
        val errorCode: Int = 0,
        /** Only for [MediaEvent.RESIZE]: the new picture size. */
        val width: Int = 0,
        val height: Int = 0,
    ) : DiagMessage

    data class Sample(val id: Int, val ahead: Double, val width: Int, val height: Int, val frames: Long, val dropped: Long) : DiagMessage

    enum class PageWhat { VISIBLE, HIDDEN, ONLINE, OFFLINE }

    data class Page(val what: PageWhat) : DiagMessage
}

private const val MAX_ID = 10_000
private const val MAX_SECONDS = 10_000_000.0
private const val MAX_PIXELS = 100_000
private const val MAX_FRAMES = 1_000_000_000_000L
private val CAN_PLAY = setOf("", "maybe", "probably")

private val EVENT_KEYS = setOf("type", "id", "name", "t", "ready", "network", "pos", "ahead", "finite")

/** Parses one script message; null for anything that is not exactly an expected shape (no extra or missing field). */
fun parseDiagMessage(obj: JSONObject): DiagMessage? =
    try {
        parseOrThrow(obj)
    } catch (e: Invalid) {
        null
    }

private class Invalid : Exception()

private fun parseOrThrow(obj: JSONObject): DiagMessage {
    val keys = obj.keys().asSequence().toSet()
    val type = obj.opt("type") as? String ?: throw Invalid()
    return when (type) {
        "media" -> {
            need(keys == setOf("type", "id", "kind", "source", "same", "frame"))
            val kind = str(obj, "kind")
            need(kind == "video" || kind == "audio")
            val source = SourceKind.entries.firstOrNull { it.wire == str(obj, "source") } ?: throw Invalid()
            DiagMessage.Media(id(obj), kind == "video", source, bool(obj, "same"), bool(obj, "frame"))
        }
        "hls" -> {
            need(keys == setOf("type", "mpegurl", "xmpegurl"))
            val a = str(obj, "mpegurl")
            val b = str(obj, "xmpegurl")
            need(a in CAN_PLAY && b in CAN_PLAY)
            DiagMessage.Hls(a, b)
        }
        "event" -> {
            val event = MediaEvent.entries.firstOrNull { it.wire == obj.opt("name") } ?: throw Invalid()
            val extra =
                when (event) {
                    MediaEvent.LOADSTART -> setOf("again")
                    MediaEvent.ERROR -> setOf("code")
                    MediaEvent.RESIZE -> setOf("w", "h")
                    else -> emptySet()
                }
            need(keys == EVENT_KEYS + extra)
            DiagMessage.Event(
                id = id(obj),
                event = event,
                sinceAppeared = num(obj, "t", MAX_SECONDS),
                readyState = int(obj, "ready", 4),
                networkState = int(obj, "network", 3),
                position = num(obj, "pos", MAX_SECONDS),
                ahead = num(obj, "ahead", MAX_SECONDS),
                finite = bool(obj, "finite"),
                again = if (event == MediaEvent.LOADSTART) bool(obj, "again") else false,
                errorCode = if (event == MediaEvent.ERROR) int(obj, "code", 4) else 0,
                width = if (event == MediaEvent.RESIZE) int(obj, "w", MAX_PIXELS) else 0,
                height = if (event == MediaEvent.RESIZE) int(obj, "h", MAX_PIXELS) else 0,
            )
        }
        "sample" -> {
            need(keys == setOf("type", "id", "ahead", "w", "h", "frames", "dropped"))
            DiagMessage.Sample(
                id = id(obj),
                ahead = num(obj, "ahead", MAX_SECONDS),
                width = int(obj, "w", MAX_PIXELS),
                height = int(obj, "h", MAX_PIXELS),
                frames = long(obj, "frames"),
                dropped = long(obj, "dropped"),
            )
        }
        "page" -> {
            need(keys == setOf("type", "what"))
            val what = DiagMessage.PageWhat.entries.firstOrNull { it.name.lowercase() == obj.opt("what") } ?: throw Invalid()
            DiagMessage.Page(what)
        }
        else -> throw Invalid()
    }
}

private fun need(ok: Boolean) {
    if (!ok) throw Invalid()
}

private fun str(obj: JSONObject, key: String): String = obj.opt(key) as? String ?: throw Invalid()

private fun bool(obj: JSONObject, key: String): Boolean = obj.opt(key) as? Boolean ?: throw Invalid()

private fun num(obj: JSONObject, key: String, max: Double): Double {
    val n = (obj.opt(key) as? Number)?.toDouble() ?: throw Invalid()
    need(n.isFinite() && n >= 0.0 && n <= max)
    return n
}

private fun long(obj: JSONObject, key: String): Long {
    val n = num(obj, key, MAX_FRAMES.toDouble())
    need(n == Math.floor(n))
    return n.toLong()
}

private fun int(obj: JSONObject, key: String, max: Int): Int {
    val n = num(obj, key, max.toDouble())
    need(n == Math.floor(n))
    return n.toInt()
}

private fun id(obj: JSONObject): Int {
    val n = int(obj, "id", MAX_ID)
    need(n >= 1)
    return n
}
