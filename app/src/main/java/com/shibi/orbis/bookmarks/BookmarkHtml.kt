package com.shibi.orbis.bookmarks

import com.shibi.orbis.engine.cleanStoredTitle
import com.shibi.orbis.engine.isStorableWebUrl

/** Largest bookmark file read for an import. Bigger files are refused without parsing. */
const val MAX_IMPORT_BYTES = 10 * 1024 * 1024

/** One link found in an imported file. [addedAtMillis] is null when the file gave no usable date. */
data class ParsedBookmark(val url: String, val title: String, val addedAtMillis: Long?)

/** [skipped] counts anchors that were not usable (no href, or a URL that is not storable http(s)). */
data class ParsedBookmarks(val items: List<ParsedBookmark>, val skipped: Int)

/**
 * Netscape bookmark files, the format Firefox and Chrome both export and import. Pure.
 *
 * Reading is a hand-written single pass over the text, never a backtracking regex, because the file comes from outside:
 * the position only moves forward, an unterminated tag ends the parse, and once no `</a` is left nothing is rescanned.
 */
object BookmarkHtml {
    /** Dates above this (year 9999, in seconds) are not seconds at all (some exporters write microseconds). */
    private const val MAX_ADD_DATE_SECONDS = 253_402_300_799L

    /** More title text than this is never needed: it is cleaned and capped far below it. */
    private const val MAX_TITLE_SCAN_CHARS = 8_192

    /** Longest entity looked at, from the '&' to the ';'. */
    private const val MAX_ENTITY_CHARS = 14

