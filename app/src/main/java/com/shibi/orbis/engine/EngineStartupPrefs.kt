package com.shibi.orbis.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Engine prefs Orbis switches off before the engine starts (brief priv11-3). Both services ask a Mozilla server in plain
 * http whether the internet works; Orbis reads neither answer, and Android itself still detects sign-in (captive) networks.
 *
 * The engine reads [CONTENT] through GeckoRuntimeSettings.Builder.configFilePath: GeckoRuntime.init loads the file
 * (DebugConfig, SnakeYAML), merges its `prefs:` into the start-up prefs and hands them to the engine as default-branch prefs
 * (MOZ_DEFAULT_PREFS) before the engine runs, so even the first start after install sends no probe. Fixed content: no
 * address, no user data.
 */
internal object EngineStartupPrefs {
    /** The network connectivity service (`success.txt?ipv4` / `?ipv6` and its DNS checks). greprefs.js default: true. */
    const val CONNECTIVITY_SERVICE = "network.connectivity-service.enabled"

    /** The captive-portal service (`generate_204`). A static pref; set off as well so no later default can start it. */
    const val CAPTIVE_PORTAL_SERVICE = "network.captive-portal-service.enabled"

    /** Every pref this file sets, all to false. No other pref. */
    val OFF: List<String> = listOf(CONNECTIVITY_SERVICE, CAPTIVE_PORTAL_SERVICE)

    /** In the app's own files directory. */
    const val FILE_NAME = "orbis-engine-prefs.yaml"

    val CONTENT: String =
        buildString {
            append("prefs:\n")
            for (name in OFF) append("  \"").append(name).append("\": false\n")
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

    /** True only when every pref in [OFF] reads false; a missing or unreadable one counts as not off. */
    fun probeOff(values: Map<String, Any?>): Boolean = OFF.all { values[it] == false }

    /** The one debug log line. No address. */
    fun logLine(off: Boolean): String = "engine connectivity probe off: $off"
}
