package com.shibi.orbis.engine

import android.content.Context
import android.view.View
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin browser-engine surface. UI and feature modules must not import GeckoView types;
 * only [com.shibi.orbis.engine.GeckoEngineImpl] may.
 */
/** A login as the engine sees it (no app types in the engine layer). */
data class EngineLogin(
    val guid: String?,
    val origin: String,
    val formActionOrigin: String,
    val httpRealm: String,
    val username: String,
    val password: String,
)

/**
 * The app's password store as the engine uses it: what to fill, whether to offer saving, and what to record. Set once
 * the store exists; until then nothing is filled or offered.
 */
interface LoginBridge {
    fun fetch(domain: String): List<EngineLogin>

    /** Offer "Save password?" here? Never from a private tab, a site marked Never, or with saving switched off. */
    fun offerSave(origin: String, isPrivate: Boolean): Boolean

    fun isUpdate(origin: String, username: String, password: String): Boolean

    fun save(login: EngineLogin)

    fun used(guid: String)

    fun neverFor(origin: String)
}

interface BrowserEngine {
    val extensions: ExtensionHost
    /** The password store; see [LoginBridge]. */
    var logins: LoginBridge?
    /** Something was blocked on a page (tracking protection); see PrivacyStats. [isPrivate] is the page's mode. */
    var onContentBlocked: ((kind: com.shibi.orbis.stats.BlockKind, uri: String?, isPrivate: Boolean) -> Unit)?
        get() = null
        set(@Suppress("UNUSED_PARAMETER") value) {}

