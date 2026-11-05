package com.shibi.orbis

import android.app.Application
import android.content.Context
import com.shibi.orbis.debug.DebugLog
import com.shibi.orbis.engine.BrowserEngine
import com.shibi.orbis.engine.BrowserSession
import com.shibi.orbis.engine.GeckoEngineImpl
import com.shibi.orbis.ubo.SharedPreferencesUboPrefs
import com.shibi.orbis.ubo.UboOnboardingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
        container = AppContainer(debugLog = debugLog, engine = engine, ubo = ubo)
        runCatching { engine.warmUp() }
            .onFailure { debugLog.log("OrbisApp", "engine warmUp failed: ${it.message}") }
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
) {
    /** The single browser session. Owned here (not by a composable) so it outlives screens; first access on the main thread. */
    val session: BrowserSession by lazy { engine.createSession() }
}
