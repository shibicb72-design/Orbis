package com.shibi.orbis.engine

import org.mozilla.geckoview.GeckoRuntime

/**
 * Built-in restart of a stalled video (assets/extensions/stall). Where the engine itself plays the file (a plain media
 * file or a playlist on the video tag, not a site's Media Source player), its content script reloads an element that
 * has been stalled for 12 s, then 24 s, then 48 s, at most three times per element and page load. Allowed in private
 * tabs: it has no permissions, and stores and sends nothing.
 */
object StallExtension {
    const val ID = "stall@orbis.shibi.com"
    const val ASSET_URL = "resource://android/assets/extensions/stall/"

    fun install(runtime: GeckoRuntime, log: (String) -> Unit) {
        val controller = runtime.webExtensionController
        controller.ensureBuiltIn(ASSET_URL, ID).accept(
            { ext ->
                if (ext == null) return@accept
                controller.ensureAllowedInPrivateBrowsing(ext, true).accept({}, {})
            },
            { log("stalled video restart install failed: ${it?.javaClass?.simpleName}") },
        )
    }
}
