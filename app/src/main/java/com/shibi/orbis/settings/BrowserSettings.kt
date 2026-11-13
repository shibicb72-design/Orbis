package com.shibi.orbis.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import com.shibi.orbis.engine.SitePolicy
import com.shibi.orbis.engine.hostOfUrl
import com.shibi.orbis.home.PinnedSite
import com.shibi.orbis.home.TOP_SITES_LIMIT
import com.shibi.orbis.passwords.NeverSavePrefs
import org.json.JSONArray
import org.json.JSONObject
import com.shibi.orbis.engine.isWebUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

    /** Offer "Save password?" after a sign-in (never in private tabs). */
    var offerSavePasswords: Boolean

    /** Reader view text size (sp), font and theme. */
    var readerTextSize: Int
    var readerSerif: Boolean
    var readerTheme: ReaderTheme

    var httpsOnly: HttpsOnlyMode
    /** Light/dark choice. Stored under the legacy key "app_theme" (see KEY_APP_THEME). */
    var darkMode: DarkMode

    /** The stored theme id, or null when none was ever chosen (reads as Orbis Teal). Unknown ids are kept as stored. */
    var themeId: String?

    /** Ask pages for their dark design whatever the app theme. */
    var darkWebsites: Boolean
    /** JavaScript default for every site, and the sites the user switched the other way. */
    var javaScript: Boolean
    var javaScriptOn: Set<String>
    var javaScriptOff: Set<String>
    /** Desktop site default, and the sites the user switched the other way. */
    var alwaysDesktop: Boolean
    var desktopOn: Set<String>
    var desktopOff: Set<String>
    /** Sites switched to desktop by the auto-desktop retry, and sites where the user turned that off for good. */
    var desktopAuto: Set<String>
    var desktopAutoDeclined: Set<String>

    /** Ask to open web links in the installed app that handles them. */
    var openLinksInApps: Boolean

    /** The address bar and its buttons at the bottom of the screen (easier to reach one-handed). */
    var toolbarBottom: Boolean

    /** Finished downloads (not from private tabs) are also copied to the phone's Download folder. */
    var copyToDownloadFolder: Boolean

    /** Darken light websites that have no dark design of their own. */
    var forceDark: Boolean

    /** Who opens a tapped magnet: link (Settings > Magnet links). */
    var magnetHandler: MagnetHandler

    /** The user confirmed a torrent while the full "peers can see your IP address" note was on the sheet. */
    var torrentIpNoteSeen: Boolean
}

/** [ORBIS]: the torrent confirm sheet (the default). [OTHER_APP]: as before torrents, another app is asked to open it. */
enum class MagnetHandler { ORBIS, OTHER_APP }

enum class ReaderTheme { LIGHT, SEPIA, DARK }

enum class HttpsOnlyMode { OFF, PRIVATE, ALL }

/** Light/dark choice. Entry names are the stored values of "app_theme": never rename them. */
enum class DarkMode { SYSTEM, LIGHT, DARK }

/** The id of the default theme (Orbis Teal). */
const val DEFAULT_THEME_ID = "orbis_teal"

/**
 * A per-site on/off choice over a default: [on] and [off] hold only the sites that differ from it. Private choices
 * live in memory only and are forgotten when private browsing ends.
 */
internal class SiteChoice(
    private val default: () -> Boolean,
    private var on: Set<String>,
    private var off: Set<String>,
    private val save: (on: Set<String>, off: Set<String>) -> Unit,
) {
    private val private = HashMap<String, Boolean>()

    fun valueFor(url: String, isPrivate: Boolean): Boolean {
        val host = hostOfUrl(url)?.lowercase() ?: return default()
        if (isPrivate) private[host]?.let { return it }
        return when (host) {
            in on -> true
            in off -> false
            else -> default()
        }
    }

    fun set(url: String, value: Boolean, isPrivate: Boolean) {
        val host = hostOfUrl(url)?.lowercase() ?: return
        if (isPrivate) {
            private[host] = value
            return
        }
        on = on - host
        off = off - host
        if (value != default()) {
            if (value) on = on + host else off = off + host
        }
        save(on, off)
    }

    fun forgetPrivate() = private.clear()
}

class SharedPreferencesBrowserPrefs(private val prefs: SharedPreferences) : BrowserPrefs, NeverSavePrefs {
    override var offerSavePasswords: Boolean
        get() = prefs.getBoolean(KEY_OFFER_SAVE, true)
        set(value) {
            prefs.edit { putBoolean(KEY_OFFER_SAVE, value) }
        }

