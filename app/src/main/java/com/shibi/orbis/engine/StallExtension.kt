package com.shibi.orbis.engine

import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension

/**
 * Built-in restart of a stalled video (assets/extensions/stall). Where the engine itself plays the file (a plain media
 * file or a playlist on the video tag, not a site's Media Source player), its content script reloads an element that
 * has been stalled for 12 s, then 24 s, then 48 s, at most three times per element and page load. A live playlist the
 * engine's HLS player gave up on gets a 30-second reconnect window; when that fails the script tells the app
 * ([StreamSignal]) so the browser screen can say so. Allowed in private tabs: it stores nothing, and its only message
 * goes to the app and carries no address.
 */
object StallExtension {
    const val ID = "stall@orbis.shibi.com"
    const val ASSET_URL = "resource://android/assets/extensions/stall/"
    const val NATIVE_APP = "orbisStall"

    private val lock = Any()
    private var extension: WebExtension? = null
    private val waiting = ArrayList<(WebExtension) -> Unit>()

    fun install(runtime: GeckoRuntime, log: (String) -> Unit) {
        val controller = runtime.webExtensionController
        controller.ensureBuiltIn(ASSET_URL, ID).accept(
            { ext ->
                if (ext == null) return@accept
                controller.ensureAllowedInPrivateBrowsing(ext, true).accept(
                    { allowed -> ready(allowed ?: ext) },
                    { ready(ext) },
                )
            },
            { log("stalled video restart install failed: ${it?.javaClass?.simpleName}") },
        )
    }

    private fun ready(ext: WebExtension) {
        val pending =
            synchronized(lock) {
                extension = ext
                waiting.toList().also { waiting.clear() }
            }
        pending.forEach { it(ext) }
    }

    /** Runs [attach] with the installed extension: now if it is ready, else once it is. */
    fun whenReady(attach: (WebExtension) -> Unit) {
        val ext =
            synchronized(lock) {
                extension.also { if (it == null) waiting += attach }
            }
        if (ext != null) attach(ext)
    }
}

/** The stall script's two signals to the app. */
internal enum class StreamSignal {
    /** The live stream could not be reconnected within the 30-second window. */
    DROPPED,

    /** It plays again. */
    PLAYING,
}

/**
 * Reads one message on the stall channel. Only a message from the stall extension itself ([senderExtensionId]) that is
 * exactly `{ "type": "dropped" }` or `{ "type": "playing" }` counts; anything else is null and is dropped without a log
 * line (the page is untrusted, and a log must not repeat what it sent).
 */
internal fun parseStallMessage(senderExtensionId: String?, message: Any?): StreamSignal? {
    if (senderExtensionId != StallExtension.ID) return null
    val obj = message as? JSONObject ?: return null
    if (obj.length() != 1) return null
    return when (obj.opt("type")) {
        "dropped" -> StreamSignal.DROPPED
        "playing" -> StreamSignal.PLAYING
        else -> null
    }
}
