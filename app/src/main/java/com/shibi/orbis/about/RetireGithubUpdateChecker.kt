package com.shibi.orbis.about

import android.content.Context

/** Drops the preferences file written by the removed GitHub update checker. */
fun retireGithubUpdateChecker(context: Context) {
    context.deleteSharedPreferences("update_prefs")
}
