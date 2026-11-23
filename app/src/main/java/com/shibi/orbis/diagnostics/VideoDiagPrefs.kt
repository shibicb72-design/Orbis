package com.shibi.orbis.diagnostics

import android.content.SharedPreferences
import androidx.core.content.edit

/** Settings > Video diagnostics. Its own file; not in Orbis's backups (the backup lists its settings one by one). */
interface VideoDiagPrefs {
    /** Off by default. */
    val recording: Boolean

    /** When recording was last switched on (epoch millis); 0 when never. */
    val recordingSince: Long

    var includeSiteNames: Boolean
    var privateTabs: Boolean

    /** When the last line was written (epoch millis); 0 for none. */
    var lastEntryAt: Long

    /** Written to disk before it returns: "off" must be stored before the extension is disabled. */
    fun setRecording(on: Boolean, since: Long)
}

class SharedPreferencesVideoDiagPrefs(private val prefs: SharedPreferences) : VideoDiagPrefs {
    override val recording: Boolean get() = prefs.getBoolean(KEY_RECORDING, false)
    override val recordingSince: Long get() = prefs.getLong(KEY_SINCE, 0L)

    override var includeSiteNames: Boolean
        get() = prefs.getBoolean(KEY_SITE_NAMES, false)
        set(value) {
            prefs.edit { putBoolean(KEY_SITE_NAMES, value) }
        }

    override var privateTabs: Boolean
        get() = prefs.getBoolean(KEY_PRIVATE, false)
        set(value) {
            prefs.edit(commit = true) { putBoolean(KEY_PRIVATE, value) }
        }

    override var lastEntryAt: Long
        get() = prefs.getLong(KEY_LAST_ENTRY, 0L)
        set(value) {
            prefs.edit { putLong(KEY_LAST_ENTRY, value) }
        }

    override fun setRecording(on: Boolean, since: Long) {
        prefs.edit(commit = true) {
            putBoolean(KEY_RECORDING, on)
            putLong(KEY_SINCE, since)
        }
    }

    companion object {
        const val FILE_NAME = "video_diagnostics_prefs"
        private const val KEY_RECORDING = "recording"
        private const val KEY_SINCE = "recording_since"
        private const val KEY_SITE_NAMES = "include_site_names"
        private const val KEY_PRIVATE = "private_tabs"
        private const val KEY_LAST_ENTRY = "last_entry_at"
    }
}
