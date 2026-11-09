package com.shibi.orbis.update

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Update checks for builds not installed from Google Play: manual (Settings > About Orbis) and, when the user leaves it
 * on, automatic at most once a day. Results are only shown; nothing is downloaded or installed by Orbis.
 */
class UpdateController(
    context: Context,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val check: suspend (String) -> UpdateResult = ::checkForUpdate,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** The installed version name ("0.4.6"). */
    val currentVersion: String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    /** True when Google Play installed Orbis: Play updates it, so no check is made. */
    val fromPlay: Boolean =
        runCatching {
            val pm = context.packageManager
            val installer =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    pm.getInstallSourceInfo(context.packageName).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstallerPackageName(context.packageName)
                }
            installedFromPlay(installer)
        }.getOrDefault(false)

    private val _auto = MutableStateFlow(prefs.getBoolean(KEY_AUTO, true))
    val autoCheck: StateFlow<Boolean> = _auto.asStateFlow()

    private val _result = MutableStateFlow<UpdateResult?>(null)

    /** The last check's answer this run; null before any. */
    val result: StateFlow<UpdateResult?> = _result.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    fun setAutoCheck(on: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO, on) }
        _auto.value = on
    }

    /** At app start: checks when automatic checks are on and the last one is a day old. */
    fun checkIfDue() {
        if (fromPlay || !_auto.value) return
        if (now() - prefs.getLong(KEY_LAST, 0L) < UPDATE_CHECK_INTERVAL_MS) return
        checkNow()
    }

    fun checkNow() {
        if (fromPlay || _checking.value || currentVersion.isEmpty()) return
        _checking.value = true
        scope.launch {
            val r = check(currentVersion)
            prefs.edit { putLong(KEY_LAST, now()) }
            _result.value = r
            _checking.value = false
        }
    }

    /** The version the user was already told about (so the notice shows once per version). */
    var announcedVersion: String?
        get() = prefs.getString(KEY_ANNOUNCED, null)
        set(value) {
            prefs.edit { putString(KEY_ANNOUNCED, value) }
        }

    companion object {
        const val FILE_NAME = "update_prefs"
        private const val KEY_AUTO = "auto_check"
        private const val KEY_LAST = "last_check_millis"
        private const val KEY_ANNOUNCED = "announced_version"
    }
}