    /**
     * A blocked YouTube ad request, or the title of a YouTube watch page, for the on-device time-saved estimate.
     * The built-in observer does not run in private tabs. Content blocking still passes private pages through; the
     * store drops them.
     */
    var onYoutubeAdEvent: ((event: com.shibi.orbis.youtube.YoutubeAdEvent) -> Unit)?
        get() = null
        set(@Suppress("UNUSED_PARAMETER") value) {}
    /** Search for text the user selected on a page (selection menu > Search); [isPrivate] is the page's mode. */
    var onSelectionSearch: ((text: String, isPrivate: Boolean) -> Unit)?
        get() = null
        set(@Suppress("UNUSED_PARAMETER") value) {}
    /** The system permission dialogs Gecko needs. App level (not per tab); the browser screen launches them. */
    val androidPermissions: AndroidPermissionBroker
    /**
     * Our decision store is the only authority for location: tells the engine to forget its own copies of location
     * answers. Call after a stored decision was removed. Main thread.
     */
    fun reconcileSitePermissions()
    fun warmUp()
    /**
     * Page text size in percent (Settings > Text size). 100 leaves the engine's own default (which follows the system
     * font scale); anything else sets a fixed factor for every page. Applies now and to a runtime created later.
     */
    fun setTextZoom(percent: Int) {}
    /** Lets every page be pinch-zoomed, even one that disables zooming. Applies now and later. */
    fun setForceZoom(enabled: Boolean) {}
    /** HTTPS-only mode: http pages are upgraded, and refused when the site has no https version. */
    fun setHttpsOnly(normalTabs: Boolean, privateTabs: Boolean) {}
    /** The color scheme pages are asked for (prefers-color-scheme): true dark, false light, null the system's. */
    fun setWebsiteColorScheme(dark: Boolean?) {}
    /** Darkens light pages that have no dark design (a built-in extension; images and videos keep their colours). */
    fun setForceDark(on: Boolean) {}
    /** Per-site desktop and JavaScript choices, read before each top-level load. */
    fun setSitePolicy(policy: SitePolicy) {}
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
    /**
     * Clears one site's cookies, site storage, sign-in sessions and cache in the normal profile (Site settings).
     * Permissions and the content blocker's settings stay. False when the engine is not running or it failed.
     */
    suspend fun clearSiteData(url: String): Boolean = false
    /**
     * Fetches [url] again from byte [fromByte] (a paused download being resumed), in the private context when
     * [isPrivate]. Null when the request failed. The server may ignore the range: then [IncomingDownload.startOffset] is 0.
     * [ifRange] (the first response's [IncomingDownload.validator]) is sent as `If-Range`, so a changed file comes back whole.
     */
    suspend fun fetchFrom(url: String, fromByte: Long, isPrivate: Boolean, ifRange: String? = null): IncomingDownload? = null
    /**
     * Clears the engine's cookies and site data ([cookiesAndSiteData]) and/or its caches ([cache]). Never touches site
     * permission answers or settings. Gecko has no normal-only scope for this: it reaches private storage too, so a
     * caller must make sure no private tab is open. Clearing cookies and site data may also drop open network
     * connections, such as a running download. Main thread. Returns true only when Gecko confirmed the clear; false if
     * the engine is not running, refused, or failed. Selecting neither returns true without calling Gecko.
     */
    suspend fun clearProfileData(cookiesAndSiteData: Boolean, cache: Boolean): Boolean
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
    /** The page asked for fullscreen (e.g. a video's fullscreen button) and the engine granted it. */
    val fullScreen: Boolean = false,
    /**
     * The user turned tracking protection off for this site (a site exception): Strict ETP does not apply to it.
     * Null when the page has no web site (blank, about:). Read from the engine's own permission record.
     */
    val trackingProtectionOff: Boolean? = null,
    /** JavaScript runs on the current page (Settings > JavaScript and the page menu's per-site choice). */
    val javaScriptOn: Boolean = true,
    /** The last load failed because HTTPS-only mode found no secure version of the site. */
    val httpsOnlyBlocked: Boolean = false,
    /** What the on-device translator knows about the page; null until it reported. */
    val translation: TranslationInfo? = null,
    /** The page looks like an article (Readability's check): reader view is offered. Reset at each page start. */
    val readerable: Boolean = false,
    /** The page's media (audio or video): null when it has none active. */
    val media: MediaState? = null,
    /** A video went fullscreen and is wider than tall: the screen turns to landscape, like Chrome and Firefox. */
    val fullScreenVideoLandscape: Boolean = false,
    /**
     * Monotonic counters that make load boundaries observable even when the state flow conflates them (a collector only
     * ever sees the latest value, so "started, then failed" can look like "nothing happened"). Never reset.
     * [loadStarts]: top-level page loads started (onPageStart).
     */
    val loadStarts: Int = 0,
    /**
     * Load-end events by any route: page stop, load error, content process gone. It counts events, not loads: Gecko may
     * report an error and then still call onPageStop for the same load, so one failed load can count twice. Consumers
     * only compare it with an earlier snapshot ("did any end event arrive since"); never read it as a number of loads.
     */
    val loadEnds: Int = 0,
    /**
     * Load errors (onLoadError) only, so "the load failed" stays visible when the error arrives without a start, or
     * its start and error are conflated into one emission. A stop or abort of an old load is NOT an error and does not
     * move it.
     */
    val loadErrors: Int = 0,
    /**
     * Navigations that were asked for but cannot complete: a beforeunload prompt ended without leaving the page (stay,
     * dismissed by Gecko, the queue, a crash), or the content process died.
     */
    val navigationsCancelled: Int = 0,
)

// The one definition of how engine events change the session state, so the counters are maintained in one place and
// the address bar's tests drive the same transitions the engine applies. url is never written here (onLocationChange).

internal fun SessionState.afterPageStart(): SessionState =
    copy(
        isLoading = true,
        progress = 0,
        loadFailed = false,
        failedUrl = null,
        httpsOnlyBlocked = false,
        crashed = false,
        findResult = null,
        loadStarts = loadStarts + 1,
    )

internal fun SessionState.afterPageStop(success: Boolean): SessionState =
    copy(isLoading = false, progress = if (success) 100 else 0, loadEnds = loadEnds + 1)

/** [failedUrl] is the http(s) address that failed, or null. url is deliberately untouched: the old page is still displayed. */
internal fun SessionState.afterLoadError(failedUrl: String?): SessionState =
    copy(
        failedUrl = failedUrl,
        loadFailed = true,
        isLoading = false,
        progress = 0,
        loadEnds = loadEnds + 1,
        loadErrors = loadErrors + 1,
    )

