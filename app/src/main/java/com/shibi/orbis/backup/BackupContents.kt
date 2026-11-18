package com.shibi.orbis.backup

import com.shibi.orbis.addons.AddonCatalog
import com.shibi.orbis.bookmarks.MAX_BOOKMARKS
import com.shibi.orbis.bookmarks.cleanFolderName
import com.shibi.orbis.clear.SharedPreferencesClearOnExitPrefs
import com.shibi.orbis.engine.SharedPreferencesSearchEnginePrefs
import com.shibi.orbis.engine.SitePermissionCodec
import com.shibi.orbis.engine.SitePermissionDecision
import com.shibi.orbis.engine.cleanStoredTitle
import com.shibi.orbis.engine.hostOfUrl
import com.shibi.orbis.engine.isStorableWebUrl
import com.shibi.orbis.engine.isWebUrl
import com.shibi.orbis.engine.validateCustomEngine
import com.shibi.orbis.history.HistoryEntry
import com.shibi.orbis.history.MAX_HISTORY_ENTRIES
import com.shibi.orbis.history.MAX_VISIT_COUNT
import com.shibi.orbis.home.PinnedSite
import com.shibi.orbis.home.TOP_SITES_LIMIT
import com.shibi.orbis.passwords.ImportedLogin
import com.shibi.orbis.passwords.loginOrigin
import com.shibi.orbis.privacy.SharedPreferencesPrivacyPrefs
import com.shibi.orbis.settings.DarkMode
import com.shibi.orbis.settings.DohProvider
import com.shibi.orbis.settings.HttpsOnlyMode
import com.shibi.orbis.settings.MagnetHandler
import com.shibi.orbis.settings.ReaderTheme
import com.shibi.orbis.settings.SharedPreferencesBrowserPrefs
import com.shibi.orbis.settings.SharedPreferencesDohPrefs
import com.shibi.orbis.settings.SharedPreferencesToolbarPrefs
import com.shibi.orbis.settings.TEXT_ZOOM_STEPS
import com.shibi.orbis.settings.ToolbarButton
import com.shibi.orbis.settings.mergeMenuHidden
import com.shibi.orbis.settings.mergeMenuOrder
import com.shibi.orbis.settings.CUSTOMIZABLE_MENU_ACTIONS
import com.shibi.orbis.tabs.MAX_TABS
import com.shibi.orbis.tabs.PersistedTab
import com.shibi.orbis.tabs.TabGroup
import com.shibi.orbis.tabs.TabGroupColor
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/*
 * What goes into a backup ([BackupPayload] sections) and how each section is read back. Pure: no Android, no store, no
 * clock. Every section is a JSON object with `"v":1`; each decoder checks every entry with the same rules the app's own
 * store or prefs class uses, and counts what it drops as skipped. [BackupManager] collects and applies the sections.
 *
 * Never in any section: cookies and sign-ins, site data, cache, downloads and torrents, private tabs, tab saved state,
 * privacy stats, the error log, thumbnails, Cast pins, the stored backup password, add-on data and prefs, and anything
 * of uBlock Origin beyond whether it is installed.
 */

/** Section names, in the order a restore applies them (settings first: per-site choices depend on their defaults). */
object BackupSection {
    const val SETTINGS = "settings"
    const val SITE_PERMISSIONS = "sitePermissions"
    const val BOOKMARKS = "bookmarks"
    const val PASSWORDS = "passwords"
    const val TABS = "tabs"
    const val HISTORY = "history"
    const val ADDONS = "addons"

    val ALL: List<String> = listOf(SETTINGS, SITE_PERMISSIONS, BOOKMARKS, PASSWORDS, TABS, HISTORY, ADDONS)
}

/** Every section this version writes and reads. */
const val SECTION_VERSION = 1

/** What the user ticked. Open tabs are in by default; browsing history only when asked (Shibi's ruling). */
data class BackupOptions(val includeTabs: Boolean = true, val includeHistory: Boolean = false)

/** One section read back. [skipped] counts the entries (or keys) that were unknown or failed the checks. */
sealed interface SectionRead<out T> {
    data class Ok<T>(val value: T, val skipped: Int) : SectionRead<T>

    /** Written by a newer Orbis (`v` above [SECTION_VERSION]): not applied, reported. */
    data class Newer(val version: Int) : SectionRead<Nothing>

    /** Not this section's shape at all. */
    data object Damaged : SectionRead<Nothing>
}

