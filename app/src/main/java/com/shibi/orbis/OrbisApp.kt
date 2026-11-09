package com.shibi.orbis

import android.app.Application
import kotlinx.coroutines.flow.combine
import com.shibi.orbis.settings.HttpsOnlyMode
import com.shibi.orbis.settings.AppTheme
import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.webkit.MimeTypeMap
import com.shibi.orbis.bookmarks.BookmarkManager
import com.shibi.orbis.bookmarks.FileBookmarkStore
import com.shibi.orbis.clear.BrowsingDataClearer
import com.shibi.orbis.clear.ClearController
import com.shibi.orbis.clear.ClearTargets
import com.shibi.orbis.debug.DebugLog
import com.shibi.orbis.downloads.DownloadForegroundController
import com.shibi.orbis.downloads.DownloadManager
import com.shibi.orbis.downloads.DownloadNotifications
import com.shibi.orbis.downloads.DownloadStore
import com.shibi.orbis.engine.AndroidPermissionBroker
import com.shibi.orbis.engine.BrowserEngine
import com.shibi.orbis.engine.FileSitePermissionStore
import com.shibi.orbis.engine.GeckoEngineImpl
import com.shibi.orbis.engine.SearchEngineSettings
import com.shibi.orbis.media.MediaCenter
import com.shibi.orbis.media.MediaPlaybackService
import com.shibi.orbis.passwords.FileLoginStore
import com.shibi.orbis.passwords.KeystoreLoginCrypto
import com.shibi.orbis.passwords.LoginBridgeImpl
import com.shibi.orbis.passwords.LoginManager
import com.shibi.orbis.settings.BrowserSettings
import com.shibi.orbis.suggest.HttpSearchSuggestSource
import com.shibi.orbis.suggest.SearchSuggestSource
import com.shibi.orbis.settings.SharedPreferencesBrowserPrefs
import com.shibi.orbis.engine.SharedPreferencesAskedPermissions
import com.shibi.orbis.engine.SharedPreferencesSearchEnginePrefs
import com.shibi.orbis.engine.SitePermissionDecisions
import com.shibi.orbis.history.FileHistoryStore
import com.shibi.orbis.history.HistoryManager
import com.shibi.orbis.storage.AtomicTextFile
import com.shibi.orbis.tabs.FileTabStore
import com.shibi.orbis.tabs.TabManager
import com.shibi.orbis.tabs.VisitSink
import com.shibi.orbis.ubo.SharedPreferencesUboPrefs
import com.shibi.orbis.ubo.UboOnboardingController
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow

class OrbisApp : Application() {
    lateinit var container: AppContainer
        private set

    /** Keeps the process alive while downloads run. Null in Gecko child processes, like [container]. */
    var downloadForeground: DownloadForegroundController? = null
        private set

