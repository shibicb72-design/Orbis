package com.shibi.orbis.downloads

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.charset.Charset

/**
 * Turns attacker-controlled response metadata into a safe local file name. Pure and Android-free.
 *
 * Security-critical: the result is joined onto the downloads directory, so it must never contain a path
 * separator, a traversal segment, control or bidi characters, and must stay within filesystem length limits.
 * [isInside] is the second line of defence, checked again right before anything is written.
 */
object DownloadFileNames {
    const val FALLBACK_NAME = "download"
    const val DEFAULT_MIME = "application/octet-stream"
    const val MAX_NAME_BYTES = 200
    private const val MAX_EXTENSION_BYTES = 32
    private const val MAX_HOST_LENGTH = 253

    private val RESERVED =
        buildSet {
            addAll(listOf("CON", "PRN", "AUX", "NUL"))
            for (i in 1..9) {
                add("COM$i")
                add("LPT$i")
            }
        }
    private const val ILLEGAL = "<>:\"|?*"
    private val MIME_REGEX =
        Regex("[a-z0-9][a-z0-9!#$&^_.+-]{0,126}/[a-z0-9][a-z0-9!#$&^_.+-]{0,126}")
    private val EXTENSION_REGEX = Regex("[A-Za-z0-9]{1,10}")

    /**
     * Order: `filename*` (RFC 6266/5987) beats `filename`, then the last URL path segment, then [FALLBACK_NAME].
     * An extension from [extensionForMime] is appended only when the chosen name has none.
     */
    fun resolve(
        contentDisposition: String?,
        url: String,
        contentType: String?,
        extensionForMime: (String) -> String?,
    ): String {
        val parsed = parseDisposition(contentDisposition)
        var name =
            listOf(parsed.extended, parsed.plain, nameFromUrl(url))
                .firstNotNullOfOrNull { candidate -> candidate?.let(::sanitize)?.takeIf { it.isNotEmpty() } }
                ?: FALLBACK_NAME
        if (!hasExtension(name)) {
            extensionFor(contentType, extensionForMime)?.let { name = "$name.$it" }
        }
        return capLength(guardReserved(name))
    }

