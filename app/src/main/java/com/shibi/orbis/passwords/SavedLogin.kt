package com.shibi.orbis.passwords

import com.shibi.orbis.engine.hostOfUrl
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * One saved login. [origin] is "scheme://host[:port]" of the page; [formActionOrigin] where the form posts (may be
 * empty); [httpRealm] set instead for HTTP authentication logins. Times are epoch milliseconds.
 */
data class SavedLogin(
    val guid: String,
    val origin: String,
    val formActionOrigin: String = "",
    val httpRealm: String = "",
    val username: String,
    val password: String,
    val timeCreated: Long = 0L,
    val timeLastUsed: Long = 0L,
    val timesUsed: Int = 0,
) {
    val host: String get() = hostOfUrl(origin) ?: origin
}

/** "scheme://host[:port]" (lowercase, default port dropped) of a web address, or null. */
fun loginOrigin(url: String?): String? {
    if (url == null) return null
    val scheme = url.substringBefore("://", "").lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return null
    val authority = url.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
    if (authority.isEmpty()) return null
    val lower = authority.lowercase(Locale.ROOT)
    return "$scheme://" + lower.removeSuffix(if (scheme == "http") ":80" else ":443")
}

/**
 * Whether a login saved for [origin] may be offered on a page whose base domain is [domain] (what GeckoView asks
 * for): the login's host is the domain or a subdomain of it. Scheme is checked by Gecko itself (it does not fill an
 * https login into http).
 */
fun loginMatchesDomain(origin: String, domain: String): Boolean {
    val host = hostOfUrl(origin)?.lowercase(Locale.ROOT) ?: return false
    val d = domain.lowercase(Locale.ROOT).trim().trimStart('.')
    return d.isNotEmpty() && (host == d || host.endsWith(".$d"))
}

/** Pure JSON codec for the decrypted login list. */
object LoginCodec {
    private const val VERSION = 1

    fun encode(items: List<SavedLogin>): String {
        val array = JSONArray()
        for (l in items) {
            array.put(
                JSONObject()
                    .put("guid", l.guid).put("origin", l.origin).put("formActionOrigin", l.formActionOrigin)
                    .put("httpRealm", l.httpRealm).put("username", l.username).put("password", l.password)
                    .put("created", l.timeCreated).put("lastUsed", l.timeLastUsed).put("used", l.timesUsed),
            )
        }
        return JSONObject().put("version", VERSION).put("logins", array).toString()
    }

    /** Throws if the document cannot be parsed; skips single bad entries (no origin, no password, repeated guid). */
    fun decode(text: String): List<SavedLogin> {
        val array = JSONObject(text).getJSONArray("logins")
        val out = ArrayList<SavedLogin>()
        val guids = HashSet<String>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val origin = loginOrigin(str(o, "origin")) ?: continue
            val guid = str(o, "guid").ifEmpty { continue }
            val password = str(o, "password")
            if (password.isEmpty() || !guids.add(guid)) continue
            out +=
                SavedLogin(
                    guid = guid,
                    origin = origin,
                    formActionOrigin = loginOrigin(str(o, "formActionOrigin")).orEmpty(),
                    httpRealm = str(o, "httpRealm"),
                    username = str(o, "username"),
                    password = password,
                    timeCreated = o.optLong("created", 0L),
                    timeLastUsed = o.optLong("lastUsed", 0L),
                    timesUsed = o.optInt("used", 0).coerceAtLeast(0),
                )
        }
        return out
    }

    private fun str(o: JSONObject, key: String): String = if (o.isNull(key)) "" else o.optString(key, "")
}

/** A login read from a CSV export, before it gets a guid. */
data class ImportedLogin(val origin: String, val username: String, val password: String, val formActionOrigin: String, val httpRealm: String)

/** What an import found: usable rows and how many rows were skipped (no web address or no password). */
data class CsvImport(val logins: List<ImportedLogin>, val skipped: Int)

/**
 * CSV import/export for Chrome ("name,url,username,password,note") and Firefox ("url,username,password,httpRealm,
 * formActionOrigin,guid,timeCreated,timeLastUsed,timePasswordChanged") password exports. Columns are found by header
 * name, so either order works. RFC 4180 quoting (commas, quotes and newlines inside quotes).
 */
/** U+FEFF, which spreadsheet exports often put first. */
private val BYTE_ORDER_MARK = 0xFEFF.toChar()

object LoginCsv {
    val EXPORT_HEADER = listOf("url", "username", "password", "httpRealm", "formActionOrigin")

    fun parse(text: String): CsvImport {
        val rows = parseRows(text.trimStart(BYTE_ORDER_MARK))
        if (rows.isEmpty()) return CsvImport(emptyList(), 0)
        val header = rows.first().map { it.trim().lowercase(Locale.ROOT) }
        val url = header.indexOfFirst { it == "url" || it == "origin" || it == "hostname" }
        val user = header.indexOf("username")
        val pass = header.indexOf("password")
        if (url < 0 || pass < 0) return CsvImport(emptyList(), rows.size - 1)
        val realm = header.indexOf("httprealm")
        val action = header.indexOf("formactionorigin")
        val out = ArrayList<ImportedLogin>()
        var skipped = 0
        for (r in rows.drop(1)) {
            if (r.all { it.isBlank() }) continue
            val origin = loginOrigin(r.getOrNull(url)?.trim())
            val password = r.getOrNull(pass).orEmpty()
            if (origin == null || password.isEmpty()) {
                skipped++
                continue
            }
            out +=
                ImportedLogin(
                    origin = origin,
                    username = r.getOrNull(user).orEmpty(),
                    password = password,
                    formActionOrigin = loginOrigin(r.getOrNull(action)?.trim()).orEmpty(),
                    httpRealm = r.getOrNull(realm).orEmpty(),
                )
        }
        return CsvImport(out, skipped)
    }

    fun export(logins: List<SavedLogin>): String {
        val sb = StringBuilder()
        sb.append(EXPORT_HEADER.joinToString(",")).append("\r\n")
        for (l in logins) {
            sb.append(listOf(l.origin, l.username, l.password, l.httpRealm, l.formActionOrigin).joinToString(",") { quote(it) }).append("\r\n")
        }
        return sb.toString()
    }

    private fun quote(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    private fun parseRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        row += field.toString()
                        field.setLength(0)
                    }
                    '\r' -> Unit
                    '\n' -> {
                        row += field.toString()
                        field.setLength(0)
                        rows += row
                        row = ArrayList()
                    }
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            rows += row
        }
        return rows
    }
}
