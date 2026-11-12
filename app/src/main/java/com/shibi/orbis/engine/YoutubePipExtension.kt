package com.shibi.orbis.engine

import org.mozilla.geckoview.GeckoRuntime

/**
 * Built-in fix for picture-in-picture on mobile YouTube (assets/extensions/yt-pip). YouTube leaves fullscreen and
 * pauses when its window shrinks, and picture-in-picture shrinks the whole page. Firefox for Android ships the same fix
 * as a webcompat intervention (bug 1956165), which GeckoView does not include. Allowed in private tabs: it stores and
 * sends nothing. Covers youtube.com pages only, not YouTube players embedded in other sites (all_frames is false).
 */
object YoutubePipExtension {
    const val ID = "yt-pip@orbis.shibi.com"
    const val ASSET_URL = "resource://android/assets/extensions/yt-pip/"

    fun install(runtime: GeckoRuntime, log: (String) -> Unit) {
        val controller = runtime.webExtensionController
        controller.ensureBuiltIn(ASSET_URL, ID).accept(
            { ext ->
                if (ext == null) return@accept
                controller.setAllowedInPrivateBrowsing(ext, true).accept({ log("youtube pip fix ready") }, { log("youtube pip fix ready") })
            },
            { log("youtube pip fix install failed: ${it?.javaClass?.simpleName}") },
        )
    }
}