internal fun SessionState.afterContentProcessGone(): SessionState =
    copy(
        crashed = true,
        isLoading = false,
        progress = 0,
        canGoBack = false,
        canGoForward = false,
        findResult = null,
        fullScreen = false,
        fullScreenVideoLandscape = false,
        media = null,
        loadEnds = loadEnds + 1,
        navigationsCancelled = navigationsCancelled + 1,
    )

/** A beforeunload prompt ended without the user leaving: the navigation that raised it will not happen. */
internal fun SessionState.afterNavigationCancelled(): SessionState = copy(navigationsCancelled = navigationsCancelled + 1)

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
    /**
     * Navigates to about:blank (Home with no custom homepage): a blank tab shows the Orbis start page. The only non-web
     * load a session accepts, and it takes no argument. Test doubles may leave it a no-op.
     */
    fun showStartPage() {}
    /** A URL handed to us by another app (VIEW intent). http(s) only. */
    fun loadExternalUrl(url: String)

    /**
     * A PDF or HTML file copied into the import cache. The engine loads it only when the URL is one of those
     * files; anything else is ignored. Private sessions ignore it. A name ending in `.pdf` is what makes
     * GeckoView open its built-in pdf.js viewer (see the engine implementation).
     */
    fun loadImportedFile(url: String) {}
    /** Switches user agent and viewport between mobile and desktop, then reloads the page. */
    fun setDesktopMode(enabled: Boolean)
    /** Returns false (and does nothing) when there is no history to go back to. */
    fun goBack(): Boolean
    /** Returns false (and does nothing) when there is no history to go forward to. */
    fun goForward(): Boolean
    fun reload()
    fun stop()
    /**
     * Parses the current page for reader view; [onResult] gets null when it cannot (not an article, no page, the
     * reader is not ready). Runs on the main thread. Test doubles may answer null.
     */
    fun requestReaderArticle(onResult: (ReaderArticle?) -> Unit) = onResult(null)
    /**
     * Translates the page on the device from its detected language into the phone's language (the language pack is
     * downloaded from Mozilla the first time). [onDone] gets false on failure. Test doubles answer false.
     */
    fun translatePage(onDone: (Boolean) -> Unit) = onDone(false)
    /** Shows the page as it was before translating. */
    fun showOriginal() {}
    /** Loads an installed extension's own page (moz-extension://, e.g. its settings). Anything else is refused. */
    fun loadExtensionPage(url: String) {}
    /** Opens the system print dialog for the page (which also saves as PDF). Needs the page shown in a view. */
    fun printPage() {}
    /** Turns JavaScript on or off for this tab's page and reloads it. */
    fun setJavaScriptEnabled(enabled: Boolean) {}
    /** Media controls for the page's active media (notification, lock screen, PiP). Test doubles may no-op. */
    fun mediaPlay() {}
    fun mediaPause() {}
    fun mediaStop() {}

    /**
     * Reads video and audio addresses from the current page. [onResult] gets one JSON string, or null when the page
     * cannot be read. Main thread. Test doubles answer null.
     */
    fun probeCastMedia(onResult: (String?) -> Unit) = onResult(null)

    /** Pauses every video and audio element on the page, including ones with no media session. Test doubles may no-op. */
    fun pausePageMedia() {}
    /** Leaves page fullscreen (Back, a tab switch); the engine then reports fullScreen = false. Test doubles may no-op. */
    fun exitFullScreen() {}
    /**
     * Fetches [url] (http/https) in this session's mode (private stays private) and offers the response through the
     * normal download path (host.onDownload: the same dialog, file naming and private handling). "Save image".
     */
    fun saveUrl(url: String) {}

    /** True when the built-in PDF viewer shows the current page. False on any doubt. Test doubles answer false. */
    suspend fun isPdfDocument(): Boolean = false

    /**
     * The PDF the built-in viewer is showing, as the viewer holds it (no new network request). Null when the page is
     * not a PDF, the engine does not answer in time, or the save fails. The caller ends it with openBody or discard.
     */
    suspend fun currentPdf(): IncomingDownload? = null
    /**
     * Opens a non-http(s) link in another app (magnet:, tel:, intent:, …). The user already chose it, from a tap the
     * engine handled itself or from the long-press menu. Test doubles may no-op.
     */
    fun openExternalScheme(url: String) {}
    /**
     * Turns tracking protection off ([off] true) or back on for the current page's site, then reloads it. Private tabs
     * keep the exception only for the private session. No-op without a web page. Test doubles may no-op.
     */
    fun setTrackingProtectionOff(off: Boolean) {}
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
/** The user's per-site choices. Private tabs read the normal choices but their own changes are kept apart. */
interface SitePolicy {
    fun desktopFor(url: String, isPrivate: Boolean): Boolean
    fun javaScriptFor(url: String, isPrivate: Boolean): Boolean
    /** Offer to open web links in the app that handles them (Settings > Open links in apps). */
    fun offerAppsForLinks(): Boolean = true
    /** Tapped magnet: links go to Orbis's torrent downloader instead of another app (Settings > Magnet links). */
    fun magnetsToOrbis(): Boolean = false
}

