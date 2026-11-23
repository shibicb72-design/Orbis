package com.shibi.orbis.engine

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.ExperimentalGeckoViewApi
import org.mozilla.geckoview.GeckoPreferenceController

/**
 * THROW-AWAY instrumentation for diagnosis etp21-1 (branch spike/etp-content). Never merged.
 * Logcat tag [TAG]: one start line naming the build, a read-back of the tracking prefs, and one line per block event
 * with the raw GeckoView classes and the request HOST only (no path, no page address).
 */
internal object EtpSpike {
    const val TAG = "OrbisEtp"

    /** K0 = main as it is (plus this logging). */
    const val BUILD = "K0"
    const val WHAT = "main as it is (antiTracking=STRICT incl. CONTENT; no skip list)"

    val START_LINE = "etp21-1 spike build $BUILD: $WHAT"

    /** The prefs the diagnosis needs to see, each read on its own (a missing pref must not hide the others). */
    val PREFS =
        listOf(
            "urlclassifier.trackingTable",
            "urlclassifier.trackingSkipURLs",
            "urlclassifier.trackingAnnotationSkipURLs",
            "urlclassifier.trackingAnnotationTable",
            "browser.contentblocking.category",
            "privacy.trackingprotection.allow_list.baseline.enabled",
            "privacy.trackingprotection.allow_list.convenience.enabled",
            "privacy.trackingprotection.allow_list.hasMigratedCategoryPrefs",
            "privacy.annotate_channels.strict_list.enabled",
            "privacy.trackingprotection.annotate_channels",
            "privacy.trackingprotection.socialtracking.enabled",
            "privacy.antitracking.enableWebcompat",
        )

    private val readBackAfterFirstBlock = AtomicBoolean(false)

    fun classNames(at: Int): String {
        val names =
            listOf(
                ContentBlocking.AntiTracking.AD to "AD",
                ContentBlocking.AntiTracking.ANALYTIC to "ANALYTIC",
                ContentBlocking.AntiTracking.SOCIAL to "SOCIAL",
                ContentBlocking.AntiTracking.CONTENT to "CONTENT",
                ContentBlocking.AntiTracking.TEST to "TEST",
                ContentBlocking.AntiTracking.CRYPTOMINING to "CRYPTOMINING",
                ContentBlocking.AntiTracking.FINGERPRINTING to "FINGERPRINTING",
                ContentBlocking.AntiTracking.STP to "STP",
                ContentBlocking.AntiTracking.EMAIL to "EMAIL",
            ).filter { at and it.first != 0 }.map { it.second }
        return if (names.isEmpty()) "NONE" else names.joinToString("|")
    }

    fun hostOf(uri: String?): String = runCatching { android.net.Uri.parse(uri).host }.getOrNull() ?: "?"

    fun logEvent(kind: String, event: ContentBlocking.BlockEvent, isPrivate: Boolean) {
        Log.i(
            TAG,
            "$kind classes=${classNames(event.antiTrackingCategory)} cookie=${event.cookieBehaviorCategory} " +
                "sb=${event.safeBrowsingCategory} host=${hostOf(event.uri)} private=$isPrivate build=$BUILD",
        )
        if (kind == "blocked" && readBackAfterFirstBlock.compareAndSet(false, true)) readBack("after-first-block") { }
    }

    /** Reads each of [PREFS] (default and user branch) and logs it; [inApp] also gets each line. Never throws. */
    @androidx.annotation.OptIn(markerClass = [ExperimentalGeckoViewApi::class])
    fun readBack(whenTag: String, inApp: (String) -> Unit) {
        for (name in PREFS) {
            runCatching {
                GeckoPreferenceController.getGeckoPref(name).accept(
                    { p ->
                        val line =
                            "etp21-1 pref[$whenTag] $name default=${p?.defaultValue} user=${p?.userValue} " +
                                "effective=${p?.value} build=$BUILD"
                        Log.i(TAG, line)
                        inApp(line)
                    },
                    { e ->
                        val line = "etp21-1 pref[$whenTag] $name read failed: ${e.javaClass.simpleName} build=$BUILD"
                        Log.i(TAG, line)
                        inApp(line)
                    },
                )
            }.onFailure { Log.i(TAG, "etp21-1 pref[$whenTag] $name threw ${it.javaClass.simpleName}") }
        }
    }
}
