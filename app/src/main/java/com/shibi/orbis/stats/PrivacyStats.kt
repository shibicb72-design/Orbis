package com.shibi.orbis.stats

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** What the engine blocked, by the kind of thing it was. */
enum class BlockKind { AD, TRACKER, SOCIAL, FINGERPRINTER, CRYPTOMINER, COOKIE }

/** One day's counts. [day] is the local date as yyyy-MM-dd. Bytes are what Orbis sent and received over the network. */
data class DayStats(
    val day: String,
    val blocked: Map<BlockKind, Long> = emptyMap(),
    val bytesUsed: Long = 0L,
) {
    val totalBlocked: Long get() = blocked.values.sum()
}

/** A period's numbers for the dashboard. */
data class StatsSummary(
    val days: List<DayStats>,
    val byKind: Map<BlockKind, Long>,
    val totalBlocked: Long,
    val bytesUsed: Long,
    val estimatedBytesSaved: Long,
    val estimatedMillisSaved: Long,
    val topTrackers: List<Pair<String, Long>>,
    val since: String?,
)

/**
 * Average cost of one blocked request, used for the "saved" estimates (labelled as estimates in the UI): ads are larger
 * (scripts, images, video), trackers and cookies small. Time is the load time a request would have added; 50 ms per
 * blocked request is the figure Brave uses for its "time saved".
 */
internal fun estimatedBytes(kind: BlockKind): Long =
    when (kind) {
        BlockKind.AD -> 45_000L
        BlockKind.TRACKER, BlockKind.SOCIAL, BlockKind.FINGERPRINTER, BlockKind.CRYPTOMINER -> 12_000L
        BlockKind.COOKIE -> 0L
    }

internal const val MILLIS_SAVED_PER_REQUEST = 50L

/** Days kept on disk. */
internal const val STATS_DAYS_KEPT = 90

/** Tracker host names kept for "Top trackers blocked". */
internal const val TOP_TRACKERS_KEPT = 50

/** Sums [days] (already the period wanted) into a summary. Pure. */
internal fun summarize(days: List<DayStats>, trackerHosts: Map<String, Long>, since: String?): StatsSummary {
    val byKind = BlockKind.entries.associateWith { k -> days.sumOf { it.blocked[k] ?: 0L } }
    val requestsBlocked = byKind.filterKeys { it != BlockKind.COOKIE }.values.sum()
    return StatsSummary(
        days = days,
        byKind = byKind,
        totalBlocked = byKind.values.sum(),
        bytesUsed = days.sumOf { it.bytesUsed },
        estimatedBytesSaved = byKind.entries.sumOf { (k, n) -> estimatedBytes(k) * n },
        estimatedMillisSaved = requestsBlocked * MILLIS_SAVED_PER_REQUEST,
        topTrackers = trackerHosts.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
        since = since,
    )
}

/** The last [count] days ending at [today], oldest first, filling days with no activity. Pure. */
internal fun lastDays(stored: Map<String, DayStats>, today: LocalDate, count: Int): List<DayStats> =
    (count - 1 downTo 0).map { back ->
        val d = today.minusDays(back.toLong()).toString()
        stored[d] ?: DayStats(d)
    }

/** The network bytes added since the previous reading; a counter that went down (reboot) counts from zero. Pure. */
internal fun trafficDelta(previous: Long?, current: Long): Long =
    when {
        current < 0 -> 0L
        previous == null -> 0L
        current >= previous -> current - previous
        else -> current
    }

/** Shortest form of a tracker host for display and grouping: "www."/"ssl." prefixes dropped. */
internal fun trackerHostOf(uri: String): String? {
    val afterScheme = uri.substringAfter("://", "")
    if (afterScheme.isEmpty()) return null
    val host = afterScheme.substringBefore('/').substringBefore('?').substringAfterLast('@').substringBefore(':').lowercase()
    if (host.isEmpty() || host.length > 120) return null
    return host.removePrefix("www.").removePrefix("ssl.")
}

/**
 * Counts what Orbis blocked and the data it used, per day, on this phone only (never sent anywhere). Keeps only counts
 * and the host names of blocked trackers; never the sites the user visited. Private tabs add to the counts but not to
 * the tracker host list. Main thread; the file is written off it, at most every few seconds.
 */
