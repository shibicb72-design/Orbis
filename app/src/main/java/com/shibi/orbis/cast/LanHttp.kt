package com.shibi.orbis.cast

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets

internal data class LanExchange(val code: Int, val headers: Map<String, String>, val body: String)

/**
 * HTTP/1.1 over a raw socket, and only to a local address.
 * The app forbids cleartext on the platform HTTP stack; DLNA descriptions and SOAP are plain HTTP on the LAN, so they
 * go through a socket that never leaves the local network. Redirects that leave the LAN are refused.
 */
internal fun lanExchange(
    method: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    body: String = "",
    timeoutMs: Int = 4_000,
    bind: ((Socket) -> Unit)? = null,
): LanExchange? {
    val first = lanExchangeOnce(method, url, headers, body, timeoutMs, bind) ?: return null
    if (first.code !in 300..399) return first
    val next = first.headers["location"] ?: return first
    val resolved =
        try {
            URI(url).resolve(next).toASCIIString()
        } catch (_: Exception) {
            return null
        }
    if (resolved == url) return first
    return lanExchangeOnce(method, resolved, headers, body, timeoutMs, bind)
}

internal fun lanExchangeOnce(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String,
    timeoutMs: Int,
    bind: ((Socket) -> Unit)?,
): LanExchange? {
    val target = localTarget(url) ?: return null
    val payload = body.toByteArray(StandardCharsets.UTF_8)
    val request = encodeHttpRequest(method, target.pathAndQuery, target.hostHeader, headers, payload.size)
    val socket = Socket()
    try {
        bind?.invoke(socket)
        socket.soTimeout = timeoutMs
        socket.connect(InetSocketAddress(target.address, target.port), timeoutMs)
        val out = socket.getOutputStream()
        out.write(request)
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
        val raw = readLimited(socket.getInputStream(), 512 * 1024) ?: return null
        return parseHttpResponse(raw)
    } catch (_: Exception) {
        return null
    } finally {
        runCatching { socket.close() }
    }
}

internal data class LocalTarget(
    val address: InetAddress,
    val port: Int,
    val hostHeader: String,
    val pathAndQuery: String,
)

/** The URL as a connection target, or null when it is not http(s) on a local address. */
internal fun localTarget(url: String): LocalTarget? {
    val uri =
        try {
            URI(url)
        } catch (_: Exception) {
            return null
        }
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    if (scheme == "https") return null
    val host = uri.host ?: return null
    if (host.length > 253) return null
    val port = if (uri.port != -1) uri.port else 80
    if (port !in 1..65535) return null
    val addresses =
        try {
            InetAddress.getAllByName(host)
        } catch (_: Exception) {
            return null
        }
    if (addresses.isEmpty() || addresses.any { !isLocalLanAddress(it) }) return null
    val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
    val hostHeader = if (port == 80) host else "$host:$port"
    return LocalTarget(addresses.first(), port, hostHeader, path + query)
}

internal fun encodeHttpRequest(
    method: String,
    pathAndQuery: String,
    hostHeader: String,
    headers: Map<String, String>,
    bodyLength: Int,
): ByteArray {
    val text =
        buildString {
            append(method)
            append(' ')
            append(pathAndQuery)
            append(" HTTP/1.1\r\n")
            append("Host: ")
            append(hostHeader)
            append("\r\n")
            append("Connection: close\r\n")
            append("User-Agent: Orbis\r\n")
            append("Content-Length: ")
            append(bodyLength)
            append("\r\n")
            for ((name, value) in headers) {
                if (name.equals("Host", true) || name.equals("Content-Length", true) || name.equals("Connection", true)) continue
                append(name)
                append(": ")
                append(value.replace("\r", "").replace("\n", ""))
                append("\r\n")
            }
            append("\r\n")
        }
    return text.toByteArray(StandardCharsets.UTF_8)
}

internal fun parseHttpResponse(raw: ByteArray): LanExchange? {
    val headerEnd = indexOf(raw, "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)) ?: return null
    val head = String(raw, 0, headerEnd, StandardCharsets.ISO_8859_1)
    val lines = head.split("\r\n")
    val status = lines.firstOrNull()?.split(' ') ?: return null
    if (status.size < 2 || !status[0].startsWith("HTTP/1.")) return null
    val code = status[1].toIntOrNull() ?: return null
    val headers = LinkedHashMap<String, String>()
    for (line in lines.drop(1)) {
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        val name = line.substring(0, colon).trim().lowercase()
        val value = line.substring(colon + 1).trim()
        if (name !in headers) headers[name] = value
    }
    val bodyBytes = raw.copyOfRange(headerEnd + 4, raw.size)
    val body =
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            decodeChunks(bodyBytes) ?: return null
        } else {
            val declared = headers["content-length"]?.toIntOrNull()
            if (declared != null && declared in 0..bodyBytes.size) {
                bodyBytes.copyOf(declared)
            } else {
                bodyBytes
            }
        }
    return LanExchange(code, headers, String(body, StandardCharsets.UTF_8))
}

private fun decodeChunks(raw: ByteArray): ByteArray? {
    val out = ByteArrayOutputStream()
    var i = 0
    val text = String(raw, StandardCharsets.ISO_8859_1)
    while (i < text.length) {
        val lineEnd = text.indexOf("\r\n", i)
        if (lineEnd < 0) return null
        val size = text.substring(i, lineEnd).substringBefore(';').trim().toIntOrNull(16) ?: return null
        if (size == 0) return out.toByteArray()
        val start = lineEnd + 2
        val end = start + size
        if (end > raw.size) return null
        out.write(raw, start, size)
        i = end + 2
        if (out.size() > 512 * 1024) return null
    }
    return null
}

private fun readLimited(input: java.io.InputStream, max: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(4096)
    while (out.size() < max) {
        val n =
            try {
                input.read(buf)
            } catch (_: Exception) {
                break
            }
        if (n < 0) break
        val room = max - out.size()
        out.write(buf, 0, n.coerceAtMost(room))
        if (n > room) break
    }
    return out.toByteArray()
}

private fun indexOf(data: ByteArray, needle: ByteArray): Int? {
    if (needle.isEmpty() || data.size < needle.size) return null
    outer@ for (i in 0..data.size - needle.size) {
        for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
        return i
    }
    return null
}
