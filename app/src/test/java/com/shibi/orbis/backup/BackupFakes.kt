package com.shibi.orbis.backup

import android.view.View
import com.shibi.orbis.addons.AddonState
import com.shibi.orbis.bookmarks.Bookmark
import com.shibi.orbis.bookmarks.BookmarkManager
import com.shibi.orbis.bookmarks.BookmarkStore
import com.shibi.orbis.clear.ClearOnExitPrefs
import com.shibi.orbis.clear.cleanExceptions
import com.shibi.orbis.engine.BrowserSession
import com.shibi.orbis.engine.SearchEnginePrefs
import com.shibi.orbis.engine.SearchEngineSettings
import com.shibi.orbis.engine.SearchProvider
import com.shibi.orbis.engine.SessionHost
import com.shibi.orbis.engine.SessionInit
import com.shibi.orbis.engine.SessionState
import com.shibi.orbis.engine.SitePermissionDecision
import com.shibi.orbis.engine.SitePermissionDecisions
import com.shibi.orbis.engine.SitePermissionStore
import com.shibi.orbis.engine.SitePrompt
import com.shibi.orbis.history.HistoryEntry
import com.shibi.orbis.history.HistoryManager
import com.shibi.orbis.history.HistoryStore
import com.shibi.orbis.home.PinnedSite
import com.shibi.orbis.passwords.LoginManager
import com.shibi.orbis.passwords.LoginStore
import com.shibi.orbis.passwords.NeverSavePrefs
import com.shibi.orbis.passwords.SavedLogin
import com.shibi.orbis.privacy.PrivacyPrefs
import com.shibi.orbis.settings.BrowserPrefs
import com.shibi.orbis.settings.BrowserSettings
import com.shibi.orbis.settings.DarkMode
import com.shibi.orbis.settings.DohPrefs
import com.shibi.orbis.settings.DohProvider
import com.shibi.orbis.settings.DohSettings
import com.shibi.orbis.settings.HttpsOnlyMode
import com.shibi.orbis.settings.MagnetHandler
import com.shibi.orbis.settings.ReaderTheme
import com.shibi.orbis.settings.ToolbarPrefs
import com.shibi.orbis.settings.ToolbarSettings
import com.shibi.orbis.tabs.TabManager
import com.shibi.orbis.tabs.TabStore
import com.shibi.orbis.tabs.TabsSnapshot
import com.shibi.orbis.torrent.TorrentPrefs
import com.shibi.orbis.torrent.TorrentSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher

internal class MemBrowserPrefs : BrowserPrefs, NeverSavePrefs {
    override var homepage: String? = null
    override var searchSuggestions = true
    override var textZoomPercent = 100
    override var forceZoom = false
    override var pinnedSites: List<PinnedSite> = emptyList()
    override var hiddenSites: Set<String> = emptySet()
    override var offerSavePasswords = true
    override var readerTextSize = 18
    override var readerSerif = true
    override var readerTheme = ReaderTheme.LIGHT
    override var httpsOnly = HttpsOnlyMode.ALL
    override var darkMode = DarkMode.SYSTEM
    override var themeId: String? = null
    override var darkWebsites = false
    override var javaScript = true
    override var javaScriptOn: Set<String> = emptySet()
    override var javaScriptOff: Set<String> = emptySet()
    override var alwaysDesktop = false
    override var desktopOn: Set<String> = emptySet()
    override var desktopOff: Set<String> = emptySet()
    override var desktopAuto: Set<String> = emptySet()
    override var desktopAutoDeclined: Set<String> = emptySet()
    override var openLinksInApps = true
    override var toolbarBottom = false
    override var copyToDownloadFolder = true
    override var forceDark = false
    override var protectedVideo = true
    override var magnetHandler = MagnetHandler.ORBIS
    override var torrentIpNoteSeen = false
    override var neverSave: Set<String> = emptySet()
}

internal class MemSearchPrefs : SearchEnginePrefs {
    override var selectedId: String? = null
    override var custom: List<SearchProvider.Custom> = emptyList()
    override var nextCustomNumber = 1
}

internal class MemToolbarPrefs : ToolbarPrefs {
    override var menuOrder: List<String> = emptyList()
    override var menuHidden: Set<String> = emptySet()
    override var toolbarButton: String? = null

    override fun clear() {
        menuOrder = emptyList()
        menuHidden = emptySet()
        toolbarButton = null
    }
}

internal class MemDohPrefs : DohPrefs {
    override var enabled = false
    override var provider = DohProvider.QUAD9
    override var customUrl: String? = null
}

internal class MemClearPrefs : ClearOnExitPrefs {
    override var enabled = false
    override var exceptions: Set<String> = emptySet()
        set(value) {
            field = cleanExceptions(value)
        }
}

internal class MemPrivacyPrefs : PrivacyPrefs {
    override var lockPrivateTabs = false
}

internal class MemTorrentPrefs : TorrentPrefs {
    val map = HashMap<String, Any>()

    override fun boolean(key: String): Boolean? = map[key] as? Boolean

    override fun int(key: String): Int? = map[key] as? Int

    override fun float(key: String): Float? = map[key] as? Float

    override fun write(values: Map<String, Any?>) {
        for ((k, v) in values) if (v == null) map.remove(k) else map[k] = v
    }
}

