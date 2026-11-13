package com.shibi.orbis.engine

import com.shibi.orbis.youtube.YoutubeAdEvent
import com.shibi.orbis.youtube.parseYoutubeAdEvent
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension

/**
 * Built-in observer for YouTube ad breaks. It reads the player response with `webRequest.filterResponseData` and writes
 * every byte back unchanged. GeckoView 159 provides that API for this manifest-v2 extension once `webRequestBlocking`
 * is granted. A background script also reports break-specific requests that were cancelled or blocked. Nothing here
 * blocks or rewrites a request, and the extension is not allowed in private tabs.
 */
class YoutubeAdExtensionHolder(
    private val onEvent: () -> ((YoutubeAdEvent) -> Unit)?,
    private val log: (String) -> Unit,
) {
    fun install(runtime: GeckoRuntime) {
        val controller = runtime.webExtensionController
        controller.ensureBuiltIn(ASSET_URL, ID).accept(
            { ext ->
                if (ext == null) return@accept
                attach(ext)
                controller.ensureAllowedInPrivateBrowsing(ext, false).accept(
                    { updated ->
                        attach(updated ?: ext)
                        log("youtube ad observer ready")
                    },
                    {
                        attach(ext)
                        log("youtube ad observer ready")
                    },
                )
            },
            { log("youtube ad observer install failed: ${it?.javaClass?.simpleName}") },
        )
    }

    private fun attach(ext: WebExtension) {
        runCatching { ext.setMessageDelegate(delegate(), NATIVE_APP) }
            .onFailure { log("youtube ad observer delegate failed: ${it.javaClass.simpleName}") }
    }

    private fun delegate(): WebExtension.MessageDelegate =
        object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                port.setDelegate(
                    object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            deliver(message)
                        }
                    },
                )
            }

            override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): org.mozilla.geckoview.GeckoResult<Any>? {
                deliver(message)
                return null
            }
        }

    private fun deliver(message: Any) {
        val json = message as? JSONObject ?: (message as? String)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        val event = parseYoutubeAdEvent(json) ?: return
        onEvent()?.invoke(event)
    }

    companion object {
        const val ID = "yt-ads@orbis.shibi.com"
        const val ASSET_URL = "resource://android/assets/extensions/yt-ads/"
        const val NATIVE_APP = "orbisYoutubeAds"
    }
}
