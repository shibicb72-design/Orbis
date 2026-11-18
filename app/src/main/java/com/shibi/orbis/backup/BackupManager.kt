package com.shibi.orbis.backup

import com.shibi.orbis.addons.AddonState
import com.shibi.orbis.bookmarks.BookmarkManager
import com.shibi.orbis.bookmarks.ParsedBookmark
import com.shibi.orbis.bookmarks.ParsedBookmarks
import com.shibi.orbis.clear.ClearOnExitPrefs
import com.shibi.orbis.clear.cleanExceptions
import com.shibi.orbis.engine.SearchEngine
import com.shibi.orbis.engine.SearchEngineSettings
import com.shibi.orbis.engine.SearchProvider
import com.shibi.orbis.engine.SitePermissionDecision
import com.shibi.orbis.engine.SitePermissionDecisions
import com.shibi.orbis.engine.isWebUrl
import com.shibi.orbis.history.HistoryManager
import com.shibi.orbis.home.PinnedSite
import com.shibi.orbis.passwords.ImportedLogin
import com.shibi.orbis.passwords.LoginCsv
import com.shibi.orbis.passwords.LoginManager
import com.shibi.orbis.passwords.SavedLogin
import com.shibi.orbis.privacy.PrivacyPrefs
import com.shibi.orbis.settings.BrowserPrefs
import com.shibi.orbis.settings.BrowserSettings
import com.shibi.orbis.settings.CUSTOMIZABLE_MENU_ACTIONS
import com.shibi.orbis.settings.DarkMode
import com.shibi.orbis.settings.DohProvider
import com.shibi.orbis.settings.DohSettings
import com.shibi.orbis.settings.HttpsOnlyMode
import com.shibi.orbis.settings.MagnetHandler
import com.shibi.orbis.settings.ReaderTheme
import com.shibi.orbis.settings.ToolbarButton
import com.shibi.orbis.settings.ToolbarSettings
import com.shibi.orbis.tabs.TabManager
import com.shibi.orbis.torrent.TorrentSettings
import com.shibi.orbis.torrent.TorrentSettingsStore
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The live objects a backup reads and a restore writes. All of them are main-thread objects. [browserPrefs] is read
 * only (the per-site JavaScript and desktop lists, which [BrowserSettings] does not list); every write goes through
 * [browserSettings], so its flows follow.
 */
class BackupSources(
    val bookmarks: BookmarkManager,
    val logins: LoginManager,
    val browserSettings: BrowserSettings,
    val browserPrefs: BrowserPrefs,
    val searchEngine: SearchEngineSettings,
    val toolbar: ToolbarSettings,
    val doh: DohSettings,
    val clearOnExit: ClearOnExitPrefs,
    val privacy: PrivacyPrefs,
    val sitePermissions: SitePermissionDecisions,
    val tabs: TabManager,
    val history: HistoryManager,
    val torrentSettings: TorrentSettingsStore,
    /** AddonManager.states. */
    val addonStates: () -> Map<String, AddonState>,
    /** uBlock Origin is installed. */
    val ublockInstalled: () -> Boolean,
)

/** Per section: entries added, settings or permissions replaced, and entries skipped (already there, unknown or invalid). */
data class SectionCounts(val added: Int = 0, val replaced: Int = 0, val skipped: Int = 0)

/** Why a whole section was not applied. */
enum class SectionSkip {
    /** This version does not know the section. */
    UNKNOWN,

    /** Written by a newer Orbis (higher `v`). */
    NEWER,

    /** Not the shape this version writes. */
    DAMAGED,
}

sealed interface RestoreResult {
    /** Nothing was changed: the file could not be opened or read. */
    data class Failed(val error: BackupError) : RestoreResult

    /**
     * The file was read; every readable section was applied. [counts] holds one entry per applied section.
     * [failedSections] are sections whose apply step failed (the others still applied). [addonsToOffer] are curated
     * add-ons from the file that are not installed here: a restore never installs one, the screen offers "Add" for each.
     */
    data class Restored(
        val counts: Map<String, SectionCounts>,
        val skippedSections: Map<String, SectionSkip>,
        val failedSections: Set<String>,
        val addonsToOffer: List<BackupAddon>,
        val offerUblockOrigin: Boolean,
    ) : RestoreResult
}

/** [BackupManager.create] refused: the password is too short or the data too large for one file. */
class BackupRefusedException(val reason: BackupSealError) : Exception("backup refused: $reason")