internal class MemBookmarkStore(var data: List<Bookmark> = emptyList()) : BookmarkStore {
    override fun load() = data

    override fun save(items: List<Bookmark>) {
        data = items
    }
}

internal class MemLoginStore(var data: List<SavedLogin> = emptyList()) : LoginStore {
    var failLoad = false

    override fun load(): List<SavedLogin> = if (failLoad) throw java.io.IOException("keystore busy") else data

    override fun save(items: List<SavedLogin>) {
        data = items
    }

    override fun setAside() = Unit
}

internal class MemPermissionStore(var data: List<SitePermissionDecision> = emptyList()) : SitePermissionStore {
    override fun load() = data

    override fun save(decisions: List<SitePermissionDecision>) {
        data = decisions
    }
}

internal class MemTabStore(var snapshot: TabsSnapshot? = null) : TabStore {
    override fun load() = snapshot

    override fun save(snapshot: TabsSnapshot) {
        this.snapshot = snapshot
    }
}

internal class MemHistoryStore(var data: List<HistoryEntry> = emptyList()) : HistoryStore {
    override fun load() = data

    override fun save(items: List<HistoryEntry>) {
        data = items
    }
}

/** A session that only records what it was asked to load; a load sets its url, as a committed navigation would. */
internal class BackupFakeSession(init: SessionInit) : BrowserSession {
    override val isPrivate = init.isPrivate
    val stateFlow = MutableStateFlow(SessionState(desktopMode = init.desktopMode))
    override val state: StateFlow<SessionState> = stateFlow
    override val prompts: StateFlow<SitePrompt?> = MutableStateFlow(null)
    override var host: SessionHost? = null
    val loads = mutableListOf<String>()

    override fun attachTo(contentView: View) = Unit
    override fun detachFrom(contentView: View) = Unit
    override fun loadUrl(url: String) {
        loads += url
        stateFlow.value = stateFlow.value.copy(url = url, title = "Private page")
    }
    override fun loadExternalUrl(url: String) = loadUrl(url)
    override fun setDesktopMode(enabled: Boolean) = Unit
    override fun goBack() = false
    override fun goForward() = false
    override fun reload() = Unit
    override fun stop() = Unit
    override fun find(query: String, backwards: Boolean) = Unit
    override fun clearFind() = Unit
    override fun setActive(active: Boolean) = Unit
    override fun savedState(): String? = "SESSION-STATE-BLOB"
    override fun close() = Unit
}

/** One app's worth of real managers and settings classes over in-memory stores. Call [load], then advance the scheduler. */
internal class BackupWorld(
    private val scope: CoroutineScope,
    private val dispatcher: TestDispatcher,
    private val scheduler: TestCoroutineScheduler,
) {
    val browserPrefs = MemBrowserPrefs()
    val bookmarkStore = MemBookmarkStore()
    val loginStore = MemLoginStore()
    val permissionStore = MemPermissionStore()
    val tabStore = MemTabStore()
    val historyStore = MemHistoryStore()
    val sessions = mutableListOf<BackupFakeSession>()
    var addonStates: Map<String, AddonState> = emptyMap()
    var ublockInstalled = false

    val browserSettings by lazy { BrowserSettings(browserPrefs) }
    val searchEngine by lazy { SearchEngineSettings(MemSearchPrefs()) }
    val toolbar by lazy { ToolbarSettings(MemToolbarPrefs()) }
    val doh by lazy { DohSettings(MemDohPrefs()) }
    val clearPrefs = MemClearPrefs()
    val privacy = MemPrivacyPrefs()
    val torrentPrefs = MemTorrentPrefs()
    val torrentSettings by lazy { TorrentSettingsStore(torrentPrefs) }
    val bookmarks by lazy { BookmarkManager(bookmarkStore, scope, {}, { 5_000L }, dispatcher) }
    val logins by lazy { LoginManager(loginStore, browserPrefs, scope, {}, { 5_000L }, dispatcher, newGuid = { "{g${guid++}}" }) }
    private var guid = 1
    val sitePermissions by lazy { SitePermissionDecisions(permissionStore, scope, dispatcher, { 0L }, {}) }
    val tabs by lazy {
        TabManager(factory = { init -> BackupFakeSession(init).also { sessions += it } }, store = tabStore, scope = scope, log = {}, io = dispatcher)
    }
    val history by lazy { HistoryManager(historyStore, scope, {}, { 5_000L }, dispatcher) }

    val sources by lazy {
        BackupSources(
            bookmarks = bookmarks,
            logins = logins,
            browserSettings = browserSettings,
            browserPrefs = browserPrefs,
            searchEngine = searchEngine,
            toolbar = toolbar,
            doh = doh,
            clearOnExit = clearPrefs,
            privacy = privacy,
            sitePermissions = sitePermissions,
            tabs = tabs,
            history = history,
            torrentSettings = torrentSettings,
            addonStates = { addonStates },
            ublockInstalled = { ublockInstalled },
        )
    }

    val manager by lazy {
        BackupManager(
            sources = sources,
            appVersion = "test",
            crypto = BackupCrypto(dispatcher, 100_000, BackupCrypto.secureRandomBytes()),
            main = dispatcher,
            work = dispatcher,
        )
    }

    fun load() {
        bookmarks.load()
        logins.load()
        sitePermissions.load()
        tabs.restore()
        history.load()
        scheduler.advanceUntilIdle()
    }
}
