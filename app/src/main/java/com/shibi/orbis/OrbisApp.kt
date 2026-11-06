package com.shibi.orbis

import android.app.Application
import android.content.Context
import android.os.Environment
import android.webkit.MimeTypeMap
import com.shibi.orbis.debug.DebugLog
import com.shibi.orbis.downloads.DownloadManager
import com.shibi.orbis.downloads.DownloadStore
import com.shibi.orbis.engine.BrowserEngine
import com.shibi.orbis.engine.GeckoEngineImpl
import com.shibi.orbis.tabs.FileTabStore
import com.shibi.orbis.tabs.TabManager
import com.shibi.orbis.ubo.SharedPreferencesUboPrefs
import com.shibi.orbis.ubo.UboOnboardingController
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

class OrbisApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Gecko child processes (":tab", ":gpu", ...) run this too; they must not create a runtime or container.
        if (Application.getProcessName() != packageName) return
        val debugLog = DebugLog(filesDir)
        val engine: BrowserEngine = GeckoEngineImpl(this, debugLog)
        // App-lifetime scope: installs must survive screens leaving composition.
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val uboPrefs =
            SharedPreferencesUboPrefs(
                getSharedPreferences(SharedPreferencesUboPrefs.FILE_NAME, Context.MODE_PRIVATE),
            )
        val ubo =
            UboOnboardingController(
                host = engine.extensions,
                prefs = uboPrefs,
                scope = appScope,
                log = { debugLog.log("Ubo", it) },
                now = System::currentTimeMillis,
            )
        // The tab manager creates NO session here: sessions appear only once the browser screen asks for them, which
        // is after the onboarding decision. restore() only reads the stored tab list.
        val tabs =
            TabManager(
                factory = { init -> engine.createSession(init) },
                store = FileTabStore(File(filesDir, "tabs/tabs.json")),
                scope = appScope,
                log = { debugLog.log("Tabs", it) },
            )
        val downloads =
            DownloadManager(
                dir = { getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(filesDir, "downloads") },
                store = DownloadStore(File(filesDir, "downloads_index.json")),
                scope = appScope,
                io = Dispatchers.IO,
                log = { debugLog.log("Downloads", it) },
                now = System::currentTimeMillis,
                extensionForMime = { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) },
            )
        container = AppContainer(debugLog = debugLog, engine = engine, ubo = ubo, tabs = tabs, downloads = downloads)
        runCatching { engine.warmUp() }
            .onFailure { debugLog.log("OrbisApp", "engine warmUp failed: ${it.message}") }
        tabs.restore()
        downloads.restore()
        ubo.start()
    }

    override fun onTerminate() {
        if (::container.isInitialized) runCatching { container.engine.shutdown() }
        super.onTerminate()
    }
}

class AppContainer(
    val debugLog: DebugLog,
    val engine: BrowserEngine,
    val ubo: UboOnboardingController,
    /** Owns every tab and session; outlives screens, so Settings never destroys a page. */
    val tabs: TabManager,
    val downloads: DownloadManager,
) {
    /**
     * An http(s) URL another app asked us to open. Set by MainActivity, consumed (and cleared) by BrowserScreen, which
     * only exists after the onboarding decision, so an intent arriving earlier waits and causes no network load.
     */
    val pendingExternalUrl = MutableStateFlow<String?>(null)
}
