package com.shibi.orbis.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import com.shibi.orbis.engine.hostOfUrl
import com.shibi.orbis.home.PinnedSite
import com.shibi.orbis.home.TOP_SITES_LIMIT
import org.json.JSONArray
import org.json.JSONObject
import com.shibi.orbis.engine.isWebUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where browsing preferences are kept. */
interface BrowserPrefs {
    /** A custom homepage (http/https), or null for the Orbis start page. */
    var homepage: String?

    /** Live search suggestions from the chosen search engine while typing (never in private tabs). */
    var searchSuggestions: Boolean

    /** Page text size in percent of the default. */
    var textZoomPercent: Int

    /** Allow pinch zoom on every page, even one that disables it. */
    var forceZoom: Boolean

    /** Start page tiles the user pinned, in order. */
    var pinnedSites: List<PinnedSite>

    /** Sites (hosts) the user removed from Top sites. */
    var hiddenSites: Set<String>
}

class SharedPreferencesBrowserPrefs(private val prefs: SharedPreferences) : BrowserPrefs {
    override var homepage: String?
        get() = prefs.getString(KEY_HOMEPAGE, null)?.takeIf { isWebUrl(it) }
        set(value) {
            prefs.edit { if (value == null) remove(KEY_HOMEPAGE) else putString(KEY_HOMEPAGE, value) }
        }

    override var searchSuggestions: Boolean
        get() = prefs.getBoolean(KEY_SUGGESTIONS, true)
        set(value) {
            prefs.edit { putBoolean(KEY_SUGGESTIONS, value) }
        }

    override var textZoomPercent: Int
        get() = clampTextZoom(prefs.getInt(KEY_TEXT_ZOOM, DEFAULT_TEXT_ZOOM))
        set(value) {
            prefs.edit { putInt(KEY_TEXT_ZOOM, clampTextZoom(value)) }
        }

    override var forceZoom: Boolean
        get() = prefs.getBoolean(KEY_FORCE_ZOOM, false)
        set(value) {
            prefs.edit { putBoolean(KEY_FORCE_ZOOM, value) }
        }

    override var pinnedSites: List<PinnedSite>
        get() =
            runCatching {
                val a = JSONArray(prefs.getString(KEY_PINNED, "[]"))
                (0 until a.length()).mapNotNull { i ->
                    val o = a.optJSONObject(i) ?: return@mapNotNull null
                    val url = o.optString("url")
                    if (isWebUrl(url)) PinnedSite(url, o.optString("title")) else null
                }
            }.getOrDefault(emptyList())
        set(value) {
            val a = JSONArray()
            value.forEach { a.put(JSONObject().put("url", it.url).put("title", it.title)) }
            prefs.edit { putString(KEY_PINNED, a.toString()) }
        }

    override var hiddenSites: Set<String>
        get() = prefs.getStringSet(KEY_HIDDEN, emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit { putStringSet(KEY_HIDDEN, value.toSet()) }
        }

    companion object {
        const val FILE_NAME = "browser_prefs"
        private const val KEY_PINNED = "pinned_sites"
        private const val KEY_HIDDEN = "hidden_sites"
        private const val KEY_FORCE_ZOOM = "force_zoom"
        private const val KEY_HOMEPAGE = "homepage"
        private const val KEY_SUGGESTIONS = "search_suggestions"
        private const val KEY_TEXT_ZOOM = "text_zoom_percent"
    }
}

const val DEFAULT_TEXT_ZOOM = 100
val TEXT_ZOOM_STEPS = listOf(50, 75, 90, 100, 110, 125, 150, 175, 200)

fun clampTextZoom(percent: Int): Int = percent.coerceIn(TEXT_ZOOM_STEPS.first(), TEXT_ZOOM_STEPS.last())

/** Observable browsing preferences. Main thread. */
class BrowserSettings(private val prefs: BrowserPrefs) {
    private val _homepage = MutableStateFlow(prefs.homepage)
    val homepage: StateFlow<String?> = _homepage.asStateFlow()

    private val _searchSuggestions = MutableStateFlow(prefs.searchSuggestions)
    val searchSuggestions: StateFlow<Boolean> = _searchSuggestions.asStateFlow()

    private val _textZoomPercent = MutableStateFlow(prefs.textZoomPercent)
    val textZoomPercent: StateFlow<Int> = _textZoomPercent.asStateFlow()

    /** Null resets to the Orbis start page. A non-web address is refused (returns false). */
    fun setHomepage(url: String?): Boolean {
        if (url != null && !isWebUrl(url)) return false
        prefs.homepage = url
        _homepage.value = url
        return true
    }

    fun setSearchSuggestions(on: Boolean) {
        prefs.searchSuggestions = on
        _searchSuggestions.value = on
    }

    private val _forceZoom = MutableStateFlow(prefs.forceZoom)
    val forceZoom: StateFlow<Boolean> = _forceZoom.asStateFlow()

    fun setForceZoom(on: Boolean) {
        prefs.forceZoom = on
        _forceZoom.value = on
    }

    private val _pinnedSites = MutableStateFlow(prefs.pinnedSites)
    val pinnedSites: StateFlow<List<PinnedSite>> = _pinnedSites.asStateFlow()

    private val _hiddenSites = MutableStateFlow(prefs.hiddenSites)
    val hiddenSites: StateFlow<Set<String>> = _hiddenSites.asStateFlow()

    /** Pins a start page tile (at the end). A pinned site is never hidden. Max [TOP_SITES_LIMIT] pins. */
    fun pinSite(url: String, title: String) {
        if (!isWebUrl(url)) return
        val host = hostOfUrl(url) ?: return
        val now = _pinnedSites.value.filterNot { hostOfUrl(it.url) == host } + PinnedSite(url, title)
        val pins = now.takeLast(TOP_SITES_LIMIT)
        prefs.pinnedSites = pins
        _pinnedSites.value = pins
        if (host in _hiddenSites.value) setHidden(_hiddenSites.value - host)
    }

    fun unpinSite(url: String) {
        val host = hostOfUrl(url)
        val pins = _pinnedSites.value.filterNot { it.url == url || (host != null && hostOfUrl(it.url) == host) }
        prefs.pinnedSites = pins
        _pinnedSites.value = pins
    }

    /** Removes a site from Top sites (and unpins it). It comes back only if pinned again. */
    fun removeSite(url: String) {
        val host = hostOfUrl(url) ?: return
        unpinSite(url)
        setHidden(_hiddenSites.value + host)
    }

    private fun setHidden(hosts: Set<String>) {
        prefs.hiddenSites = hosts
        _hiddenSites.value = hosts
    }

    fun setTextZoomPercent(percent: Int) {
        val v = clampTextZoom(percent)
        prefs.textZoomPercent = v
        _textZoomPercent.value = v
    }
}

/**
 * A typed homepage as a web address, or null if it is not one. "example.com" becomes https://example.com; text that
 * would turn into a search is refused (a homepage is an address, not a query).
 */
fun parseHomepageInput(raw: String): String? {
    val url = com.shibi.orbis.engine.normalizeAddressInput(raw) ?: return null
    if (com.shibi.orbis.home.isSearchResultPage(url) && !raw.trim().lowercase().startsWith("http")) return null
    return url.takeIf { isWebUrl(it) }
}