    override fun onCreate() {
        super.onCreate()
        // Gecko child processes (":tab", ":gpu", ...) run this too; they must not create a runtime or container.
        if (Application.getProcessName() != packageName) return
        val debugLog = DebugLog(filesDir)
        // App-lifetime scope: installs must survive screens leaving composition.
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val sitePermissions =
            SitePermissionDecisions(
                store = FileSitePermissionStore(File(filesDir, "site_permissions.json")),
                scope = appScope,
                io = Dispatchers.IO,
                now = SystemClock::elapsedRealtime,
                log = { debugLog.log("SitePermissions", it) },
            )
        val androidPermissions =
            AndroidPermissionBroker(
                asked = SharedPreferencesAskedPermissions(
                    getSharedPreferences(SharedPreferencesAskedPermissions.FILE_NAME, Context.MODE_PRIVATE),
                ),
                now = SystemClock::elapsedRealtime,
            )
        val engine: BrowserEngine = GeckoEngineImpl(this, debugLog, sitePermissions, androidPermissions)
        val uboPrefs =
            SharedPreferencesUboPrefs(
                getSharedPreferences(SharedPreferencesUboPrefs.FILE_NAME, Context.MODE_PRIVATE),
            )
        val searchEngine =
            SearchEngineSettings(
                SharedPreferencesSearchEnginePrefs(
                    getSharedPreferences(SharedPreferencesSearchEnginePrefs.FILE_NAME, Context.MODE_PRIVATE),
                ),
            )
        val browserSettings =
            BrowserSettings(
                SharedPreferencesBrowserPrefs(
                    getSharedPreferences(SharedPreferencesBrowserPrefs.FILE_NAME, Context.MODE_PRIVATE),
                ),
            )
        // Text size and force zoom follow the saved preferences, now and whenever they change.
        appScope.launch { browserSettings.textZoomPercent.collect { engine.setTextZoom(it) } }
        appScope.launch { browserSettings.forceZoom.collect { engine.setForceZoom(it) } }
        engine.setSitePolicy(browserSettings)
        appScope.launch { browserSettings.forceDark.collect { engine.setForceDark(it) } }
        appScope.launch {
            browserSettings.httpsOnly.collect {
                engine.setHttpsOnly(normalTabs = it == HttpsOnlyMode.ALL, privateTabs = it != HttpsOnlyMode.OFF)
            }
        }
        appScope.launch {
            combine(browserSettings.appTheme, browserSettings.darkWebsites) { theme, darkSites ->
                when {
                    darkSites -> true
                    theme == AppTheme.LIGHT -> false
                    theme == AppTheme.DARK -> true
                    else -> null
                }
            }.collect { engine.setWebsiteColorScheme(it) }
        }
        val ubo =
            UboOnboardingController(
                host = engine.extensions,
                prefs = uboPrefs,
                scope = appScope,
                log = { debugLog.log("Ubo", it) },
                now = System::currentTimeMillis,
            )
        val bookmarks =
            BookmarkManager(
                store = FileBookmarkStore(AtomicTextFile(File(filesDir, "bookmarks/bookmarks.json"))),
                scope = appScope,
                io = Dispatchers.IO,
                log = { debugLog.log("Bookmarks", it) },
                now = System::currentTimeMillis,
            )
        val history =
            HistoryManager(
                store = FileHistoryStore(AtomicTextFile(File(filesDir, "history/history.json"))),
                scope = appScope,
                io = Dispatchers.IO,
                log = { debugLog.log("History", it) },
                now = System::currentTimeMillis,
            )
        val prefsFile = getSharedPreferences(SharedPreferencesBrowserPrefs.FILE_NAME, Context.MODE_PRIVATE)
        val logins =
            LoginManager(
                store = FileLoginStore(AtomicTextFile(File(filesDir, "logins/logins.enc")), KeystoreLoginCrypto()),
                never = SharedPreferencesBrowserPrefs(prefsFile),
                scope = appScope,
                io = Dispatchers.IO,
                log = { debugLog.log("Logins", it) },
                now = System::currentTimeMillis,
            )
        logins.load()
        engine.logins = LoginBridgeImpl(logins) { browserSettings.offerSavePasswords.value }
        var downloadsRef: DownloadManager? = null
        // The tab manager creates NO session here: sessions appear only once the browser screen asks for them, which
        // is after the onboarding decision. restore() only reads the stored tab list.
        val tabs =
            TabManager(
                factory = { init -> engine.createSession(init) },
                store = FileTabStore(File(filesDir, "tabs/tabs.json")),
                scope = appScope,
                log = { debugLog.log("Tabs", it) },
                visits = object : VisitSink {
                    override fun onVisit(url: String, title: String) = history.recordVisit(url, title)

                    override fun onTitle(url: String, title: String) = history.updateTitle(url, title)
                },
                onPrivateBrowsingEnded = {
                    engine.clearPrivateData()
                    // Created just below; private browsing cannot end before then (no session exists yet).
                    downloadsRef?.forgetPrivate()
                    browserSettings.forgetPrivate()
                },
            )
        // Selection menu > Search: a new tab (private for a private page) with the user's search engine.
        engine.onSelectionSearch = { text, isPrivate ->
            val url = searchEngine.selected.value.searchPrefix + java.net.URLEncoder.encode(text, "UTF-8")
            if (isPrivate) tabs.newPrivateTab(url) else tabs.newTab(url)
        }
        val downloads =
            DownloadManager(
                dir = { getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(filesDir, "downloads") },
                store = DownloadStore(File(filesDir, "downloads_index.json")),
                scope = appScope,
                io = Dispatchers.IO,
                log = { debugLog.log("Downloads", it) },
                now = System::currentTimeMillis,
                extensionForMime = { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) },
                fetchFrom = { url, from, isPrivate -> engine.fetchFrom(url, from, isPrivate) },
            )
        downloadsRef = downloads
        // Finished downloads also go to the phone's Download folder (setting; never a private tab's). Only downloads
        // that finish while Orbis runs: the ones already listed at start are taken as handled.
        appScope.launch {
            val handled = HashSet<Long>()
            var first = true
            downloads.entries.collect { list ->
                if (first) {
                    first = false
                    list.forEach { handled += it.id }
                    return@collect
                }
                val fresh = com.shibi.orbis.downloads.newlyCompleted(list, handled)
                fresh.forEach { handled += it.id }
                if (fresh.isEmpty() || !browserSettings.copyToDownloadFolder.value) return@collect
                launch(Dispatchers.IO) {
                    for (e in fresh) {
                        val ok = com.shibi.orbis.downloads.copyToPublicDownloads(this@OrbisApp, File(e.filePath!!), e.fileName, e.mimeType)
                        debugLog.log("Downloads", if (ok) "copied to Download folder" else "copy to Download folder failed")
                    }
                }
            }
        }
        // Everything here runs on the main thread (appScope is Main). Site permissions and uBO are not touched.
        val clearTargets =
            object : ClearTargets {
                override fun clearHistory() {
                    history.clearAll()
                    tabs.forgetClosed()
                }

