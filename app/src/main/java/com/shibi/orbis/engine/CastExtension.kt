package com.shibi.orbis.engine

import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension

/**
 * Built-in page probe for Cast. It only answers the app (media addresses on the current page, and a pause request).
 * Allowed in private browsing: a private tab may cast, and the extension stores nothing.
 */
class CastExtensionHolder(private val log: (String) -> Unit) {
    @Volatile var extension: WebExtension? = null
        private set

    private val waiting = ArrayList<(WebExtension) -> Unit>()

    fun install(runtime: GeckoRuntime) {
        val controller = runtime.webExtensionController
        controller.ensureBuiltIn(ASSET_URL, ID).accept(
            { ext ->
                if (ext == null) return@accept
                controller.setAllowedInPrivateBrowsing(ext, true).accept(
                    { allowed -> ready(allowed ?: ext) },
                    { ready(ext) },
                )
            },
            { log("cast extension install failed: ${it?.javaClass?.simpleName}") },
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
        const val ID = "cast@orbis.shibi.com"
        const val ASSET_URL = "resource://android/assets/extensions/cast/"
        const val NATIVE_APP = "orbisCast"
    }
}