    fun export(list: List<Bookmark>): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
        sb.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
        sb.append("<TITLE>Bookmarks</TITLE>\n")
        sb.append("<H1>Bookmarks</H1>\n")
        sb.append("<DL><p>\n")
        fun item(b: Bookmark, indent: String) {
            sb.append(indent).append("<DT><A HREF=\"").append(escape(b.url))
            sb.append("\" ADD_DATE=\"").append(maxOf(b.createdAt, 0L) / 1000L).append("\">")
            sb.append(escape(b.title)).append("</A>\n")
        }
        for (b in list) if (b.folder.isEmpty()) item(b, "    ")
        // Each folder as a Netscape sub-list, which Chrome and Firefox import as a folder.
        for (name in folderNames(list)) {
            sb.append("    <DT><H3>").append(escape(name)).append("</H3>\n")
            sb.append("    <DL><p>\n")
            for (b in list) if (b.folder == name) item(b, "        ")
            sb.append("    </DL><p>\n")
        }
        sb.append("</DL><p>\n")
        return sb.toString()
    }

    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun parse(html: String): ParsedBookmarks {
        val text = if (html.startsWith('\uFEFF')) html.substring(1) else html
        val n = text.length
        val items = ArrayList<ParsedBookmark>()
        var skipped = 0
        var pos = 0
        // Position of the next "</a" at or after the last search start; -1 once there is none left.
        var closeAt = -2
        while (pos < n && items.size < MAX_BOOKMARKS) {
            val lt = text.indexOf('<', pos)
            if (lt < 0 || lt + 2 >= n) break
            val isAnchor = (text[lt + 1] == 'a' || text[lt + 1] == 'A') && text[lt + 2].isWhitespace()
            if (!isAnchor) {
                pos = lt + 1
                continue
            }
            val tag = readAttributes(text, lt + 2) ?: break // unterminated tag: nothing after it can be trusted
            val titleStart = tag.end + 1
            if (closeAt != -1 && closeAt < titleStart) closeAt = findCloseAnchor(text, titleStart)
            if (closeAt < 0) {
                // No "</a" anywhere after this point, so no later anchor can finish either.
                skipped++
                break
            }
            val href = tag.href?.let { decodeEntities(it).trim() }
            if (href == null || !isStorableWebUrl(href)) {
                skipped++
            } else {
                val title = cleanStoredTitle(decodeEntities(stripTags(text, titleStart, closeAt))).ifEmpty { href }
                items += ParsedBookmark(href, title, parseAddDate(tag.addDate))
            }
            pos = closeAt + 3
        }
        return ParsedBookmarks(items, skipped)
    }

    private class Tag(val href: String?, val addDate: String?, val end: Int)

    /** Reads attributes starting just after "<a ". Returns null if the tag (or a quoted value) never ends. */
    private fun readAttributes(text: String, start: Int): Tag? {
        val n = text.length
        var i = start
        var href: String? = null
        var addDate: String? = null
        while (true) {
            while (i < n && (text[i].isWhitespace() || text[i] == '/')) i++
            if (i >= n) return null
            if (text[i] == '>') return Tag(href, addDate, i)
            // Attribute name. A stray '=' is taken as part of the name so the loop always advances.
            val nameStart = i
            i++
            while (i < n && !text[i].isWhitespace() && text[i] != '=' && text[i] != '>' && text[i] != '/') i++
            val name = text.substring(nameStart, i)
            while (i < n && text[i].isWhitespace()) i++
            var value: String? = null
            if (i < n && text[i] == '=') {
                i++
                while (i < n && text[i].isWhitespace()) i++
                if (i >= n) return null
                val q = text[i]
                if (q == '"' || q == '\'') {
                    val close = text.indexOf(q, i + 1)
                    if (close < 0) return null
                    value = text.substring(i + 1, close)
                    i = close + 1
                } else {
                    val vs = i
                    while (i < n && !text[i].isWhitespace() && text[i] != '>') i++
                    value = text.substring(vs, i)
                }
            }
            if (value != null) {
                if (href == null && name.equals("href", ignoreCase = true)) href = value
                if (addDate == null && name.equals("add_date", ignoreCase = true)) addDate = value
            }
        }
    }

    /** Index of the next "</a" that is a whole tag name (not "</abbr"), or -1. */
    private fun findCloseAnchor(text: String, from: Int): Int {
        var i = from
        while (true) {
            val at = text.indexOf("</", i)
            if (at < 0 || at + 2 >= text.length) return -1
            val c = text[at + 2]
            if (c == 'a' || c == 'A') {
                val after = if (at + 3 < text.length) text[at + 3] else '>'
                if (after == '>' || after == '/' || after.isWhitespace()) return at
            }
            i = at + 2
        }
    }

    /** The text in text[from, to) without any `<...>` tags, at most [MAX_TITLE_SCAN_CHARS] long. */
    private fun stripTags(text: String, from: Int, to: Int): String {
        val sb = StringBuilder()
        var inTag = false
        var i = from
        while (i < to && sb.length < MAX_TITLE_SCAN_CHARS) {
            val c = text[i]
            when {
                inTag -> if (c == '>') inTag = false
                c == '<' -> inTag = true
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** ADD_DATE is in seconds. Anything unparseable, negative or too large is dropped. */
    private fun parseAddDate(raw: String?): Long? {
        val seconds = decodeEntities(raw ?: return null).trim().toLongOrNull() ?: return null
        if (seconds < 0 || seconds > MAX_ADD_DATE_SECONDS) return null
        return seconds * 1000L
    }

    /**
     * Decodes &amp; &lt; &gt; &quot; &apos; and numeric references (decimal and hex). A numeric reference to a code
     * point that cannot exist is dropped; any other text, including unknown names, stays as it is.
     */
    internal fun decodeEntities(s: String): String {
        if (s.indexOf('&') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') {
                sb.append(c)
                i++
                continue
            }
            // Only a short window is searched: a long run of '&' with no ';' must stay linear.
            var semi = i + 1
            val limit = minOf(s.length, i + MAX_ENTITY_CHARS + 1)
            while (semi < limit && s[semi] != ';') semi++
            if (semi >= limit) {
                sb.append(c)
                i++
                continue
            }
            val body = s.substring(i + 1, semi)
            val named =
                when (body) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    else -> null
                }
            if (named != null) {
                sb.append(named)
                i = semi + 1
                continue
            }
            if (body.length >= 2 && body[0] == '#') {
                val hex = body[1] == 'x' || body[1] == 'X'
                val digits = if (hex) body.substring(2) else body.substring(1)
                val code = digits.takeIf { it.isNotEmpty() }?.toLongOrNull(if (hex) 16 else 10)
                if (code != null && digits.all { it.isLetterOrDigit() }) {
                    if (code in 1..0x10FFFF && code !in 0xD800..0xDFFF) sb.appendCodePoint(code.toInt())
                    i = semi + 1
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
