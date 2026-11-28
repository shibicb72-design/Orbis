package com.shibi.orbis.diagnostics

import com.shibi.orbis.stats.BlockKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The words of the log's lines. Pure. Nothing here takes an address, a title or any page text: every value is a number,
 * a yes/no or one of a fixed set of words.
 */

private val TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.ROOT)
private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

fun diagTime(millis: Long, zone: ZoneId): String = TIME.format(Instant.ofEpochMilli(millis).atZone(zone))

fun diagDateTime(millis: Long, zone: ZoneId): String = DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(zone))

/** One decimal, with a dot whatever the phone's language. */
fun oneDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

/** "video 3", "audio 1"; "media 2" when the script never said which. */
fun mediaName(video: Boolean?, number: Int): String =
    when (video) {
        true -> "video $number"
        false -> "audio $number"
        null -> "media $number"
    }

fun formatMedia(media: DiagMessage.Media, name: String, first: Boolean): String {
    val site = if (media.sameSite) "same site" else "other site"
    val frame = if (media.inFrame) ", in a frame" else ""
    return if (first) "$name appeared: ${media.source.label}, $site$frame" else "$name source now: ${media.source.label}, $site$frame"
}

fun formatEvent(event: DiagMessage.Event, name: String): String {
    val what =
        when (event.event) {
            MediaEvent.LOADSTART -> if (event.again) "loadstart (reloaded, same source)" else "loadstart"
            MediaEvent.ERROR -> "error code ${event.errorCode}"
            MediaEvent.RESIZE -> "resize to ${event.width}x${event.height}"
            MediaEvent.FOUND -> "found already on the page"
            else -> event.event.wire
        }
    val duration = if (event.finite) "finite" else "not finite"
    return "$name $what at ${oneDecimal(event.sinceAppeared)} s: ready ${event.readyState}, network ${event.networkState}, " +
        "position ${oneDecimal(event.position)}, buffered ahead ${oneDecimal(event.ahead)} s, duration $duration"
}

fun formatStallEnd(name: String, seconds: Double, endedBy: MediaEvent): String =
    "$name stall ended after ${oneDecimal(seconds)} s (${endedBy.wire})"

fun formatSample(sample: DiagMessage.Sample, name: String, video: Boolean?): String {
    val base = "$name sample: buffered ahead ${oneDecimal(sample.ahead)} s"
    if (video == false) return base
    return "$base, picture ${sample.width}x${sample.height}, frames ${sample.frames}, dropped ${sample.dropped}"
}

private fun canPlay(answer: String) = if (answer.isEmpty()) "\"\" (no)" else "\"$answer\""

fun formatHls(hls: DiagMessage.Hls): String =
    "HLS support (canPlayType): application/vnd.apple.mpegurl ${canPlay(hls.mpegurl)}, application/x-mpegURL ${canPlay(hls.xMpegurl)}"

fun formatPage(page: DiagMessage.Page): String = "page ${page.what.name.lowercase()}"

private val UBO_BADGE = Regex("^[0-9]{1,6}$")

/** uBlock Origin's badge as shown, only when it is 1 to 6 digits; otherwise null. */
fun uboBadgeOrNull(badge: String?): String? = badge?.takeIf { UBO_BADGE.matches(it) }

/** "blocked on this page: tracker 12, ad 3; uBlock Origin badge 45", or null when there is nothing to say. */
fun formatBlocking(blocked: Map<BlockKind, Int>, uboBadge: String?): String? {
    val parts = ArrayList<String>()
    val counts = BlockKind.entries.mapNotNull { kind -> blocked[kind]?.takeIf { it > 0 }?.let { "${kind.name.lowercase()} $it" } }
    if (counts.isNotEmpty()) parts += "blocked on this page: " + counts.joinToString(", ")
    uboBadgeOrNull(uboBadge)?.let { parts += "uBlock Origin badge $it" }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
}

fun onOff(on: Boolean): String = if (on) "on" else "off"