/** [BackupManager.create] cannot run yet: [section] has not finished loading, so the backup would miss it. */
class BackupNotReadyException(val section: String) : Exception("$section not loaded yet")

/**
 * Makes and restores backup files: collects the sections ([BackupContents]), seals them with [BackupCrypto], and on
 * restore applies each section with the rules of the existing imports. Bookmarks, passwords, tabs and history are added
 * to what is there (duplicates skipped); settings and site permissions replace the current value key by key; add-ons
 * are only listed. Nothing is logged about the content.
 */
class BackupManager(
    private val sources: BackupSources,
    private val appVersion: String,
    private val crypto: BackupCrypto = BackupCrypto(),
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val work: CoroutineDispatcher = Dispatchers.Default,
    private val today: () -> LocalDate = LocalDate::now,
) {
    /**
     * A complete backup file. Zeroes [password]. Throws [BackupRefusedException] (password too short, too much data) and
     * [BackupNotReadyException] (bookmarks, saved passwords, tabs, or history when asked for, not loaded yet).
     */
    suspend fun create(password: CharArray, options: BackupOptions = BackupOptions()): ByteArray {
        val plain =
            try {
                val payload = withContext(main) { collect(options) }
                withContext(work) { payload.encode() }
            } catch (e: Throwable) {
                password.fill('\u0000')
                throw e
            }
        try {
            return when (val r = crypto.seal(plain, password, appVersion, today())) {
                is BackupSealResult.Sealed -> r.file
                is BackupSealResult.Refused -> throw BackupRefusedException(r.reason)
            }
        } finally {
            plain.fill(0)
        }
    }

    /**
     * Opens [file] with [password] (zeroed), reads every section, and only then applies them. Throws
     * [BackupNotReadyException], changing nothing, when the bookmarks have not loaded yet. A section that fails to
     * apply is listed in [RestoreResult.Restored.failedSections]; the others still apply.
     */
    suspend fun restore(file: ByteArray, password: CharArray): RestoreResult {
        val opened =
            when (val o = crypto.open(file, password)) {
                is BackupOpenResult.Opened -> o
                is BackupOpenResult.Failed -> return RestoreResult.Failed(o.error)
            }
        val read: Map<String, SectionRead<Any>> =
            try {
                when (val p = withContext(work) { BackupPayload.decode(opened.payload) }) {
                    is BackupPayload.DecodeResult.Ok -> withContext(work) { p.payload.sections.mapValues { (name, s) -> readSection(name, s) } }
                    is BackupPayload.DecodeResult.Failed -> return RestoreResult.Failed(p.error)
                }
            } finally {
                opened.payload.fill(0)
            }
        return withContext(main) {
            // Bookmarks restored before the stored list is read would be replaced by that read: change nothing.
            if (!sources.bookmarks.loaded) throw BackupNotReadyException(BackupSection.BOOKMARKS)
            apply(read)
        }
    }

    private fun readSection(name: String, s: JSONObject): SectionRead<Any> =
        when (name) {
            BackupSection.SETTINGS -> SettingsSection.decode(s)
            BackupSection.SITE_PERMISSIONS -> SitePermissionsSection.decode(s)
            BackupSection.BOOKMARKS -> BookmarksSection.decode(s)
            BackupSection.PASSWORDS -> PasswordsSection.decode(s)
            BackupSection.TABS -> TabsSection.decode(s)
            BackupSection.HISTORY -> HistorySection.decode(s)
            BackupSection.ADDONS -> AddonsSection.decode(s)
            else -> Unknown
        }

    /** A section name this version does not know. */
    private object Unknown : SectionRead<Nothing>

    // ---- create --------------------------------------------------------------------------------------------

    private fun collect(options: BackupOptions): BackupPayload {
        val s = sources
        if (!s.bookmarks.loaded) throw BackupNotReadyException(BackupSection.BOOKMARKS)
        if (!s.logins.loaded) throw BackupNotReadyException(BackupSection.PASSWORDS)
        val sections = LinkedHashMap<String, JSONObject>()
        sections[BackupSection.SETTINGS] = SettingsSection.encode(settings().mapValues { (_, keys) -> keys.mapNotNull { (k, v) -> v.read()?.let { k to it } }.toMap() })
        sections[BackupSection.SITE_PERMISSIONS] = SitePermissionsSection.encode(collectSitePermissions())
        sections[BackupSection.BOOKMARKS] =
            BookmarksSection.encode(s.bookmarks.entries.value.map { BackupBookmark(it.url, it.title, it.createdAt, it.folder) })
        sections[BackupSection.PASSWORDS] =
            PasswordsSection.encode(s.logins.logins.value.map { ImportedLogin(it.origin, it.username, it.password, it.formActionOrigin, it.httpRealm) })
        if (options.includeTabs) {
            if (!s.tabs.ready.value) throw BackupNotReadyException(BackupSection.TABS)
            val snap = s.tabs.normalTabsForBackup()
            val tabs = snap.tabs.filter { isWebUrl(it.url) }
            val used = tabs.mapNotNull { it.groupId }.toSet()
            sections[BackupSection.TABS] = TabsSection.encode(TabsBackup(tabs, snap.groups.filter { it.id in used }))
        }
        if (options.includeHistory) {
            if (!s.history.loaded) throw BackupNotReadyException(BackupSection.HISTORY)
            sections[BackupSection.HISTORY] = HistorySection.encode(s.history.entries.value)
        }
        val addons = s.addonStates().mapNotNull { (id, st) -> (st as? AddonState.Installed)?.let { BackupAddon(id, it.enabled) } }
        sections[BackupSection.ADDONS] = AddonsSection.encode(AddonsBackup(addons, s.ublockInstalled()))
        return BackupPayload(sections)
    }

    private fun collectSitePermissions(): SitePermissionsBackup {
        val s = sources
        val decisions = s.sitePermissions.entries.value.filter { !it.session }.map { SitePermissionDecision(it.key, it.allow) }
        val p = s.browserPrefs
        val jsOn = p.javaScriptOn
        val js = (jsOn + p.javaScriptOff).mapNotNull(::siteHost).associateWith { it in jsOn }
        val desktop =
            (p.desktopOn + p.desktopOff + p.desktopAuto + p.desktopAutoDeclined).mapNotNull(::siteHost).associateWith(::desktopChoice)
        return SitePermissionsBackup(decisions, js, desktop)
    }

    private fun desktopChoice(host: String): DesktopChoice {
        val s = sources.browserSettings
        val url = siteUrl(host)
        return when {
            s.isAutoDesktop(url, false) -> DesktopChoice.AUTO
            s.isAutoDesktopDeclined(url, false) -> DesktopChoice.DECLINED
            s.desktopFor(url, false) -> DesktopChoice.ON
            else -> DesktopChoice.OFF
        }
    }

    // ---- restore -------------------------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun apply(read: Map<String, SectionRead<Any>>): RestoreResult.Restored {
        val counts = LinkedHashMap<String, SectionCounts>()
        val skipped = LinkedHashMap<String, SectionSkip>()
        val failed = LinkedHashSet<String>()
        var offer = emptyList<BackupAddon>()
        var offerUbo = false
        for ((name, r) in read) {
            when (r) {
                Unknown -> skipped[name] = SectionSkip.UNKNOWN
                is SectionRead.Newer -> skipped[name] = SectionSkip.NEWER
                SectionRead.Damaged -> skipped[name] = SectionSkip.DAMAGED
                is SectionRead.Ok -> Unit
            }
        }
        for (name in BackupSection.ALL) {
            val r = read[name] as? SectionRead.Ok ?: continue
            try {
                counts[name] =
                    when (name) {
                        BackupSection.SETTINGS -> applySettings(r.value as SettingsValues)
                        BackupSection.SITE_PERMISSIONS -> applySitePermissions(r.value as SitePermissionsBackup)
                        BackupSection.BOOKMARKS -> applyBookmarks(r.value as List<BackupBookmark>)
                        BackupSection.PASSWORDS -> applyPasswords(r.value as List<ImportedLogin>)
                        BackupSection.TABS -> applyTabs(r.value as TabsBackup)
                        BackupSection.HISTORY -> applyHistory(r.value as List<com.shibi.orbis.history.HistoryEntry>)
                        else -> {
                            val a = r.value as AddonsBackup
                            val states = sources.addonStates()
                            offer = a.addons.filter { states[it.id] !is AddonState.Installed }
                            offerUbo = a.ublockOrigin && !sources.ublockInstalled()
                            SectionCounts(skipped = a.addons.size - offer.size)
                        }
                    }.let { it.copy(skipped = it.skipped + r.skipped) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += name
            }
        }
        return RestoreResult.Restored(counts, skipped, failed, offer, offerUbo)
    }

    private fun applySettings(values: SettingsValues): SectionCounts {
        val access = settings()
        var replaced = 0
        var skipped = 0
        for ((file, keys) in values) {
            for ((key, value) in keys) {
                val setting = access[file]?.get(key)
                when {
                    setting == null || setting.read() == value -> skipped++
                    setting.write(value) -> replaced++
                    else -> skipped++
                }
            }
        }
        return SectionCounts(replaced = replaced, skipped = skipped)
    }

    private fun applySitePermissions(value: SitePermissionsBackup): SectionCounts {
        val s = sources.browserSettings
        var added = 0
        var replaced = 0
        var skipped = 0
        val current = sources.sitePermissions.entries.value.filter { !it.session }.associate { it.key to it.allow }
        for (d in value.decisions) {
            when (current[d.key]) {
                d.allow -> skipped++
                null -> added++
                else -> replaced++
            }
            if (current[d.key] != d.allow) sources.sitePermissions.record(d.key, d.allow, remember = true)
        }
        for ((host, on) in value.javaScript) {
            val url = siteUrl(host)
            if (s.javaScriptFor(url, false) == on) {
                skipped++
            } else {
                s.rememberJavaScript(url, on, false)
                replaced++
            }
        }
        for ((host, choice) in value.desktop) {
            if (desktopChoice(host) == choice) {
                skipped++
                continue
            }
            val url = siteUrl(host)
            when (choice) {
                DesktopChoice.AUTO -> s.rememberAutoDesktop(url, false)
                DesktopChoice.DECLINED -> s.declineAutoDesktop(url, false)
                DesktopChoice.ON -> s.rememberDesktop(url, true, false)
                DesktopChoice.OFF -> s.rememberDesktop(url, false, false)
            }
            replaced++
        }
        return SectionCounts(added, replaced, skipped)
    }

    private fun applyBookmarks(items: List<BackupBookmark>): SectionCounts {
        val b = sources.bookmarks
        val fresh = items.filter { !b.isBookmarked(it.url) }.mapTo(HashSet()) { it.url }
        val result = b.importAll(ParsedBookmarks(items.map { ParsedBookmark(it.url, it.title, it.createdAt.takeIf { t -> t > 0 }) }, 0))
        // The import keeps no folder: file each newly added bookmark. One already saved keeps its own folder.
        for (item in items) {
            if (item.folder.isEmpty() || item.url !in fresh) continue
            val id = b.entries.value.firstOrNull { it.url == item.url }?.id ?: continue
            b.moveToFolder(id, item.folder)
        }
        return SectionCounts(added = result.added, skipped = result.skipped)
    }

    private fun applyPasswords(items: List<ImportedLogin>): SectionCounts {
        val l = sources.logins
        check(l.loaded) { "logins not loaded" }
        if (items.isEmpty()) return SectionCounts()
        // The CSV import is the existing rule: a login for the same site, username and realm is kept, not overwritten.
        val csv = LoginCsv.export(items.map { SavedLogin("", it.origin, it.formActionOrigin, it.httpRealm, it.username, it.password) })
        val r = l.importCsv(csv)
        return SectionCounts(added = r.added, skipped = r.skipped)
    }

    private fun applyTabs(value: TabsBackup): SectionCounts {
        val t = sources.tabs
        check(t.ready.value) { "tabs not ready" }
        // An address already open in a normal tab is not opened twice.
        val open = t.tabs.value.filter { !it.isPrivate }.mapTo(HashSet()) { it.url }
        val fresh = value.tabs.filter { open.add(it.url) }
        val added = t.addRestoredTabs(fresh, value.groups)
        return SectionCounts(added = added, skipped = value.tabs.size - added)
    }

    private fun applyHistory(items: List<com.shibi.orbis.history.HistoryEntry>): SectionCounts {
        val added = sources.history.importEntries(items)
        return SectionCounts(added = added, skipped = items.size - added)
    }

    // ---- settings: one reader and one writer per allow-listed key ----------------------------------------

    /** [read] gives the value as [SettingType.parse] would (null: nothing to back up); [write] returns false when refused. */
    internal class Setting(val read: () -> Any?, val write: (Any) -> Boolean)

    private fun bool(read: () -> Boolean, write: (Boolean) -> Unit) = Setting(read) { write(it as Boolean); true }

    private fun int(read: () -> Int, write: (Int) -> Unit) = Setting(read) { write(it as Int); true }

    private fun torrent(read: (TorrentSettings) -> Any, write: (TorrentSettings, Any) -> TorrentSettings): Setting {
        val store = sources.torrentSettings
        return Setting({ read(store.settings.value) }) { v -> store.update { write(it, v) }; true }
    }

    private inline fun <reified E : Enum<E>> enum(crossinline read: () -> E, crossinline write: (E) -> Unit) =
        Setting({ read().name }) { v -> enumValues<E>().firstOrNull { it.name == v }?.let { write(it); true } ?: false }

    /**
     * Every allow-listed key of [SettingsAllowList], file by file. A test checks the two lists name the same keys.
     * Writes go through the settings classes, so their flows (and the engine, which follows them) update at once.
     */
    internal fun settings(): Map<String, Map<String, Setting>> {
        val b = sources.browserSettings
        val search = sources.searchEngine
        val toolbar = sources.toolbar
        val doh = sources.doh
        val logins = sources.logins
        val clear = sources.clearOnExit
        val privacy = sources.privacy
        return mapOf(
            com.shibi.orbis.settings.SharedPreferencesBrowserPrefs.FILE_NAME to
                mapOf(
                    "app_theme" to enum<DarkMode>({ b.darkMode.value }, b::setDarkMode),
                    "theme_id" to Setting({ b.themeId.value }) { b.setThemeId(it as String); true },
                    "dark_websites" to bool({ b.darkWebsites.value }, b::setDarkWebsites),
                    "force_dark" to bool({ b.forceDark.value }, b::setForceDark),
                    "homepage" to Setting({ b.homepage.value ?: NoValue }) { b.setHomepage(if (it == NoValue) null else it as String) },
                    "search_suggestions" to bool({ b.searchSuggestions.value }, b::setSearchSuggestions),
                    "text_zoom_percent" to int({ b.textZoomPercent.value }, b::setTextZoomPercent),
                    "force_zoom" to bool({ b.forceZoom.value }, b::setForceZoom),
                    "https_only" to enum<HttpsOnlyMode>({ b.httpsOnly.value }, b::setHttpsOnly),
                    "javascript" to bool({ b.javaScript.value }, b::setJavaScript),
                    "always_desktop" to bool({ b.alwaysDesktop.value }, b::setAlwaysDesktop),
                    "open_links_in_apps" to bool({ b.openLinksInApps.value }, b::setOpenLinksInApps),
                    "toolbar_bottom" to bool({ b.toolbarBottom.value }, b::setToolbarBottom),
                    "copy_to_download_folder" to bool({ b.copyToDownloadFolder.value }, b::setCopyToDownloadFolder),
                    "magnet_handler" to enum<MagnetHandler>({ b.magnetHandler.value }, b::setMagnetHandler),
                    "offer_save_passwords" to bool({ b.offerSavePasswords.value }, b::setOfferSavePasswords),
                    "never_save_logins" to
                        Setting({ logins.neverSave.value }) { v ->
                            @Suppress("UNCHECKED_CAST")
                            val target = v as Set<String>
                            val current = logins.neverSave.value
                            for (o in current - target) logins.setNeverSave(o, false)
                            for (o in target - current) logins.setNeverSave(o, true)
                            true
                        },
                    "reader_text_size" to int({ b.readerTextSize.value }, b::setReaderTextSize),
                    "reader_serif" to bool({ b.readerSerif.value }, b::setReaderSerif),
                    "reader_theme" to enum<ReaderTheme>({ b.readerTheme.value }, b::setReaderTheme),
                    "pinned_sites" to
                        Setting({ b.pinnedSites.value }) { v ->
                            @Suppress("UNCHECKED_CAST")
                            val target = v as List<PinnedSite>
                            for (p in b.pinnedSites.value) b.unpinSite(p.url)
                            for (p in target) b.pinSite(p.url, p.title)
                            true
                        },
                ),
            com.shibi.orbis.engine.SharedPreferencesSearchEnginePrefs.FILE_NAME to
                mapOf(
                    "custom_engines" to
                        Setting({ search.custom.value.map { BackupSearchEngine(it.name, it.template) } }) { v ->
                            @Suppress("UNCHECKED_CAST")
                            val target = v as List<BackupSearchEngine>
                            for (c in search.custom.value) if (BackupSearchEngine(c.name, c.template) !in target) search.removeCustom(c.id)
                            for (e in target) {
                                if (search.custom.value.none { it.name == e.name && it.template == e.template }) search.addCustom(e.name, e.template)
                            }
                            true
                        },
                    "engine" to
                        Setting({
                            when (val p = search.selected.value) {
                                is SearchProvider.BuiltIn -> p.engine.name
                                is SearchProvider.Custom -> p.name
                            }
                        }) { v ->
                            val provider =
                                SearchEngine.entries.firstOrNull { it.name == v }?.let { SearchProvider.BuiltIn(it) }
                                    ?: search.custom.value.firstOrNull { it.name == v }
                            provider?.let { search.select(it) } != null
                        },
                ),
            com.shibi.orbis.settings.SharedPreferencesToolbarPrefs.FILE_NAME to
                mapOf(
                    "menu_order" to Setting({ toolbarOrder() }) { v ->
                        @Suppress("UNCHECKED_CAST")
                        val target = (v as List<String>).mapNotNull { n -> CUSTOMIZABLE_MENU_ACTIONS.firstOrNull { it.name == n } }
                        // ToolbarSettings only moves one step at a time: bring each entry up to its place.
                        for ((i, action) in target.withIndex()) {
                            while (toolbarOrder().indexOf(action.name) > i && toolbar.moveUp(action)) {}
                        }
                        true
                    },
                    "menu_hidden" to Setting({ toolbar.menuHidden.value.mapTo(HashSet()) { it.name } }) { v ->
                        @Suppress("UNCHECKED_CAST")
                        val target = v as Set<String>
                        for (action in CUSTOMIZABLE_MENU_ACTIONS) toolbar.setShown(action, action.name !in target)
                        true
                    },
                    "toolbar_button" to Setting({ toolbar.toolbarButton.value?.name ?: NoValue }) { v ->
                        toolbar.setToolbarButton(ToolbarButton.entries.firstOrNull { it.name == v })
                        true
                    },
                ),
            com.shibi.orbis.privacy.SharedPreferencesPrivacyPrefs.FILE_NAME to
                mapOf("lock_private_tabs" to bool({ privacy.lockPrivateTabs }) { privacy.lockPrivateTabs = it }),
            com.shibi.orbis.settings.SharedPreferencesDohPrefs.FILE_NAME to
                mapOf(
                    "enabled" to bool({ doh.config.value.enabled }, doh::setEnabled),
                    "provider" to enum<DohProvider>({ doh.config.value.provider }, doh::setProvider),
                    "custom_url" to Setting({ doh.config.value.customUrl }) { doh.setCustomUrl(it as String) },
                ),
            com.shibi.orbis.clear.SharedPreferencesClearOnExitPrefs.FILE_NAME to
                mapOf(
                    "enabled" to bool({ clear.enabled }) { clear.enabled = it },
                    "exceptions" to Setting({ clear.exceptions }) { v ->
                        @Suppress("UNCHECKED_CAST")
                        clear.exceptions = cleanExceptions(v as Set<String>)
                        true
                    },
                ),
            com.shibi.orbis.torrent.SharedPreferencesTorrentPrefs.FILE_NAME to
                mapOf(
                    // Through the store's update(), so the running torrent session follows the same flow the screen uses.
                    TorrentSettingsStore.KEY_WIFI_ONLY to torrent({ it.wifiOnly }) { t, v -> t.copy(wifiOnly = v as Boolean) },
                    TorrentSettingsStore.KEY_STOP_SEEDING to torrent({ it.stopSeeding }) { t, v -> t.copy(stopSeeding = v as Boolean) },
                    TorrentSettingsStore.KEY_SEED_RATIO to torrent({ it.seedRatioLimit ?: NoValue }) { t, v -> t.copy(seedRatioLimit = v as? Float) },
                    TorrentSettingsStore.KEY_DHT to torrent({ it.dht }) { t, v -> t.copy(dht = v as Boolean) },
                    TorrentSettingsStore.KEY_PEX to torrent({ it.pex }) { t, v -> t.copy(pex = v as Boolean) },
                    TorrentSettingsStore.KEY_LSD to torrent({ it.lsd }) { t, v -> t.copy(lsd = v as Boolean) },
                    TorrentSettingsStore.KEY_DOWN_BPS to torrent({ it.downBps }) { t, v -> t.copy(downBps = v as Int) },
                    TorrentSettingsStore.KEY_UP_BPS to torrent({ it.upBps }) { t, v -> t.copy(upBps = v as Int) },
                ),
        )
    }

    private fun toolbarOrder(): List<String> =
        sources.toolbar.menuOrder.value.filter { it in CUSTOMIZABLE_MENU_ACTIONS }.map { it.name }
}
