package com.shibi.orbis.engine

import android.content.Context
import android.view.View
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin browser-engine surface. UI and feature modules must not import GeckoView types;
 * only [com.shibi.orbis.engine.GeckoEngineImpl] may.
 */
interface BrowserEngine {
    val extensions: ExtensionHost
    fun warmUp()
    /** Main thread only. */
    fun createSession(): BrowserSession
    /** Returns an engine-backed view (GeckoView behind the scenes). */
    fun createContentView(context: Context): View
    fun shutdown()
}

/**
 * Observable per-session state. [url] is the committed location only (it survives a crash so the page can be reloaded).
 * [failedUrl] is the http(s) address of a load that failed; it is never shown as the current page address.
 */
data class SessionState(
    val url: String = "",
    val title: String? = null,
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val loadFailed: Boolean = false,
    val crashed: Boolean = false,
    val failedUrl: String? = null,
)

interface BrowserSession {
    val state: StateFlow<SessionState>
    fun attachTo(contentView: View)
    /** Releases the session from [contentView] if that view currently holds it. Does not close it. */
    fun detachFrom(contentView: View)
    fun loadUrl(url: String)
    /** Returns false (and does nothing) when there is no history to go back to. */
    fun goBack(): Boolean
    /** Returns false (and does nothing) when there is no history to go forward to. */
    fun goForward(): Boolean
    fun reload()
    fun stop()
    fun close()
}
