package com.shibi.orbis.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.net.toUri

/** The application id Google Play distributes. Fixture and r8check builds keep this listing. */
const val ORBIS_PLAY_ID = "com.shibi.orbis"

fun playMarketUri(packageName: String = ORBIS_PLAY_ID): String = "market://details?id=$packageName"

fun playWebUri(packageName: String = ORBIS_PLAY_ID): String =
    "https://play.google.com/store/apps/details?id=$packageName"

internal fun playListingIntents(packageName: String = ORBIS_PLAY_ID): List<Intent> =
    listOf(playMarketUri(packageName), playWebUri(packageName)).map { uri ->
        Intent(Intent.ACTION_VIEW, uri.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

/**
 * Opens the Play listing. The Play app (market://) is tried first; the https page is used when nothing handles it.
 * Returns false when neither could be started.
 */
fun openPlayListing(context: Context, packageName: String = ORBIS_PLAY_ID): Boolean =
    openFirst(playListingIntents(packageName)) { intent ->
        try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

/** Tries each intent until [start] accepts one. */
internal fun openFirst(intents: List<Intent>, start: (Intent) -> Boolean): Boolean {
    for (intent in intents) {
        if (start(intent)) return true
    }
    return false
}

/** The installed version name ("0.5.0"), or empty when the package manager cannot say. */
fun installedVersionName(context: Context): String =
    runCatching {
        val pm = context.packageManager
        val info =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
        info.versionName
    }.getOrNull().orEmpty()