                override fun closeNormalTabs() = tabs.closeAllNormal()

                override fun clearDownloadsList() {
                    // Result notifications name the file, so the cleared ones go with their entries.
                    DownloadNotifications.cancelResults(this@OrbisApp, downloads.clearIndex())
                }

                override fun clearBookmarks() = bookmarks.clearAll()

                override fun clearPasswords() = logins.clearAll()

                override fun hasPrivateTabs() = tabs.hasPrivateTabs

                override suspend fun clearEngine(cookiesAndSiteData: Boolean, cache: Boolean) =
                    engine.clearProfileData(cookiesAndSiteData, cache)
            }
        val clear =
            ClearController(
                clearer = BrowsingDataClearer(clearTargets, log = { debugLog.log("Clear", it) }),
                scope = appScope,
            )
        container =
            AppContainer(
                debugLog = debugLog,
                engine = engine,
                ubo = ubo,
                tabs = tabs,
                downloads = downloads,
                sitePermissions = sitePermissions,
                bookmarks = bookmarks,
                history = history,
                clear = clear,
                searchEngine = searchEngine,
                browserSettings = browserSettings,
                logins = logins,
            )
        // A tab that starts playing brings up the media notification (and keeps playback alive in the background).
        appScope.launch {
            container.media.current
                .map { it?.media?.playing == true }
                .distinctUntilChanged()
                .collect { playing -> if (playing) MediaPlaybackService.start(this@OrbisApp) }
        }
        // Sessions only appear after the onboarding decision; this has long finished by then.
        sitePermissions.load()
        container.updates = com.shibi.orbis.update.UpdateController(this, appScope).also { it.checkIfDue() }
        runCatching { engine.warmUp() }
            .onFailure { debugLog.log("OrbisApp", "engine warmUp failed: ${it.message}") }
        // Both lists are read before any tab can load a page; changes made before they arrive are replayed on top.
        bookmarks.load()
        history.load()
        tabs.restore()
        downloads.restore()
        // After restore(): restored entries are never IN_PROGRESS, so this cannot start the service at launch.
        downloadForeground =
            DownloadForegroundController(
                context = this,
                downloads = downloads,
                scope = appScope,
                log = { debugLog.log("Downloads", it) },
            ).also { it.start() }
        ubo.start()
    }

    override fun onTerminate() {
        if (::container.isInitialized) runCatching { container.engine.shutdown() }
        super.onTerminate()
    }
}

class AppContainer(
    val debugLog: DebugLog,
    /** Tab page previews for the tab grid (memory only). */
    val thumbnails: com.shibi.orbis.tabs.TabThumbnails = com.shibi.orbis.tabs.TabThumbnails(),
    /** Update checks for builds not from Google Play; set by the app after creation. */
    var updates: com.shibi.orbis.update.UpdateController? = null,
    val engine: BrowserEngine,
    val ubo: UboOnboardingController,
    /** Owns every tab and session; outlives screens, so Settings never destroys a page. */
    val tabs: TabManager,
    val downloads: DownloadManager,
    /** The user's camera, microphone and location answers; Settings lists and removes them. */
    val sitePermissions: SitePermissionDecisions,
    val bookmarks: BookmarkManager,
    val history: HistoryManager,
    /** Clear browsing data. Runs in the app scope, so a clear in flight outlives the screen that started it. */
    val clear: ClearController,
    /** Which engine address-bar searches go to; one choice for normal and private tabs. */
    val searchEngine: SearchEngineSettings,
    val browserSettings: BrowserSettings,
    val logins: LoginManager,
    /** Page media across tabs (notification, background playback, picture-in-picture). */
    val media: MediaCenter = MediaCenter(tabs, kotlinx.coroutines.MainScope()),
    /** Live search suggestions; only asked for normal tabs with the setting on (see BrowserScreen). */
    val searchSuggest: SearchSuggestSource = HttpSearchSuggestSource(),
) {
    /**
     * An http(s) URL another app asked us to open. Set by MainActivity, consumed (and cleared) by BrowserScreen, which
     * only exists after the onboarding decision, so an intent arriving earlier waits and causes no network load.
     */
    val pendingExternalUrl = MutableStateFlow<String?>(null)

    /** A download notification was tapped. Consumed by OrbisRoot, which navigates once, and only after onboarding. */
    val pendingShowDownloads = MutableStateFlow(false)

    /** The home screen widget was tapped: open a new tab ready for typing (a flag only). */
    val pendingWidgetSearch = MutableStateFlow(false)

    /**
     * How many screens that show secrets (Settings > Passwords) are on screen. Above zero, MainActivity protects the
     * window (FLAG_SECURE, no Recents snapshot) as it does for private tabs.
     */
    val sensitiveScreens = MutableStateFlow(0)
}
