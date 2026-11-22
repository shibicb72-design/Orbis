package com.shibi.orbis.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Engine prefs Orbis switches off before the engine starts (brief priv11-3). Both services ask a Mozilla server in plain
 * http whether the internet works; Orbis reads neither answer.
 *
 * The engine reads [CONTENT] through GeckoRuntimeSettings.Builder.configFilePath: GeckoRuntime.init loads the file
 * (DebugConfig, SnakeYAML), merges its `prefs:` into the start-up prefs and hands them to the engine as default-branch prefs
 * (MOZ_DEFAULT_PREFS) before the engine runs, so even the first start after install sends no probe. Fixed content: no
 * address, no user data. Release builds need the SnakeYAML keep rule in proguard-rules.pro for this.
 *
 * Two safety nets: a start marker (a start that died inside engine creation leaves the file out until the app is
 * updated, see [useStartupFile]), and a late set of both prefs when the read-back does not show them off ([afterReadBack]).
 */
internal object EngineStartupPrefs {
    /** The network connectivity service (`success.txt?ipv4` / `?ipv6` and its DNS checks). greprefs.js default: true. */
    const val CONNECTIVITY_SERVICE = "network.connectivity-service.enabled"

    /** The captive-portal service (`generate_204`). A static pref; set off as well so no later default can start it. */
    const val CAPTIVE_PORTAL_SERVICE = "network.captive-portal-service.enabled"

    /** The prefs this file switches off, all to false. The numeric ones are in [NUMBERS]. */
    val OFF: List<String> = listOf(CONNECTIVITY_SERVICE, CAPTIVE_PORTAL_SERVICE)

    /** A whole-number engine pref and the value this file gives it. Not an "off" switch: see [NUMBERS]. */
    data class NumberPref(val name: String, val value: Int)

    /**
     * Media cache settings for plain video files (a direct MP4 or WebM; not a site's own player, not a live stream), all
     * whole numbers (brief live12-3; engine defaults at 160.0.1 from MediaCache.cpp and StaticPrefList):
     * - read-ahead limit, seconds: 60 on Wi-Fi, 30 on mobile data, both become 300;
     * - resume threshold, seconds (a stopped read starts again when the buffer falls below it): 30 and 10, both become 240;
     * - cache size on mobile data, KiB: 32768, becomes 512000 (the Wi-Fi default; `media.cache_size` itself is not set).
     * The cache is an anonymous temporary file on disk, not memory (FileBlockCache). `media.throttle-cellular-regardless-
     * of-download-rate` is left as the engine has it.
     */
    val NUMBERS: List<NumberPref> =
        listOf(
            NumberPref("media.cache_readahead_limit", 300),
            NumberPref("media.cache_readahead_limit.cellular", 300),
            NumberPref("media.cache_resume_threshold", 240),
            NumberPref("media.cache_resume_threshold.cellular", 240),
            NumberPref("media.cache_size.cellular", 512000),
        )

    /** In the app's own files directory. */
    const val FILE_NAME = "orbis-engine-prefs.yaml"

    /** In the app's own files directory: present only while the engine is being created with [FILE_NAME]. */
    const val MARKER_NAME = "orbis-engine-prefs.starting"

    val CONTENT: String =
        buildString {
            append("prefs:\n")
            for (name in OFF) append("  \"").append(name).append("\": false\n")
            for (pref in NUMBERS) append("  \"").append(pref.name).append("\": ").append(pref.value).append('\n')
        }

    enum class FileState { UNCHANGED, WRITTEN, FAILED }

    /**
     * Makes [file] hold exactly [CONTENT]: left alone when it already does, else written through a temp file and a
     * rename. Never throws; FAILED means the engine must start without the file.
     */
    fun ensureFile(file: File): FileState =
        try {
            if (file.isFile && file.readText(Charsets.UTF_8) == CONTENT) {
                FileState.UNCHANGED
            } else {
                val temp = File(file.parentFile, "${file.name}.tmp")
                try {
                    temp.writeText(CONTENT, Charsets.UTF_8)
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } finally {
                    temp.delete()
                }
                FileState.WRITTEN
            }
        } catch (e: Exception) {
            FileState.FAILED
        }

    // ---- safety net one: the start marker ----

    /** What the marker holds: the versionCode of the start that wrote it. */
    fun markerContent(versionCode: Int): String = versionCode.toString()

    /**
     * Whether this start may pass [FILE_NAME] to the engine. [markerText] is the marker's content, or null when there is
     * no marker. A marker means an earlier start died inside engine creation with the file: skip the file while the app
     * is still that version (an unreadable marker counts as this version); use it again once the versionCode differs.
     */
    fun useStartupFile(markerText: String?, versionCode: Int): Boolean {
        if (markerText == null) return true
        val failedVersion = markerText.trim().toIntOrNull() ?: return false
        return failedVersion != versionCode
    }

    /** The marker's content, or null when there is none or it cannot be read. */
    fun readMarker(marker: File): String? =
        try {
            if (marker.isFile) marker.readText(Charsets.UTF_8) else null
        } catch (e: Exception) {
            null
        }

    /** Writes the marker. False when it cannot be written: the engine must then start without the file. Never throws. */
    fun writeMarker(marker: File, versionCode: Int): Boolean =
        try {
            marker.writeText(markerContent(versionCode), Charsets.UTF_8)
            true
        } catch (e: Exception) {
            false
        }

    const val SKIPPED_LINE = "engine start-up file skipped after a failed start"

    // ---- read-back and safety net two: the late set ----

    enum class ProbeState(val word: String) { OFF_AT_START("true"), OFF_LATE("late"), NOT_OFF("false") }

    /** True only when every pref in [OFF] reads false; a missing or unreadable one counts as not off. */
    fun probeOff(values: Map<String, Any?>): Boolean = OFF.all { values[it] == false }

    /**
     * After the engine's read-back ([values], null when it failed): both off logs `true`. Otherwise both prefs are set
     * off through [setBothOff], which reports whether that worked: `late` when it did, `false` when it did not.
     */
    fun afterReadBack(values: Map<String, Any?>?, setBothOff: (onDone: (Boolean) -> Unit) -> Unit, log: (String) -> Unit) {
        if (values != null && probeOff(values)) {
            log(logLine(ProbeState.OFF_AT_START))
            return
        }
        setBothOff { ok -> log(logLine(if (ok) ProbeState.OFF_LATE else ProbeState.NOT_OFF)) }
    }

    /** The one debug log line. No address. */
    fun logLine(state: ProbeState): String = "engine connectivity probe off: ${state.word}"
}