private inline fun <T> readSection(o: JSONObject, body: (JSONObject) -> SectionRead.Ok<T>): SectionRead<T> =
    try {
        val v = o.opt("v") as? Int
        when {
            v == SECTION_VERSION -> body(o)
            v != null && v > SECTION_VERSION -> SectionRead.Newer(v)
            else -> SectionRead.Damaged
        }
    } catch (_: JSONException) {
        SectionRead.Damaged
    } catch (_: ClassCastException) {
        SectionRead.Damaged
    }

private fun section(): JSONObject = JSONObject().put("v", SECTION_VERSION)

/** org.json's optString turns JSON null into "null" on some implementations; this does not. */
private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")

private inline fun JSONArray.objects(action: (JSONObject?) -> Unit) {
    for (i in 0 until length()) action(optJSONObject(i))
}

// ---- bookmarks ---------------------------------------------------------------------------------------------

/** A bookmark without its local id. [createdAt] is epoch milliseconds; [folder] is empty for none. */
data class BackupBookmark(val url: String, val title: String, val createdAt: Long, val folder: String = "")

object BookmarksSection {
    fun encode(items: List<BackupBookmark>): JSONObject {
        val a = JSONArray()
        for (b in items) {
            a.put(
                JSONObject().put("url", b.url).put("title", b.title).put("created", b.createdAt)
                    .apply { if (b.folder.isNotEmpty()) put("folder", b.folder) },
            )
        }
        return section().put("items", a)
    }

    /** The BookmarkCodec rules: storable web addresses only, the first of a repeated address wins, titles and folders cleaned. */
    fun decode(o: JSONObject): SectionRead<List<BackupBookmark>> =
        readSection(o) { s ->
            val a = s.getJSONArray("items")
            val out = ArrayList<BackupBookmark>()
            val urls = HashSet<String>()
            a.objects { e ->
                val url = e?.str("url").orEmpty()
                if (e == null || out.size >= MAX_BOOKMARKS || !isStorableWebUrl(url) || !urls.add(url)) return@objects
                val created = if (e.isNull("created")) 0L else e.optLong("created", 0L)
                out += BackupBookmark(url, cleanStoredTitle(e.str("title")).ifEmpty { url }, created.coerceAtLeast(0L), cleanFolderName(e.str("folder")))
            }
            SectionRead.Ok(out, a.length() - out.size)
        }
}

// ---- passwords ---------------------------------------------------------------------------------------------

object PasswordsSection {
    fun encode(items: List<ImportedLogin>): JSONObject {
        val a = JSONArray()
        for (l in items) {
            a.put(
                JSONObject().put("origin", l.origin).put("username", l.username).put("password", l.password)
                    .put("formActionOrigin", l.formActionOrigin).put("httpRealm", l.httpRealm),
            )
        }
        return section().put("items", a)
    }

    /** The CSV import rules: a web origin and a non-empty password, or the entry is skipped. */
    fun decode(o: JSONObject): SectionRead<List<ImportedLogin>> =
        readSection(o) { s ->
            val a = s.getJSONArray("items")
            val out = ArrayList<ImportedLogin>()
            a.objects { e ->
                if (e == null) return@objects
                val origin = loginOrigin(e.str("origin")) ?: return@objects
                val password = e.str("password")
                if (password.isEmpty()) return@objects
                out += ImportedLogin(origin, e.str("username"), password, loginOrigin(e.str("formActionOrigin")).orEmpty(), e.str("httpRealm"))
            }
            SectionRead.Ok(out, a.length() - out.size)
        }
}

// ---- settings ----------------------------------------------------------------------------------------------

/** The value of a setting that is explicitly unset: the start page as homepage, no extra toolbar button. JSON null. */
object NoValue {
    override fun toString() = "NoValue"
}

/** An added search engine as backed up: its id is local to the phone, so only name and address go. */
data class BackupSearchEngine(val name: String, val template: String)

/**
 * How one allow-listed setting is checked. [parse] takes the JSON value and returns the value as the app uses it, or
 * null when it is not valid; [toJson] is the reverse. Values are Boolean, Int, String, [NoValue], Set<String>,
 * List<String>, List<PinnedSite> or List<BackupSearchEngine>.
 */
abstract class SettingType {
    abstract fun parse(raw: Any?): Any?

