package com.shibi.orbis.cast

import org.json.JSONArray
import org.json.JSONObject

/**
 * The only place in `cast/` and `youtube/` that parses JSON text Orbis did not write itself (a TV, the lounge API, a
 * page probe). The platform tokener recurses once per `[` or `{`, so a deeply nested payload could overflow the stack
 * of the thread that reads it. [jsonDepthWithin] refuses such text before parsing; the [StackOverflowError] catch is
 * the backstop for any tokener behaviour the scan does not mirror.
 */
internal const val CAST_JSON_MAX_DEPTH = 32

internal fun parseJsonObject(text: String): JSONObject? {
    if (!jsonDepthWithin(text)) return null
    return try {
        JSONObject(text)
    } catch (_: Exception) {
        null
    } catch (_: StackOverflowError) {
        null
    }
}

internal fun parseJsonArray(text: String): JSONArray? {
    if (!jsonDepthWithin(text)) return null
    return try {
        JSONArray(text)
    } catch (_: Exception) {
        null
    } catch (_: StackOverflowError) {
        null
    }
}

/** Characters that end an unquoted literal in the platform tokener (`JSONTokener.readLiteral`). */
private const val LITERAL_END = "{}[]/\\:,=;# \t\u000c\r\n"

/**
 * True when no `[`/`{` nesting in [text] goes deeper than [max]. One pass, no recursion. Counts what the lenient
 * platform tokener counts: the contents of `"` and `'` strings (with backslash escapes), of `/* */`, `//` and `#`
 * comments, and of unquoted literals (where a quote is an ordinary character) are skipped. An unterminated string or
 * comment returns false.
 */
internal fun jsonDepthWithin(text: String, max: Int = CAST_JSON_MAX_DEPTH): Boolean {
    var depth = 0
    var i = 0
    val n = text.length
    while (i < n) {
        val c = text[i]
        when {
            c == '"' || c == '\'' -> {
                i += 1
                var closed = false
                while (i < n) {
                    val s = text[i]
                    if (s == '\\') {
                        i += 2
                        continue
                    }
                    i += 1
                    if (s == c) {
                        closed = true
                        break
                    }
                }
                if (!closed) return false
                continue
            }
            c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                val end = text.indexOf("*/", i + 2)
                if (end < 0) return false
                i = end + 2
                continue
            }
            (c == '/' && i + 1 < n && text[i + 1] == '/') || c == '#' -> {
                while (i < n && text[i] != '\n' && text[i] != '\r') i += 1
                if (i >= n) return false
                continue
            }
            c == '[' || c == '{' -> {
                depth += 1
                if (depth > max) return false
            }
            c == ']' || c == '}' -> if (depth > 0) depth -= 1
            c in LITERAL_END -> Unit
            else -> {
                // An unquoted literal runs to the next delimiter; a quote inside it does not open a string.
                while (i < n && text[i] !in LITERAL_END) i += 1
                continue
            }
        }
        i += 1
    }
    return true
}
