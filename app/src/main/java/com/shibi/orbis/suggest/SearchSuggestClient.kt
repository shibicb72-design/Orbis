package com.shibi.orbis.suggest

import com.shibi.orbis.engine.SearchEngine
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fetches live search suggestions. Callers must only use it for normal tabs with the setting on. */
interface SearchSuggestSource {
    suspend fun suggest(engine: SearchEngine, query: String): List<String>
}

/**
 * HttpURLConnection, no cookies (no CookieHandler is installed), no app identity in the User-Agent, short timeouts,
 * a small response cap. Any failure gives no suggestions; nothing is logged with the query (it is what the user types).
 */
class HttpSearchSuggestSource(private val io: CoroutineDispatcher = Dispatchers.IO) : SearchSuggestSource {
    override suspend fun suggest(engine: SearchEngine, query: String): List<String> {
        val url = suggestUrl(engine, query) ?: return emptyList()
        return withContext(io) {
            runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = TIMEOUT_MS
                    c.readTimeout = TIMEOUT_MS
                    c.instanceFollowRedirects = false
                    c.useCaches = false
                    c.setRequestProperty("User-Agent", USER_AGENT)
                    c.setRequestProperty("Accept", "application/json, application/x-suggestions+json")
                    if (c.responseCode != 200) return@runCatching emptyList()
                    val text = c.inputStream.use { readCapped(it, MAX_BYTES) }
                    parseOpenSearchSuggestions(text)
                } finally {
                    c.disconnect()
                }
            }.getOrDefault(emptyList())
        }
    }

    private fun readCapped(input: java.io.InputStream, cap: Int): String {
        // InputStream.readNBytes is API 33; minSdk is 31.
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (out.size() < cap) {
            val n = input.read(buf, 0, minOf(buf.size, cap - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    private companion object {
        const val TIMEOUT_MS = 2_500
        const val MAX_BYTES = 32 * 1024
        const val USER_AGENT = "Mozilla/5.0 (Android; Mobile) Gecko Firefox"
    }
}
