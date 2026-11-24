package com.shibi.orbis.stats

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * One point of the memory history. Numbers only, by design: the time, the measured totals in kilobytes, and how many
 * normal tabs were open and awake. No title, address, host or tab id can be stored, and private tabs are not counted
 * (their memory is in the totals).
 */
data class MemorySample(
    val at: Long,
    val totalKb: Long,
    val appKb: Long,
    val webKb: Long,
    val otherKb: Long,
    val normalTabs: Int,
    val awakeNormalTabs: Int,
)

/** Days of samples kept. */
internal const val MEMORY_HISTORY_DAYS = 30

internal const val MEMORY_HISTORY_KEEP_MILLIS = MEMORY_HISTORY_DAYS * 24L * 60 * 60 * 1000

/** At most this many samples are kept (30 days of one every 15 minutes, with room for dashboard openings). */
internal const val MEMORY_HISTORY_MAX_SAMPLES = 6_000

/** The only keys a stored sample has; every value is a number. */
internal val MEMORY_SAMPLE_KEYS = setOf("t", "total", "app", "web", "other", "tabs", "awake")

internal fun encodeMemorySamples(samples: List<MemorySample>): String {
    val a = JSONArray()
    for (s in samples) {
        a.put(
            JSONObject()
                .put("t", s.at)
                .put("total", s.totalKb)
                .put("app", s.appKb)
                .put("web", s.webKb)
                .put("other", s.otherKb)
                .put("tabs", s.normalTabs)
                .put("awake", s.awakeNormalTabs),
        )
    }
    return JSONObject().put("version", 1).put("samples", a).toString()
}

/** Reads what [encodeMemorySamples] wrote; skips bad entries. Never throws. */
internal fun decodeMemorySamples(text: String?): List<MemorySample> {
    if (text.isNullOrBlank()) return emptyList()
    val a = runCatching { JSONObject(text).optJSONArray("samples") }.getOrNull() ?: return emptyList()
    val out = ArrayList<MemorySample>()
    for (i in 0 until a.length()) {
        val o = a.optJSONObject(i) ?: continue
        val at = o.optLong("t", -1L)
        val total = o.optLong("total", -1L)
        if (at <= 0 || total < 0) continue
        out +=
            MemorySample(
                at = at,
                totalKb = total,
                appKb = o.optLong("app", 0L).coerceAtLeast(0L),
                webKb = o.optLong("web", 0L).coerceAtLeast(0L),
                otherKb = o.optLong("other", 0L).coerceAtLeast(0L),
                normalTabs = o.optInt("tabs", 0).coerceAtLeast(0),
                awakeNormalTabs = o.optInt("awake", 0).coerceAtLeast(0),
            )
    }
    return out
}

/** [samples] newer than 30 days before [now] (and none from the future), oldest first, at most the cap. Pure. */
internal fun keptSamples(samples: List<MemorySample>, now: Long): List<MemorySample> =
    samples.filter { it.at > now - MEMORY_HISTORY_KEEP_MILLIS && it.at <= now + 60_000 }
        .sortedBy { it.at }
        .distinctBy { it.at }
        .takeLast(MEMORY_HISTORY_MAX_SAMPLES)

/**
 * The dashboard's memory history, in its own file beside the privacy statistics: on this phone only, not in backups
 * (Orbis's backups and Android's are off for it), not cleared by Clear browsing data or Clear on exit (it holds numbers,
 * nothing about what was browsed). Deleted by its own button and by the dashboard's Reset. Main thread; the file is
 * read and written off it, writes in order.
 */
class MemoryHistory(
    private val file: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private var samples: List<MemorySample> = emptyList()
    private var loaded = false
    private var clearedBeforeLoad = false
    private val writes = Channel<String?>(Channel.CONFLATED)
    private var writerStarted = false

    private val _version = MutableStateFlow(0)

    /** Bumped on every change; the chart re-reads [samples] when it moves. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun load() {
        scope.launch {
            val text = withContext(io) { runCatching { if (file.isFile) file.readText() else null }.getOrNull() }
            if (!clearedBeforeLoad) samples = keptSamples(decodeMemorySamples(text) + samples, clock())
            loaded = true
            _version.value++
            if (clearedBeforeLoad) save()
        }
    }

    /** The kept samples since [sinceMillis], oldest first. */
    fun samples(sinceMillis: Long = 0L): List<MemorySample> {
        val from = maxOf(sinceMillis, clock() - MEMORY_HISTORY_KEEP_MILLIS + 1)
        return samples.filter { it.at >= from }
    }

    /** The newest sample's time, or null. */
    val lastSampleAt: Long? get() = samples.lastOrNull()?.at

    fun add(sample: MemorySample) {
        samples = keptSamples(samples + sample, clock())
        _version.value++
        if (loaded) save()
    }

    /** Deletes the whole history, on disk too. */
    fun clear() {
        samples = emptyList()
        if (!loaded) clearedBeforeLoad = true
        _version.value++
        if (loaded) save()
    }

    private fun save() {
        if (!writerStarted) {
            writerStarted = true
            scope.launch(io) {
                for (text in writes) {
                    runCatching {
                        if (text == null) {
                            file.delete()
                        } else {
                            val tmp = File(file.parentFile, file.name + ".tmp")
                            tmp.writeText(text)
                            if (!tmp.renameTo(file)) {
                                file.delete()
                                tmp.renameTo(file)
                            }
                        }
                    }.onFailure { log("memory history save failed: ${it.javaClass.simpleName}") }
                }
            }
        }
        writes.trySend(if (samples.isEmpty()) null else encodeMemorySamples(samples))
    }
}