    open fun toJson(value: Any): Any =
        when (value) {
            NoValue -> JSONObject.NULL
            is Set<*> -> JSONArray(value.map { it.toString() }.sorted())
            is List<*> -> JSONArray(value.map { it.toString() })
            else -> value
        }

    object Bool : SettingType() {
        override fun parse(raw: Any?): Any? = raw as? Boolean
    }

    class IntIn(private val range: IntRange) : SettingType() {
        override fun parse(raw: Any?): Any? =
            when (raw) {
                is Int -> raw
                is Long -> raw.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
                else -> null
            }?.takeIf { it in range }
    }

    class OneOf(private val names: List<String>) : SettingType() {
        override fun parse(raw: Any?): Any? = (raw as? String)?.takeIf { it in names }
    }

    /** A string through [check]; JSON null is [NoValue] when [nullable]. */
    class Text(private val nullable: Boolean = false, private val check: (String) -> String?) : SettingType() {
        override fun parse(raw: Any?): Any? =
            when {
                raw == JSONObject.NULL -> if (nullable) NoValue else null
                raw is String -> check(raw)
                else -> null
            }
    }

    /** A JSON array of strings. [clean] maps the whole list the way the prefs class does (and may drop entries). */
    class Strings(private val asSet: Boolean, private val clean: (List<String>) -> Collection<String>) : SettingType() {
        override fun parse(raw: Any?): Any? {
            val a = raw as? JSONArray ?: return null
            val items = (0 until a.length()).map { a.get(it) as? String ?: return null }
            val cleaned = clean(items)
            return if (asSet) cleaned.toSet() else cleaned.toList()
        }
    }

    object Pins : SettingType() {
        override fun parse(raw: Any?): Any? {
            val a = raw as? JSONArray ?: return null
            val out = ArrayList<PinnedSite>()
            for (i in 0 until a.length()) {
                val o = a.get(i) as? JSONObject ?: return null
                val url = o.str("url")
                // The prefs getter keeps web addresses only.
                if (isWebUrl(url)) out += PinnedSite(url, o.str("title"))
            }
            return out.takeLast(TOP_SITES_LIMIT)
        }

        override fun toJson(value: Any): Any =
            JSONArray().apply {
                @Suppress("UNCHECKED_CAST")
                for (p in value as List<PinnedSite>) put(JSONObject().put("url", p.url).put("title", p.title))
            }
    }

    object Engines : SettingType() {
        override fun parse(raw: Any?): Any? {
            val a = raw as? JSONArray ?: return null
            val out = ArrayList<BackupSearchEngine>()
            val checked = ArrayList<com.shibi.orbis.engine.SearchProvider.Custom>()
            for (i in 0 until a.length()) {
                val o = a.get(i) as? JSONObject ?: return null
                val name = o.str("name").trim()
                val template = o.str("template").trim()
                // The rules an added engine passes (decodeCustomEngines drops the rest the same way).
                if (validateCustomEngine(name, template, checked) != null) continue
                checked += com.shibi.orbis.engine.SearchProvider.Custom("backup:$i", name, template)
                out += BackupSearchEngine(name, template)
            }
            return out
        }

        override fun toJson(value: Any): Any =
            JSONArray().apply {
                @Suppress("UNCHECKED_CAST")
                for (e in value as List<BackupSearchEngine>) put(JSONObject().put("name", e.name).put("template", e.template))
            }
    }
}

private fun names(values: List<Enum<*>>) = values.map { it.name }

private val CUSTOMIZABLE_NAMES = CUSTOMIZABLE_MENU_ACTIONS.map { it.name }.toSet()

/**
 * The only settings a backup holds, per preference file, with how each value is checked. Nothing else of any file is
 * written or read back (never a raw dump). Left out on purpose: `browser_prefs` hidden_sites (no way to un-hide a site
 * on restore), torrent_ip_note_seen (a one-time notice); the per-site JavaScript and desktop lists (in the
 * sitePermissions section instead); search_prefs custom_next (a local counter); addon_prefs and ubo_prefs entirely;
 * torrent prefs.
 */
