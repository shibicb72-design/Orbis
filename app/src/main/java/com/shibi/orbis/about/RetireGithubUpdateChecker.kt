package com.shibi.orbis.about

import android.content.Context

/** SharedPreferences file written by the removed GitHub update checker. */
const val LEGACY_UPDATE_PREFS = "update_prefs"

/**
 * Unique work name (and tag) of a daily GitHub update check.
 * Builds since 0.4.6 checked on startup and did not enqueue this job. Cancelling it drops a job left by any build
 * that did schedule one, and does nothing when none was scheduled.
 */
const val LEGACY_UPDATE_WORK = "com.shibi.orbis.update.check"

fun interface UpdateWorkCanceller {
    fun cancel(uniqueName: String)
}

/** Drops the GitHub update checker's preferences and cancels its WorkManager job, if one is still scheduled. */
fun retireGithubUpdateChecker(context: Context) {
    retireGithubUpdateChecker(context, WorkManagerUpdateCanceller(context))
}

internal fun retireGithubUpdateChecker(context: Context, canceller: UpdateWorkCanceller) {
    context.deleteSharedPreferences(LEGACY_UPDATE_PREFS)
    runCatching { canceller.cancel(LEGACY_UPDATE_WORK) }
}

/**
 * Android Components already ship WorkManager. Calling it reflectively avoids a second dependency declaration
 * (that re-resolves older metadata and fails checksum verification) while still cancelling the leftover job.
 * The methods are kept in [proguard-rules.pro] so R8 does not strip them.
 */
internal class WorkManagerUpdateCanceller(private val context: Context) : UpdateWorkCanceller {
    override fun cancel(uniqueName: String) {
        runCatching {
            val wmClass = Class.forName("androidx.work.WorkManager")
            val work = wmClass.getMethod("getInstance", Context::class.java).invoke(null, context)
            wmClass.getMethod("cancelUniqueWork", String::class.java).invoke(work, uniqueName)
            wmClass.getMethod("cancelAllWorkByTag", String::class.java).invoke(work, uniqueName)
        }
    }
}