    override var readerTextSize: Int
        get() = prefs.getInt(KEY_READER_SIZE, 18).coerceIn(12, 32)
        set(value) {
            prefs.edit { putInt(KEY_READER_SIZE, value.coerceIn(12, 32)) }
        }

    override var readerSerif: Boolean
        get() = prefs.getBoolean(KEY_READER_SERIF, true)
        set(value) {
            prefs.edit { putBoolean(KEY_READER_SERIF, value) }
        }

    override var readerTheme: ReaderTheme
        get() = ReaderTheme.entries.firstOrNull { it.name == prefs.getString(KEY_READER_THEME, null) } ?: ReaderTheme.LIGHT
        set(value) {
            prefs.edit { putString(KEY_READER_THEME, value.name) }
        }

    override var magnetHandler: MagnetHandler
        get() = MagnetHandler.entries.firstOrNull { it.name == prefs.getString(KEY_MAGNET_HANDLER, null) } ?: MagnetHandler.ORBIS
        set(value) {
            prefs.edit { putString(KEY_MAGNET_HANDLER, value.name) }
        }

    override var torrentIpNoteSeen: Boolean
        get() = prefs.getBoolean(KEY_TORRENT_IP_NOTE, false)
        set(value) {
            prefs.edit { putBoolean(KEY_TORRENT_IP_NOTE, value) }
        }

    override var httpsOnly: HttpsOnlyMode
        get() = HttpsOnlyMode.entries.firstOrNull { it.name == prefs.getString(KEY_HTTPS_ONLY, null) } ?: HttpsOnlyMode.ALL
        set(value) {
            prefs.edit { putString(KEY_HTTPS_ONLY, value.name) }
        }

    override var darkMode: DarkMode
        get() = DarkMode.entries.firstOrNull { it.name == prefs.getString(KEY_APP_THEME, null) } ?: DarkMode.SYSTEM
        set(value) {
            prefs.edit { putString(KEY_APP_THEME, value.name) }
        }

    override var themeId: String?
        get() = prefs.getString(KEY_THEME_ID, null)
        set(value) {
            prefs.edit { if (value == null) remove(KEY_THEME_ID) else putString(KEY_THEME_ID, value) }
        }

    override var darkWebsites: Boolean
        get() = prefs.getBoolean(KEY_DARK_WEBSITES, false)
        set(value) {
            prefs.edit { putBoolean(KEY_DARK_WEBSITES, value) }
        }

    override var javaScript: Boolean
        get() = prefs.getBoolean(KEY_JS, true)
        set(value) {
            prefs.edit { putBoolean(KEY_JS, value) }
        }

    override var javaScriptOn: Set<String>
        get() = prefs.getStringSet(KEY_JS_ON, emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit { putStringSet(KEY_JS_ON, value.toSet()) }
        }

    override var javaScriptOff: Set<String>
        get() = prefs.getStringSet(KEY_JS_OFF, emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit { putStringSet(KEY_JS_OFF, value.toSet()) }
        }

    override var alwaysDesktop: Boolean
        get() = prefs.getBoolean(KEY_ALWAYS_DESKTOP, false)
        set(value) {
            prefs.edit { putBoolean(KEY_ALWAYS_DESKTOP, value) }
        }

    override var desktopOn: Set<String>
        get() = prefs.getStringSet(KEY_DESKTOP_ON, emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit { putStringSet(KEY_DESKTOP_ON, value.toSet()) }
        }

    override var desktopOff: Set<String>
        get() = prefs.getStringSet(KEY_DESKTOP_OFF, emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit { putStringSet(KEY_DESKTOP_OFF, value.toSet()) }
        }

    override var desktopAuto: Set<String>
        get() = prefs.getStringSet(KEY_DESKTOP_AUTO, emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit { putStringSet(KEY_DESKTOP_AUTO, value.toSet()) }
        }

    override var desktopAutoDeclined: Set<String>
        get() = prefs.getStringSet(KEY_DESKTOP_AUTO_DECLINED, emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit { putStringSet(KEY_DESKTOP_AUTO_DECLINED, value.toSet()) }
        }

    override var forceDark: Boolean
        get() = prefs.getBoolean(KEY_FORCE_DARK, false)
        set(value) {
            prefs.edit { putBoolean(KEY_FORCE_DARK, value) }
        }

