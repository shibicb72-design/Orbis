package com.shibi.orbis

import android.app.Application
import kotlinx.coroutines.flow.combine
import com.shibi.orbis.settings.HttpsOnlyMode
import com.shibi.orbis.settings.AppTheme
import android.content.Context
import androidx.core.net.toUri
import android.os.Environment
import android.os.SystemClock
import android.webkit.MimeTypeMap
import com.shibi.orbis.about.retireGithubUpdateChecker
import com.shibi.orbis.bookmarks.BookmarkManager
import com.shibi.orbis.bookmarks.FileBookmarkStore
import com.shibi.orbis.clear.BrowsingDataClearer
import com.shibi.orbis.clear.ClearController
import com.shibi.orbis.clear.ClearTargets
import com.shibi.orbis.clear.clearDownloadLists
import com.shibi.orbis.clear.clearSavedLogins
import com.shibi.orbis.debug.DebugLog
import com.shibi.orbis.downloads.DownloadForegroundController
import com.shibi.orbis.downloads.DownloadManager
import com.shibi.orbis.downloads.DownloadNotifications
import com.shibi.orbis.downloads.DownloadStore
import com.shibi.orbis.torrent.AndroidTorrentNetwork
import com.shibi.orbis.torrent.Libtorrent4jEngine
import com.shibi.orbis.torrent.TorrentDirs
import com.shibi.orbis.torrent.TorrentForegroundController
import com.shibi.orbis.torrent.TorrentLog
import com.shibi.orbis.torrent.TorrentManager
import com.shibi.orbis.torrent.TorrentNotifications
import com.shibi.orbis.torrent.TorrentPublisher
import com.shibi.orbis.torrent.TorrentStore
import com.shibi.orbis.engine.AndroidPermissionBroker
import com.shibi.orbis.engine.BrowserEngine
import com.shibi.orbis.engine.FileSitePermissionStore
import com.shibi.orbis.engine.GeckoEngineImpl
import com.shibi.orbis.engine.SearchEngineSettings
import com.shibi.orbis.cast.CastCoordinator
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
import com.shibi.orbis.engine.ImportOpenQueue
import com.shibi.orbis.engine.ImportedFileCache
import com.shibi.orbis.share.ShareOutCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow

class OrbisApp : Application() {
    lateinit var container: AppContainer
        private set

    /**
     * Main-thread scope for the whole process. Shared-document copies use it so a rotation does not cancel them.
     * Child processes never launch work on it (they return before [container] exists).
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Keeps the process alive while downloads run. Null in Gecko child processes, like [container]. */
    var downloadForeground: DownloadForegroundController? = null
        private set

    /** Keeps the process alive while torrents run. Null in Gecko child processes, like [container]. */
    var torrentForeground: TorrentForegroundController? = null
        private set

    /** Started MainActivity instances, which can overlap, and the foreground and background that follow from them. */
    val startedActivities =
        StartedActivities { foreground ->
            container.tabs.setForeground(foreground)
            if (foreground) {
                downloadForeground?.onAppForeground()
                torrentForeground?.onAppForeground()
            } else {
                downloadForeground?.onAppBackground()
                torrentForeground?.onAppBackground()
            }
        }

