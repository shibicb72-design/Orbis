package com.shibi.orbis.engine

import android.content.Context
import android.view.View
import com.shibi.orbis.debug.DebugLog
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

/**
 * Path B GeckoView implementation. Keeps Gecko types out of UI packages.
 * ETP Strict / ContentBlocking on as defense in depth (uBO AMO onboarding is separate).
 */
class GeckoEngineImpl(
    private val appContext: Context,
    private val debugLog: DebugLog,
) : BrowserEngine {
    @Volatile
    private var runtime: GeckoRuntime? = null

    override fun warmUp() {
        synchronized(this) {
            if (runtime != null) return
            val settings =
                GeckoRuntimeSettings.Builder()
                    .aboutConfigEnabled(false)
                    .build()
            // Baseline: Enhanced Tracking Protection Strict.
            settings.contentBlocking.setEnhancedTrackingProtectionLevel(
                ContentBlocking.EtpLevel.STRICT,
            )
            runtime = GeckoRuntime.create(appContext, settings)
            debugLog.log("GeckoEngine", "runtime created; ETP=STRICT")
        }
    }

    override fun createSession(): BrowserSession {
        warmUp()
        val sessionSettings =
            GeckoSessionSettings.Builder()
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .build()
        val session = GeckoSession(sessionSettings)
        session.open(requireRuntime())
        return GeckoBrowserSession(session, debugLog)
    }

    override fun createContentView(context: Context): View {
        warmUp()
        return GeckoView(context)
    }

    override fun shutdown() {
        synchronized(this) {
            runtime?.shutdown()
            runtime = null
            debugLog.log("GeckoEngine", "runtime shut down")
        }
    }

    private fun requireRuntime(): GeckoRuntime =
        runtime ?: error("GeckoRuntime not warmed up")
}

private class GeckoBrowserSession(
    private val session: GeckoSession,
    private val debugLog: DebugLog,
) : BrowserSession {
    override fun attachTo(contentView: View) {
        val geckoView = contentView as? GeckoView
            ?: error("contentView must be created by BrowserEngine.createContentView")
        geckoView.setSession(session)
    }

    override fun loadUrl(url: String) {
        val normalized =
            when {
                url.startsWith("http://") || url.startsWith("https://") -> url
                url.contains('.') && !url.contains(' ') -> "https://$url"
                else -> "https://duckduckgo.com/?q=${java.net.URLEncoder.encode(url, Charsets.UTF_8.name())}"
            }
        session.loadUri(normalized)
        debugLog.log("GeckoSession", "load $normalized")
    }

    override fun goBack(): Boolean {
        session.goBack()
        return true
    }

    override fun goForward(): Boolean {
        session.goForward()
        return true
    }

    override fun reload() {
        session.reload()
    }

    override fun stop() {
        session.stop()
    }

    override fun close() {
        runCatching { session.close() }
            .onFailure { debugLog.log("GeckoSession", "close failed: ${it.message}") }
    }
}