    override var copyToDownloadFolder: Boolean
        get() = prefs.getBoolean(KEY_COPY_DOWNLOADS, true)
        set(value) {
            prefs.edit { putBoolean(KEY_COPY_DOWNLOADS, value) }
        }

    override var toolbarBottom: Boolean
        get() = prefs.getBoolean(KEY_TOOLBAR_BOTTOM, false)
        set(value) {
            prefs.edit { putBoolean(KEY_TOOLBAR_BOTTOM, value) }
        }

    override var openLinksInApps: Boolean
        get() = prefs.getBoolean(KEY_OPEN_IN_APPS, true)
        set(value) {
            prefs.edit { putBoolean(KEY_OPEN_IN_APPS, value) }
        }

    override var neverSave: Set<String>
        get() = prefs.getStringSet(KEY_NEVER_SAVE, emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit { putStringSet(KEY_NEVER_SAVE, value.toSet()) }
        }

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
        private const val KEY_OFFER_SAVE = "offer_save_passwords"
        private const val KEY_READER_SIZE = "reader_text_size"
        private const val KEY_READER_SERIF = "reader_serif"
        private const val KEY_READER_THEME = "reader_theme"
        private const val KEY_NEVER_SAVE = "never_save_logins"
        private const val KEY_HTTPS_ONLY = "https_only"
        private const val KEY_OPEN_IN_APPS = "open_links_in_apps"
        private const val KEY_TOOLBAR_BOTTOM = "toolbar_bottom"
        private const val KEY_COPY_DOWNLOADS = "copy_to_download_folder"
        private const val KEY_FORCE_DARK = "force_dark"
        private const val KEY_MAGNET_HANDLER = "magnet_handler"
        private const val KEY_TORRENT_IP_NOTE = "torrent_ip_note_seen"
        // Legacy name from before themes existed: it holds the DarkMode. Never change the key or its values.
        private const val KEY_APP_THEME = "app_theme"
        private const val KEY_THEME_ID = "theme_id"
        private const val KEY_DARK_WEBSITES = "dark_websites"
        private const val KEY_JS = "javascript"
        private const val KEY_JS_ON = "javascript_on_sites"
        private const val KEY_JS_OFF = "javascript_off_sites"
        private const val KEY_ALWAYS_DESKTOP = "always_desktop"
        private const val KEY_DESKTOP_ON = "desktop_on_sites"
        private const val KEY_DESKTOP_OFF = "desktop_off_sites"
        private const val KEY_DESKTOP_AUTO = "desktop_auto_sites"
        private const val KEY_DESKTOP_AUTO_DECLINED = "desktop_auto_declined_sites"
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
class BrowserSettings(private val prefs: BrowserPrefs) : SitePolicy {
    private val _httpsOnly = MutableStateFlow(prefs.httpsOnly)
    val httpsOnly: StateFlow<HttpsOnlyMode> = _httpsOnly.asStateFlow()

    fun setHttpsOnly(mode: HttpsOnlyMode) {
        prefs.httpsOnly = mode
        _httpsOnly.value = mode
    }

    private val _darkMode = MutableStateFlow(prefs.darkMode)
    val darkMode: StateFlow<DarkMode> = _darkMode.asStateFlow()

    fun setDarkMode(mode: DarkMode) {
        prefs.darkMode = mode
        _darkMode.value = mode
    }

    private val _themeId = MutableStateFlow(prefs.themeId ?: DEFAULT_THEME_ID)

    /** The stored theme id (Orbis Teal when none). It may name a theme this build does not have: resolve it with AppThemes.byId. */
    val themeId: StateFlow<String> = _themeId.asStateFlow()

    fun setThemeId(id: String) {
        prefs.themeId = id
        _themeId.value = id
    }

    private val _darkWebsites = MutableStateFlow(prefs.darkWebsites)
    val darkWebsites: StateFlow<Boolean> = _darkWebsites.asStateFlow()

    fun setDarkWebsites(on: Boolean) {
        prefs.darkWebsites = on
        _darkWebsites.value = on
    }

    private val _javaScript = MutableStateFlow(prefs.javaScript)
    val javaScript: StateFlow<Boolean> = _javaScript.asStateFlow()

    /** The default for sites without a choice of their own; per-site choices keep their value. */
    fun setJavaScript(on: Boolean) {
        prefs.javaScript = on
        _javaScript.value = on
    }

    private val _alwaysDesktop = MutableStateFlow(prefs.alwaysDesktop)
    val alwaysDesktop: StateFlow<Boolean> = _alwaysDesktop.asStateFlow()

