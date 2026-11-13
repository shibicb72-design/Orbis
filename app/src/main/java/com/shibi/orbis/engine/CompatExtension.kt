package com.shibi.orbis.engine

import com.shibi.orbis.compat.PageSample
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension

/**
 * Built-in "unsupported browser" probe (assets/extensions/compat). Its content script sends the app at most one short
 * sample of the top page, or "clean"; see the auto-desktop retry in GeckoBrowserSession. Allowed in private tabs: it
 * stores nothing and sends nothing anywhere else.
 */
class CompatExtensionHolder(private val log: (String) -> Unit) {
    @Volatile var extension: WebExtension? = null
        private set

    private val waiting = ArrayList<(WebExtension) -> Unit>()

    fun install(runtime: GeckoRuntime) {
        val controller = runtime.webExtensionController
        controller.ensureBuiltIn(ASSET_URL, ID).accept(
            { ext ->
                if (ext == null) return@accept
                controller.ensureAllowedInPrivateBrowsing(ext, true).accept(
                    { allowed -> ready(allowed ?: ext) },
                    { ready(ext) },
                )
            },
            { log("compat extension install failed: ${it?.javaClass?.simpleName}") },
        )
    }

    private fun ready(ext: WebExtension) {
        extension = ext
        val pending = synchronized(waiting) { waiting.toList().also { waiting.clear() } }
        pending.forEach { it(ext) }
    }

    fun whenReady(attach: (WebExtension) -> Unit) {
        val ext = extension
        if (ext != null) attach(ext) else synchronized(waiting) { waiting += attach }
    }

    companion object {
        const val ID = "compat@orbis.shibi.com"
        const val ASSET_URL = "resource://android/assets/extensions/compat/"
        const val NATIVE_APP = "orbisCompat"
    }
}

/** One message from the compat content script. */
internal sealed interface CompatMessage {
    data class Sample(val sample: PageSample) : CompatMessage

    data object Clean : CompatMessage
}

/** Parses a compat port message; null for anything malformed. Lengths are capped again here: the page is untrusted. */
internal fun parseCompatMessage(obj: JSONObject): CompatMessage? =
    when (obj.optString("type")) {
        "clean" -> CompatMessage.Clean
        "sample" -> {
            val heads = obj.optJSONArray("headlines")
            val headlines =
                if (heads == null) {
                    emptyList()
                } else {
                    (0 until minOf(heads.length(), 5)).mapNotNull { i -> heads.optString(i, null)?.take(200) }
                }
            CompatMessage.Sample(
                PageSample(
                    title = obj.optString("title").take(1000),
                    headlines = headlines,
                    // One char over the classifier's limit is enough for it to refuse a long text.
                    visibleText = obj.optString("text").take(801),
                    blockPath = obj.optBoolean("blockPath", false),
                ),
            )
        }
        else -> null
    }