    /**
     * Keeps only the last path segment (both separator kinds), removes control/format/bidi characters, replaces
     * characters illegal on common filesystems, and trims leading dots and trailing dots/spaces.
     * Returns "" when nothing usable remains.
     */
    fun sanitize(raw: String): String {
        val segment = raw.substringAfterLast('/').substringAfterLast('\\')
        val sb = StringBuilder(segment.length)
        var i = 0
        while (i < segment.length) {
            val cp = segment.codePointAt(i)
            i += Character.charCount(cp)
            when {
                isStripped(cp) -> Unit
                cp < 0x80 && ILLEGAL.indexOf(cp.toChar()) >= 0 -> sb.append('_')
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString().trimStart { it.isWhitespace() || it == '.' }.trimEnd { it.isWhitespace() || it == '.' }
    }

    /** Base mime type, lower-case, parameters removed; [DEFAULT_MIME] when missing or malformed. */
    fun cleanMime(contentType: String?): String {
        val base = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return DEFAULT_MIME
        return if (MIME_REGEX.matches(base)) base else DEFAULT_MIME
    }

    /** Host of [url] in lower case, or null when it cannot be parsed. Never throws. */
    fun sourceHost(url: String): String? =
        try {
            URI(url).host?.lowercase()?.takeIf { it.isNotEmpty() && it.length <= MAX_HOST_LENGTH }
        } catch (e: Exception) {
            null
        }

    /** True when [file] resolves (symlinks and `..` included) to a path strictly below [dir]. */
    fun isInside(dir: File, file: File): Boolean =
        try {
            val d = dir.canonicalPath
            val prefix = if (d.endsWith(File.separator)) d else d + File.separator
            val f = file.canonicalPath
            f.length > prefix.length && f.startsWith(prefix)
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }

    /**
     * One path segment from untrusted metadata (a torrent's file or folder name) made safe on its own: [sanitize], then
     * the reserved-name guard and the [MAX_NAME_BYTES] cap that [resolve] applies. "" when nothing usable remains.
     */
    fun safeName(raw: String): String {
        val s = sanitize(raw)
        return if (s.isEmpty()) "" else capLength(guardReserved(s))
    }

    /** "name.ext" -> "name (n).ext"; used when the plain name is already taken. */
    fun withCounter(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
    }

    // ---- internals ----

    private class Disposition(val extended: String?, val plain: String?)

    private fun parseDisposition(header: String?): Disposition {
        if (header.isNullOrBlank()) return Disposition(null, null)
        var extended: String? = null
        var plain: String? = null
        for (token in splitParams(header)) {
            val eq = token.indexOf('=')
            if (eq <= 0) continue
            val key = token.substring(0, eq).trim().lowercase()
            val value = unquote(token.substring(eq + 1).trim())
            when (key) {
                "filename*" -> if (extended == null) extended = decodeExtendedValue(value)
                "filename" -> if (plain == null) plain = value
            }
        }
        return Disposition(extended, plain)
    }

    /** Splits on ';' outside double quotes, honouring \" and \\ inside quotes. */
    private fun splitParams(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inQuotes) {
                sb.append(c)
                if (c == '\\' && i + 1 < s.length && (s[i + 1] == '"' || s[i + 1] == '\\')) {
                    sb.append(s[i + 1])
                    i++
                } else if (c == '"') {
                    inQuotes = false
                }
            } else if (c == '"') {
                inQuotes = true
                sb.append(c)
            } else if (c == ';') {
                out += sb.toString()
                sb.setLength(0)
            } else {
                sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    private fun unquote(v: String): String {
        if (!v.startsWith('"')) return v
        val sb = StringBuilder()
        var i = 1
        while (i < v.length) {
            val c = v[i]
            // Only \" and \\ are unescaped: legacy servers send raw Windows paths ("C:\dir\file.txt") that must
            // keep their backslashes so [sanitize] can drop the directory part.
            if (c == '\\' && i + 1 < v.length && (v[i + 1] == '"' || v[i + 1] == '\\')) {
                sb.append(v[i + 1])
                i += 2
            } else if (c == '"') {
                break
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** `charset'lang'pct-encoded`. Unsupported charset or malformed shape yields null so `filename` can win. */
    private fun decodeExtendedValue(v: String): String? {
        val first = v.indexOf('\'')
        if (first < 0) return null
        val second = v.indexOf('\'', first + 1)
        if (second < 0) return null
        val charset =
            when (v.substring(0, first).trim().lowercase()) {
                "utf-8", "utf8" -> Charsets.UTF_8
                "iso-8859-1", "latin1" -> Charsets.ISO_8859_1
                else -> return null
            }
        return percentDecode(v.substring(second + 1), charset)
    }

    private fun nameFromUrl(url: String): String? {
        val path =
            try {
                URI(url).rawPath
            } catch (e: Exception) {
                url.substringBefore('#').substringBefore('?').substringAfter("://").substringAfter('/', "")
            } ?: return null
        return percentDecode(path.substringAfterLast('/'), Charsets.UTF_8)
    }

    /** Lenient percent-decoding: a '%' not followed by two hex digits is kept literally. Never throws. */
    private fun percentDecode(s: String, charset: Charset): String {
        val out = StringBuilder(s.length)
        val pending = ByteArrayOutputStream()

        fun flush() {
            if (pending.size() > 0) {
                out.append(String(pending.toByteArray(), charset))
                pending.reset()
            }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val hi = if (c == '%' && i + 2 < s.length) hexValue(s[i + 1]) else -1
            val lo = if (hi >= 0) hexValue(s[i + 2]) else -1
            if (lo >= 0) {
                pending.write(hi * 16 + lo)
                i += 3
            } else {
                flush()
                out.append(c)
                i++
            }
        }
        flush()
        return out.toString()
    }

    private fun hexValue(c: Char): Int =
        when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }

    private fun isStripped(cp: Int): Boolean =
        Character.isISOControl(cp) ||
            cp in 0xD800..0xDFFF ||
            cp == 0x2028 ||
            cp == 0x2029 ||
            // Cf covers U+202A-202E, U+2066-2069, U+200B-200F, U+061C, U+FEFF and friends.
            Character.getType(cp) == Character.FORMAT.toInt()

    private fun hasExtension(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        return dot > 0 && dot < name.length - 1
    }

    private fun extensionFor(contentType: String?, extensionForMime: (String) -> String?): String? {
        val mime = cleanMime(contentType)
        if (mime == DEFAULT_MIME) return null
        val ext = try { extensionForMime(mime) } catch (e: Exception) { null }
        return ext?.trim()?.trimStart('.')?.takeIf { EXTENSION_REGEX.matches(it) }
    }

    private fun guardReserved(name: String): String {
        val base = name.substringBefore('.').trimEnd()
        return if (base.uppercase() in RESERVED) "_$name" else name
    }

    private fun capLength(name: String): String {
        if (utf8Length(name) <= MAX_NAME_BYTES) return name
        val dot = name.lastIndexOf('.')
        var ext = if (dot > 0) name.substring(dot) else ""
        if (utf8Length(ext) > MAX_EXTENSION_BYTES + 1) ext = ""
        val base = truncateUtf8(name.substring(0, name.length - ext.length), MAX_NAME_BYTES - utf8Length(ext))
            .trimEnd { it.isWhitespace() || it == '.' }
        return if (base.isEmpty()) FALLBACK_NAME + ext else base + ext
    }

    private fun utf8Length(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            n += cpBytes(cp)
        }
        return n
    }

    private fun cpBytes(cp: Int): Int =
        when {
            cp < 0x80 -> 1
            cp < 0x800 -> 2
            cp < 0x10000 -> 3
            else -> 4
        }

    /** Longest prefix of [s] within [maxBytes] of UTF-8, cut only on code point boundaries. */
    private fun truncateUtf8(s: String, maxBytes: Int): String {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val b = cpBytes(cp)
            if (bytes + b > maxBytes) break
            bytes += b
            i += Character.charCount(cp)
        }
        return s.substring(0, i)
    }
}