object SettingsAllowList {
    val files: Map<String, Map<String, SettingType>> =
        linkedMapOf(
            SharedPreferencesBrowserPrefs.FILE_NAME to
                linkedMapOf(
                    "app_theme" to SettingType.OneOf(names(DarkMode.entries)),
                    "theme_id" to SettingType.Text { id -> id.takeIf { it.isNotEmpty() && it.length <= 64 && it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '_' } } },
                    "dark_websites" to SettingType.Bool,
                    "force_dark" to SettingType.Bool,
                    "homepage" to SettingType.Text(nullable = true) { it.takeIf(::isWebUrl) },
                    "search_suggestions" to SettingType.Bool,
                    "text_zoom_percent" to SettingType.IntIn(TEXT_ZOOM_STEPS.first()..TEXT_ZOOM_STEPS.last()),
                    "force_zoom" to SettingType.Bool,
                    "https_only" to SettingType.OneOf(names(HttpsOnlyMode.entries)),
                    "javascript" to SettingType.Bool,
                    "always_desktop" to SettingType.Bool,
                    "open_links_in_apps" to SettingType.Bool,
                    "toolbar_bottom" to SettingType.Bool,
                    "copy_to_download_folder" to SettingType.Bool,
                    "magnet_handler" to SettingType.OneOf(names(MagnetHandler.entries)),
                    "offer_save_passwords" to SettingType.Bool,
                    "never_save_logins" to SettingType.Strings(asSet = true) { list -> list.mapNotNull(::loginOrigin) },
                    "reader_text_size" to SettingType.IntIn(12..32),
                    "reader_serif" to SettingType.Bool,
                    "reader_theme" to SettingType.OneOf(names(ReaderTheme.entries)),
                    "pinned_sites" to SettingType.Pins,
                ),
            SharedPreferencesSearchEnginePrefs.FILE_NAME to
                linkedMapOf(
                    // Before "engine": the selected engine may be one of them.
                    "custom_engines" to SettingType.Engines,
                    // A built-in engine's name (GOOGLE), or the name of an added engine. No added engine can carry a
                    // built-in name: validateCustomEngine refuses the built-in labels, which equal the names but for case.
                    "engine" to SettingType.Text { it.trim().takeIf { t -> t.isNotEmpty() && t.length <= 64 } },
                ),
            SharedPreferencesToolbarPrefs.FILE_NAME to
                linkedMapOf(
                    // Customizable entries only, in order; mergeMenuOrder drops unknown names and puts Settings last.
                    "menu_order" to SettingType.Strings(asSet = false) { list -> mergeMenuOrder(list).map { it.name }.filter { it in CUSTOMIZABLE_NAMES } },
                    "menu_hidden" to SettingType.Strings(asSet = true) { list -> mergeMenuHidden(list.toSet()).map { it.name } },
                    "toolbar_button" to SettingType.Text(nullable = true) { it.takeIf { n -> ToolbarButton.entries.any { b -> b.name == n } } },
                ),
            SharedPreferencesPrivacyPrefs.FILE_NAME to linkedMapOf("lock_private_tabs" to SettingType.Bool),
            SharedPreferencesDohPrefs.FILE_NAME to
                linkedMapOf(
                    "enabled" to SettingType.Bool,
                    "provider" to SettingType.OneOf(names(DohProvider.entries)),
                    "custom_url" to SettingType.Text { com.shibi.orbis.settings.validDohAddress(it) },
                ),
            SharedPreferencesClearOnExitPrefs.FILE_NAME to
                linkedMapOf(
                    "enabled" to SettingType.Bool,
                    "exceptions" to SettingType.Strings(asSet = true) { list -> com.shibi.orbis.clear.cleanExceptions(list) },
                ),
        )

}

/** Settings values per file and key, as [SettingType.parse] returns them. */
typealias SettingsValues = Map<String, Map<String, Any>>

object SettingsSection {
    /** Writes only the allow-listed keys of [values], each only when it passes its check. */
    fun encode(values: SettingsValues): JSONObject {
        val files = JSONObject()
        for ((file, keys) in SettingsAllowList.files) {
            val given = values[file] ?: continue
            val out = JSONObject()
            for ((key, type) in keys) {
                val v = given[key] ?: continue
                val json = type.toJson(v)
                if (type.parse(json) != null) out.put(key, json)
            }
            if (out.length() > 0) files.put(file, out)
        }
        return section().put("files", files)
    }