    override fun onCreate() {
        super.onCreate()
        // Gecko child processes (":tab", ":gpu", ...) run this too; they must not create a runtime or container.
        if (Application.getProcessName() != packageName) return
        // Play delivers updates. Drop the GitHub checker's prefs.
        retireGithubUpdateChecker(this)
        val debugLog = DebugLog(filesDir)
        val imports = ImportedFileCache(cacheDir)
        val pendingImports = ImportOpenQueue()
        // Files copied in from other apps: drop the ones older than a day. Young ones stay so an open tab can reload.
        appScope.launch(Dispatchers.IO) { imports.deleteOlderThan() }
        val shareOut = ShareOutCache(cacheDir)
        // Private tabs are never restored, so no private share outlives the process. Normal shares last a day.
        appScope.launch(Dispatchers.IO) {
            shareOut.clearPrivate()
            shareOut.deleteOlderThan()
        }
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
        var torrentsRef: TorrentManager? = null
        // The tab manager creates NO session here: sessions appear only once the browser screen asks for them, which
        // is after the onboarding decision. restore() only reads the stored tab list.
        val tabs =
            TabManager(
                factory = { init -> engine.createSession(init) },
                store = FileTabStore(File(filesDir, "tabs/tabs.json")),
                scope = appScope,
                log = { debugLog.log("Tabs", it) },
                torrentLog = { debugLog.log(TorrentLog.TAG, it) },
                visits = object : VisitSink {
                    override fun onVisit(url: String, title: String) = history.recordVisit(url, title)

                    override fun onTitle(url: String, title: String) = history.updateTitle(url, title)
                },
                onPrivateBrowsingEnded = {
                    engine.clearPrivateData()
                    appScope.launch(Dispatchers.IO) { runCatching { shareOut.clearPrivate() } }
                    // Created just below; private browsing cannot end before then (no session exists yet).
                    // The private entries leave the list; their result notifications must not stay in the shade.
                    downloadsRef?.let { DownloadNotifications.cancelResults(this@OrbisApp, it.forgetPrivate()) }
                    browserSettings.forgetPrivate()
                    // Private torrents leave the list and their partial files are deleted; their result notifications
                    // go too (the paused one follows the list by itself).
                    torrentsRef?.let { TorrentNotifications.cancelResults(this@OrbisApp, it.forgetPrivate()) }
                },
                releaseImport = { url ->
                    appScope.launch(Dispatchers.IO) {
                        runCatching { imports.deleteImport(url) }
                            .onFailure { debugLog.log("Import", "not deleted") }
                    }
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
                fetchFrom = { url, from, isPrivate, ifRange -> engine.fetchFrom(url, from, isPrivate, ifRange) },
                // Still running when private browsing ended: it never gets a result notification; cancel any anyway.
                onPrivateDropped = { id -> DownloadNotifications.cancelResults(this@OrbisApp, listOf(id)) },
            )
        downloadsRef = downloads
        // Torrents: restore() below only reads the index; the native engine starts with the first active torrent.
        val torrentLog = TorrentLog.sink(debugLog)
        val torrentStore = TorrentStore(File(filesDir, "torrents"))
        // Settings > Torrents: app-wide, never in the torrent index. The manager applies every change at once.
        val torrentSettings =
            com.shibi.orbis.torrent.TorrentSettingsStore(
                getSharedPreferences(com.shibi.orbis.torrent.SharedPreferencesTorrentPrefs.FILE_NAME, MODE_PRIVATE),
            )
        val torrents =
            TorrentManager(
                engine = Libtorrent4jEngine(torrentStore.sessionFile, torrentLog),
                store = torrentStore,
                // A finished private torrent is kept where private file downloads go (F-06).
                dirs = TorrentDirs(payloadRoot = { getExternalFilesDir("torrents") ?: File(filesDir, "torrents/data") }, keepRoot = downloads::privateDir),
                scope = appScope,
                io = Dispatchers.IO,
                log = torrentLog,
                settings = torrentSettings.settings,
                listenPort = torrentSettings::listenPort,
                network = AndroidTorrentNetwork(this),
                now = System::currentTimeMillis,
                publisher =
                    object : TorrentPublisher {
                        override fun publish(file: File, relativeDir: String, displayName: String): TorrentPublisher.Published? {
                            val ext = displayName.substringAfterLast('.', "").lowercase()
                            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                            return com.shibi.orbis.downloads.publishTorrentFile(this@OrbisApp, file, displayName, mime, relativeDir)
                        }

                        override fun unpublish(handle: String) {
                            if (!com.shibi.orbis.downloads.deletePublished(this@OrbisApp, handle.toUri())) torrentLog("unpublish failed")
                        }
                    },
            )
        torrentsRef = torrents
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
                        val ok = com.shibi.orbis.downloads.copyToPublicDownloads(this@OrbisApp, File(e.filePath!!), e.fileName, e.mimeType) != null
                        debugLog.log("Downloads", if (ok) "copied to Download folder" else "copy to Download folder failed")
                    }
                }
            }
        }
        // Estimated time saved from blocked YouTube ads. On this phone only; private tabs are not recorded.
        val youtubeAds =
            com.shibi.orbis.youtube.YoutubeAdSavings(
                File(filesDir, "youtube_ad_savings.json"),
                appScope,
                Dispatchers.IO,
                log = { debugLog.log("YouTubeAds", it) },
            )
        youtubeAds.load()
        engine.onYoutubeAdEvent = { youtubeAds.onEvent(it) }
        // Everything here runs on the main thread (appScope is Main). Site permissions and uBO are not touched.
        val clearTargets =
            object : ClearTargets {
                override fun clearHistory() {
                    history.clearAll()
                    tabs.forgetClosed()
                    // Opened files are a record of what was viewed, same as history. The cache clear does this too.
                    deleteImports()
                    // Video ids and titles from the YouTube estimate are browsing history, cleared with this category.
                    youtubeAds.clear()
                }

                override fun closeNormalTabs() = tabs.closeAllNormal()

                override fun clearDownloadsList() {
                    // Result notifications name the file, so the cleared ones go with their entries (torrents too, F-07).
                    val cleared = clearDownloadLists(downloads, torrents)
                    DownloadNotifications.cancelResults(this@OrbisApp, cleared.downloads)
                    TorrentNotifications.cancelResults(this@OrbisApp, cleared.torrents)
                }

                override fun clearBookmarks() = bookmarks.clearAll()

                override fun clearPasswords() = clearSavedLogins(logins)

                override fun hasPrivateTabs() = tabs.hasPrivateTabs

                override suspend fun clearEngine(cookiesAndSiteData: Boolean, cache: Boolean): Boolean {
                    // Documents opened from other apps live in the cache. Private tabs block this whole clear
                    // before we get here, so this never runs while a private tab is open. History clears them too.
                    if (cache) deleteImports()
                    return engine.clearProfileData(cookiesAndSiteData, cache)
                }

                /**
                 * Drops every imported document. The queue is cleared on this thread so a waiting tab is not opened
                 * onto a file this delete removes. The files themselves go on the IO dispatcher: [clearHistory]
                 * runs on the main thread and must not block there. The cache lock is not held during a provider
                 * read, so the delete does not wait on another app.
                 */
                fun deleteImports() {
                    pendingImports.clear()
                    appScope.launch(Dispatchers.IO) {
                        val removed = runCatching { imports.clearAll() }.isSuccess
                        if (!removed) debugLog.log("Clear", "imported files not cleared")
                        if (runCatching { shareOut.clearAll() }.isFailure) debugLog.log("Clear", "shared files not cleared")
                    }
                }
            }
        val clear =
            ClearController(
                clearer = BrowsingDataClearer(clearTargets, log = { debugLog.log("Clear", it) }),
                scope = appScope,
            )
        val cast = CastCoordinator(this, appScope, log = { debugLog.log("Cast", it) })
        tabs.onPrivateTabDisposed = { id -> cast.onPrivateTabClosed(id) }
        container =
            AppContainer(
                debugLog = debugLog,
                cast = cast,
                engine = engine,
                ubo = ubo,
                tabs = tabs,
                downloads = downloads,
                imports = imports,
                shareOut = shareOut,
                pendingImports = pendingImports,
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
        // Privacy dashboard: counts of what was blocked, and the app's network use (Android's per-app counter, read
        // once a minute while Orbis runs). Counts only, on this phone.
        val stats =
            com.shibi.orbis.stats.PrivacyStats(File(filesDir, "privacy_stats.json"), appScope, Dispatchers.IO, log = { debugLog.log("Stats", it) })
        stats.load()
        container.privacyStats = stats
        container.youtubeAdSavings = youtubeAds
        container.torrents = torrents
        container.torrentSettings = torrentSettings
        engine.onContentBlocked = { kind, uri, isPrivate -> stats.recordBlocked(kind, uri, isPrivate) }
        appScope.launch {
            val uid = android.os.Process.myUid()
            while (true) {
                val rx = android.net.TrafficStats.getUidRxBytes(uid)
                val tx = android.net.TrafficStats.getUidTxBytes(uid)
                if (rx >= 0 && tx >= 0) stats.recordTraffic(rx + tx)
                kotlinx.coroutines.delay(60_000)
            }
        }
        runCatching { engine.warmUp() }
            .onFailure { debugLog.log("OrbisApp", "engine warmUp failed: ${it.javaClass.simpleName}") }
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
        torrents.restore()
        torrentForeground =
            TorrentForegroundController(
                context = this,
                torrents = torrents,
                scope = appScope,
                log = torrentLog,
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
    /** Cast to a TV or speaker. Memory only: a private tab's page is not written down. */
    val cast: CastCoordinator,
    /** Tab page previews for the tab grid (memory only). */
    val thumbnails: com.shibi.orbis.tabs.TabThumbnails = com.shibi.orbis.tabs.TabThumbnails(),
    /** What Orbis blocked and the data it used (the start page dashboard); set by the app after creation. */
    var privacyStats: com.shibi.orbis.stats.PrivacyStats? = null,
    /** Estimated time saved from blocked YouTube ads; set by the app after creation. Cleared with browsing history. */
    var youtubeAdSavings: com.shibi.orbis.youtube.YoutubeAdSavings? = null,
    /** Torrent downloads; set by the app after creation. */
    var torrents: com.shibi.orbis.torrent.TorrentManager? = null,
    /** Settings > Torrents; set by the app after creation. */
    var torrentSettings: com.shibi.orbis.torrent.TorrentSettingsStore? = null,
    val engine: BrowserEngine,
    val ubo: UboOnboardingController,
    /** Owns every tab and session; outlives screens, so Settings never destroys a page. */
    val tabs: TabManager,
    val downloads: DownloadManager,
    /** PDF and HTML files opened from other apps. Cleared with history and the cache, and trimmed at startup. */
    val imports: ImportedFileCache,
    /** Copies handed to other apps by Share (a PDF, an imported document). */
    val shareOut: ShareOutCache,
    /** Documents copied from other apps, waiting to be opened. Cleared with the files. */
    val pendingImports: ImportOpenQueue,
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

    /**
     * A torrent another app handed over (a `content:` .torrent or a magnet: link). Set by MainActivity or
     * LinkRouterActivity, taken by BrowserScreen into the confirm sheet once onboarding and Clear browsing data are done.
     * Nothing is added before the sheet's Download tap.
     */
    val pendingTorrentIntake = MutableStateFlow<com.shibi.orbis.torrent.IntakeRequest?>(null)

    /** A download notification was tapped. Consumed by OrbisRoot, which navigates once, and only after onboarding. */
    val pendingShowDownloads = MutableStateFlow(false)

    /** The home screen widget was tapped: open a new tab ready for typing (a flag only). */
    val pendingWidgetSearch = MutableStateFlow(false)

    /** The cast notification was tapped. A flag only: it carries no page address. */
    val pendingShowCast = MutableStateFlow(false)

    /**
     * How many screens that show secrets (Settings > Passwords) are on screen. Above zero, MainActivity protects the
     * window (FLAG_SECURE, no Recents snapshot) as it does for private tabs.
     */
    val sensitiveScreens = MutableStateFlow(0)

    /** A password Orbis copied, cleared from the clipboard after a minute (memory only). */
    val sensitiveClip = com.shibi.orbis.passwords.SensitiveClip()
}
