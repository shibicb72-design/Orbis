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
    /** The system permission dialogs Gecko needs. App level (not per tab); the browser screen launches them. */
    val androidPermissions: AndroidPermissionBroker
    /**
     * Our decision store is the only authority for location: tells the engine to forget its own copies of location
     * answers. Call after a stored decision was removed. Main thread.
     */
    fun reconcileSitePermissions()
    fun warmUp()
    /**
     * Main thread only. The ONE factory for sessions (new, restored and popup children alike), so ETP Strict
     * (useTrackingProtection) is applied to every one of them.
     */
    fun createSession(init: SessionInit = SessionInit()): BrowserSession
    /** Returns an engine-backed view (GeckoView behind the scenes). */
    fun createContentView(context: Context): View
    /**
     * Discards what the ended private set stored in the engine (cookies, site data, cache keyed to it). Call once the
     * last private session has closed. Never touches the normal profile. Main thread.
     */
    fun clearPrivateData()
    fun shutdown()
}

/**
 * How to start a session. [restoredState] is an opaque blob from [BrowserSession.savedState]; [fallbackUrl] (http/https
 * only) is loaded when there is no state or it cannot be parsed, never in addition to it. [isPrivate] sessions leave
 * nothing in the normal profile and share one storage scope that [BrowserEngine.clearPrivateData] discards.
 */
data class SessionInit(
    val desktopMode: Boolean = false,
    val restoredState: String? = null,
    val fallbackUrl: String? = null,
    val isPrivate: Boolean = false,
)

/** Result of the latest find-in-page query: [current] is 1-based, [total] the match count. */
data class FindResult(val found: Boolean, val current: Int, val total: Int)

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
    val desktopMode: Boolean = false,
    val findResult: FindResult? = null,
)

interface BrowserSession {
    /** True for a private session. Fixed for the session's lifetime. */
    val isPrivate: Boolean
    val state: StateFlow<SessionState>
    /** Head of this session's prompt queue; null when nothing is pending. */
    val prompts: StateFlow<SitePrompt?>
    /** Callbacks that need the tab layer. Set by whoever owns the session (the tab manager). */
    var host: SessionHost?
    /** Puts this session in [contentView], releasing whatever the view held first. No-op if it already holds it. */
    fun attachTo(contentView: View)
    /** Releases the session from [contentView] if that view currently holds it. Does not close it. */
    fun detachFrom(contentView: View)
    fun loadUrl(url: String)
    /** A URL handed to us by another app (VIEW intent). http(s) only. */
    fun loadExternalUrl(url: String)
    /** Switches user agent and viewport between mobile and desktop, then reloads the page. */
    fun setDesktopMode(enabled: Boolean)
    /** Returns false (and does nothing) when there is no history to go back to. */
    fun goBack(): Boolean
    /** Returns false (and does nothing) when there is no history to go forward to. */
    fun goForward(): Boolean
    fun reload()
    fun stop()
    fun find(query: String, backwards: Boolean)
    fun clearFind()
    /** False for background tabs. */
    fun setActive(active: Boolean)
    /** The latest engine session-state blob (history, scroll, form data), or null before the first one. */
    fun savedState(): String?
    /** Dismisses every pending prompt, then closes the engine session. Idempotent. */
    fun close()
}

/** Everything a session needs from the tab layer. All calls arrive on the main thread. */
interface SessionHost {
    /**
     * True if one more tab may be opened. Returns false (and tells the user once) at the tab cap.
     * The engine asks before accepting a popup.
     */
    fun canOpenTab(): Boolean
    /** Long-press "open in new tab". */
    fun onOpenInNewTab(url: String, opener: BrowserSession)
    /** window.open / target=_blank. [child] is NOT yet open: the engine opens it, so never open it here. */
    fun onPopupSession(child: BrowserSession, opener: BrowserSession)
    /** window.close */
    fun onCloseRequest(session: BrowserSession)
    /** The engine will not render this response. The host must end it with openBody or discard. */
    fun onDownload(download: IncomingDownload, session: BrowserSession)
    fun onLinkContextMenu(url: String, session: BrowserSession)
    /** The session's saved-state blob changed (history, scroll position...). */
    fun onSessionStateChanged(session: BrowserSession)
    /** A page was visited (a finished load, or a same-document navigation). [title] may still be empty. */
    fun onVisit(session: BrowserSession, url: String, title: String)
    /** The page that [onVisit] reported for [url] got or changed its title. Never creates a visit. */
    fun onVisitTitle(session: BrowserSession, url: String, title: String)
}
