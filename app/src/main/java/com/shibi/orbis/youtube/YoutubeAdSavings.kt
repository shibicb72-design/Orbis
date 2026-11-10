package com.shibi.orbis.youtube

import com.shibi.orbis.storage.AtomicTextFile
import java.io.File
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

/** How many recent videos the dashboard lists. */
internal const val RECENT_VIDEOS_SHOWN = 8

/** Per-video rows kept on disk. Lifetime totals are kept after older rows are dropped. */
internal const val VIDEOS_KEPT = 100

private const val MAX_PENDING = 200
private const val TITLE_CACHE = 40
private const val SAVE_DELAY_MILLIS = 3_000L

/** One video, as the dashboard shows it. */
data class YoutubeVideoAd(
    val videoId: String,
    val title: String?,
    val adCount: Int,
    val estimatedSeconds: Long,
    val timestampMillis: Long,
)

/** Lifetime totals plus the newest videos. Totals can exceed the rows once old rows are dropped. */
data class YoutubeAdSummary(
    val totalAds: Long,
    val totalEstimatedSeconds: Long,
    val recent: List<YoutubeVideoAd>,
) {
    companion object {
        val EMPTY = YoutubeAdSummary(0, 0, emptyList())
    }
}

/**
 * On-device estimate of time saved by blocked YouTube ads. Main thread. Nothing is sent off the phone, and private
 * tabs are not recorded. Rows are video id, title when the page had one, ad count, estimated seconds, and the time of
 * the latest ping. Cleared with browsing history.
 *
 * [secondsPerAd] defaults to [AVERAGE_YOUTUBE_AD_SECONDS] (15). Repeat pings for the same ad do not add another ad.
 */
