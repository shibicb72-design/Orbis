package com.shibi.orbis.settings

import android.content.SharedPreferences
import androidx.core.content.edit
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

    companion object {
        const val FILE_NAME = "browser_prefs"
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
