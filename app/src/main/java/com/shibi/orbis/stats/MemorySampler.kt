package com.shibi.orbis.stats

import com.shibi.orbis.tabs.NormalTabCounts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A sample is stored every this often while Orbis is in front. */
internal const val MEMORY_SAMPLE_INTERVAL_MILLIS = 15 * 60 * 1000L

/** Opening the dashboard stores a sample only when the newest one is at least this old (Android's own limit). */
internal const val MEMORY_DASHBOARD_SAMPLE_GAP_MILLIS = SYSTEM_MEMORY_RATE_LIMIT_MILLIS

/** Opening the dashboard takes no new reading when one succeeded less than this long ago and no sample is due. */
internal const val MEMORY_DASHBOARD_READ_FLOOR_MILLIS = 30_000L

/** The sample for [reading] with the tab counts [tabs]. Numbers only. Pure. */
internal fun sampleOf(reading: MemoryReading, tabs: NormalTabCounts): MemorySample =
    MemorySample(
        at = reading.measuredAt,
        totalKb = reading.totalKb,
        appKb = reading.kb(ProcessKind.APP),
        webKb = reading.kb(ProcessKind.WEB),
        otherKb = reading.kb(ProcessKind.OTHER),
        normalTabs = tabs.open,
        awakeNormalTabs = tabs.awake,
    )

/**
 * Takes the memory readings: when the dashboard is opened (for the screen, and stored if the last stored sample is at
 * least [MEMORY_DASHBOARD_SAMPLE_GAP_MILLIS] old), and every [MEMORY_SAMPLE_INTERVAL_MILLIS] while Orbis is in front.
 * Never in the background: no alarm, no service, and a reading that finishes after Orbis left the front is not stored.
 * The reading runs on [io]; everything else on the main thread.
 */
class MemorySampler(
    private val read: () -> MemoryReading?,
    private val tabCounts: () -> NormalTabCounts,
    private val history: MemoryHistory,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    /** CPU time of the calling thread in milliseconds, for the cost line in the debug log. */
    private val threadCpuMillis: () -> Long = { 0L },
    private val log: (String) -> Unit = {},
) {
    private val _latest = MutableStateFlow<MemoryReading?>(null)

    /** The last reading, for the dashboard. Memory only. */
    val latest: StateFlow<MemoryReading?> = _latest.asStateFlow()

    private var foreground = false
    private var loop: Job? = null
    private var reading: Job? = null
    private var lastReadAt: Long? = null

    /** Orbis came to the front (true) or left it (false). The periodic sampling runs only in between. */
    fun onForeground(value: Boolean) {
        if (value == foreground) return
        foreground = value
        loop?.cancel()
        loop = null
        if (!value) return
        val since = clock()
        loop =
            scope.launch {
                var tried = since
                while (isActive) {
                    val now = clock()
                    // The first one 15 minutes after coming to the front; a failed reading also waits a full interval.
                    val due = maxOf(history.lastSampleAt ?: since, tried) + MEMORY_SAMPLE_INTERVAL_MILLIS
                    delay(maxOf(due - now, 1_000L))
                    if (!foreground) break
                    val last = history.lastSampleAt
                    if (last == null || clock() - last >= MEMORY_SAMPLE_INTERVAL_MILLIS) {
                        tried = clock()
                        measure(store = true)
                    }
                }
            }
    }

    /** The dashboard's memory section came on screen. */
    fun onDashboardOpened() {
        if (!foreground) return
        val last = history.lastSampleAt
        val store = last == null || clock() - last >= MEMORY_DASHBOARD_SAMPLE_GAP_MILLIS
        val age = lastReadAt?.let { clock() - it }
        if (!store && _latest.value != null && age != null && age in 0L until MEMORY_DASHBOARD_READ_FLOOR_MILLIS) return
        if (reading?.isActive == true) return
        reading = scope.launch { measure(store) }
    }

    private suspend fun measure(store: Boolean) {
        if (!foreground) return
        val result =
            withContext(io) {
                val cpu0 = threadCpuMillis()
                val wall0 = System.nanoTime()
                val r = runCatching(read).getOrNull()
                val cpu = threadCpuMillis() - cpu0
                val wall = (System.nanoTime() - wall0) / 1_000_000
                log("memory read cpuMs=$cpu wallMs=$wall processes=${r?.processes?.size ?: 0} limited=${r?.rateLimited}")
                r
            } ?: return
        _latest.value = result
        lastReadAt = clock()
        // Orbis may have left the front while the reading ran: then nothing is stored.
        if (store && foreground) history.add(sampleOf(result, tabCounts()))
    }
}