class YoutubeAdSavings(
    file: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val secondsPerAd: Int = AVERAGE_YOUTUBE_AD_SECONDS,
    private val log: (String) -> Unit = {},
) {
    private val store = AtomicTextFile(file)
    private val videos = LinkedHashMap<String, VideoAdMemory>()
    private val titles = LinkedHashMap<String, String>()
    private val pending = ArrayDeque<Pair<YoutubeAdEvent, Long>>()
    private var totalAds = 0L
    private var totalEstimatedSeconds = 0L
    private var loaded = false
    private var loadStarted = false
    private var discardLoad = false
    private var saveGen = 0
    private var saveJob: Job? = null
    private val writeLock = Any()

    private val _version = MutableStateFlow(0)

    /** Bumped on every change; the dashboard re-reads [summary] when it moves. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun load() {
        if (loaded || loadStarted) return
        loadStarted = true
        scope.launch {
            val text = withContext(io) { runCatching { store.read() }.getOrNull() }
            if (!discardLoad && text != null) {
                runCatching { decode(text) }.onFailure { log("youtube ad savings unreadable: ${it.javaClass.simpleName}") }
            }
            loaded = true
            if (discardLoad) {
                videos.clear()
                titles.clear()
                pending.clear()
                totalAds = 0
                totalEstimatedSeconds = 0
                flushNow()
            } else {
                val queued = pending.toList()
                pending.clear()
                queued.forEach { (event, now) -> apply(event, now) }
            }
            _version.value++
        }
    }

    /** A blocked ad request, or a watch-page title. Private events are ignored and not queued. */
    fun onEvent(event: YoutubeAdEvent, nowMillis: Long = System.currentTimeMillis()) {
        if (event.isPrivate) return
        if (!loaded) {
            if (pending.size < MAX_PENDING) pending.addLast(event to nowMillis)
            return
        }
        apply(event, nowMillis)
    }

    /** Forgets every video, the totals, and the file. Safe to call before [load] finishes. */
    fun clear() {
        videos.clear()
        titles.clear()
        pending.clear()
        totalAds = 0
        totalEstimatedSeconds = 0
        discardLoad = true
        _version.value++
        flushNow()
    }

    fun summary(): YoutubeAdSummary {
        val recent =
            videos.values
                .sortedWith(compareByDescending<VideoAdMemory> { it.timestampMillis }.thenBy { it.videoId })
                .take(RECENT_VIDEOS_SHOWN)
                .map { it.toPublic() }
        return YoutubeAdSummary(totalAds, totalEstimatedSeconds, recent)
    }

    private fun apply(event: YoutubeAdEvent, nowMillis: Long) {
        when (event) {
            is YoutubeAdEvent.Title -> noteTitle(event.pageUrl, event.title, nowMillis)
            is YoutubeAdEvent.Blocked -> applyBlocked(event.observation, nowMillis)
        }
    }

    private fun applyBlocked(observation: YoutubeAdObservation, nowMillis: Long) {
        val classified =
            classifyBlockedYoutubeAd(
                observation.pageUrl,
                observation.pageTitle ?: titles[resolveYoutubeVideoId(observation.pageUrl, observation.requestUrl).orEmpty()],
                observation.requestUrl,
                observation.error,
                observation.isPrivate,
            ) ?: return
        val at = observation.atMillis.takeIf { it > 0 } ?: nowMillis
        val title = classified.title ?: titles[classified.videoId]
        val result = applyAdPing(videos[classified.videoId], classified.copy(title = title, atMillis = at), secondsPerAd)
        videos[result.memory.videoId] = result.memory
        if (result.countedNewAd) {
            totalAds += 1
            totalEstimatedSeconds += estimatedSecondsSaved(1, secondsPerAd)
        }
        changed()
    }

    private fun noteTitle(pageUrl: String, title: String, nowMillis: Long) {
        val id = youtubeVideoId(pageUrl) ?: return
        val clean = cleanTitle(title) ?: return
        rememberTitle(id, clean)
        val current = videos[id] ?: return
        if (current.title == clean) return
        videos[id] = current.copy(title = clean, timestampMillis = maxOf(current.timestampMillis, nowMillis))
        changed()
    }

    private fun rememberTitle(id: String, title: String) {
        titles.remove(id)
        titles[id] = title
        while (titles.size > TITLE_CACHE) {
            val eldest = titles.keys.firstOrNull() ?: break
            titles.remove(eldest)
        }
    }

    private fun changed() {
        trim()
        _version.value++
        scheduleSave()
    }

    private fun trim() {
        while (videos.size > VIDEOS_KEPT) {
            val oldest = videos.values.minWithOrNull(compareBy<VideoAdMemory> { it.timestampMillis }.thenBy { it.videoId }) ?: break
            videos.remove(oldest.videoId)
        }
    }

    private fun scheduleSave() {
        if (!loaded) return
        if (saveJob?.isActive == true) return
        val gen = saveGen
        saveJob =
            scope.launch {
                delay(SAVE_DELAY_MILLIS)
                if (gen != saveGen) return@launch
                val text = encode()
                writeSnapshot(gen, text)
            }
    }

    private fun flushNow() {
        saveGen++
        saveJob?.cancel()
        saveJob = null
        val gen = saveGen
        val text = encode()
        scope.launch { writeSnapshot(gen, text) }
    }

    private suspend fun writeSnapshot(gen: Int, text: String) {
        withContext(io) {
            synchronized(writeLock) {
                if (gen != saveGen) return@synchronized
                runCatching { store.write(text) }.onFailure { log("youtube ad savings save failed: ${it.javaClass.simpleName}") }
            }
        }
    }

    internal fun encode(): String {
        val rows = JSONArray()
        videos.values.sortedBy { it.videoId }.forEach { video ->
            val keys = JSONArray()
            video.adKeys.forEach { keys.put(it) }
            rows.put(
                JSONObject()
                    .put("videoId", video.videoId)
                    .put("title", video.title ?: JSONObject.NULL)
                    .put("adCount", video.adCount)
                    .put("estimatedSeconds", video.estimatedSeconds)
                    .put("timestamp", video.timestampMillis)
                    .put("adKeys", keys)
                    .put("pendingBurst", video.pendingBurst)
                    .put("lastBreakAt", video.lastBreakAtMillis),
            )
        }
        return JSONObject()
            .put("version", 1)
            .put("totalAds", totalAds)
            .put("totalEstimatedSeconds", totalEstimatedSeconds)
            .put("videos", rows)
            .toString()
    }

    internal fun decode(text: String) {
        val root = JSONObject(text)
        val rows = root.optJSONArray("videos") ?: JSONArray()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optString("videoId")
            if (!isVideoId(id)) continue
            val count = row.optInt("adCount", 0).coerceAtLeast(0)
            if (count == 0) continue
            val keys = LinkedHashSet<String>()
            val storedKeys = row.optJSONArray("adKeys")
            if (storedKeys != null) {
                for (k in 0 until storedKeys.length()) {
                    val key = storedKeys.optString(k)
                    if (key.isNotBlank() && key.length <= 100) keys.add(key)
                }
            }
            val incoming =
                VideoAdMemory(
                    videoId = id,
                    title = if (row.isNull("title")) null else cleanTitle(row.optString("title")),
                    adCount = count,
                    estimatedSeconds = row.optLong("estimatedSeconds", 0L).coerceAtLeast(0L),
                    timestampMillis = row.optLong("timestamp", 0L).coerceAtLeast(0L),
                    adKeys = keys,
                    pendingBurst = row.optBoolean("pendingBurst", false),
                    lastBreakAtMillis = row.optLong("lastBreakAt", 0L).coerceAtLeast(0L),
                )
            val existing = videos[id]
            videos[id] =
                if (existing == null || incoming.adCount >= existing.adCount) incoming
                else existing.copy(adKeys = existing.adKeys + incoming.adKeys, title = existing.title ?: incoming.title)
        }
        val summedAds = videos.values.sumOf { it.adCount.toLong() }
        val summedSeconds = videos.values.sumOf { it.estimatedSeconds }
        totalAds = maxOf(root.optLong("totalAds", 0L).coerceAtLeast(0L), summedAds, totalAds)
        totalEstimatedSeconds = maxOf(root.optLong("totalEstimatedSeconds", 0L).coerceAtLeast(0L), summedSeconds, totalEstimatedSeconds)
        trim()
    }

    private fun VideoAdMemory.toPublic() =
        YoutubeVideoAd(videoId, title, adCount, estimatedSeconds, timestampMillis)
}
