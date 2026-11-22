package com.shibi.orbis.engine

import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import java.io.File

/**
 * SPIKE live12-4s, never merged. Installs assets/extensions/segrepl and answers its two native messages:
 * "config" (read from `files/segrepl.json`, written with `adb shell run-as <package>`; defaults: on, T = 3000 ms) and
 * "counters" (written to logcat under the tag OrbisSegRepl; counters only, no address or host).
 */
object SegmentReplacementSpike {
    const val ID = "segrepl@orbis.shibi.com"
    const val ASSET_URL = "resource://android/assets/extensions/segrepl/"
    const val NATIVE_APP = "orbisSegRepl"
    private const val TAG = "OrbisSegRepl"

    fun install(runtime: GeckoRuntime, filesDir: File, log: (String) -> Unit) {
        val controller = runtime.webExtensionController
        controller.ensureBuiltIn(ASSET_URL, ID).accept(
            { ext ->
                if (ext == null) return@accept
                ext.setMessageDelegate(delegate(filesDir), NATIVE_APP)
                controller.ensureAllowedInPrivateBrowsing(ext, false).accept(
                    { updated ->
                        updated?.setMessageDelegate(delegate(filesDir), NATIVE_APP)
                        log("segment replacement spike ready")
                    },
                    { log("segment replacement spike ready") },
                )
            },
            { log("segment replacement spike install failed: ${it?.javaClass?.simpleName}") },
        )
    }

    private fun delegate(filesDir: File) =
        object : WebExtension.MessageDelegate {
            override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                val json = message as? JSONObject ?: return null
                return when (json.optString("type")) {
                    "config" -> {
                        val cfg = runCatching { JSONObject(File(filesDir, "segrepl.json").readText()) }.getOrElse { JSONObject() }
                        Log.i(TAG, "config ${cfg.toString()}")
                        GeckoResult.fromValue(cfg)
                    }
                    "counters" -> {
                        Log.i(TAG, "counters ${json.optJSONObject("c")}")
                        null
                    }
                    else -> null
                }
            }
        }
}
