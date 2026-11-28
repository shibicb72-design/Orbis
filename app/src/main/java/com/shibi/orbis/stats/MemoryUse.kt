package com.shibi.orbis.stats

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import java.io.File

/** The kind of an Orbis process, for the dashboard's split. Torrents run in the app's own process. */
enum class ProcessKind { APP, WEB, OTHER }

/** One of Orbis's own processes as Android lists it. */
data class RunningProcess(val pid: Int, val name: String)

/** One measured process: its kind and its proportional set size (PSS) in kilobytes. No pid, no name, no tab. */
data class ProcessMemory(val kind: ProcessKind, val pssKb: Long)

/**
 * Orbis's memory at one moment, as Android measured it. [measuredAt] is the time of the oldest value in it (a value
 * Android limits to one fresh reading every few minutes can be older than the call). [rateLimited] is true when any
 * value came from that limited call.
 */
data class MemoryReading(
    val measuredAt: Long,
    val processes: List<ProcessMemory>,
    val rateLimited: Boolean,
) {
    val totalKb: Long get() = processes.sumOf { it.pssKb }

    fun kb(kind: ProcessKind): Long = processes.filter { it.kind == kind }.sumOf { it.pssKb }

    /** The web-page processes' sizes in kilobytes, largest first. */
    val webProcessesKb: List<Long> get() = processes.filter { it.kind == ProcessKind.WEB }.map { it.pssKb }.sortedDescending()
}

/**
 * The engine names its web-content processes `<package>:sandboxed_tab_<suffix>` (and `isolatedTab` / `zygoteTab` when
 * those are used); everything else of the engine (`gpu`, `socket`, `utility`, `media`, ...) is "other". Pure.
 */
internal fun processKind(processName: String, packageName: String): ProcessKind {
    if (processName == packageName) return ProcessKind.APP
    val suffix = processName.removePrefix("$packageName:")
    return if (suffix.startsWith("sandboxed_tab") || suffix.startsWith("isolatedTab") || suffix.startsWith("zygoteTab")) {
        ProcessKind.WEB
    } else {
        ProcessKind.OTHER
    }
}

/** The `Pss:` line of `/proc/<pid>/smaps_rollup`, in kilobytes; null when the text has none. Pure. */
internal fun pssKbFromSmapsRollup(text: String): Long? =
    text.lineSequence()
        .firstOrNull { it.startsWith("Pss:") }
        ?.removePrefix("Pss:")
        ?.trim()
        ?.substringBefore(' ')
        ?.toLongOrNull()
        ?.takeIf { it > 0 }

/** Android returns the same value again when [ActivityManager.getProcessMemoryInfo] is asked sooner than this. */
internal const val SYSTEM_MEMORY_RATE_LIMIT_MILLIS = 5 * 60 * 1000L

/**
 * Measures Orbis's processes. Each process is read from `/proc/<pid>/smaps_rollup` when the system lets it (fresh every
 * time); the app's own process falls back to [readOwnKb] (Debug.MemoryInfo, also fresh); any other falls back to
 * [readSystemKb] (ActivityManager.getProcessMemoryInfo), which Android rate-limits. Such a value is kept with the time
 * it was read and asked for again only after [SYSTEM_MEMORY_RATE_LIMIT_MILLIS], so the time shown stays true. A
 * process that has gone away between listing and measuring (no file, a zero) is left out, not an error. Blocking: call
 * it off the main thread. Thread-safe.
 */
class MemoryReader(
    private val listProcesses: () -> List<RunningProcess>,
    private val ownPid: Int,
    private val readRollupKb: (Int) -> Long?,
    private val readOwnKb: () -> Long?,
    private val readSystemKb: (IntArray) -> LongArray,
    private val packageName: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Cached(val kb: Long, val at: Long)

    private val systemCache = HashMap<RunningProcess, Cached>()

    @Synchronized
    fun read(): MemoryReading? {
        val processes = runCatching(listProcesses).getOrNull().orEmpty()
        if (processes.isEmpty()) return null
        val now = clock()
        val measured = ArrayList<Pair<ProcessMemory, Long>>()
        val viaSystem = ArrayList<RunningProcess>()
        for (p in processes) {
            val kind = processKind(p.name, packageName)
            val direct = runCatching { readRollupKb(p.pid) }.getOrNull()
                ?: if (p.pid == ownPid) runCatching(readOwnKb).getOrNull()?.takeIf { it > 0 } else null
            if (direct != null) measured += ProcessMemory(kind, direct) to now else viaSystem += p
        }
        systemCache.keys.retainAll(viaSystem.toSet())
        val due = viaSystem.filter { p -> systemCache[p]?.let { now - it.at >= SYSTEM_MEMORY_RATE_LIMIT_MILLIS || now < it.at } ?: true }
        if (due.isNotEmpty()) {
            val values = runCatching { readSystemKb(due.map { it.pid }.toIntArray()) }.getOrNull()
            due.forEachIndexed { i, p ->
                val kb = values?.getOrNull(i) ?: 0L
                // Zero: the process ended after it was listed.
                if (kb > 0) systemCache[p] = Cached(kb, now) else systemCache.remove(p)
            }
        }
        var limited = false
        for (p in viaSystem) {
            val c = systemCache[p] ?: continue
            measured += ProcessMemory(processKind(p.name, packageName), c.kb) to c.at
            limited = true
        }
        if (measured.isEmpty()) return null
        return MemoryReading(measuredAt = measured.minOf { it.second }, processes = measured.map { it.first }, rateLimited = limited)
    }

    companion object {
        /** The reader for this app on the phone: Orbis's own processes only (its uid), no permission needed. */
        fun forApp(context: Context): MemoryReader {
            val am = context.getSystemService(ActivityManager::class.java)
            val uid = android.os.Process.myUid()
            val pkg = context.packageName
            return MemoryReader(
                listProcesses = {
                    am?.runningAppProcesses.orEmpty()
                        .filter { it.uid == uid && (it.processName == pkg || it.processName.startsWith("$pkg:")) }
                        .map { RunningProcess(it.pid, it.processName) }
                },
                ownPid = android.os.Process.myPid(),
                readRollupKb = { pid -> runCatching { pssKbFromSmapsRollup(File("/proc/$pid/smaps_rollup").readText()) }.getOrNull() },
                readOwnKb = { Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss.toLong() },
                readSystemKb = { pids -> am?.getProcessMemoryInfo(pids)?.map { it.totalPss.toLong() }?.toLongArray() ?: LongArray(pids.size) },
                packageName = pkg,
            )
        }
    }
}
