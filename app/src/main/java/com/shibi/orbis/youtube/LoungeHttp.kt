package com.shibi.orbis.youtube

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * HTTPS POST for the lounge API, built like the search-suggest client: no cookies (no CookieHandler is installed and
 * the browser engine's cookie jar is never used), a generic User-Agent, no redirects, no cache, a capped response.
 * Only `https://www.youtube.com/api/lounge/` addresses are accepted.
 */
internal class HttpsLoungeTransport : LoungeTransport {
    override fun post(req: LoungeRequest, timeoutMs: Int): LoungeResponse? {
        require(req.url.startsWith(ALLOWED_PREFIX)) { "not a lounge address" }
        val connection = URL(req.url).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            req.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val bytes = req.body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { readCapped(it, LOUNGE_MAX_RESPONSE_CHARS + 1) }.orEmpty()
            return LoungeResponse(code, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun readCapped(input: InputStream, cap: Int): String {
        // InputStream.readNBytes is API 33; minSdk is 31.
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (out.size() < cap) {
            val n = input.read(buf, 0, minOf(buf.size, cap - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    private companion object {
        const val ALLOWED_PREFIX = "https://www.youtube.com/api/lounge/"
        const val USER_AGENT = "Mozilla/5.0 (Android; Mobile) Gecko Firefox"
    }
}
