package com.shibi.orbis.update

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Where Orbis's public releases are listed. */
const val RELEASES_API = "https://api.github.com/repos/shibicb72-design/Orbis/releases/latest"
const val RELEASES_PAGE = "https://github.com/shibicb72-design/Orbis/releases"

/** At most one automatic check a day. */
const val UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

/** The newest published release: its version and the page to get it from. */
data class ReleaseInfo(val version: String, val pageUrl: String)

sealed interface UpdateResult {
    data class Available(val release: ReleaseInfo) : UpdateResult
    data object UpToDate : UpdateResult
    /** No release published yet, no network, or GitHub said no. */
    data object Unknown : UpdateResult
}

/** "v0.4.5" or "0.4.5-beta" to [0, 4, 5]; null when it is not a dotted number. */
internal fun versionParts(raw: String): List<Int>? {
    val core = raw.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
    if (core.isEmpty()) return null
    return core.split('.').map { it.toIntOrNull() ?: return null }
}

/** True when [candidate] is a later version than [current] (missing parts count as 0). */
internal fun isNewer(candidate: String, current: String): Boolean {
    val a = versionParts(candidate) ?: return false
    val b = versionParts(current) ?: return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/** The release in GitHub's "latest release" answer; drafts and pre-releases are not offered. */
internal fun parseRelease(json: String): ReleaseInfo? =
    runCatching {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val tag = o.optString("tag_name").takeIf { versionParts(it) != null } ?: return null
        val page = o.optString("html_url").takeIf { it.startsWith("https://github.com/shibicb72-design/Orbis/") } ?: RELEASES_PAGE
        ReleaseInfo(tag.removePrefix("v"), page)
    }.getOrNull()

/** Builds were installed from Google Play, which updates them itself. */
internal fun installedFromPlay(installer: String?): Boolean = installer == "com.android.vending"

/**
 * Asks GitHub for the latest release. Sends nothing but the request itself (a generic user agent, no identifiers).
 * Never throws: failures are [UpdateResult.Unknown].
 */
suspend fun checkForUpdate(currentVersion: String): UpdateResult =
    withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(RELEASES_API).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "Orbis-update-check")
                if (conn.responseCode != 200) return@runCatching UpdateResult.Unknown
                val body = conn.inputStream.use { stream -> stream.readBytes().take(256 * 1024).toByteArray().decodeToString() }
                val release = parseRelease(body) ?: return@runCatching UpdateResult.Unknown
                if (isNewer(release.version, currentVersion)) UpdateResult.Available(release) else UpdateResult.UpToDate
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(UpdateResult.Unknown)
    }