/** The translator's view of a page. Language tags are BCP 47 ("en", "fr", "zh-Hans"); empty when unknown. */
data class TranslationInfo(
    val pageLanguage: String,
    val userLanguage: String,
    val pageLanguageSupported: Boolean,
    /** The language the page is shown in after a translation, or empty when it is the original. */
    val translatedTo: String = "",
    val error: String = "",
) {
    /** Translate is offered: the page's language is known, supported, and not already the user's. */
    val canTranslate: Boolean get() =
        pageLanguageSupported && pageLanguage.isNotEmpty() && userLanguage.isNotEmpty() && !sameLanguage(pageLanguage, userLanguage)
}

/** "en-US" and "en" are the same language for this purpose. */
fun sameLanguage(a: String, b: String): Boolean = a.substringBefore('-').equals(b.substringBefore('-'), ignoreCase = true)

/** A page's active media. [title]/[artist] come from the page (Media Session metadata) and may be empty. */
data class MediaState(
    val playing: Boolean,
    val title: String = "",
    val artist: String = "",
    /** The media element has video (picture-in-picture makes sense). */
    val hasVideo: Boolean = false,
    /** Video size when known (for the picture-in-picture shape); 0 when unknown. */
    val videoWidth: Long = 0,
    val videoHeight: Long = 0,
)

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
    /** Long-press on an image ([linkUrl] when the image is also a link). Test doubles may ignore it. */
    fun onImageContextMenu(imageUrl: String, linkUrl: String?, session: BrowserSession) {}
    /**
     * Long-press on a link and/or image, including non-http schemes such as magnet:. The engine calls this instead of
     * [onLinkContextMenu] so the address is not dropped for failing to be http(s).
     */
    fun onPageContextMenu(menu: PageContextMenu, session: BrowserSession) {}
    /** No installed app could open [url]. The UI offers to copy it. */
    fun onExternalAppMissing(url: String) {}
    /**
     * A tapped magnet: link for Orbis's own downloader (Settings > Magnet links). True when the host shows the torrent
     * confirm sheet for it; false (the default, e.g. a Custom Tab) sends the link down the "open in another app" path.
     */
    fun onTorrentIntake(request: com.shibi.orbis.torrent.IntakeRequest, session: BrowserSession): Boolean = false
    /** The session's saved-state blob changed (history, scroll position...). */
    fun onSessionStateChanged(session: BrowserSession)
    /** A page was visited (a finished load, or a same-document navigation). [title] may still be empty. */
    fun onVisit(session: BrowserSession, url: String, title: String)
    /** The page that [onVisit] reported for [url] got or changed its title. Never creates a visit. */
    fun onVisitTitle(session: BrowserSession, url: String, title: String)
}
