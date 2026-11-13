package com.shibi.orbis.engine

import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension

/** A page parsed for reader view (Mozilla Readability). [contentHtml] is Readability's cleaned article HTML. */
data class ReaderArticle(val url: String, val title: String, val byline: String, val siteName: String, val contentHtml: String)

/** Parses one message from the reader content script; null for anything else. Pure (org.json only). */
fun parseReaderMessage(msg: JSONObject): ReaderMessage? =
    when (msg.optString("type")) {
        "readerable" -> ReaderMessage.Readerable(msg.optString("url"), msg.optBoolean("value", false))
        "article" -> {
            val a = msg.optJSONObject("article")
            val url = msg.optString("url")
            ReaderMessage.Article(
                url,
                a?.let {
                    ReaderArticle(
                        url = url,
                        title = it.optString("title"),
                        byline = it.optString("byline"),
                        siteName = it.optString("siteName"),
                        contentHtml = it.optString("content"),
                    )
                }?.takeIf { it.contentHtml.isNotBlank() },
            )
        }
        else -> null
    }

sealed class ReaderMessage {
    data class Readerable(val url: String, val value: Boolean) : ReaderMessage()

    data class Article(val url: String, val article: ReaderArticle?) : ReaderMessage()
}

/**
 * The built-in reader extension (assets/extensions/reader): installed once per runtime, then attached to every session
 * (sessions created before it is ready are attached when it is). Allowed in private browsing: it only talks to the app.
 */
class ReaderExtensionHolder(private val log: (String) -> Unit) {
    @Volatile var extension: WebExtension? = null
        private set
    /** Attach callbacks of sessions created before the extension was ready (few; run once, then dropped). */
    private val waiting = ArrayList<(WebExtension) -> Unit>()

    fun install(runtime: GeckoRuntime) {
        val controller = runtime.webExtensionController
        controller.ensureBuiltIn(ASSET_URL, ID).accept(
            { ext ->
                if (ext == null) return@accept
                controller.ensureAllowedInPrivateBrowsing(ext, true).accept({ allowed -> ready(allowed ?: ext) }, { ready(ext) })
            },
            { log("reader extension install failed: ${it?.javaClass?.simpleName}") },
        )
    }

    private fun ready(ext: WebExtension) {
        extension = ext
        val pending = synchronized(waiting) { waiting.toList().also { waiting.clear() } }
        pending.forEach { it(ext) }
        log("reader extension ready")
    }

    /** Runs [attach] now if the extension is ready, else once it is. */
    fun whenReady(attach: (WebExtension) -> Unit) {
        val ext = extension
        if (ext != null) attach(ext) else synchronized(waiting) { waiting += attach }
    }

    companion object {
        const val ID = "reader@orbis.shibi.com"
        const val ASSET_URL = "resource://android/assets/extensions/reader/"
        const val NATIVE_APP = "orbisReader"
    }
}