class PrivacyStats(
    private val file: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val today: () -> LocalDate = { LocalDate.now(ZoneId.systemDefault()) },
    private val log: (String) -> Unit = {},
) {
    private val days = LinkedHashMap<String, DayStats>()
    private val trackers = HashMap<String, Long>()
    private var since: String? = null
    private var lastTraffic: Long? = null
    private var loaded = false
    private var saveJob: Job? = null

    private val _version = MutableStateFlow(0)

    /** Bumped on every change; the dashboard re-reads [summary] when it moves. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun load() {
        scope.launch {
            val text = withContext(io) { runCatching { if (file.isFile) file.readText() else null }.getOrNull() }
            if (text != null) {
                runCatching { decode(text) }.onFailure { log("privacy stats unreadable: ${it.javaClass.simpleName}") }
            }
            loaded = true
            _version.value++
        }
    }

    /** One blocked request (or cookie). [trackerUri] is the blocked address; [isPrivate] keeps it out of the host list. */
    fun recordBlocked(kind: BlockKind, trackerUri: String?, isPrivate: Boolean) {
        val d = today().toString()
        val cur = days[d] ?: DayStats(d)
        days[d] = cur.copy(blocked = cur.blocked + (kind to ((cur.blocked[kind] ?: 0L) + 1)))
        if (since == null) since = d
        if (!isPrivate && kind != BlockKind.COOKIE) {
            trackerUri?.let(::trackerHostOf)?.let { h -> trackers[h] = (trackers[h] ?: 0L) + 1 }
        }
        changed()
    }

    /** Adds the app's network use since the last reading ([totalBytes]: the system counter for this app). */
    fun recordTraffic(totalBytes: Long) {
        val delta = trafficDelta(lastTraffic, totalBytes)
        lastTraffic = totalBytes.takeIf { it >= 0 }
        if (delta <= 0) return
        val d = today().toString()
        val cur = days[d] ?: DayStats(d)
        days[d] = cur.copy(bytesUsed = cur.bytesUsed + delta)
        if (since == null) since = d
        changed()
    }

    /** The last [count] days (7 for the week), or every kept day when [count] is null. */
    fun summary(count: Int?): StatsSummary {
        val list = if (count == null) days.values.sortedBy { it.day } else lastDays(days, today(), count)
        return summarize(list, trackers, since)
    }

    /** Forgets everything counted so far. */
    fun reset() {
        days.clear()
        trackers.clear()
        since = null
        changed()
    }

    private fun changed() {
        trim()
        _version.value++
        if (!loaded) return
        if (saveJob?.isActive == true) return
        saveJob =
            scope.launch {
                delay(3_000)
                val text = encode()
                withContext(io) {
                    runCatching {
                        val tmp = File(file.parentFile, file.name + ".tmp")
                        tmp.writeText(text)
                        if (!tmp.renameTo(file)) {
                            file.delete()
                            tmp.renameTo(file)
                        }
                    }.onFailure { log("privacy stats save failed: ${it.javaClass.simpleName}") }
                }
            }
    }

    private fun trim() {
        val cutoff = today().minusDays((STATS_DAYS_KEPT - 1).toLong()).toString()
        days.keys.filter { it < cutoff }.forEach { days.remove(it) }
        if (trackers.size > TOP_TRACKERS_KEPT * 2) {
            val keep = trackers.entries.sortedByDescending { it.value }.take(TOP_TRACKERS_KEPT).map { it.key }.toSet()
            trackers.keys.retainAll(keep)
        }
    }

    internal fun encode(): String {
        val d = JSONArray()
        days.values.sortedBy { it.day }.forEach { s ->
            val b = JSONObject()
            s.blocked.forEach { (k, n) -> b.put(k.name, n) }
            d.put(JSONObject().put("day", s.day).put("blocked", b).put("bytes", s.bytesUsed))
        }
        val t = JSONObject()
        trackers.entries.sortedByDescending { it.value }.take(TOP_TRACKERS_KEPT).forEach { (h, n) -> t.put(h, n) }
        return JSONObject().put("version", 1).put("since", since ?: JSONObject.NULL).put("days", d).put("trackers", t).toString()
    }

    /** Merges a stored file under what was counted since start (counted before the load finished is kept). */
    internal fun decode(text: String) {
        val o = JSONObject(text)
        val d = o.optJSONArray("days") ?: JSONArray()
        for (i in 0 until d.length()) {
            val e = d.optJSONObject(i) ?: continue
            val day = e.optString("day").takeIf { runCatching { LocalDate.parse(it) }.isSuccess } ?: continue
            val b = e.optJSONObject("blocked") ?: JSONObject()
            val stored = BlockKind.entries.associateWith { b.optLong(it.name, 0L).coerceAtLeast(0L) }.filterValues { it > 0 }
            val cur = days[day] ?: DayStats(day)
            days[day] =
                DayStats(
                    day,
                    BlockKind.entries.associateWith { (stored[it] ?: 0L) + (cur.blocked[it] ?: 0L) }.filterValues { it > 0 },
                    e.optLong("bytes", 0L).coerceAtLeast(0L) + cur.bytesUsed,
                )
        }
        val t = o.optJSONObject("trackers")
        t?.keys()?.forEach { h -> trackers[h] = (trackers[h] ?: 0L) + t.optLong(h, 0L).coerceAtLeast(0L) }
        val storedSince = o.optString("since").takeIf { it.isNotEmpty() && it != "null" }
        since = listOfNotNull(storedSince, since).minOrNull()
        trim()
    }

    companion object {
        /** Today in this phone's time zone, for a timestamp; used by tests. */
        internal fun dayOf(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }
}
