package com.shibi.orbis

import android.app.Application
import com.shibi.orbis.debug.DebugLog
import com.shibi.orbis.engine.BrowserEngine
import com.shibi.orbis.engine.GeckoEngineImpl

class OrbisApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val debugLog = DebugLog(filesDir)
        val engine: BrowserEngine = GeckoEngineImpl(this, debugLog)
        container = AppContainer(debugLog = debugLog, engine = engine)
        runCatching { engine.warmUp() }
            .onFailure { debugLog.log("OrbisApp", "engine warmUp failed: ${it.message}") }
    }

    override fun onTerminate() {
        runCatching { container.engine.shutdown() }
        super.onTerminate()
    }
}

data class AppContainer(
    val debugLog: DebugLog,
    val engine: BrowserEngine,
)