    fun setAlwaysDesktop(on: Boolean) {
        prefs.alwaysDesktop = on
        _alwaysDesktop.value = on
    }

    private val jsChoice =
        SiteChoice({ _javaScript.value }, prefs.javaScriptOn, prefs.javaScriptOff) { on, off ->
            prefs.javaScriptOn = on
            prefs.javaScriptOff = off
        }
    private val desktopChoice =
        SiteChoice({ _alwaysDesktop.value }, prefs.desktopOn, prefs.desktopOff) { on, off ->
            prefs.desktopOn = on
            prefs.desktopOff = off
        }

    override fun desktopFor(url: String, isPrivate: Boolean): Boolean = desktopChoice.valueFor(url, isPrivate)

    override fun alwaysDesktopOn(): Boolean = _alwaysDesktop.value

    // Auto desktop (see compat/AutoDesktopRetry). Normal tabs persist; private tabs keep their own sets in memory, read
    // the normal ones underneath (like SiteChoice), and lose theirs in forgetPrivate().
    private var desktopAuto = prefs.desktopAuto
    private var desktopAutoDeclined = prefs.desktopAutoDeclined
    private val privateDesktopAuto = HashSet<String>()
    private val privateDesktopAutoDeclined = HashSet<String>()

    private fun siteKey(url: String): String? = hostOfUrl(url)?.lowercase()

    /** The retry worked: Desktop site on for the site, and remembered as an automatic choice. */
    override fun rememberAutoDesktop(url: String, isPrivate: Boolean) {
        val host = siteKey(url) ?: return
        desktopChoice.set(url, true, isPrivate)
        if (isPrivate) {
            privateDesktopAuto += host
            privateDesktopAutoDeclined -= host
        } else {
            desktopAuto = desktopAuto + host
            desktopAutoDeclined = desktopAutoDeclined - host
            prefs.desktopAuto = desktopAuto
            prefs.desktopAutoDeclined = desktopAutoDeclined
        }
    }

    /** The user (or a failed retry) turned automatic desktop off for the site: desktop off, and never retried again. */
    override fun declineAutoDesktop(url: String, isPrivate: Boolean) {
        val host = siteKey(url) ?: return
        desktopChoice.set(url, false, isPrivate)
        if (isPrivate) {
            privateDesktopAuto -= host
            privateDesktopAutoDeclined += host
        } else {
            desktopAuto = desktopAuto - host
            desktopAutoDeclined = desktopAutoDeclined + host
            prefs.desktopAuto = desktopAuto
            prefs.desktopAutoDeclined = desktopAutoDeclined
        }
    }

    fun isAutoDesktop(url: String, isPrivate: Boolean): Boolean {
        val host = siteKey(url) ?: return false
        if (isPrivate) {
            if (host in privateDesktopAuto) return true
            if (host in privateDesktopAutoDeclined) return false
        }
        return host in desktopAuto
    }

    override fun isAutoDesktopDeclined(url: String, isPrivate: Boolean): Boolean {
        val host = siteKey(url) ?: return false
        if (isPrivate) {
            if (host in privateDesktopAutoDeclined) return true
            if (host in privateDesktopAuto) return false
        }
        return host in desktopAutoDeclined
    }

    private val _openLinksInApps = MutableStateFlow(prefs.openLinksInApps)
    val openLinksInApps: StateFlow<Boolean> = _openLinksInApps.asStateFlow()

    fun setOpenLinksInApps(on: Boolean) {
        prefs.openLinksInApps = on
        _openLinksInApps.value = on
    }

    override fun offerAppsForLinks(): Boolean = _openLinksInApps.value

    private val _magnetHandler = MutableStateFlow(prefs.magnetHandler)
    val magnetHandler: StateFlow<MagnetHandler> = _magnetHandler.asStateFlow()

    fun setMagnetHandler(handler: MagnetHandler) {
        prefs.magnetHandler = handler
        _magnetHandler.value = handler
    }

    override fun magnetsToOrbis(): Boolean = _magnetHandler.value == MagnetHandler.ORBIS

    private val _torrentIpNoteSeen = MutableStateFlow(prefs.torrentIpNoteSeen)
    val torrentIpNoteSeen: StateFlow<Boolean> = _torrentIpNoteSeen.asStateFlow()

    /** The full IP note was on the sheet when the user tapped Download: from now on the one-line reminder. */
    fun markTorrentIpNoteSeen() {
        if (_torrentIpNoteSeen.value) return
        prefs.torrentIpNoteSeen = true
        _torrentIpNoteSeen.value = true
    }