    /** Allow-listed keys with valid values. An unknown file, an unknown key and a value that fails its check are skipped. */
    fun decode(o: JSONObject): SectionRead<SettingsValues> =
        readSection(o) { s ->
            val files = s.getJSONObject("files")
            var skipped = 0
            val out = LinkedHashMap<String, Map<String, Any>>()
            for (file in files.keys()) {
                val allowed = SettingsAllowList.files[file]
                val keys = files.optJSONObject(file)
                if (allowed == null || keys == null) {
                    skipped += keys?.length() ?: 1
                    continue
                }
                val values = LinkedHashMap<String, Any>()
                for (key in keys.keys()) {
                    val v = allowed[key]?.parse(keys.get(key))
                    if (v == null) skipped++ else values[key] = v
                }
                // In allow-list order, so a restore applies them in that order.
                out[file] = allowed.keys.filter { it in values }.associateWith { values.getValue(it) }
            }
            SectionRead.Ok(SettingsAllowList.files.keys.filter { it in out }.associateWith { out.getValue(it) }, skipped)
        }
}

// ---- site permissions --------------------------------------------------------------------------------------

/** A site's desktop-site choice. [AUTO]: switched on by the auto-desktop retry; [DECLINED]: the user turned that off for good. */
enum class DesktopChoice { ON, OFF, AUTO, DECLINED }

/** Camera, microphone and location answers, and the per-site JavaScript and desktop choices (host to choice). */
data class SitePermissionsBackup(
    val decisions: List<SitePermissionDecision> = emptyList(),
    val javaScript: Map<String, Boolean> = emptyMap(),
    val desktop: Map<String, DesktopChoice> = emptyMap(),
)

/** [raw] as a host the per-site lists store (lower case, no port), or null. */
internal fun siteHost(raw: String): String? {
    if (raw.isEmpty() || raw.length > 253) return null
    if (!raw.all { it in 'a'..'z' || it in '0'..'9' || it in ".-_:[]" }) return null
    return raw.takeIf { hostOfUrl("https://$it/") == it }
}

/** The address the per-site setters take for [host]. */
internal fun siteUrl(host: String) = "https://$host/"

object SitePermissionsSection {
    fun encode(value: SitePermissionsBackup): JSONObject {
        val js = JSONArray()
        for ((host, on) in value.javaScript.toSortedMap()) js.put(JSONObject().put("host", host).put("on", on))
        val desktop = JSONArray()
        for ((host, choice) in value.desktop.toSortedMap()) desktop.put(JSONObject().put("host", host).put("choice", choice.name))
        return section()
            // The store's own codec, so the entries are exactly what site_permissions.json holds.
            .put("decisions", JSONObject(SitePermissionCodec.encode(value.decisions)).getJSONArray("entries"))
            .put("javascript", js)
            .put("desktop", desktop)
    }

    fun decode(o: JSONObject): SectionRead<SitePermissionsBackup> =
        readSection(o) { s ->
            var skipped = 0
            val raw = s.optJSONArray("decisions") ?: JSONArray()
            // SitePermissionCodec's checks: known kind, origins that survive siteOriginOf unchanged.
            val decisions = SitePermissionCodec.decode(JSONObject().put("version", 1).put("entries", raw).toString())
            skipped += raw.length() - decisions.size
            val js = LinkedHashMap<String, Boolean>()
            val jsRaw = s.optJSONArray("javascript") ?: JSONArray()
            jsRaw.objects { e ->
                val host = e?.let { siteHost(it.str("host")) }
                val on = e?.opt("on") as? Boolean
                if (host == null || on == null || host in js) skipped++ else js[host] = on
            }
            val desktop = LinkedHashMap<String, DesktopChoice>()
            val dRaw = s.optJSONArray("desktop") ?: JSONArray()
            dRaw.objects { e ->
                val host = e?.let { siteHost(it.str("host")) }
                val choice = e?.let { DesktopChoice.entries.firstOrNull { c -> c.name == it.str("choice") } }
                if (host == null || choice == null || host in desktop) skipped++ else desktop[host] = choice
            }
            SectionRead.Ok(SitePermissionsBackup(decisions, js, desktop), skipped)
        }
}

// ---- tabs --------------------------------------------------------------------------------------------------

/** Normal tabs: address, title, desktop flag and group. Never a private tab, never the engine's saved state. */
data class TabsBackup(val tabs: List<PersistedTab>, val groups: List<TabGroup>)

object TabsSection {
    fun encode(value: TabsBackup): JSONObject {
        val tabs = JSONArray()
        for (t in value.tabs) {
            tabs.put(
                JSONObject().put("url", t.url).put("title", t.title).put("desktop", t.desktop)
                    .apply { t.groupId?.let { put("group", it) } },
            )
        }
        val groups = JSONArray()
        for (g in value.groups) {
            groups.put(JSONObject().put("id", g.id).put("name", g.name).put("color", g.color.name).put("collapsed", g.collapsed))
        }
        return section().put("tabs", tabs).put("groups", groups)
    }