    /** Settings > Torrents: the next confirm sheet shows the full IP note again. */
    fun resetTorrentIpNote() {
        prefs.torrentIpNoteSeen = false
        _torrentIpNoteSeen.value = false
    }

    private val _forceDark = MutableStateFlow(prefs.forceDark)
    val forceDark: StateFlow<Boolean> = _forceDark.asStateFlow()

    fun setForceDark(on: Boolean) {
        prefs.forceDark = on
        _forceDark.value = on
    }

    private val _copyToDownloadFolder = MutableStateFlow(prefs.copyToDownloadFolder)
    val copyToDownloadFolder: StateFlow<Boolean> = _copyToDownloadFolder.asStateFlow()

    fun setCopyToDownloadFolder(on: Boolean) {
        prefs.copyToDownloadFolder = on
        _copyToDownloadFolder.value = on
    }

    private val _toolbarBottom = MutableStateFlow(prefs.toolbarBottom)
    val toolbarBottom: StateFlow<Boolean> = _toolbarBottom.asStateFlow()

    fun setToolbarBottom(on: Boolean) {
        prefs.toolbarBottom = on
        _toolbarBottom.value = on
    }

    override fun javaScriptFor(url: String, isPrivate: Boolean): Boolean = jsChoice.valueFor(url, isPrivate)

    /** Remembers the page menu's Desktop site choice for the page's site. */
    /**
     * Turning it off on a site that auto desktop switched on also stops future retries there ([declineAutoDesktop]);
     * turning it on clears that.
     */
    fun rememberDesktop(url: String, on: Boolean, isPrivate: Boolean) {
        val host = siteKey(url) ?: return
        if (!on && isAutoDesktop(url, isPrivate)) {
            declineAutoDesktop(url, isPrivate)
            return
        }
        if (on) {
            if (isPrivate) {
                privateDesktopAutoDeclined -= host
            } else if (host in desktopAutoDeclined) {
                desktopAutoDeclined = desktopAutoDeclined - host
                prefs.desktopAutoDeclined = desktopAutoDeclined
            }
        }
        desktopChoice.set(url, on, isPrivate)
    }

    /** Remembers the page menu's JavaScript choice for the page's site. */
    fun rememberJavaScript(url: String, on: Boolean, isPrivate: Boolean) = jsChoice.set(url, on, isPrivate)

    /** Private browsing ended: its per-site choices go. */
    fun forgetPrivate() {
        jsChoice.forgetPrivate()
        desktopChoice.forgetPrivate()
        privateDesktopAuto.clear()
        privateDesktopAutoDeclined.clear()
    }

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

    private val _offerSavePasswords = MutableStateFlow(prefs.offerSavePasswords)
    val offerSavePasswords: StateFlow<Boolean> = _offerSavePasswords.asStateFlow()

    fun setOfferSavePasswords(on: Boolean) {
        prefs.offerSavePasswords = on
        _offerSavePasswords.value = on
    }

    private val _readerTextSize = MutableStateFlow(prefs.readerTextSize)
    val readerTextSize: StateFlow<Int> = _readerTextSize.asStateFlow()
    private val _readerSerif = MutableStateFlow(prefs.readerSerif)
    val readerSerif: StateFlow<Boolean> = _readerSerif.asStateFlow()
    private val _readerTheme = MutableStateFlow(prefs.readerTheme)
    val readerTheme: StateFlow<ReaderTheme> = _readerTheme.asStateFlow()

    fun setReaderTextSize(sp: Int) {
        val v = sp.coerceIn(12, 32)
        prefs.readerTextSize = v
        _readerTextSize.value = v
    }

    fun setReaderSerif(on: Boolean) {
        prefs.readerSerif = on
        _readerSerif.value = on
    }

    fun setReaderTheme(t: ReaderTheme) {
        prefs.readerTheme = t
        _readerTheme.value = t
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

/**
 * What pages are told about the colour scheme: dark, light, or null to follow the system. It follows only the
 * light/dark choice and "dark websites", never the theme, so a theme can never restyle a web page.
 */
fun BrowserSettings.websiteColorScheme(): Flow<Boolean?> =
    combine(darkMode, darkWebsites) { mode, darkSites ->
        when {
            darkSites -> true
            mode == DarkMode.LIGHT -> false
            mode == DarkMode.DARK -> true
            else -> null
        }
    }