    /**
     * Web addresses only (TabCodec's rule), at most [MAX_TABS]. A group keeps the id from the file only to link its
     * tabs; the tab manager gives fresh ids and checks the names. An unknown colour is grey.
     */
    fun decode(o: JSONObject): SectionRead<TabsBackup> =
        readSection(o) { s ->
            val groups = ArrayList<TabGroup>()
            (s.optJSONArray("groups") ?: JSONArray()).objects { e ->
                val id = if (e == null || e.isNull("id")) -1L else e.optLong("id", -1L)
                if (e == null || id <= 0 || groups.any { it.id == id }) return@objects
                groups += TabGroup(id, e.str("name"), TabGroupColor.fromStored(e.str("color")), e.optBoolean("collapsed", false))
            }
            val a = s.getJSONArray("tabs")
            val tabs = ArrayList<PersistedTab>()
            a.objects { e ->
                val url = e?.str("url").orEmpty()
                if (e == null || tabs.size >= MAX_TABS || !isWebUrl(url)) return@objects
                val group = if (e.isNull("group")) null else e.optLong("group", -1L).takeIf { g -> groups.any { it.id == g } }
                tabs += PersistedTab(tabs.size + 1L, url, e.str("title"), e.optBoolean("desktop", false), null, group)
            }
            SectionRead.Ok(TabsBackup(tabs, groups), a.length() - tabs.size)
        }
}

// ---- history -----------------------------------------------------------------------------------------------

object HistorySection {
    fun encode(items: List<HistoryEntry>): JSONObject {
        val a = JSONArray()
        for (e in items) a.put(JSONObject().put("url", e.url).put("title", e.title).put("visited", e.visitedAt).put("visits", e.visits))
        return section().put("items", a)
    }

    /** HistoryCodec's rules: storable web addresses, the first of a repeated address wins, at most [MAX_HISTORY_ENTRIES]. */
    fun decode(o: JSONObject): SectionRead<List<HistoryEntry>> =
        readSection(o) { s ->
            val a = s.getJSONArray("items")
            val out = ArrayList<HistoryEntry>()
            val urls = HashSet<String>()
            a.objects { e ->
                val url = e?.str("url").orEmpty()
                if (e == null || out.size >= MAX_HISTORY_ENTRIES || !isStorableWebUrl(url) || !urls.add(url)) return@objects
                val visited = if (e.isNull("visited")) 0L else e.optLong("visited", 0L)
                val visits = if (e.isNull("visits")) 1 else e.optInt("visits", 1).coerceIn(1, MAX_VISIT_COUNT)
                out += HistoryEntry(url, cleanStoredTitle(e.str("title")), visited.coerceAtLeast(0L), visits)
            }
            SectionRead.Ok(out, a.length() - out.size)
        }
}

// ---- add-ons -----------------------------------------------------------------------------------------------

/** An installed curated add-on and whether it was on. Never its data. */
data class BackupAddon(val id: String, val enabled: Boolean)

/** The curated add-ons, and whether uBlock Origin was installed (nothing else of it). */
data class AddonsBackup(val addons: List<BackupAddon>, val ublockOrigin: Boolean)

object AddonsSection {
    fun encode(value: AddonsBackup): JSONObject {
        val a = JSONArray()
        for (addon in value.addons) a.put(JSONObject().put("id", addon.id).put("enabled", addon.enabled))
        return section().put("items", a).put("ublockOrigin", value.ublockOrigin)
    }

    /** Only ids in [AddonCatalog]; anything else is skipped. */
    fun decode(o: JSONObject): SectionRead<AddonsBackup> =
        readSection(o) { s ->
            val a = s.optJSONArray("items") ?: JSONArray()
            val out = ArrayList<BackupAddon>()
            a.objects { e ->
                val id = e?.str("id").orEmpty()
                if (e == null || AddonCatalog.find(id) == null || out.any { it.id == id }) return@objects
                out += BackupAddon(id, e.optBoolean("enabled", true))
            }
            SectionRead.Ok(AddonsBackup(out, s.optBoolean("ublockOrigin", false)), a.length() - out.size)
        }
}
