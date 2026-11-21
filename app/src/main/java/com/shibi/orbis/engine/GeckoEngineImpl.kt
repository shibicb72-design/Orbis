package com.shibi.orbis.engine

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.shibi.orbis.compat.AUTO_DESKTOP_RETRY
import com.shibi.orbis.compat.Action
import com.shibi.orbis.compat.AutoDesktopRetry
import com.shibi.orbis.compat.isUnsupportedBrowserPage
import com.shibi.orbis.debug.DebugLog
import com.shibi.orbis.downloads.DownloadIntents
import com.shibi.orbis.history.VisitTracker
import com.shibi.orbis.youtube.CONTENT_BLOCKING_ERROR
import com.shibi.orbis.youtube.YoutubeAdEvent
import com.shibi.orbis.youtube.YoutubeAdObservation
import com.shibi.orbis.youtube.mightReportYoutubeAd
import com.shibi.orbis.youtube.titleForSameVideo
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import org.json.JSONObject
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.TranslationsController
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebExtensionController
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse

/**
 * Path B GeckoView implementation. Keeps Gecko types out of UI packages.
 * ETP Strict / ContentBlocking on as defense in depth (uBO AMO onboarding is separate).
 */
class GeckoEngineImpl(
    private val appContext: Context,
    private val debugLog: DebugLog,
    private val decisions: SitePermissionDecisions,
    private val broker: AndroidPermissionBroker,
) : BrowserEngine {
    @Volatile
    private var runtime: GeckoRuntime? = null

    private val reconciler = GeolocationReconciler({ runtime }, debugLog)

    /** The built-in reader view extension (see ReaderExtensionHolder). */
    private val reader = ReaderExtensionHolder { debugLog.log("GeckoEngine", it) }

    /** Page probe for Cast (see CastExtensionHolder). Allowed in private tabs; it stores nothing. */
    private val castExtension = CastExtensionHolder { debugLog.log("GeckoEngine", it) }

    /** "Unsupported browser" probe for the auto-desktop retry (see CompatExtensionHolder). Only with AUTO_DESKTOP_RETRY. */
    private val compatExtension = CompatExtensionHolder { debugLog.log("GeckoEngine", it) }

    /** Notices blocked YouTube ad requests for the on-device time-saved estimate. Not installed in private tabs. */
    private val youtubeAdObserver = YoutubeAdExtensionHolder(onEvent = { onYoutubeAdEvent }, log = { debugLog.log("GeckoEngine", it) })

    /** Keeps the clear-on-exit exceptions' cookies (see clearProfileDataExcept). Disabled except during that wipe. */
    private val clearExit = ClearExitExtensionHolder { debugLog.log("GeckoEngine", it) }

    /** Sites with tracking protection turned off by the user (see TrackingExceptions). */
    private val trackingExceptions = TrackingExceptions()

    override val androidPermissions: AndroidPermissionBroker get() = broker

    @Volatile override var logins: LoginBridge? = null

    override fun reconcileSitePermissions() = reconciler.runNow()

    private val extensionHost =
        GeckoExtensionHost(debugLog) {
            warmUp()
            requireRuntime()
        }

    override val extensions: ExtensionHost get() = extensionHost

    /** Picked files copied for upload. Wiped once per process start, never while a page may still hold one. */
    private val uploads = UploadCache(appContext.cacheDir)
    private val uploadsCleared = AtomicBoolean(false)

    /** Temps the camera app writes photos into. Wiped with [uploads]: a photo is copied to uploads before Gecko sees it. */
    private val captureFiles = CameraCaptureFiles(appContext.cacheDir)

    /** Private sessions built and not yet closed, popups included. See [recordsLoginUse]. */
    private val privateSessions = AtomicInteger(0)

    /** Gecko asks for and reports logins here; the app store answers through [logins]. Nothing is logged about them. */
    private fun loginStorageDelegate() =
        object : Autocomplete.StorageDelegate {
            override fun onLoginFetch(domain: String): GeckoResult<Array<Autocomplete.LoginEntry>>? {
                val found = logins?.fetch(domain).orEmpty()
                return GeckoResult.fromValue(found.map { it.toGecko() }.toTypedArray())
            }

            override fun onLoginSave(login: Autocomplete.LoginEntry) {
                logins?.save(login.toEngine())
            }

            override fun onLoginUsed(login: Autocomplete.LoginEntry, usedFields: Int) {
                // The delegate is per runtime and is not told which session used the login: while any private session
                // exists, no use is recorded, so a private tab never leaves a "last used" trace.
                if (!recordsLoginUse(privateSessions.get())) return
                login.guid?.let { logins?.used(it) }
            }
        }

    override var onSelectionSearch: ((String, Boolean) -> Unit)? = null
    override var onContentBlocked: ((com.shibi.orbis.stats.BlockKind, String?, Boolean) -> Unit)? = null
    override var onYoutubeAdEvent: ((YoutubeAdEvent) -> Unit)? = null

    // Matches the setting's default (HttpsOnlyMode.ALL) before the setting flow delivers.
    @Volatile private var httpsOnlyNormal = true
    @Volatile private var httpsOnlyPrivate = true
    @Volatile private var websiteDark: Boolean? = null
    @Volatile private var sitePolicy: SitePolicy? = null

    @Volatile private var forceDarkWanted = false
    @Volatile private var darkenExtension: WebExtension? = null

    override fun setForceDark(on: Boolean) {
        forceDarkWanted = on
        runtime?.let(::applyForceDark)
    }

    /** Installs the built-in darken extension once, then keeps it enabled exactly while [forceDarkWanted]. */
    private fun applyForceDark(rt: GeckoRuntime) {
        val controller = rt.webExtensionController
        val ext = darkenExtension
        if (ext == null) {
            if (!forceDarkWanted) return
            controller.ensureBuiltIn(DARKEN_ASSET_URL, DARKEN_ID).accept(
                { installed ->
                    if (installed == null) return@accept
                    darkenExtension = installed
                    controller.ensureAllowedInPrivateBrowsing(installed, true).accept({ applyForceDark(rt) }, { applyForceDark(rt) })
                },
                { debugLog.log("GeckoEngine", "darken extension install failed: ${it?.javaClass?.simpleName}") },
            )
            return
        }
        val enabled = ext.metaData.enabled
        val next =
            when {
                forceDarkWanted && !enabled -> controller.enable(ext, WebExtensionController.EnableSource.APP)
                !forceDarkWanted && enabled -> controller.disable(ext, WebExtensionController.EnableSource.APP)
                else -> null
            }
        next?.accept({ updated -> if (updated != null) darkenExtension = updated }, { debugLog.log("GeckoEngine", "darken toggle failed") })
        debugLog.log("GeckoEngine", "force dark=$forceDarkWanted")
    }

    override fun setSitePolicy(policy: SitePolicy) {
        sitePolicy = policy
    }

    override fun setHttpsOnly(normalTabs: Boolean, privateTabs: Boolean) {
        httpsOnlyNormal = normalTabs
        httpsOnlyPrivate = privateTabs
        runtime?.let(::applyPagePrefs)
    }

    @Volatile private var secureDnsUrl: String? = null

    override fun setSecureDns(url: String?) {
        secureDnsUrl = url
        runtime?.let(::applySecureDns)
    }

    private fun applySecureDns(rt: GeckoRuntime) {
        val url = secureDnsUrl
        runCatching {
            if (url == null) {
                rt.settings.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_OFF)
            } else {
                rt.settings.setTrustedRecursiveResolverUri(url)
                rt.settings.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_FIRST)
            }
        }.onFailure { debugLog.log("GeckoEngine", "secure dns failed: ${it.javaClass.simpleName}") }
        debugLog.log("GeckoEngine", "secure dns on=${url != null}")
    }

    override fun setWebsiteColorScheme(dark: Boolean?) {
        websiteDark = dark
        runtime?.let(::applyPagePrefs)
    }

    private fun applyPagePrefs(rt: GeckoRuntime) {
        runCatching {
            rt.settings.setAllowInsecureConnections(
                when {
                    httpsOnlyNormal -> GeckoRuntimeSettings.HTTPS_ONLY
                    httpsOnlyPrivate -> GeckoRuntimeSettings.HTTPS_ONLY_PRIVATE
                    else -> GeckoRuntimeSettings.ALLOW_ALL
                },
            )
            rt.settings.setPreferredColorScheme(
                when (websiteDark) {
                    true -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
                    false -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
                    null -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
                },
            )
        }.onFailure { debugLog.log("GeckoEngine", "page prefs failed: ${it.javaClass.simpleName}") }
        debugLog.log("GeckoEngine", "page prefs httpsOnly=$httpsOnlyNormal/$httpsOnlyPrivate dark=$websiteDark")
    }

    @Volatile private var textZoomPercent = 100
    @Volatile private var forceZoom = false
    private var defaultAutomaticFontSize = true

    override fun setTextZoom(percent: Int) {
        textZoomPercent = percent
        runtime?.let(::applyDisplayPrefs)
    }

    override fun setForceZoom(enabled: Boolean) {
        forceZoom = enabled
        runtime?.let(::applyDisplayPrefs)
    }

    private fun applyDisplayPrefs(rt: GeckoRuntime) {
        val s = rt.settings
        // GeckoView refuses a font size factor while automatic adjustment is on: switch that off first.
        runCatching {
            if (textZoomPercent == 100 && defaultAutomaticFontSize) {
                s.setAutomaticFontSizeAdjustment(true)
            } else {
                // A fixed size wins over the system font scale, like Firefox's own text size setting.
                s.setAutomaticFontSizeAdjustment(false)
                s.setFontSizeFactor(textZoomPercent / 100f)
            }
            s.setForceUserScalableEnabled(forceZoom)
        }.onFailure { debugLog.log("GeckoEngine", "display prefs failed: ${it.javaClass.simpleName}") }
        debugLog.log("GeckoEngine", "display prefs text=$textZoomPercent% forceZoom=$forceZoom")
    }

    override fun warmUp() {
        synchronized(this) {
            if (runtime != null) return
            if (uploadsCleared.compareAndSet(false, true)) {
                Thread(
                    {
                        runCatching { uploads.clearAll() }
                        runCatching { captureFiles.clearAll() }
                    },
                    "orbis-upload-clear",
                ).start()
            }
            // Built via ContentBlocking.Settings.Builder: setEnhancedTrackingProtectionLevel alone only sets ETP prefs.
            val contentBlocking =
                ContentBlocking.Settings.Builder()
                    .antiTracking(ContentBlocking.AntiTracking.STRICT)
                    .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
                    .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
                    .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
                    .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                    .enhancedTrackingProtectionCategory(ContentBlocking.EtpCategory.STRICT)
                    .strictSocialTrackingProtection(true)
                    .build()
            val dnsUrl = secureDnsUrl
            val settings =
                GeckoRuntimeSettings.Builder()
                    .aboutConfigEnabled(false)
                    // No page addresses in the system log (brief priv11-2): sets consoleservice.logcat and devtools.console.stdout.chrome to false and the engine log level to Fatal.
                    .debugLogging(false)
                    .contentBlocking(contentBlocking)
                    .trustedRecursiveResolverMode(
                        if (dnsUrl == null) GeckoRuntimeSettings.TRR_MODE_OFF else GeckoRuntimeSettings.TRR_MODE_FIRST,
                    )
                    .apply { if (dnsUrl != null) trustedRecursiveResolverUri(dnsUrl) }
                    .build()
            val created = GeckoRuntime.create(appContext, settings)
            defaultAutomaticFontSize = created.settings.automaticFontSizeAdjustment
            applyDisplayPrefs(created)
            applyPagePrefs(created)
            applySecureDns(created)
            // The darken extension may already be installed from an earlier run: find it, then match the setting.
            created.webExtensionController.list().accept(
                { list ->
                    darkenExtension = list.orEmpty().firstOrNull { it.id == DARKEN_ID }
                    applyForceDark(created)
                },
                { applyForceDark(created) },
            )
            // Normal-profile exceptions the user made earlier (stored by the engine).
            created.storageController.allPermissions.accept(
                { list ->
                    val allowed =
                        list.orEmpty().filter {
                            it.permission == GeckoSession.PermissionDelegate.PERMISSION_TRACKING &&
                                it.value == GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW &&
                                !it.privateMode
                        }.map { it.uri }
                    trackingExceptions.loadNormal(allowed)
                    debugLog.log("GeckoEngine", "tracking exceptions loaded: ${allowed.size}")
                },
                { debugLog.log("GeckoEngine", "tracking exceptions load failed") },
            )
            created.autocompleteStorageDelegate = loginStorageDelegate()
            reader.install(created)
            castExtension.install(created)
            if (AUTO_DESKTOP_RETRY) compatExtension.install(created)
            youtubeAdObserver.install(created)
            YoutubePipExtension.install(created) { debugLog.log("GeckoEngine", it) }
            clearExit.install(created)
            // Without a prompt delegate, WebExtensionController.install() hangs forever.
            created.webExtensionController.setPromptDelegate(extensionHost.promptDelegate)
            runtime = created
            // The add-ons' toolbar buttons (the menu's "Add-ons" list).
            extensionHost.refreshActions()
            debugLog.log(
                "GeckoEngine",
                "runtime created; ETP level=STRICT category=STRICT antiTracking=STRICT cookies=isolate-others",
            )
            // Older builds denied location silently and Gecko stored every such answer for good.
            reconciler.runNow()
        }
    }

    /**
     * Storage scope shared by every session of the current private set (main thread). Rotated by [clearPrivateData],
     * so a session of a later set can never be given the id of an earlier one.
     */
    private var privateContextId = newPrivateContextId()

    override fun createSession(init: SessionInit): BrowserSession {
        val session = buildSession(init)
        session.start(init)
        debugLog.log("GeckoEngine", "session opened; useTrackingProtection=true private=${init.isPrivate}")
        return session
    }

    override fun clearPrivateData() {
        val ended = privateContextId
        // Rotated first: whatever races with the teardown below, a later private set starts in a scope the earlier one
        // never wrote to.
        privateContextId = newPrivateContextId()
        trackingExceptions.clearPrivate()
        try {
            // Only this id's data. StorageController.clearData(...) has no private scope and would wipe the normal
            // profile (cookies, cache, site data), so it must never be used here.
            runtime?.storageController?.clearDataForSessionContext(ended)
        } catch (e: Exception) {
            debugLog.log("GeckoEngine", "private data clear failed: ${e.javaClass.simpleName}")
        }
    }

    override suspend fun fetchFrom(url: String, fromByte: Long, isPrivate: Boolean, ifRange: String?): IncomingDownload? {
        if (!isWebUrl(url)) return null
        val rt = runtime ?: return null
        val builder = WebRequest.Builder(url).header("Range", "bytes=$fromByte-")
        // With If-Range the server sends the rest (206) only when the file is unchanged, else the whole file (200).
        if (ifRange != null) builder.header("If-Range", ifRange)
        val request = builder.build()
        val flags = if (isPrivate) GeckoWebExecutor.FETCH_FLAGS_PRIVATE else GeckoWebExecutor.FETCH_FLAGS_NONE
        return try {
            // GeckoWebExecutor must be used from the main thread; the caller is a download job on an IO thread.
            withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                GeckoWebExecutor(rt).fetch(request, flags).accept(
                    { response ->
                        if (response == null || response.statusCode !in 200..299) {
                            runCatching { response?.body?.close() }
                            if (cont.isActive) cont.resume(null)
                            return@accept
                        }
                        val range = response.headers.entries.firstOrNull { it.key.equals("content-range", true) }?.value
                        val start = if (response.statusCode == 206) rangeStart(range) ?: 0L else 0L
                        if (cont.isActive) cont.resume(GeckoIncomingDownload(response, start)) else runCatching { response.body?.close() }
                    },
                    { if (cont.isActive) cont.resume(null) },
                )
            }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            debugLog.log("GeckoEngine", "resume fetch failed: ${e.javaClass.simpleName}")
            null
        }
    }

    override suspend fun clearSiteData(url: String): Boolean {
        val host = hostOfUrl(url)?.lowercase() ?: return false
        val storage = runtime?.storageController ?: return false
        val flags = profileClearFlags(cookiesAndSiteData = true, cache = true)
        // The host itself, then its site (a.example.com and example.com share cookies); without a public suffix list
        // the site is the host without "www.". Neither ever reaches private data (separate context).
        val targets = listOf(host, host.removePrefix("www.")).distinct()
        return try {
            var ok = true
            for (t in targets) {
                ok = ok and suspendCancellableCoroutine { cont ->
                    storage.clearDataFromHost(t, flags).accept(
                        { if (cont.isActive) cont.resume(true) },
                        { if (cont.isActive) cont.resume(false) },
                    )
                }
                ok = ok and suspendCancellableCoroutine { cont ->
                    storage.clearDataFromBaseDomain(t, flags).accept(
                        { if (cont.isActive) cont.resume(true) },
                        { if (cont.isActive) cont.resume(false) },
                    )
                }
            }
            debugLog.log("GeckoEngine", "site data cleared ok=$ok")
            ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            debugLog.log("GeckoEngine", "site data clear failed: ${e.javaClass.simpleName}")
            false
        }
    }

    override suspend fun clearProfileData(cookiesAndSiteData: Boolean, cache: Boolean): Boolean {
        val flags = profileClearFlags(cookiesAndSiteData, cache)
        if (flags == 0L) return true
        val storage = runtime?.storageController
        if (storage == null) {
            debugLog.log("GeckoEngine", "profile data clear skipped: engine not running")
            return false
        }
        return try {
            // clearData has no private scope, which is why clearPrivateData above never uses it. Here it is used on
            // purpose, and that reach into private storage is why the caller must have no private tab open.
            suspendCancellableCoroutine { cont ->
                storage.clearData(flags).accept(
                    { if (cont.isActive) cont.resume(true) },
                    { error ->
                        debugLog.log("GeckoEngine", "profile data clear failed: ${error?.javaClass?.simpleName}")
                        if (cont.isActive) cont.resume(false)
                    },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            debugLog.log("GeckoEngine", "profile data clear failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Clear on exit (candidate A): the excepted sites' cookies are read by the built-in clearexit extension, everything
     * is cleared with [clearProfileData], then the extension writes those cookies back. A complete wipe: when the
     * extension does not answer in time the wipe still runs and the exceptions lose their cookies too. Logs counts only.
     */
    override suspend fun clearProfileDataExcept(exceptions: Set<String>): Boolean {
        val rt = runtime
        if (rt == null) {
            debugLog.log("GeckoEngine", "clear on exit skipped: engine not running")
            return false
        }
        if (exceptions.isEmpty()) return clearProfileData(cookiesAndSiteData = true, cache = true)
        val kept =
            try {
                withTimeoutOrNull(CLEAR_EXIT_KEEP_TIMEOUT_MILLIS) { clearExit.snapshot(rt, exceptions) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                debugLog.log("GeckoEngine", "clear on exit: exceptions read failed: ${e.javaClass.simpleName}")
                null
            }
        if (kept == null) debugLog.log("GeckoEngine", "clear on exit: exceptions not read; wiping without them")
        try {
            val ok = clearProfileData(cookiesAndSiteData = true, cache = true)
            if (ok && kept != null && kept > 0) {
                val restored = withTimeoutOrNull(CLEAR_EXIT_RESTORE_TIMEOUT_MILLIS) { clearExit.restore() }
                debugLog.log(
                    "GeckoEngine",
                    if (restored == null) "clear on exit: exceptions not written back" else "clear on exit: exceptions kept=${restored.first} failed=${restored.second}",
                )
            }
            return ok
        } finally {
            clearExit.stop(rt)
        }
    }

    private fun newPrivateContextId() = "orbis-private-${UUID.randomUUID()}"

    /**
     * The ONLY place a GeckoSession is constructed (new, restored and popup children), so every session gets
     * useTrackingProtection(true). The result is not open yet: [GeckoBrowserSession.start] opens it, while a popup
     * child is opened by Gecko itself after onNewSession returns it.
     */
    private fun buildSession(init: SessionInit): GeckoBrowserSession {
        warmUp()
        val sessionSettings =
            GeckoSessionSettings.Builder()
                .userAgentMode(
                    if (init.desktopMode) {
                        GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                    } else {
                        GeckoSessionSettings.USER_AGENT_MODE_MOBILE
                    },
                )
                .viewportMode(
                    if (init.desktopMode) {
                        GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                    } else {
                        GeckoSessionSettings.VIEWPORT_MODE_MOBILE
                    },
                )
                // Defaults to false: without this ETP does nothing on the session.
                .useTrackingProtection(true)
                .apply { if (init.isPrivate) usePrivateMode(true).contextId(privateContextId) }
                .build()
        return GeckoBrowserSession(
            runtime = requireRuntime(),
            appContext = appContext,
            uploads = uploads,
            captureFiles = captureFiles,
            session = GeckoSession(sessionSettings),
            initialDesktopMode = init.desktopMode,
            isPrivate = init.isPrivate,
            debugLog = debugLog,
            decisions = decisions,
            broker = broker,
            reconciler = reconciler,
            trackingExceptions = trackingExceptions,
            logins = { logins },
            onSelectionSearch = { onSelectionSearch },
            onBlocked = { onContentBlocked },
            reader = reader,
            castExtension = castExtension,
            compatExtension = compatExtension,
            sitePolicy = { sitePolicy },
            onYoutubeAd = { onYoutubeAdEvent },
            // A popup child takes the desktop/mobile choice of its own site (never the opener's page: that would carry
            // one site's desktop mode to another) and the opener's privacy. It is never opened by us.
            newPopupChild = { opener, uri ->
                buildSession(
                    SessionInit(
                        desktopMode = opener.desktopWantedFor(uri) ?: opener.state.value.desktopMode,
                        isPrivate = opener.isPrivate,
                    ),
                )
            },
            onClosed = { if (init.isPrivate) privateSessions.decrementAndGet() },
        ).also { if (init.isPrivate) privateSessions.incrementAndGet() }
    }

    override fun createContentView(context: Context): View {
        warmUp()
        return OrbisGeckoView(context)
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

/**
 * Whether Gecko's report that a saved login was used may update its "last used" time: only while no private session
 * exists. Conservative on purpose: a normal tab's use is not recorded while a private tab is open either.
 */
internal fun recordsLoginUse(privateSessions: Int): Boolean = privateSessions <= 0

/**
 * The StorageController.ClearFlags for [GeckoEngineImpl.clearProfileData]: cookies, auth sessions and DOM storage for
 * cookies and site data, all caches for the cache. Deliberately never ALL, SITE_DATA, PERMISSIONS or SITE_SETTINGS: the
 * site permission answers and the content blocker's settings must survive.
 */
internal fun profileClearFlags(cookiesAndSiteData: Boolean, cache: Boolean): Long {
    var flags = 0L
    if (cookiesAndSiteData) {
        flags = flags or StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.AUTH_SESSIONS or
            StorageController.ClearFlags.DOM_STORAGES
    }
    if (cache) flags = flags or StorageController.ClearFlags.ALL_CACHES
    return flags
}

private typealias Response = GeckoSession.PromptDelegate.PromptResponse
private typealias AuthFlags = GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.Flags

private class GeckoBrowserSession(
    private val runtime: GeckoRuntime,
    private val appContext: Context,
    private val uploads: UploadCache,
    private val captureFiles: CameraCaptureFiles,
    private val session: GeckoSession,
    initialDesktopMode: Boolean,
    override val isPrivate: Boolean,
    private val debugLog: DebugLog,
    private val decisions: SitePermissionDecisions,
    private val broker: AndroidPermissionBroker,
    private val reconciler: GeolocationReconciler,
    private val trackingExceptions: TrackingExceptions,
    private val logins: () -> LoginBridge?,
    private val onSelectionSearch: () -> ((String, Boolean) -> Unit)?,
    private val onBlocked: () -> ((com.shibi.orbis.stats.BlockKind, String?, Boolean) -> Unit)?,
    private val reader: ReaderExtensionHolder,
    private val castExtension: CastExtensionHolder,
    private val compatExtension: CompatExtensionHolder,
    private val sitePolicy: () -> SitePolicy?,
    private val onYoutubeAd: () -> ((YoutubeAdEvent) -> Unit)?,
    private val newPopupChild: (opener: GeckoBrowserSession, uri: String) -> GeckoBrowserSession,
    /** Runs once, when this session closes. */
    private val onClosed: () -> Unit,
) : BrowserSession {
    private val _state = MutableStateFlow(SessionState(desktopMode = initialDesktopMode))
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Work tied to this session's prompts (file copies). Cancelled in [close]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** `<cacheDir>/imports`. The only `file:` tree this session will load. */
    private val importRoot = File(appContext.cacheDir, IMPORT_CACHE_DIR)

    /**
     * True after an imported document forced this session's JavaScript. Forward to a website skips
     * [onLoadRequest], so the site would otherwise keep the import's setting. Cleared once site policy is applied,
     * before any reload, so that reload does not apply it again.
     */
    private var scriptForcedByImport = false

    /**
     * Host (lowercase) of a pending auto-desktop retry: desktop for this tab only until the retry is kept or reverted.
     * Every other desktop choice comes from the site policy, which both menus store.
     */
    private var retryDesktopHost: String? = null

    /** A top-level page started since the last commit; a commit without one is same-document (hash, pushState). */
    private var pageStartedSinceCommit = false

    /** The last commit reloaded because its desktop mode was wrong (see [desktopAfterCommit]). */
    private var desktopReloadPending = false

    /** Host of the last committed web page, lowercase; null for a non-web page. */
    private var committedHost: String? = null

    /** This tab's back/forward list as Orbis saw it commit; Gecko reports its own too late (see [prepareHistoryStep]). */
    private val tabHistory = TabHistoryTracker()

    private val autoDesktop = AutoDesktopRetry()
    private val _autoDesktopKept = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val autoDesktopKept: SharedFlow<Unit> = _autoDesktopKept.asSharedFlow()

    private val queue = PromptQueue()
    override val prompts: StateFlow<SitePrompt?> = queue.head

    override var host: SessionHost? = null

    private val abuseGuard = DialogAbuseGuard(now = { SystemClock.elapsedRealtime() })

    /** Turns navigation callbacks into history visits. */
    private val visits = VisitTracker(now = SystemClock::elapsedRealtime)

    /** Identical pending camera/microphone asks share one dialog. */
    private data class MediaAsk(val origin: String, val embedder: String?, val kinds: Set<SitePermissionKind>)

    /** Private sessions neither read nor write the stored site decisions. */
    private val permissionPolicy = SessionPermissionPolicy(isPrivate, decisions::lookup, decisions::record)

    private val geoCoalescer = PermissionPromptCoalescer<SitePermissionKey>()
    private val mediaCoalescer = PermissionPromptCoalescer<MediaAsk>()

    /** Set once [close] has run; every later call is ignored (composition may still touch a closed session). */
    @Volatile
    private var closed = false

    /** What the tab layer last asked for. Applied once the session is open, which a popup child is not yet. */
    private var wantActive = false

    private var latestSessionState: GeckoSession.SessionState? = null

    /** Page URL that [SessionState.title] belongs to. YouTube's SPA updates the address and the title separately. */
    private var titleForUrl: String? = null
    private var findGeneration = 0

    /**
     * Upload copies handed to Gecko by a private session (main thread only). Normal sessions leave theirs for the wipe at
     * the next process start; a private one deletes them when it closes. A copy dismissed before Gecko got it is already
     * discarded and never listed. The camera temp needs no entry: [cameraBridge] deletes it when its prompt ends, which
     * is right after the copy.
     */
    private val privateUploads = ArrayList<File>()

    val geckoSession: GeckoSession get() = session

    init {
        // Delegates must be set before open().
        session.navigationDelegate = navigationDelegate()
        session.progressDelegate = progressDelegate()
        session.contentDelegate = contentDelegate()
        session.promptDelegate = promptDelegate()
        session.permissionDelegate = permissionDelegate()
        session.mediaSessionDelegate = mediaSessionDelegate()
        session.translationsSessionDelegate = translationDelegate()
        session.historyDelegate =
            object : GeckoSession.HistoryDelegate {
                override fun onHistoryStateChange(session: GeckoSession, historyList: GeckoSession.HistoryDelegate.HistoryList) {
                    history = historyList
                    tabHistory.onReportedList(historyList.map { it.uri }, historyList.currentIndex, _state.value.url)
                }
            }
        session.contentBlockingDelegate =
            object : ContentBlocking.Delegate {
                override fun onContentBlocked(session: GeckoSession, event: ContentBlocking.BlockEvent) {
                    if (!event.isBlocking) return
                    val uri = event.uri
                    if (!isPrivate && mightReportYoutubeAd(_state.value.url, uri)) {
                        onYoutubeAd()?.invoke(
                            YoutubeAdEvent.Blocked(
                                YoutubeAdObservation(
                                    pageUrl = _state.value.url,
                                    pageTitle = titleForSameVideo(_state.value.title, titleForUrl, _state.value.url),
                                    requestUrl = uri,
                                    isPrivate = false,
                                    error = CONTENT_BLOCKING_ERROR,
                                    atMillis = System.currentTimeMillis(),
                                ),
                            ),
                        )
                    }
                    val kind = blockKindOf(event.antiTrackingCategory, event.cookieBehaviorCategory) ?: return
                    onBlocked()?.invoke(kind, uri, isPrivate)
                }
            }
    }

    /** Opens the session (never called for a popup child: Gecko opens that one) and applies [init]. */
    fun start(init: SessionInit) {
        session.open(runtime)
        attachReader()
        attachCast()
        attachCompat()
        restorePage(init.restoredState, init.fallbackUrl)
    }

    /**
     * Brings a page back into the open session: from [blob] (a [savedState] string) when it parses, else by loading
     * [fallbackUrl] (http/https only). The path for a restored tab ([start]) and for a page whose content process died.
     */
    private fun restorePage(blob: String?, fallbackUrl: String?) {
        // Reopening a restored tab is not a visit.
        visits.suppress(fallbackUrl)
        // A restored state brings back its back/forward list, which Orbis has not seen.
        if (blob != null) tabHistory.onRestoreStarted()
        // A restored page reloads without passing onLoadRequest: its site's choices are applied first.
        fallbackUrl?.takeIf { isWebUrl(it) }?.let { url ->
            applyTrackingProtection(!trackingExceptions.isExcepted(url, isPrivate))
            applySitePolicy(url)
        }
        var restored = false
        if (blob != null) {
            val parsed = runCatching { GeckoSession.SessionState.fromString(blob) }.getOrNull()
            if (parsed != null) {
                restored = runCatching { session.restoreState(parsed) }.isSuccess
            }
            debugLog.log("GeckoSession", if (restored) "restored from saved state" else "saved state unusable")
        }
        // The fallback is only for "no state" or "state would not parse"; it never runs on top of a restore.
        if (!restored && fallbackUrl != null && isWebUrl(fallbackUrl)) session.loadUri(fallbackUrl)
    }

    private fun originHost(): String = hostOfUrl(_state.value.url).orEmpty()

    private fun applyActive() {
        if (closed || !session.isOpen) return
        runCatching { session.setActive(wantActive) }
    }

    private fun navigationDelegate() =
        object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                // The blank document of a session reopened after its content process died is not a page.
                if (url != null && _state.value.isFreshSessionBlank(url)) return
                // The only writer of state.url. A load that is pending, halted or failed leaves the OLD page
                // displayed, so showing its address would let a hostile page spoof the address bar.
                if (url != null) {
                    importLeaveAllowed = false
                    _state.update { it.afterLocationChange(url) }
                    // Back/Forward commits without onLoadRequest. An import forces JavaScript (HTML off, PDF on).
                    // Forward back to a website has to restore that site's setting, or it stays off.
                    applyImportedScript(url, reloadIfChanged = true)
                    tabHistory.onCommit(url)
                    onCommitForDesktop(url)
                }
                // Gecko hands every web page its tracking permission record; ALLOW means a stored (normal) exception.
                trackingPermission = perms.firstOrNull { it.permission == GeckoSession.PermissionDelegate.PERMISSION_TRACKING }
                if (!isPrivate && trackingPermission?.value == GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW) {
                    trackingExceptions.set(url, isPrivate = false, excepted = true)
                }
                val off = if (isWebUrl(url.orEmpty())) trackingExceptions.isExcepted(url, isPrivate) else null
                _state.update { it.copy(trackingProtectionOff = off, javaScriptOn = session.settings.allowJavascript) }
                // Fallback for navigations that did not pass onLoadRequest (Back/Forward, restores).
                applyTrackingProtection(off != true)
                applyActive()
                visits.onLocationChange(url)?.let { host?.onVisit(this@GeckoBrowserSession, it.url, it.title) }
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                _state.update { it.copy(canGoBack = canGoBack) }
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                _state.update { it.copy(canGoForward = canGoForward) }
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny>? {
                // target=_blank / window.open become a real tab (see onNewSession). Only with a user gesture and
                // an http(s) target, so a page cannot spawn tabs unprompted or open javascript:/data:/file: windows.
                if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                    val allow =
                        request.hasUserGesture && isWebUrl(request.uri) && host?.canOpenTab() == true
                    return GeckoResult.fromValue(if (allow) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
                }
                // A shared file must not send the tab to the web by itself (meta refresh); a tapped link still works,
                // and so does the server redirect that follows it.
                if (blocksImportAutoNavigation(
                        committedUrl = _state.value.url,
                        targetUrl = request.uri,
                        hasUserGesture = request.hasUserGesture,
                        isDirectNavigation = request.isDirectNavigation,
                        redirectOfAllowedLoad = request.isRedirect && importLeaveAllowed,
                    )
                ) {
                    debugLog.log("GeckoSession", "import auto-navigation blocked")
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                if ((request.hasUserGesture || request.isDirectNavigation) && isWebUrl(request.uri)) importLeaveAllowed = true
                if (request.hasUserGesture && !request.isRedirect) lastGestureNavigationAt = SystemClock.elapsedRealtime()
                // Only the load loadExtensionPage started counts as Orbis's own; a page cannot set this flag.
                val appExtensionLoad =
                    schemeOf(request.uri) == "moz-extension" && request.isDirectNavigation && pendingExtensionLoad
                if (appExtensionLoad) pendingExtensionLoad = false
                when (topLevelLoadVerdict(request.uri, _state.value.url, appExtensionLoad)) {
                    LoadVerdict.DENY -> {
                        debugLog.log("GeckoSession", "refused load scheme=${loggableScheme(request.uri)}")
                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                    }
                    // Gecko applies its own rules (about:, data:, blob:, javascript:, an allowed extension page).
                    LoadVerdict.PASS -> return null
                    // file: is only a document we copied into the import cache. Anything else (another cache file,
                    // the app's private storage) is refused. HTML then runs with JavaScript off. applySitePolicy
                    // returns immediately for a non-web URL, so this is the place that sets it, before the document
                    // loads. PDF keeps JavaScript: pdf.js needs it. History navigations that skip this callback are
                    // corrected in onPageStart and onLocationChange.
                    LoadVerdict.IMPORT_FILE -> {
                        if (!importFileAllowed(importRoot, request.uri)) {
                            debugLog.log("GeckoSession", "refused file uri")
                            return GeckoResult.fromValue(AllowOrDeny.DENY)
                        }
                        applyImportedScript(request.uri)
                        return null
                    }
                    // Links for other apps (tel:, mailto:, intent:, an app's own scheme): never loaded here.
                    LoadVerdict.EXTERNAL -> return externalSchemeRequest(request)
                    LoadVerdict.WEB -> Unit
                }
                // A web link an installed app opens: ask first (link taps only, not typed addresses or same-site links).
                if (request.hasUserGesture && !request.isDirectNavigation && isWebUrl(request.uri) &&
                    sitePolicy()?.offerAppsForLinks() != false &&
                    hostOfUrl(request.uri) != hostOfUrl(_state.value.url)
                ) {
                    externalApps.forWebLink(request.uri)?.let { target ->
                        applyTrackingProtection(!trackingExceptions.isExcepted(request.uri, isPrivate))
                        applySitePolicy(request.uri)
                        return askOpenInApp(target, webLink = true)
                    }
                }
                // Decide tracking protection for the site BEFORE its page loads (see TrackingExceptions).
                applyTrackingProtection(!trackingExceptions.isExcepted(request.uri, isPrivate))
                // Measured on GeckoView 160: a 302 from a desktop page to a mobile site keeps the desktop User-Agent
                // (header and navigator.userAgent) for that one document, although the session is switched to mobile
                // here. It is not reloaded: redirect targets can be one-time URLs (sign-in callbacks, magic links).
                // The next navigation is mobile.
                applySitePolicy(request.uri)
                return null
            }

            override fun onSubframeLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny>? {
                // file:, content:, resource:, chrome:, view-source: and jar: subframes are denied. GeckoView has no
                // callback for img, script or stylesheet loads (bug 1684947: those can read the app data dir).
                // Imported HTML carries a CSP that blocks them; see IMPORT_CONTENT_SECURITY_POLICY.
                if (!subframeLoadAllowed(request.uri)) {
                    debugLog.log("GeckoSession", "refused subframe scheme=${loggableScheme(request.uri)}")
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return null
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                val owner = host
                // onLoadRequest (user gesture + http(s)) is the primary gate; Gecko consults it before this runs.
                // The scheme check here is defense in depth.
                if (owner == null || !isWebUrl(uri) || !owner.canOpenTab()) {
                    debugLog.log("GeckoSession", "popup refused")
                    return GeckoResult.fromValue<GeckoSession>(null)
                }
                // The child is deliberately NOT opened: Gecko opens the session it is handed, and opening it
                // here too throws. The tab layer keeps the strong reference and attaches it to the view.
                val child = newPopupChild(this@GeckoBrowserSession, uri)
                runCatching { child.attachReader() }
                runCatching { child.attachCast() }
                runCatching { child.attachCompat() }
                owner.onPopupSession(child, this@GeckoBrowserSession)
                debugLog.log("GeckoSession", "popup session created")
                return GeckoResult.fromValue(child.geckoSession)
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError,
            ): GeckoResult<String>? {
                debugLog.log(
                    "GeckoSession",
                    "load error code=0x${error.code.toString(16)} category=0x${error.category.toString(16)}",
                )
                visits.onLoadError()
                tabHistory.onLoadEndedWithoutCommit()
                _state.update { it.afterLoadError(uri?.takeIf { failed -> isWebUrl(failed) }) }
                if (error.code == WebRequestError.ERROR_HTTPS_ONLY) _state.update { it.copy(httpsOnlyBlocked = true) }
                // Null halts the load; the shell shows its own banner.
                return null
            }
        }

    private fun progressDelegate() =
        object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                // The blank document of a session reopened after its content process died is not a page.
                if (_state.value.isFreshSessionBlank(url)) return
                // History can land here without onLoadRequest. An import forces script; a website reached from
                // an import gets its site policy back. Either reloads once when the setting had to change.
                applyImportedScript(url, reloadIfChanged = true)
                // A new top-level page ends any dialog-spam lockout and invalidates the find highlight.
                abuseGuard.onPageStart()
                // A message from the page being replaced must not be read as one about the next page.
                compatPort = null
                pageStartedSinceCommit = true
                // A permission dialog must not outlive the page that asked; dismissing denies without recording.
                queue.dismissWhere { it is SitePrompt.SitePermission }
                reconciler.onPageStart()
                applyActive()
                // url is not written here (see onLocationChange).
                _state.update { it.afterPageStart(url) }
                _state.update { it.copy(readerable = false) }
                visits.onPageStart()
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                _state.update { it.afterPageStop(success) }
                visits.onPageStop(success)?.let { host?.onVisit(this@GeckoBrowserSession, it.url, it.title) }
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                _state.update { it.copy(progress = progress) }
            }

            // The lock follows this report, never the URL scheme. Expected after the location change, and again when
            // mixed content changes the page's state; that ordering is to be confirmed on a device by H-D21.
            // mixedModeActive cannot be CONTENT_LOADED today: security.mixed_content.block_active_content is at its
            // default (true), so Gecko blocks mixed scripts and the page stays secure. The check guards a pref change.
            override fun onSecurityChange(
                session: GeckoSession,
                securityInfo: GeckoSession.ProgressDelegate.SecurityInformation,
            ) {
                _state.update {
                    it.afterSecurityChange(
                        connectionSecure = securityInfo.isSecure,
                        certificateException = securityInfo.isException,
                        mixedActiveLoaded =
                            securityInfo.mixedModeActive == GeckoSession.ProgressDelegate.SecurityInformation.CONTENT_LOADED,
                    )
                }
            }

            override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
                // The reopened blank session must not replace the lost page's state: it is what gets restored, and
                // what the tab layer persists meanwhile.
                if (_state.value.contentGone) return
                // Kept as the engine object; serialised only when somebody asks (see savedState()).
                latestSessionState = sessionState
                host?.onSessionStateChanged(this@GeckoBrowserSession)
            }
        }

    private fun mediaSessionDelegate() =
        object : MediaSession.Delegate {
            override fun onFullscreen(
                session: GeckoSession,
                mediaSession: MediaSession,
                enabled: Boolean,
                meta: MediaSession.ElementMetadata?,
            ) {
                val landscape = enabled && meta != null && meta.width > meta.height
                _state.update { s ->
                    val m = s.media
                    s.copy(
                        fullScreenVideoLandscape = landscape,
                        media =
                            if (m != null && meta != null && meta.videoTrackCount > 0) {
                                m.copy(hasVideo = true, videoWidth = meta.width, videoHeight = meta.height)
                            } else {
                                m
                            },
                    )
                }
            }

            override fun onActivated(session: GeckoSession, mediaSession: MediaSession) {
                activeMedia = mediaSession
                _state.update { it.copy(media = it.media ?: MediaState(playing = false)) }
            }

            override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
                if (activeMedia === mediaSession) activeMedia = null
                _state.update { it.copy(media = null) }
            }

            override fun onMetadata(session: GeckoSession, mediaSession: MediaSession, meta: MediaSession.Metadata) {
                _state.update { s ->
                    s.copy(media = (s.media ?: MediaState(playing = false)).copy(title = meta.title.orEmpty(), artist = meta.artist.orEmpty()))
                }
            }

            override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
                activeMedia = mediaSession
                debugLog.log("Media", "media play")
                _state.update { it.copy(media = (it.media ?: MediaState(playing = true)).copy(playing = true)) }
            }

            override fun onPause(session: GeckoSession, mediaSession: MediaSession) {
                debugLog.log("Media", "media pause")
                _state.update { it.copy(media = it.media?.copy(playing = false)) }
            }

            override fun onStop(session: GeckoSession, mediaSession: MediaSession) {
                debugLog.log("Media", "media stop")
                _state.update { it.copy(media = it.media?.copy(playing = false)) }
            }
        }

    // ---- links for other apps ----

    private val externalApps = ExternalApps(appContext)

    /** elapsedRealtime of the last top-level navigation the user tapped; null before the first. Main thread. */
    private var lastGestureNavigationAt: Long? = null

    /**
     * A tapped or app-initiated web load left the committed page and has not committed yet, so a redirect of it may
     * leave an imported file (see [blocksImportAutoNavigation]). Main thread.
     */
    private var importLeaveAllowed = false

    /**
     * [loadExtensionPage] is about to load an extension's own page. onLoadRequest consumes it for the next direct
     * `moz-extension:` load, so a page cannot open an extension page by itself (see [topLevelLoadVerdict]). Main thread.
     */
    private var pendingExtensionLoad = false

    /** An "Open in app" question is on screen or queued for this tab. Main thread. */
    private var openInAppPending = false

    private fun externalSchemeRequest(request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
        val deny = GeckoResult.fromValue(AllowOrDeny.DENY)
        // A page cannot start an app on its own: a tap, or a redirect of a navigation tapped moments ago, is needed.
        val sinceTap = lastGestureNavigationAt?.let { SystemClock.elapsedRealtime() - it }
        if (!externalLaunchAllowed(request.hasUserGesture, request.isRedirect, sinceTap)) {
            debugLog.log("GeckoSession", "external app link without a tap: blocked")
            return deny
        }
        // One question at a time: further requests while one is open are refused, so a page cannot queue a stream of them.
        if (openInAppPending) {
            debugLog.log("GeckoSession", "external app link while one is pending: blocked")
            return deny
        }
        // A magnet for Orbis's own downloader: the confirm sheet asks; the page stays where it is.
        if (schemeOf(request.uri) == "magnet" && sitePolicy()?.magnetsToOrbis() == true &&
            host?.onTorrentIntake(com.shibi.orbis.torrent.IntakeRequest.Magnet(request.uri, isPrivate), this) == true
        ) {
            return deny
        }
        when (val route = routeExternalNavigation(request.uri, appContext.packageName)) {
            is ExternalNavigation.Web -> {
                // An intent: link that is really an http(s) address stays in this tab. Never load the raw intent: URI.
                if (route.url != null) loadUrl(route.url)
                return deny
            }
            ExternalNavigation.Blocked -> return deny
            is ExternalNavigation.App -> {
                val target = externalApps.targetFor(route)
                when (externalOpenChoice(target.intent != null, target.resolvable)) {
                    ExternalOpenChoice.ASK -> return askOpenInApp(target, webLink = false, originalUrl = request.uri)
                    ExternalOpenChoice.LAUNCH -> {
                        // Not visible to PackageManager. The tap already happened: try the app anyway.
                        if (!externalApps.launch(target)) followExternalMiss(target, request.uri)
                        return deny
                    }
                    ExternalOpenChoice.MISS -> {
                        followExternalMiss(target, request.uri)
                        return deny
                    }
                }
            }
        }
    }

    /**
     * startActivity failed. An intent: link's browser_fallback_url loads here; otherwise the Play listing for the
     * package the link named; otherwise the UI says no app can open it and offers to copy [originalUrl].
     */
    private fun followExternalMiss(target: AppTarget, originalUrl: String) {
        when (val miss = planExternalMiss(target.fallbackUrl, target.marketPackage)) {
            is ExternalMiss.OpenWeb -> loadUrl(miss.url)
            is ExternalMiss.OpenMarket -> {
                val market = externalApps.forExternalScheme(miss.uri)
                if (market != null && externalApps.launch(market)) return
                if (originalUrl.isNotBlank()) host?.onExternalAppMissing(originalUrl)
            }
            ExternalMiss.NoHandler -> if (originalUrl.isNotBlank()) host?.onExternalAppMissing(originalUrl)
        }
    }

    private fun askOpenInApp(target: AppTarget, webLink: Boolean, originalUrl: String? = null): GeckoResult<AllowOrDeny> {
        val result = GeckoResult<AllowOrDeny>()
        // Staying loads a web link here; anything else has nothing to load.
        val stay = if (webLink) AllowOrDeny.ALLOW else AllowOrDeny.DENY
        if (closed) return GeckoResult.fromValue(stay)
        if (openInAppPending) return GeckoResult.fromValue(stay)
        openInAppPending = true
        queue.add(
            SitePrompt.OpenInApp(originHost(), target.label, webLink, isPrivate) { open ->
                openInAppPending = false
                if (open == true) {
                    if (externalApps.launch(target)) {
                        result.complete(AllowOrDeny.DENY)
                    } else {
                        if (!webLink) followExternalMiss(target, originalUrl.orEmpty())
                        result.complete(stay)
                    }
                } else {
                    result.complete(stay)
                }
            },
        )
        return result
    }

    // ---- translation ----

    private fun translationDelegate() =
        object : TranslationsController.SessionTranslation.Delegate {
            override fun onTranslationStateChange(session: GeckoSession, state: TranslationsController.SessionTranslation.TranslationState?) {
                val d = state?.detectedLanguages
                val pair = state?.requestedTranslationPair
                _state.update {
                    it.copy(
                        translation =
                            TranslationInfo(
                                pageLanguage = d?.docLangTag.orEmpty(),
                                userLanguage = d?.userLangTag.orEmpty(),
                                pageLanguageSupported = d?.isDocLangTagSupported == true,
                                translatedTo = if (pair != null && state.hasVisibleChange == true) pair.toLanguage.orEmpty() else "",
                                error = state?.error.orEmpty(),
                            ),
                    )
                }
            }
        }

    override fun translatePage(onDone: (Boolean) -> Unit) {
        val info = _state.value.translation
        if (closed || info == null || !info.canTranslate) {
            onDone(false)
            return
        }
        val options = TranslationsController.SessionTranslation.TranslationOptions.Builder().downloadModel(true).build()
        runCatching {
            session.sessionTranslation?.translate(info.pageLanguage, info.userLanguage, options)?.accept(
                { onDone(true) },
                {
                    debugLog.log("GeckoSession", "translation failed: ${it?.javaClass?.simpleName}")
                    onDone(false)
                },
            ) ?: onDone(false)
        }.onFailure { onDone(false) }
    }

    override fun showOriginal() {
        if (!closed) runCatching { session.sessionTranslation?.restoreOriginalPage() }
    }

    // ---- reader view ----

    private var readerPort: WebExtension.Port? = null
    private val readerWaiting = ArrayList<(ReaderArticle?) -> Unit>()

    fun attachReader() {
        reader.whenReady { ext ->
            if (closed) return@whenReady
            runCatching { attachReaderTo(ext) }.onFailure { debugLog.log("GeckoSession", "reader attach failed") }
        }
    }

    private fun attachReaderTo(ext: WebExtension) {
        run {
            session.webExtensionController.setMessageDelegate(
                ext,
                object : WebExtension.MessageDelegate {
                    override fun onConnect(port: WebExtension.Port) {
                        // One port per page load (the content script connects when the page is ready).
                        readerPort = port
                        port.setDelegate(
                            object : WebExtension.PortDelegate {
                                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                                    val parsed = (message as? JSONObject)?.let { parseReaderMessage(it) } ?: return
                                    when (parsed) {
                                        is ReaderMessage.Readerable ->
                                            if (parsed.url == _state.value.url || _state.value.url.isEmpty()) {
                                                _state.update { it.copy(readerable = parsed.value) }
                                            }
                                        is ReaderMessage.Article -> {
                                            val waiting = readerWaiting.toList()
                                            readerWaiting.clear()
                                            waiting.forEach { it(parsed.article) }
                                        }
                                    }
                                }

                                override fun onDisconnect(port: WebExtension.Port) {
                                    if (readerPort === port) readerPort = null
                                }
                            },
                        )
                    }
                },
                ReaderExtensionHolder.NATIVE_APP,
            )
        }
    }

    override fun requestReaderArticle(onResult: (ReaderArticle?) -> Unit) {
        val port = readerPort
        if (closed || port == null || !_state.value.readerable) {
            onResult(null)
            return
        }
        readerWaiting += onResult
        runCatching { port.postMessage(JSONObject().put("type", "parse")) }.onFailure {
            readerWaiting.remove(onResult)
            onResult(null)
        }
    }

    /** The page's active Gecko media session, for the play/pause/stop controls. */
    private var activeMedia: MediaSession? = null

    override fun mediaPlay() {
        if (!closed) activeMedia?.play()
    }

    override fun mediaPause() {
        if (!closed) activeMedia?.pause()
    }

    override fun mediaStop() {
        if (!closed) activeMedia?.stop()
    }

    override fun probeCastMedia(onResult: (String?) -> Unit) {
        val port = castPort
        if (closed || port == null) {
            onResult(null)
            return
        }
        castWaiting?.invoke(null)
        val generation = probeGate.start(SystemClock.uptimeMillis())
        castGeneration = generation
        castWaiting = onResult
        val message = JSONObject().put("type", "probe").put("token", generation)
        runCatching { port.postMessage(message) }.onFailure {
            deliverCast(null, generation)
            return
        }
        scope.launch {
            delay(com.shibi.orbis.cast.PROBE_TIMEOUT_MS)
            deliverCast(null, generation)
        }
    }

    override fun pausePageMedia() {
        if (closed) return
        mediaPause()
        val port = castPort ?: return
        runCatching { port.postMessage(JSONObject().put("type", "pause")) }
    }

    private var castPort: WebExtension.Port? = null
    private var castWaiting: ((String?) -> Unit)? = null
    private var castGeneration = 0
    private val probeGate = com.shibi.orbis.cast.ProbeGate()

    /** Gecko delivers port messages off the main thread. Cast then touches MediaRouter, which must be on main. */
    private fun deliverCast(json: String?, generation: Int) {
        if (!probeGate.take(generation)) return
        val waiting = castWaiting
        castWaiting = null
        scope.launch { waiting?.invoke(if (closed) null else json) }
    }

    fun attachCast() {
        castExtension.whenReady { ext ->
            if (closed) return@whenReady
            runCatching { attachCastTo(ext) }.onFailure { debugLog.log("GeckoSession", "cast attach failed") }
        }
    }

    private fun attachCastTo(ext: WebExtension) {
        session.webExtensionController.setMessageDelegate(
            ext,
            object : WebExtension.MessageDelegate {
                override fun onConnect(port: WebExtension.Port) {
                    castPort = port
                    port.setDelegate(
                        object : WebExtension.PortDelegate {
                            override fun onPortMessage(message: Any, port: WebExtension.Port) {
                                val obj = message as? JSONObject ?: return
                                if (obj.optString("type") != "probe") return
                                deliverCast(obj.toString(), obj.optInt("token", castGeneration))
                            }

                            override fun onDisconnect(port: WebExtension.Port) {
                                if (castPort === port) castPort = null
                                deliverCast(null, castGeneration)
                            }
                        },
                    )
                }
            },
            CastExtensionHolder.NATIVE_APP,
        )
    }

    // ---- auto desktop --------------------------------------------------------------------------------------

    private var compatPort: WebExtension.Port? = null

    /** The committed URL when [compatPort] connected; a message counts only while that page is still the one shown. */
    private var compatPortUrl: String? = null

    fun attachCompat() {
        if (!AUTO_DESKTOP_RETRY) return
        compatExtension.whenReady { ext ->
            if (closed) return@whenReady
            runCatching { attachCompatTo(ext) }.onFailure { debugLog.log("GeckoSession", "compat attach failed") }
        }
    }

    private fun attachCompatTo(ext: WebExtension) {
        session.webExtensionController.setMessageDelegate(
            ext,
            object : WebExtension.MessageDelegate {
                override fun onConnect(port: WebExtension.Port) {
                    // Port callbacks may arrive off the main thread; everything below runs on main, in order.
                    scope.launch {
                        compatPort = port
                        compatPortUrl = _state.value.url
                    }
                    port.setDelegate(
                        object : WebExtension.PortDelegate {
                            override fun onPortMessage(message: Any, port: WebExtension.Port) {
                                val obj = message as? JSONObject ?: return
                                scope.launch { onCompatMessage(port, obj) }
                            }

                            override fun onDisconnect(port: WebExtension.Port) {
                                scope.launch { if (compatPort === port) compatPort = null }
                            }
                        },
                    )
                }
            },
            CompatExtensionHolder.NATIVE_APP,
        )
    }

    /** The page's sample is classified and dropped here: never stored, never logged. */
    private fun onCompatMessage(port: WebExtension.Port, obj: JSONObject) {
        if (closed || !session.isOpen || port !== compatPort) return
        val url = _state.value.url
        if (!isWebUrl(url) || url != compatPortUrl) return
        val host = hostOfUrl(url)?.lowercase() ?: return
        val policy = sitePolicy() ?: return
        val message = parseCompatMessage(obj) ?: return
        val retrying = retryDesktopHost == host
        val action =
            when (message) {
                CompatMessage.Clean -> autoDesktop.onCleanPage(host)
                is CompatMessage.Sample -> {
                    // Desktop already on and not by our retry (Always desktop, the user's choice): nothing to detect.
                    if (_state.value.desktopMode && !retrying) return
                    if (isUnsupportedBrowserPage(message.sample)) {
                        autoDesktop.onBlockedPage(
                            host = host,
                            desktopMode = _state.value.desktopMode,
                            alwaysDesktop = policy.alwaysDesktopOn(),
                            declined = policy.isAutoDesktopDeclined(url, isPrivate),
                            userChoseDesktop = policy.desktopFor(url, isPrivate),
                        )
                    } else {
                        autoDesktop.onCleanPage(host)
                    }
                }
            }
        // No URL or host in the log: it would record browsing history.
        when (action) {
            Action.Retry -> {
                retryDesktopHost = host
                applyDesktop(true)
                debugLog.log("GeckoSession", "auto desktop: retry")
                session.reload()
            }
            Action.Keep -> {
                policy.rememberAutoDesktop(url, isPrivate)
                retryDesktopHost = null
                debugLog.log("GeckoSession", "auto desktop: kept")
                _autoDesktopKept.tryEmit(Unit)
            }
            Action.Revert -> {
                retryDesktopHost = null
                policy.declineAutoDesktop(url, isPrivate)
                applyDesktop(false)
                debugLog.log("GeckoSession", "auto desktop: reverted")
                session.reload()
            }
            Action.None -> Unit
        }
    }

    /**
     * The tab's desktop choice for [url]: desktop while a retry for its site is pending, else the site policy. Null for
     * a non-web URL or without a policy.
     */
    fun desktopWantedFor(url: String): Boolean? {
        if (!isWebUrl(url)) return null
        val host = hostOfUrl(url)?.lowercase()
        if (host != null && host == retryDesktopHost) return true
        return sitePolicy()?.desktopFor(url, isPrivate)
    }

    /**
     * Every commit, including Back and Forward, which skip onLoadRequest: a site never keeps the desktop mode of the
     * site shown before it. Reloads once when the mode had to change (loop-guarded, see [desktopAfterCommit]).
     */
    private fun onCommitForDesktop(url: String) {
        if (closed) return
        val host = if (isWebUrl(url)) hostOfUrl(url)?.lowercase() else null
        if (host != committedHost) {
            committedHost?.let { autoDesktop.onLeft(it) }
            committedHost = host
            if (retryDesktopHost != null && retryDesktopHost != host) retryDesktopHost = null
        }
        val reloaded = desktopReloadPending
        desktopReloadPending = false
        val pageStarted = pageStartedSinceCommit
        pageStartedSinceCommit = false
        when (val d = desktopAfterCommit(desktopWantedFor(url), _state.value.desktopMode, reloaded, pageStarted)) {
            DesktopCommit.Leave -> Unit
            is DesktopCommit.Apply -> {
                applyDesktop(d.desktop)
                if (d.reload && session.isOpen) {
                    desktopReloadPending = true
                    // No URL or host: it would record browsing history.
                    debugLog.log("GeckoSession", "desktop mode corrected on commit; reloading")
                    session.reload()
                }
            }
        }
    }


    private fun contentDelegate() =
        object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                // The reopened blank session's title is not the lost page's (see SessionState.contentGone).
                if (_state.value.contentGone) return
                titleForUrl = _state.value.url
                _state.update { it.afterTitleChange(title) }
                visits.onTitleChange(title)?.let { host?.onVisitTitle(this@GeckoBrowserSession, it.url, it.title) }
            }

            override fun onCloseRequest(session: GeckoSession) {
                host?.onCloseRequest(this@GeckoBrowserSession)
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                _state.update { it.copy(fullScreen = fullScreen, fullScreenVideoLandscape = fullScreen && it.fullScreenVideoLandscape) }
                debugLog.log("GeckoSession", "fullscreen $fullScreen")
            }

            override fun onContextMenu(
                session: GeckoSession,
                screenX: Int,
                screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement,
            ) {
                val menu =
                    pageContextMenu(
                        ContextElementInfo(
                            type =
                                if (element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE) {
                                    PageContextType.IMAGE
                                } else {
                                    PageContextType.OTHER
                                },
                            linkUri = element.linkUri,
                            srcUri = element.srcUri,
                            title = element.title,
                            altText = element.altText,
                            linkText = element.linkText,
                        ),
                    ) ?: return
                host?.onPageContextMenu(menu, this@GeckoBrowserSession)
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                val download = GeckoIncomingDownload(response)
                val owner = host
                if (owner == null) {
                    // Nobody to ask: end the response so the network connection is not held open.
                    download.discard()
                    return
                }
                owner.onDownload(download, this@GeckoBrowserSession)
                // No file name or url in the log.
                debugLog.log("GeckoSession", "download offered")
            }

            override fun onCrash(session: GeckoSession) = onContentProcessGone(ContentProcessGone.CRASH)

            override fun onKill(session: GeckoSession) = onContentProcessGone(ContentProcessGone.KILL)
        }

    /** When this session last restored its page by itself after a kill (elapsedRealtime), for the loop guard. */
    private var lastAutoRestoreAt: Long? = null

    /** A kill took the page while the tab was not shown: [setActive] true restores it. */
    private var restoreWhenShown = false

    private fun onContentProcessGone(kind: ContentProcessGone) {
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        val action =
            processGoneAction(
                kind = kind,
                isActive = wantActive,
                millisSinceLastRestore = lastAutoRestoreAt?.let { now - it },
                hasSavedState = latestSessionState != null,
                hasUrl = isWebUrl(_state.value.url),
            )
        // Prompts belong to the dead page; answer them so no GeckoResult is left waiting on a gone process.
        queue.dismissAll()
        activeMedia = null
        // The reopened session's list is not the tab's; the restored page brings the real one back.
        tabHistory.onRestoreStarted()
        // Before the reopen: the blank document it reports must find the state already marked (isFreshSessionBlank).
        // url, title and the saved state keep naming the lost page. A crash never reloads by itself (crash loops).
        _state.update { it.afterContentProcessGone(showBanner = action == ProcessGoneAction.BANNER) }
        runCatching { session.open(runtime) }
            .onFailure { debugLog.log("GeckoSession", "reopen after ${kind.logName} failed: ${it.javaClass.simpleName}") }
        applyActive()
        // No URL or host in the log.
        when (action) {
            ProcessGoneAction.RESTORE_NOW -> restoreAfterKill()
            ProcessGoneAction.RESTORE_WHEN_SHOWN -> {
                restoreWhenShown = true
                debugLog.log("GeckoSession", "content process kill; restore on show")
            }
            ProcessGoneAction.BANNER -> {
                restoreWhenShown = false
                debugLog.log(
                    "GeckoSession",
                    if (kind == ContentProcessGone.KILL) "content process kill; banner (repeat)" else "content process crash; banner",
                )
            }
            ProcessGoneAction.NOTHING -> {
                userNavigation()
                debugLog.log("GeckoSession", "content process ${kind.logName}; no page to restore")
            }
        }
    }

    private val ContentProcessGone.logName: String
        get() = if (this == ContentProcessGone.KILL) "kill" else "crash"

    /** The automatic restore after a kill; counted by the loop guard. */
    private fun restoreAfterKill() {
        lastAutoRestoreAt = SystemClock.elapsedRealtime()
        restoreLostPage()
        debugLog.log("GeckoSession", "content process kill; restored")
    }

    /** Brings back the page whose content process died: its last saved state, else its last committed web address. */
    private fun restoreLostPage() {
        restoreWhenShown = false
        val blob = latestSessionState?.toString()
        val url = _state.value.url.takeIf { isWebUrl(it) }
        _state.update { it.afterRestoreRequested() }
        restorePage(blob, url)
    }

    /** A navigation the user asked for replaces a lost page: no restore is left waiting for it. */
    private fun userNavigation() {
        restoreWhenShown = false
        _state.update { it.afterNavigationRequested() }
    }

    // ---- prompts -------------------------------------------------------------------------------------------

    private fun complete(result: GeckoResult<Response>, response: Response?) {
        runCatching { result.complete(response) }
    }

    /** Gecko-side dismissal response; null if the prompt can no longer produce one. */
    private fun dismissResponse(prompt: GeckoSession.PromptDelegate.BasePrompt): Response? =
        runCatching { prompt.dismiss() }.getOrNull()

    /**
     * Runs [confirm]; if Gecko rejects the answer, falls back to a dismissal so the page's result still completes.
     * Only the exception class is logged.
     */
    private inline fun confirmOrDismiss(
        prompt: GeckoSession.PromptDelegate.BasePrompt,
        confirm: () -> Response?,
    ): Response? =
        try {
            confirm()
        } catch (e: Exception) {
            debugLog.log("GeckoSession", "prompt confirm failed: ${e.javaClass.simpleName}")
            dismissResponse(prompt)
        }

    /**
     * Queues [make]'s prompt and returns the GeckoResult it will complete. Gecko dismissing the prompt itself
     * (navigation, script gone) completes it as dismissed too, so the result is always completed exactly once.
     */
    private fun present(
        gecko: GeckoSession.PromptDelegate.BasePrompt,
        make: (GeckoResult<Response>) -> SitePrompt,
    ): GeckoResult<Response> {
        val result = GeckoResult<Response>()
        if (closed) {
            complete(result, dismissResponse(gecko))
            return result
        }
        val prompt = make(result)
        gecko.setDelegate(
            object : GeckoSession.PromptDelegate.PromptInstanceDelegate {
                override fun onPromptDismiss(dismissed: GeckoSession.PromptDelegate.BasePrompt) {
                    prompt.dismiss()
                }
            },
        )
        queue.add(prompt)
        return result
    }

    /**
     * The camera side of one file prompt. The temp is the app's own file shared to the camera app through the
     * FileProvider; failures log the exception class only, never a name or path.
     */
    private fun cameraBridge(): CameraBridge =
        object : CameraBridge {
            // Main thread only, like the prompt that owns this bridge.
            private var file: File? = null

            override fun ensurePermission(onResult: (Boolean) -> Unit) =
                broker.ensure(
                    listOf(AndroidPermissionBroker.CAMERA),
                    { ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED },
                    onResult,
                )

            override fun newOutput(): Uri? {
                val created = captureFiles.newFile()
                if (created == null) {
                    debugLog.log("GeckoSession", "camera output unavailable")
                    return null
                }
                file = created
                return try {
                    FileProvider.getUriForFile(appContext, DownloadIntents.authority(appContext), created)
                } catch (e: Exception) {
                    debugLog.log("GeckoSession", "camera output failed: ${e.javaClass.simpleName}")
                    null
                }
            }

            override fun hasPicture(): Boolean = file?.let { captureFiles.hasPicture(it) } == true

            override fun release() {
                val temp = file ?: return
                file = null
                // Off the main thread, and not on the session scope: it may be cancelled right after the prompt ends (close).
                Thread({ runCatching { captureFiles.discard(temp) } }, "orbis-capture-discard").start()
            }
        }

    /** Dialog-spam lockout: answered as dismissed without ever reaching the UI. */
    private fun suppressed(prompt: GeckoSession.PromptDelegate.BasePrompt): GeckoResult<Response> {
        debugLog.log("GeckoSession", "prompt suppressed: too many dialogs")
        return GeckoResult.fromValue(prompt.dismiss())
    }

    /** Prompt kinds this browser has no UI for. Answered explicitly (as dismissed); the kind is logged, nothing else. */
    private fun skip(kind: String, prompt: GeckoSession.PromptDelegate.BasePrompt): GeckoResult<Response> {
        debugLog.log("GeckoSession", "prompt skipped: $kind")
        return GeckoResult.fromValue(prompt.dismiss())
    }

    private fun toNode(choice: GeckoSession.PromptDelegate.ChoicePrompt.Choice): ChoiceNode =
        ChoiceNode(
            id = choice.id,
            label = choice.label.orEmpty(),
            disabled = choice.disabled,
            selected = choice.selected,
            separator = choice.separator,
            children = choice.items?.map { toNode(it) },
        )

    private fun promptDelegate() =
        object : GeckoSession.PromptDelegate {
            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt,
            ): GeckoResult<Response> {
                if (abuseGuard.shouldSuppress()) return suppressed(prompt)
                return present(prompt) { result ->
                    SitePrompt.Alert(originHost(), prompt.message.orEmpty()) {
                        complete(result, dismissResponse(prompt))
                    }
                }
            }

            override fun onButtonPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ButtonPrompt,
            ): GeckoResult<Response> {
                if (abuseGuard.shouldSuppress()) return suppressed(prompt)
                return present(prompt) { result ->
                    SitePrompt.Confirm(originHost(), prompt.message.orEmpty()) { choice ->
                        complete(
                            result,
                            when (choice) {
                                true -> prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE)
                                false -> prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE)
                                null -> dismissResponse(prompt)
                            },
                        )
                    }
                }
            }

            override fun onTextPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.TextPrompt,
            ): GeckoResult<Response> {
                if (abuseGuard.shouldSuppress()) return suppressed(prompt)
                return present(prompt) { result ->
                    SitePrompt.TextInput(
                        originHost(),
                        prompt.message.orEmpty(),
                        prompt.defaultValue.orEmpty(),
                    ) { text ->
                        complete(result, if (text == null) dismissResponse(prompt) else prompt.confirm(text))
                    }
                }
            }

            override fun onChoicePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ChoicePrompt,
            ): GeckoResult<Response> {
                val multiple = prompt.type == GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE
                val options = flattenChoices(prompt.choices.orEmpty().map { toNode(it) })
                return present(prompt) { result ->
                    SitePrompt.Choice(originHost(), prompt.message.orEmpty(), multiple, options) { ids ->
                        complete(
                            result,
                            when {
                                ids == null -> dismissResponse(prompt)
                                multiple -> prompt.confirm(ids.toTypedArray())
                                else -> prompt.confirm(ids.first())
                            },
                        )
                    }
                }
            }

            override fun onAuthPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AuthPrompt,
            ): GeckoResult<Response> {
                val flags = prompt.authOptions.flags
                // A subresource of another origin asking for credentials is a phishing shape: never show it.
                if ((flags and AuthFlags.CROSS_ORIGIN_SUB_RESOURCE) != 0) return skip("auth-cross-origin", prompt)
                val passwordOnly = (flags and AuthFlags.ONLY_PASSWORD) != 0
                // Title with the host that will RECEIVE the password. During a top-level navigation the committed
                // url (originHost) is still the previous page, which would name the wrong site.
                val authHost = hostOfUrl(prompt.authOptions.uri.orEmpty()) ?: originHost()
                return present(prompt) { result ->
                    SitePrompt.Auth(
                        originHost = authHost,
                        message = prompt.message.orEmpty(),
                        passwordOnly = passwordOnly,
                        previousFailed = (flags and AuthFlags.PREVIOUS_FAILED) != 0,
                        username = prompt.authOptions.username.orEmpty(),
                    ) { credentials ->
                        complete(
                            result,
                            when {
                                credentials == null -> dismissResponse(prompt)
                                passwordOnly -> prompt.confirm(credentials.password)
                                else -> prompt.confirm(credentials.username, credentials.password)
                            },
                        )
                    }
                }
            }

            override fun onBeforeUnloadPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt,
            ): GeckoResult<Response> =
                present(prompt) { result ->
                    SitePrompt.BeforeUnload(originHost()) { leave ->
                        // Every way this prompt can end funnels through here (the UI, Gecko dismissing it, the queue's
                        // dismissAll on crash/close). Anything but "leave" means the navigation that raised it will not
                        // happen; no load event follows, so the address bar is told through the state. Gecko dismissing
                        // it because the navigation went ahead anyway also lands here: a harmless early signal.
                        if (leave != true) {
                            tabHistory.onLoadEndedWithoutCommit()
                            _state.update { it.afterNavigationCancelled() }
                        }
                        complete(
                            result,
                            when (leave) {
                                true -> prompt.confirm(AllowOrDeny.ALLOW)
                                false -> prompt.confirm(AllowOrDeny.DENY)
                                null -> dismissResponse(prompt)
                            },
                        )
                    }
                }

            override fun onRepostConfirmPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.RepostConfirmPrompt,
            ): GeckoResult<Response> {
                // The desktop-mode correction reloaded a form result (Back onto it is served from the cache, so its
                // mode was never sent). Resending a form the user did not resend is not Orbis's call: the reload is
                // cancelled, the page stays as shown, and the next navigation gets the right mode.
                if (desktopReloadPending) {
                    desktopReloadPending = false
                    debugLog.log("GeckoSession", "desktop mode correction skipped for a form result")
                    return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
                }
                return present(prompt) { result ->
                    SitePrompt.Repost(originHost()) { resend ->
                        complete(
                            result,
                            when (resend) {
                                true -> prompt.confirm(AllowOrDeny.ALLOW)
                                false -> prompt.confirm(AllowOrDeny.DENY)
                                null -> dismissResponse(prompt)
                            },
                        )
                    }
                }
            }

            override fun onFilePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FilePrompt,
            ): GeckoResult<Response> {
                // Folder upload is not offered; the page just sees "nothing chosen".
                if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.FOLDER) return skip("file-folder", prompt)
                if (abuseGuard.shouldSuppress()) return suppressed(prompt)
                val multiple = prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE
                val mimeTypes = prompt.mimeTypes.orEmpty().filterNotNull()
                // Capture is only an int here and the accept tokens are raw, so the route is decided by our own function.
                val route =
                    filePickRoute(
                        captureRequested = prompt.capture != GeckoSession.PromptDelegate.FilePrompt.Capture.NONE,
                        mimeTypes = mimeTypes,
                        cameraAvailable = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
                        extensionToMime = { ext -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) },
                    )
                return present(prompt) { result ->
                    var copyJob: Job? = null
                    lateinit var filePick: SitePrompt.FilePick
                    filePick =
                        SitePrompt.FilePick(
                            originHost(),
                            mimeTypes,
                            multiple,
                            prepare = { picked ->
                                // Gecko cannot resolve most content:// Uris, so hand it app-private file:// copies.
                                copyJob =
                                    scope.launch {
                                        val files =
                                            try {
                                                uploads.copy(picked.map { ContentUriSource(appContext.contentResolver, it) })
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                debugLog.log("GeckoSession", "file copy failed: ${e.javaClass.simpleName}")
                                                filePick.prepareFailed()
                                                return@launch
                                            }
                                        if (filePick.isFinished) {
                                            // Dismissed while copying: nobody will read these.
                                            uploads.discard(files)
                                        } else {
                                            // Gecko reads these until the page is gone; a private session deletes them in close().
                                            if (isPrivate) privateUploads += files
                                            filePick.filesReady(files.map { Uri.fromFile(it) })
                                        }
                                    }
                            },
                            respond = { uris ->
                                if (uris == null) copyJob?.cancel()
                                complete(
                                    result,
                                    if (uris == null) {
                                        dismissResponse(prompt)
                                    } else {
                                        confirmOrDismiss(prompt) {
                                            if (multiple) {
                                                prompt.confirm(appContext, uris.toTypedArray())
                                            } else {
                                                prompt.confirm(appContext, uris.first())
                                            }
                                        }
                                    },
                                )
                            },
                            route = route,
                            camera = if (route == FilePickRoute.DOCUMENTS) null else cameraBridge(),
                        )
                    filePick
                }
            }

            override fun onDateTimePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.DateTimePrompt,
            ): GeckoResult<Response> {
                val kind =
                    when (prompt.type) {
                        GeckoSession.PromptDelegate.DateTimePrompt.Type.DATE -> DateTimeKind.DATE
                        GeckoSession.PromptDelegate.DateTimePrompt.Type.MONTH -> DateTimeKind.MONTH
                        GeckoSession.PromptDelegate.DateTimePrompt.Type.WEEK -> DateTimeKind.WEEK
                        GeckoSession.PromptDelegate.DateTimePrompt.Type.TIME -> DateTimeKind.TIME
                        GeckoSession.PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL -> DateTimeKind.DATETIME_LOCAL
                        else -> return skip("datetime", prompt)
                    }
                if (abuseGuard.shouldSuppress()) return suppressed(prompt)
                return present(prompt) { result ->
                    SitePrompt.DateTime(
                        originHost(),
                        kind,
                        prompt.defaultValue.orEmpty(),
                        prompt.minValue.orEmpty(),
                        prompt.maxValue.orEmpty(),
                        prompt.stepValue.orEmpty(),
                    ) { value ->
                        complete(
                            result,
                            if (value == null) dismissResponse(prompt) else confirmOrDismiss(prompt) { prompt.confirm(value) },
                        )
                    }
                }
            }

            override fun onColorPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ColorPrompt,
            ): GeckoResult<Response> {
                if (abuseGuard.shouldSuppress()) return suppressed(prompt)
                // Page-supplied: keep only valid "#rrggbb", normalised to lowercase.
                val suggestions =
                    prompt.predefinedValues.orEmpty()
                        .mapNotNull { FormValues.parseColor(it)?.let(FormValues::formatColor) }
                        .distinct()
                return present(prompt) { result ->
                    SitePrompt.ColorPick(originHost(), prompt.defaultValue.orEmpty(), suggestions) { value ->
                        // Anything but a valid colour is not sent to the page.
                        val color = value?.let { FormValues.parseColor(it) }?.let(FormValues::formatColor)
                        complete(
                            result,
                            if (color == null) dismissResponse(prompt) else confirmOrDismiss(prompt) { prompt.confirm(color) },
                        )
                    }
                }
            }

            // ---- SKIP: no UI for these; each is answered explicitly so no GeckoResult is left pending. ----

            override fun onSharePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.SharePrompt,
            ): GeckoResult<Response> = skip("share", prompt)

            override fun onPopupPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.PopupPrompt,
            ): GeckoResult<Response> = skip("popup", prompt)

            override fun onRedirectPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.RedirectPrompt,
            ): GeckoResult<Response> = skip("redirect", prompt)

            override fun onFolderUploadPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FolderUploadPrompt,
            ): GeckoResult<Response> = skip("folder-upload", prompt)

            override fun onWebAuthnRelatedOriginPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.WebAuthnRelatedOriginPrompt,
            ): GeckoResult<Response> = skip("webauthn-origin", prompt)

            override fun onRequestCertificate(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.CertificateRequest,
            ): GeckoResult<Response> = skip("client-certificate", prompt)

            override fun onLoginSave(
                session: GeckoSession,
                request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.LoginSaveOption>,
            ): GeckoResult<Response> {
                val option = request.options.firstOrNull() ?: return skip("login-save", request)
                val entry = option.value
                val bridge = logins()
                if (bridge == null || !bridge.offerSave(entry.origin, isPrivate) || entry.password.isEmpty()) {
                    return skip("login-save", request)
                }
                return present(request) { result ->
                    SitePrompt.SaveLogin(originHost(), entry.username, bridge.isUpdate(entry.origin, entry.username, entry.password)) { choice ->
                        when (choice) {
                            // Gecko then calls the storage delegate's onLoginSave with the entry.
                            SaveLoginChoice.SAVE -> complete(result, request.confirm(option))
                            SaveLoginChoice.NEVER -> {
                                bridge.neverFor(entry.origin)
                                complete(result, request.dismiss())
                            }
                            SaveLoginChoice.NOT_NOW, null -> complete(result, request.dismiss())
                        }
                    }
                }
            }

            override fun onLoginSelect(
                session: GeckoSession,
                request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.LoginSelectOption>,
            ): GeckoResult<Response> {
                val options = request.options.toList()
                if (options.isEmpty()) return skip("login-select", request)
                return present(request) { result ->
                    SitePrompt.SelectLogin(originHost(), options.map { it.value.username }) { index ->
                        val chosen = index?.let { options.getOrNull(it) }
                        complete(result, if (chosen != null) request.confirm(chosen) else request.dismiss())
                    }
                }
            }

            override fun onAddressSave(
                session: GeckoSession,
                request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.AddressSaveOption>,
            ): GeckoResult<Response> = skip("address-save", request)

            override fun onAddressSelect(
                session: GeckoSession,
                request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.AddressSelectOption>,
            ): GeckoResult<Response> = skip("address-select", request)

            override fun onCreditCardSave(
                session: GeckoSession,
                request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.CreditCardSaveOption>,
            ): GeckoResult<Response> = skip("card-save", request)

            override fun onCreditCardSelect(
                session: GeckoSession,
                request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.CreditCardSelectOption>,
            ): GeckoResult<Response> = skip("card-select", request)

            override fun onSelectIdentityCredentialProvider(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.IdentityCredential.ProviderSelectorPrompt,
            ): GeckoResult<Response> = skip("identity-provider", prompt)

            override fun onSelectIdentityCredentialAccount(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.IdentityCredential.AccountSelectorPrompt,
            ): GeckoResult<Response> = skip("identity-account", prompt)

            override fun onShowPrivacyPolicyIdentityCredential(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.IdentityCredential.PrivacyPolicyPrompt,
            ): GeckoResult<Response> = skip("identity-policy", prompt)
        }

    // ---- permissions ---------------------------------------------------------------------------------------

    private fun permissionKind(permission: Int): String =
        when (permission) {
            GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> "geolocation"
            GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> "notification"
            GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> "persistent-storage"
            GeckoSession.PermissionDelegate.PERMISSION_XR -> "xr"
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> "autoplay-inaudible"
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> "autoplay-audible"
            GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> "drm"
            GeckoSession.PermissionDelegate.PERMISSION_TRACKING -> "tracking"
            GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS -> "storage-access"
            GeckoSession.PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS -> "local-device"
            GeckoSession.PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS -> "local-network"
            else -> "other"
        }

    private fun geoValue(allow: Boolean): Int =
        if (allow) {
            GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
        } else {
            GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
        }

    /**
     * Location. Our store decides: a stored answer is applied silently, otherwise the user is asked, once for any number
     * of identical pending requests. A missing or unparsable origin is denied without a dialog. Gecko stores whatever
     * we answer for good, so every answer schedules a reconcile that resets its copy (see [GeolocationReconciler]).
     */
    private fun geolocationRequest(
        perm: GeckoSession.PermissionDelegate.ContentPermission,
    ): GeckoResult<Int> {
        val top = siteOriginOf(perm.uri)
        val thirdParty = perm.thirdPartyOrigin
        val requester = if (thirdParty == null) top else siteOriginOf(thirdParty)
        if (closed || top == null || requester == null) {
            debugLog.log("GeckoSession", "permission denied: geolocation")
            reconciler.markDirty()
            return GeckoResult.fromValue(geoValue(false))
        }
        val key = SitePermissionKey(SitePermissionKind.LOCATION, requester, if (requester == top) null else top)
        val stored = permissionPolicy.lookup(key)
        if (stored != null) {
            debugLog.log("GeckoSession", "permission ${if (stored) "allowed" else "denied"}: geolocation")
            reconciler.markDirty()
            return GeckoResult.fromValue(geoValue(stored))
        }
        if (abuseGuard.shouldSuppress()) {
            debugLog.log("GeckoSession", "permission denied: geolocation")
            reconciler.markDirty()
            return GeckoResult.fromValue(geoValue(false))
        }
        val result = GeckoResult<Int>()
        val first =
            geoCoalescer.join(key) { allow ->
                debugLog.log("GeckoSession", "permission ${if (allow) "allowed" else "denied"}: geolocation")
                reconciler.markDirty()
                runCatching { result.complete(geoValue(allow)) }
            }
        if (first) {
            queue.add(
                SitePrompt.SitePermission(
                    key.origin,
                    key.embedder,
                    listOf(SitePermissionKind.LOCATION),
                    offerRemember = permissionPolicy.offerRemember,
                ) { answer ->
                    // Dismissed (null) denies and records nothing.
                    if (answer != null) permissionPolicy.record(key, answer.allow, answer.remember)
                    geoCoalescer.answer(key, answer?.allow == true)
                },
            )
        }
        return result
    }

    /** Ends a media request exactly once; a closed session may refuse the call, which must not crash us. */
    private class MediaAnswer(private val callback: GeckoSession.PermissionDelegate.MediaCallback) {
        private val done = AtomicBoolean(false)

        fun grant(
            video: GeckoSession.PermissionDelegate.MediaSource?,
            audio: GeckoSession.PermissionDelegate.MediaSource?,
        ) {
            if (!done.compareAndSet(false, true)) return
            runCatching { callback.grant(video, audio) }.onFailure { runCatching { callback.reject() } }
        }

        fun reject() {
            if (!done.compareAndSet(false, true)) return
            runCatching { callback.reject() }
        }
    }

    private class AndroidAnswer(private val callback: GeckoSession.PermissionDelegate.Callback) {
        private val done = AtomicBoolean(false)

        fun grant() {
            if (!done.compareAndSet(false, true)) return
            runCatching { callback.grant() }.onFailure { runCatching { callback.reject() } }
        }

        fun reject() {
            if (!done.compareAndSet(false, true)) return
            runCatching { callback.reject() }
        }
    }

    /**
     * Camera and microphone. Only real camera and microphone sources are ever offered (never screen or audio capture).
     * The first source of a kind is the one Gecko ranked best for the page's constraints.
     */
    private fun mediaRequest(
        uri: String,
        video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
        audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
        answer: MediaAnswer,
    ) {
        val videoSources = video?.filter { it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_CAMERA }
        val audioSources = audio?.filter { it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_MICROPHONE }
        // A kind the page asked for but that has no usable source cannot be satisfied.
        val unusable =
            (video == null && audio == null) ||
                (videoSources != null && videoSources.isEmpty()) ||
                (audioSources != null && audioSources.isEmpty())
        val origin = siteOriginOf(uri)
        // The embedder is the committed top-level page; without one the request cannot be attributed.
        val top = siteOriginOf(_state.value.url)
        if (closed || unusable || origin == null || top == null) {
            debugLog.log("GeckoSession", "camera/microphone request rejected")
            answer.reject()
            return
        }
        val chosenVideo = videoSources?.firstOrNull()
        val chosenAudio = audioSources?.firstOrNull()
        val embedder = if (origin == top) null else top
        val kinds = LinkedHashSet<SitePermissionKind>()
        if (videoSources != null) kinds += SitePermissionKind.CAMERA
        if (audioSources != null) kinds += SitePermissionKind.MICROPHONE
        fun keyOf(kind: SitePermissionKind) = SitePermissionKey(kind, origin, embedder)
        when (planMediaRequest(kinds) { permissionPolicy.lookup(keyOf(it)) }) {
            MediaPlan.GRANT -> {
                debugLog.log("GeckoSession", "camera/microphone allowed")
                answer.grant(chosenVideo, chosenAudio)
            }

            MediaPlan.DENY -> {
                debugLog.log("GeckoSession", "camera/microphone denied")
                answer.reject()
            }

            MediaPlan.ASK -> {
                if (abuseGuard.shouldSuppress()) {
                    debugLog.log("GeckoSession", "camera/microphone denied")
                    answer.reject()
                    return
                }
                val ask = MediaAsk(origin, embedder, kinds)
                val first =
                    mediaCoalescer.join(ask) { allow ->
                        debugLog.log("GeckoSession", "camera/microphone ${if (allow) "allowed" else "denied"}")
                        if (allow) answer.grant(chosenVideo, chosenAudio) else answer.reject()
                    }
                if (first) {
                    queue.add(
                        SitePrompt.SitePermission(
                            origin,
                            embedder,
                            kinds.toList(),
                            offerRemember = permissionPolicy.offerRemember,
                        ) { reply ->
                            // Dismissed (null) denies and records nothing.
                            if (reply != null) {
                                for (kind in kinds) permissionPolicy.record(keyOf(kind), reply.allow, reply.remember)
                            }
                            mediaCoalescer.answer(ask, reply?.allow == true)
                        },
                    )
                }
            }
        }
    }

    private fun androidPermissionsRequest(permissions: Array<out String>?, callback: GeckoSession.PermissionDelegate.Callback) {
        val answer = AndroidAnswer(callback)
        val requested = permissions?.toList().orEmpty()
        if (closed || requested.isEmpty() || requested.any { it !in AndroidPermissionBroker.ALLOWED }) {
            debugLog.log("GeckoSession", "android permission request rejected")
            answer.reject()
            return
        }
        val granted =
            AndroidPermissionBroker.isSatisfied(requested) {
                ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
            }
        if (granted) {
            answer.grant()
            return
        }
        val location =
            requested.any {
                it == AndroidPermissionBroker.FINE_LOCATION || it == AndroidPermissionBroker.COARSE_LOCATION
            }
        broker.request(requested) { ok ->
            debugLog.log("GeckoSession", "android permission ${if (ok) "granted" else "denied"}")
            if (ok) answer.grant() else answer.reject()
            // On a denial Gecko turns the location answer into a stored deny just after this.
            if (location) reconciler.afterLocationResult()
        }
    }

    /**
     * Location and camera/microphone go through the user (see above). Every other kind is unchanged: only inaudible
     * autoplay is allowed, notifications and the rest are denied with no dialog.
     */
    private fun permissionDelegate() =
        object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission,
            ): GeckoResult<Int> {
                if (perm.permission == GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION) {
                    return geolocationRequest(perm)
                }
                val allow = perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
                // The kind only: the page address is not logged.
                debugLog.log(
                    "GeckoSession",
                    "permission ${if (allow) "allowed" else "denied"}: ${permissionKind(perm.permission)}",
                )
                return GeckoResult.fromValue(
                    if (allow) {
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                    } else {
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                    },
                )
            }

            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<out String>?,
                callback: GeckoSession.PermissionDelegate.Callback,
            ) = androidPermissionsRequest(permissions, callback)

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback,
            ) = mediaRequest(uri, video, audio, MediaAnswer(callback))
        }

    // ---- BrowserSession ------------------------------------------------------------------------------------

    override fun attachTo(contentView: View) {
        if (closed) return
        val geckoView = contentView as? GeckoView
            ?: error("contentView must be created by BrowserEngine.createContentView")
        val current = geckoView.session
        if (current === session) return
        // setSession with another session still attached is not allowed: release it first.
        if (current != null) {
            runCatching { geckoView.releaseSession() }
                .onFailure { debugLog.log("GeckoSession", "release before attach failed: ${it.javaClass.simpleName}") }
        }
        geckoView.setSession(session)
        // Selection menu with Search and Share (GeckoView's own one has neither); needs the activity for its toolbar.
        if (session.selectionActionDelegate !is OrbisSelectionDelegate) {
            activityOf(contentView.context)?.let { activity ->
                session.selectionActionDelegate = OrbisSelectionDelegate(activity) { text -> onSelectionSearch()?.invoke(text, isPrivate) }
            }
        }
    }

    override fun detachFrom(contentView: View) {
        val geckoView = contentView as? GeckoView ?: return
        if (geckoView.session === session) {
            runCatching { geckoView.releaseSession() }
                .onFailure { debugLog.log("GeckoSession", "release failed: ${it.javaClass.simpleName}") }
        }
    }

    override fun loadUrl(url: String) {
        if (closed) return
        // Defense in depth: the address bar already normalizes to http(s) or a search URL.
        if (!isWebUrl(url)) {
            debugLog.log("GeckoSession", "refused non-web load")
            return
        }
        userNavigation()
        applyTrackingProtection(!trackingExceptions.isExcepted(url, isPrivate))
        applySitePolicy(url)
        session.loadUri(url)
        // No URL or search term in the persisted log: it would record browsing history.
        debugLog.log("GeckoSession", "load requested")
    }

    override fun loadExtensionPage(url: String) {
        // Only an installed extension's own page (its settings), opened from Orbis's UI; never a page's request.
        if (closed || !url.startsWith("moz-extension://")) return
        userNavigation()
        pendingExtensionLoad = true
        session.loadUri(url)
    }

    override fun showStartPage() {
        if (closed) return
        // The start page is about:blank: after a lost page it must count as a page again (see isFreshSessionBlank).
        userNavigation()
        session.loadUri("about:blank")
        debugLog.log("GeckoSession", "start page requested")
    }

    override fun loadExternalUrl(url: String) {
        if (closed) return
        if (!isWebUrl(url)) {
            debugLog.log("GeckoSession", "refused non-web external load")
            return
        }
        userNavigation()
        applyTrackingProtection(!trackingExceptions.isExcepted(url, isPrivate))
        applySitePolicy(url)
        session.load(
            GeckoSession.Loader()
                .uri(url)
                .flags(GeckoSession.LOAD_FLAGS_EXTERNAL),
        )
        debugLog.log("GeckoSession", "external load requested")
    }

    /**
     * Loads a document copied into the import cache.
     *
     * GeckoView's pdf.js (on by default since bug 1810106; `pdfjs.disabled` is left untouched) handles a response
     * whose content type is `application/pdf`. A `file:` URL has no Content-Type header, so Gecko sniffs the
     * extension: `.pdf` opens the built-in viewer, `.html` opens as an ordinary local page. This is a
     * normal [GeckoSession.loadUri] of that file. It is not `resource://pdf.js/...` (the viewer page is privileged)
     * and not a local HTTP server (that would give the document a network origin). No preference is flipped to
     * allow `file:` more broadly. XHTML is not imported.
     *
     * That is not enough to keep a local page inside its own file. GeckoView does not block `file:` subresources
     * into the app data directory (bug 1684947). `NavigationDelegate` only offers [onLoadRequest] and
     * [onSubframeLoadRequest]; there is no hook for `<img>`, `<script>` or `<link>`. HTML was rewritten
     * with a CSP that denies those (`default-src 'none'`, no `file:` source) and JavaScript is turned off here,
     * before [loadUri], because [applySitePolicy] does nothing for a non-web URL. The same setting is applied
     * again from [onPageStart] and [onLocationChange], which is what Back and Forward hit. Forward from that
     * page to a website restores [applySitePolicy] and reloads once if JavaScript changed; the flag is cleared
     * first so the reload does not do it again. PDF is not rewritten and keeps JavaScript so pdf.js can run.
     * [onSubframeLoadRequest] denies every `file:` subframe. Private
     * sessions never load these: a shared document opens in a normal tab.
     */
    override fun loadImportedFile(url: String) {
        if (closed || isPrivate) return
        if (!importFileAllowed(importRoot, url)) {
            debugLog.log("GeckoSession", "refused import load")
            return
        }
        userNavigation()
        applyImportedScript(url)
        session.loadUri(url)
        debugLog.log("GeckoSession", "import load requested")
    }

    /**
     * JavaScript for a commit that may have skipped [onLoadRequest]. An import forces HTML off and PDF on.
     * A website reached from an import gets [applySitePolicy] back. [reloadIfChanged] reloads once when the
     * value actually changes; [scriptForcedByImport] is cleared before that reload so it cannot loop.
     */
    private fun applyImportedScript(url: String, reloadIfChanged: Boolean = false) {
        when (val action = scriptAfterHistoryCommit(url, session.settings.allowJavascript, scriptForcedByImport)) {
            HistoryScript.Leave -> Unit
            is HistoryScript.Force -> {
                scriptForcedByImport = true
                if (session.settings.allowJavascript != action.enabled) session.settings.allowJavascript = action.enabled
                _state.update { it.copy(javaScriptOn = action.enabled) }
                if (reloadIfChanged && action.reload && !closed && session.isOpen) session.reload()
            }
            HistoryScript.RestoreSite -> {
                val before = session.settings.allowJavascript
                applySitePolicy(url)
                val changed = session.settings.allowJavascript != before
                // The flag is already clear when site policy applied. A missing policy leaves it set and
                // does not reload, so the next commit can try again without spinning.
                if (reloadIfChanged && changed && !scriptForcedByImport && !closed && session.isOpen) session.reload()
            }
        }
    }

    override fun setDesktopMode(enabled: Boolean) {
        if (closed || _state.value.desktopMode == enabled) return
        // The user decided: a pending retry for this page's site ends here (both menus store the choice first).
        hostOfUrl(_state.value.url)?.lowercase()?.let { host ->
            if (host == retryDesktopHost) {
                retryDesktopHost = null
                autoDesktop.onLeft(host)
            }
        }
        applyDesktop(enabled)
        // Settings only take effect for the next load.
        if (session.isOpen && _state.value.url.isNotEmpty()) reload()
    }

    private fun applyDesktop(enabled: Boolean) {
        if (_state.value.desktopMode == enabled) return
        session.settings.userAgentMode =
            if (enabled) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        session.settings.viewportMode =
            if (enabled) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        _state.update { it.copy(desktopMode = enabled) }
    }

    /** The site's desktop and JavaScript choices, set before its page loads (like tracking protection). */
    private fun applySitePolicy(url: String) {
        if (closed || !isWebUrl(url)) return
        val policy = sitePolicy() ?: return
        applyDesktop(desktopWantedFor(url) ?: policy.desktopFor(url, isPrivate))
        val js = policy.javaScriptFor(url, isPrivate)
        if (session.settings.allowJavascript != js) session.settings.allowJavascript = js
        _state.update { it.copy(javaScriptOn = js) }
        scriptForcedByImport = false
    }

    override fun printPage() {
        if (closed || !session.isOpen) return
        // GeckoView's own print delegate (set when the view attaches the session) opens Android's print dialog.
        runCatching { session.printPageContent() }.onFailure { debugLog.log("GeckoSession", "print failed: ${it.javaClass.simpleName}") }
    }

    override fun setJavaScriptEnabled(enabled: Boolean) {
        if (closed) return
        if (session.settings.allowJavascript != enabled) session.settings.allowJavascript = enabled
        _state.update { it.copy(javaScriptOn = enabled) }
        if (session.isOpen && _state.value.url.isNotEmpty()) reload()
    }

    /** The session's back/forward list as Gecko last reported it (main thread). */
    private var history: GeckoSession.HistoryDelegate.HistoryList? = null

    /**
     * Back and Forward skip onLoadRequest, so the entry's site gets its desktop mode here, before its request is sent.
     * Gecko honours a mode set here in both directions, desktop to mobile too (measured on GeckoView 160). The entry
     * comes from [tabHistory]: Gecko reports its own list only after about 10 s without a navigation. When the list is
     * unknown (a restored tab), Gecko's list is used if it is current; otherwise the commit fallback
     * ([onCommitForDesktop]) corrects the mode with one reload.
     */
    private fun prepareHistoryStep(offset: Int) {
        val list = history
        val uri = historyStepUri(tabHistory, offset, list?.map { it.uri }, list?.currentIndex ?: -1, _state.value.url)
        if (uri == null) {
            // No URL in the log.
            debugLog.log("GeckoSession", "history step: entry not known; desktop mode left to the commit")
            return
        }
        desktopWantedFor(uri)?.let { applyDesktop(it) }
    }

    override fun goBack(): Boolean {
        if (closed || !_state.value.canGoBack) return false
        prepareHistoryStep(-1)
        tabHistory.onStepStarted(-1)
        session.goBack()
        return true
    }

    override fun goForward(): Boolean {
        if (closed || !_state.value.canGoForward) return false
        prepareHistoryStep(1)
        tabHistory.onStepStarted(1)
        session.goForward()
        return true
    }

    override fun reload() {
        if (closed) return
        val target = reloadTarget(_state.value)
        when (target) {
            // The banner's Reload and the toolbar's are this one call.
            ReloadTarget.LostPage -> restoreLostPage()
            is ReloadTarget.Load -> {
                _state.update { it.copy(loadFailed = false, failedUrl = null, httpsOnlyBlocked = false) }
                session.loadUri(target.url)
            }
            ReloadTarget.Page -> {
                _state.update { it.copy(loadFailed = false, failedUrl = null, httpsOnlyBlocked = false) }
                session.reload()
            }
        }
    }

    override fun stop() {
        if (!closed) session.stop()
    }

    private fun applyTrackingProtection(on: Boolean) {
        if (session.settings.useTrackingProtection != on) session.settings.useTrackingProtection = on
    }

    /** The current page's tracking permission record (see onLocationChange); null without a web page. */
    private var trackingPermission: GeckoSession.PermissionDelegate.ContentPermission? = null

    override fun setTrackingProtectionOff(off: Boolean) {
        if (closed) return
        val url = _state.value.url
        if (!isWebUrl(url)) return
        trackingExceptions.set(url, isPrivate, excepted = off)
        // A normal exception is stored by the engine so it survives restarts; a private one ends with the private session.
        val perm = trackingPermission
        if (!isPrivate && perm != null) {
            val value =
                if (off) GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
            runtime.storageController.setPermission(perm, value)
        }
        applyTrackingProtection(!off)
        _state.update { it.copy(trackingProtectionOff = off) }
        debugLog.log("GeckoSession", "tracking protection exception ${if (off) "added" else "removed"}")
        session.reload()
    }

    override fun openExternalScheme(url: String) {
        if (closed || !isExternalScheme(url)) return
        when (val route = routeExternalNavigation(url, appContext.packageName)) {
            is ExternalNavigation.Web -> if (route.url != null) loadUrl(route.url)
            ExternalNavigation.Blocked -> Unit
            is ExternalNavigation.App -> {
                val target = externalApps.targetFor(route)
                // The menu row is the confirmation, so this does not ask a second time.
                val opened = target.intent != null && externalApps.launch(target)
                if (!opened) followExternalMiss(target, url)
            }
        }
    }

    override fun saveUrl(url: String) {
        if (closed || !isWebUrl(url)) return
        // Origin only (scheme://host[:port]/), like the strict-origin-when-cross-origin policy pages get: the page's
        // path and query never go to the site the link points at.
        val referrer = trackingOrigin(_state.value.url.takeIf { isWebUrl(it) })?.let { "$it/" }
        val request = WebRequest.Builder(url).apply { if (referrer != null) referrer(referrer) }.build()
        val flags = if (isPrivate) GeckoWebExecutor.FETCH_FLAGS_PRIVATE else GeckoWebExecutor.FETCH_FLAGS_NONE
        GeckoWebExecutor(runtime).fetch(request, flags).accept(
            { response ->
                if (response == null) return@accept
                val download = GeckoIncomingDownload(response)
                val owner = host
                if (closed || owner == null || response.statusCode !in 200..299) {
                    download.discard()
                    return@accept
                }
                owner.onDownload(download, this)
                debugLog.log("GeckoSession", "save offered")
            },
            { debugLog.log("GeckoSession", "save failed: ${it?.javaClass?.simpleName}") },
        )
    }

    override suspend fun isPdfDocument(): Boolean =
        withContext(Dispatchers.Main.immediate) {
            if (closed) return@withContext false
            try {
                withTimeoutOrNull(1_000L) {
                    suspendCancellableCoroutine { cont ->
                        session.isPdfJs().accept(
                            { if (cont.isActive) cont.resume(it == true) },
                            { if (cont.isActive) cont.resume(false) },
                        )
                    }
                } ?: false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        }

    /**
     * pdf.js hands over the bytes it already holds (GeckoView's own "Download" path), so nothing is fetched again.
     * GeckoView never answers on a page that is not pdf.js, hence the check first and the timeout.
     */
    override suspend fun currentPdf(): IncomingDownload? {
        if (!isPdfDocument()) return null
        return withContext(Dispatchers.Main.immediate) {
            if (closed) return@withContext null
            try {
                withTimeoutOrNull(30_000L) {
                    suspendCancellableCoroutine<IncomingDownload?> { cont ->
                        session.pdfFileSaver.save().accept(
                            { response ->
                                if (response == null || response.statusCode !in 200..299) {
                                    runCatching { response?.body?.close() }
                                    debugLog.log("GeckoSession", "pdf share failed: status")
                                    if (cont.isActive) cont.resume(null)
                                    return@accept
                                }
                                if (cont.isActive) cont.resume(GeckoIncomingDownload(response)) else runCatching { response.body?.close() }
                            },
                            {
                                debugLog.log("GeckoSession", "pdf share failed: ${it?.javaClass?.simpleName}")
                                if (cont.isActive) cont.resume(null)
                            },
                        )
                    }
                }.also { if (it != null) debugLog.log("GeckoSession", "pdf share offered") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                debugLog.log("GeckoSession", "pdf share failed: ${e.javaClass.simpleName}")
                null
            }
        }
    }

    override fun exitFullScreen() {
        if (!closed && _state.value.fullScreen) session.exitFullScreen()
    }

    override fun find(query: String, backwards: Boolean) {
        if (closed) return
        if (query.isEmpty()) {
            clearFind()
            return
        }
        val generation = ++findGeneration
        val flags = if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else GeckoSession.FINDER_FIND_FORWARD
        runCatching {
            val finder = session.finder
            finder.displayFlags = GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL
            finder.find(query, flags).accept(
                { found ->
                    // A newer query or a clear has superseded this answer.
                    if (!closed && found != null && generation == findGeneration) {
                        _state.update { it.copy(findResult = FindResult(found.found, found.current, found.total)) }
                    }
                },
                { _ -> },
            )
        }.onFailure { debugLog.log("GeckoSession", "find failed: ${it.javaClass.simpleName}") }
    }

    override fun clearFind() {
        findGeneration++
        if (!closed) runCatching { session.finder.clear() }
        _state.update { it.copy(findResult = null) }
    }

    override fun setActive(active: Boolean) {
        wantActive = active
        applyActive()
        // A page lost to a kill while the tab was in the background comes back when the tab is shown.
        if (active && !closed && restoreWhenShown) restoreAfterKill()
    }

    override fun savedState(): String? = latestSessionState?.toString()

    override fun close() {
        if (closed) return
        // Answer everything first: a pending GeckoResult must not outlive its session.
        queue.dismissAll()
        // Cancels any file copy still running; dismissAll already answered its prompt.
        scope.cancel()
        closed = true
        host = null
        runCatching { session.close() }
            .onFailure { debugLog.log("GeckoSession", "close failed: ${it.javaClass.simpleName}") }
        onClosed()
        discardPrivateUploads()
    }

    private fun discardPrivateUploads() {
        if (privateUploads.isEmpty()) return
        val files = privateUploads.toList()
        privateUploads.clear()
        // Off the main thread, and not on the session scope: it was cancelled above.
        Thread({ runCatching { uploads.discardNow(files) } }, "orbis-upload-discard").start()
    }
}

/**
 * A WebResponse the engine will not render. The body is claimed by exactly one of openBody / discard; after that the
 * other is a no-op, so the stream is never opened twice nor left unclosed.
 */
private class GeckoIncomingDownload(private val response: WebResponse, override val startOffset: Long = 0L) : IncomingDownload {
    private val claimed = AtomicBoolean(false)

    private fun header(name: String): String? =
        response.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    override val url: String get() = response.uri
    override val contentDisposition: String? get() = header("content-disposition")
    override val contentType: String? get() = header("content-type")
    override val validator: String? get() = resumeValidator(header("etag"), header("last-modified"))
    override val userRequested: Boolean get() = response.skipConfirmation
    // -1 (unknown) when the body may have been decoded: the length header then describes the compressed bytes, and a
    // known length that differs from what is received would be mistaken for a truncated download.
    override val contentLength: Long
        get() {
            val encoding = header("content-encoding")?.trim()
            if (!encoding.isNullOrEmpty() && !encoding.equals("identity", ignoreCase = true)) return -1L
            return header("content-length")?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: -1L
        }

    override fun openBody(): InputStream? = if (claimed.compareAndSet(false, true)) response.body else null

    override fun discard() {
        if (claimed.compareAndSet(false, true)) runCatching { response.body?.close() }
    }
}

private class GeckoExtensionHost(
    private val debugLog: DebugLog,
    private val runtimeProvider: () -> GeckoRuntime,
) : ExtensionHost {
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * The install in flight (main thread). The prompt only grants for [id], and only when [confirm] returns true.
     * [grantPrivate] is true for uBlock Origin only. [curated] adds the signature and blocklist refusals of the
     * catalogue path (uBlock Origin's path keeps its checks as they were).
     */
    private class PendingInstall(
        val id: String,
        val confirm: suspend (AddonAccess) -> Boolean,
        val grantPrivate: Boolean,
        val curated: Boolean,
        val scope: CoroutineScope,
    )

    @Volatile
    private var pending: PendingInstall? = null

    /** Why the prompt for this install was denied without asking, or null when it was not. */
    @Volatile
    private var promptDenial: ExtensionInstallError? = null

    val promptDelegate =
        object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>,
            ): GeckoResult<WebExtension.PermissionPromptResponse> {
                val install = pending
                if (install == null || extension.id != install.id) {
                    promptDenial = ExtensionInstallError.WRONG_EXTENSION
                    debugLog.log("Extensions", "install prompt denied for unexpected extension ${extension.id}")
                    return GeckoResult.fromValue(WebExtension.PermissionPromptResponse(false, false, false))
                }
                if (install.curated) {
                    flaggedBeforeInstall(extension.metaData)?.let { flag ->
                        promptDenial = flag
                        debugLog.log(
                            "Extensions",
                            "install prompt denied for ${extension.id}: $flag (signedState=" +
                                "${extension.metaData.signedState}, blocklistState=${extension.metaData.blocklistState})",
                        )
                        return GeckoResult.fromValue(WebExtension.PermissionPromptResponse(false, false, false))
                    }
                }
                val access = AddonAccess(permissions.toList(), origins.toList())
                val result = GeckoResult<WebExtension.PermissionPromptResponse>()
                val answered = AtomicBoolean(false)
                fun answer(granted: Boolean) {
                    if (!answered.compareAndSet(false, true)) return
                    debugLog.log(
                        "Extensions",
                        "install prompt ${if (granted) "granted" else "refused"} for ${extension.id}: " +
                            "${permissions.size} permissions, ${origins.size} origins, " +
                            "${dataCollectionPermissions.size} data permissions (not granted)",
                    )
                    // Data collection always stays off. Private mode only for uBlock Origin, so the content blocker
                    // also works in private tabs; other add-ons start off there until the user switches them on.
                    result.complete(
                        WebExtension.PermissionPromptResponse(granted, granted && install.grantPrivate, false),
                    )
                }
                // UNDISPATCHED: a confirm that does not suspend (uBlock Origin's) answers before this returns, as before.
                install.scope
                    .launch(start = CoroutineStart.UNDISPATCHED) {
                        val granted =
                            try {
                                install.confirm(access)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                debugLog.log("Extensions", "install confirm failed: ${e.javaClass.simpleName}")
                                false
                            }
                        answer(granted)
                    }.invokeOnCompletion { answer(false) }
                return result
            }

            override fun onUpdatePrompt(
                extension: WebExtension,
                newPermissions: Array<String>,
                newOrigins: Array<String>,
                newDataCollectionPermissions: Array<String>,
            ): GeckoResult<AllowOrDeny> {
                debugLog.log(
                    "Extensions",
                    "update for ${extension.id} denied: wants ${newPermissions.size} new permissions, " +
                        "${newOrigins.size} new origins",
                )
                return GeckoResult.deny()
            }
        }

    private fun controller(): WebExtensionController = runtimeProvider().webExtensionController

    // ---- Add-on buttons and panels (main thread) ----

    private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Installed add-ons by id, from the last [refreshActions], Orbis's built-in extensions left out. */
    private var addonExtensions: Map<String, WebExtension> = emptyMap()
    private var addonInstalled: List<InstalledExtension> = emptyList()

    /** Each add-on's default button. */
    private val defaultActions = HashMap<String, WebExtension.Action>()

    /** A tab's own button values (an add-on may set a badge per tab), by session. */
    private val tabActions = java.util.WeakHashMap<GeckoSession, HashMap<String, WebExtension.Action>>()

    /** The add-ons whose per-tab values each session already reports to us. */
    private val watchedSessions = java.util.WeakHashMap<GeckoSession, MutableSet<String>>()

    private var selectedSession: GeckoSession? = null
    private var selectedPrivate = false

    /** The add-on whose button the user just tapped, and when: only that tap may open its panel. */
    private var pendingClick: Pair<String, Long>? = null

    private val _actions = MutableStateFlow<List<AddonAction>>(emptyList())
    override val actions: StateFlow<List<AddonAction>> = _actions.asStateFlow()

    private val _popup = MutableStateFlow<AddonPopup?>(null)
    override val popup: StateFlow<AddonPopup?> = _popup.asStateFlow()

    private val actionDelegate =
        object : WebExtension.ActionDelegate {
            override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) =
                onAction(extension, session, action)

            override fun onPageAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) =
                onAction(extension, session, action)

            // Called after a click (Action.click) on a button that has a panel.
            override fun onTogglePopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? =
                togglePopup(extension)

            // browserAction.openPopup(): the add-on asks on its own, without a tap. Refused.
            override fun onOpenPopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? {
                debugLog.log("Extensions", "panel open without a tap refused for ${extension.id}")
                return null
            }
        }

    override var onOpenAddonPage: ((url: String) -> Unit)? = null

    /** The one page the last panel the user opened may still open (that panel open or closed); null before the first. */
    private var lastPanelPages: PanelPages? = null

    // tabs.create. uBlock Origin's dashboard button and Dark Reader's links ask this way, from the panel or from the
    // add-on's background page. Only the panel's own add-on page, once per opening, while it is open or just after
    // its own page closed it (see PanelPages); the panel then closes and the page opens in a new normal tab through
    // onOpenAddonPage. No session goes back: the engine's call then ends without a tab, which the add-ons tolerate.
    private val tabDelegate =
        object : WebExtension.TabDelegate {
            override fun onNewTab(source: WebExtension, details: WebExtension.CreateTabDetails): GeckoResult<GeckoSession>? {
                val refusal = openFromPanel(lastPanelPages, source.id, details.url, selectedSession)
                if (refusal != null) {
                    debugLog.log("Extensions", "new tab for ${source.id} refused: $refusal")
                    return null
                }
                val url = details.url ?: return null
                closePopup()
                val opener = onOpenAddonPage
                if (opener == null) {
                    debugLog.log("Extensions", "page of ${source.id} not opened: no page opener")
                } else {
                    opener(url)
                    debugLog.log("Extensions", "page of ${source.id} opened from its panel")
                }
                return null
            }
        }

    private fun onAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
        if (isBuiltInExtension(extension.id)) return
        if (session == null) {
            defaultActions[extension.id] = action
        } else {
            tabActions.getOrPut(session) { HashMap() }[extension.id] = action
        }
        publishActions()
    }

    /** The button of [id] for the selected tab: its own values over the default. */
    private fun currentAction(id: String): WebExtension.Action? {
        val default = defaultActions[id]
        val own = selectedSession?.let { tabActions[it]?.get(id) }
        return when {
            own != null && default != null -> own.withDefault(default)
            else -> own ?: default
        }
    }

    private fun publishActions() {
        if (selectedSession == null) {
            _actions.value = emptyList()
            return
        }
        val ids = defaultActions.keys + selectedSession?.let { tabActions[it]?.keys }.orEmpty()
        val all =
            ids.mapNotNull { id ->
                val action = currentAction(id) ?: return@mapNotNull null
                AddonAction(
                    extensionId = id,
                    title = addonInstalled.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() }
                        ?: action.title?.takeIf { it.isNotBlank() }
                        ?: id,
                    enabled = action.enabled != false,
                    badgeText = action.badgeText?.takeIf { it.isNotEmpty() },
                )
            }
        _actions.value = shownAddonActions(all, addonInstalled, selectedPrivate)
    }

    /** Reads the installed add-ons again and listens to each one's button. Call after anything that changes the list. */
    fun refreshActions() {
        actionScope.launch {
            val list =
                try {
                    controller().list().awaitResult().orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    debugLog.log("Extensions", "add-on list for buttons failed: ${e.javaClass.simpleName}")
                    return@launch
                }
            val addons = list.filterNot { isBuiltInExtension(it.id) }
            addonExtensions = addons.associateBy { it.id }
            addonInstalled = addons.map { it.toInstalled() }
            defaultActions.keys.retainAll(addonExtensions.keys)
            tabActions.values.forEach { it.keys.retainAll(addonExtensions.keys) }
            // A new WebExtension object after an install or a switch: listen again; the engine then sends the button.
            addons.forEach { ext ->
                runCatching { ext.setActionDelegate(actionDelegate) }
                    .onFailure { debugLog.log("Extensions", "button delegate failed for ${ext.id}: ${it.javaClass.simpleName}") }
                runCatching { ext.setTabDelegate(tabDelegate) }
                    .onFailure { debugLog.log("Extensions", "tab delegate failed for ${ext.id}: ${it.javaClass.simpleName}") }
            }
            watchedSessions.clear()
            selectedSession?.let(::watchSession)
            _popup.value?.let { open -> if (open.extensionId !in addonExtensions.keys) closePopup() }
            publishActions()
        }
    }

    private fun watchSession(session: GeckoSession) {
        val watched = watchedSessions.getOrPut(session) { HashSet() }
        for ((id, ext) in addonExtensions) {
            if (!watched.add(id)) continue
            runCatching { session.webExtensionController.setActionDelegate(ext, actionDelegate) }
                .onFailure { debugLog.log("Extensions", "tab button delegate failed for $id: ${it.javaClass.simpleName}") }
        }
    }

    override fun selectSession(session: BrowserSession?) {
        val gecko = (session as? GeckoBrowserSession)?.geckoSession
        if (gecko !== selectedSession) {
            closePopup()
            pendingClick = null
            // The add-ons' "active tab" (a button tap acts on it; tabs.query sees it). Without one the engine drops
            // every tap ("nativeTab is null" in ext-android.js). A hidden page (null) leaves no tab active.
            runCatching {
                selectedSession?.let { controller().setTabActive(it, false) }
                gecko?.let { controller().setTabActive(it, true) }
            }.onFailure { debugLog.log("Extensions", "active tab update failed: ${it.javaClass.simpleName}") }
        }
        selectedSession = gecko
        selectedPrivate = session?.isPrivate == true
        gecko?.let(::watchSession)
        publishActions()
    }

    override fun clickAction(extensionId: String) {
        if (selectedSession == null) return
        if (_actions.value.none { it.extensionId == extensionId && it.enabled }) return
        val action = currentAction(extensionId) ?: return
        pendingClick = extensionId to SystemClock.elapsedRealtime()
        runCatching { action.click() }
            .onFailure {
                pendingClick = null
                debugLog.log("Extensions", "button click failed for $extensionId: ${it.javaClass.simpleName}")
            }
    }

    private fun togglePopup(extension: WebExtension): GeckoResult<GeckoSession>? {
        val click = pendingClick
        pendingClick = null
        val tapped =
            click != null && click.first == extension.id &&
                SystemClock.elapsedRealtime() - click.second <= POPUP_AFTER_TAP_MILLIS
        if (!tapped || selectedSession == null) {
            debugLog.log("Extensions", "panel without a tap refused for ${extension.id}")
            return null
        }
        val open = _popup.value
        closePopup()
        // A second tap on the same button closes its panel, as on desktop.
        if (open?.extensionId == extension.id) return null
        val name = actions.value.firstOrNull { it.extensionId == extension.id }?.title ?: extension.metaData.name ?: extension.id
        val popupSession =
            GeckoSession(
                GeckoSessionSettings.Builder()
                    .usePrivateMode(selectedPrivate)
                    .useTrackingProtection(true)
                    .build(),
            )
        val tab = selectedSession ?: return null
        val pages = PanelPages(extension.id, extension.metaData.baseUrl.orEmpty(), tab, SystemClock::elapsedRealtime)
        lastPanelPages = pages
        val panel = GeckoAddonPopup(extension.id, name, pages, popupSession, runtimeProvider, debugLog) { closed ->
            if (_popup.value === closed) _popup.value = null
        }
        _popup.value = panel
        debugLog.log("Extensions", "panel opened for ${extension.id}")
        // The engine loads the panel's page into this session; it is opened when it is first shown (attachTo).
        return GeckoResult.fromValue(popupSession)
    }

    private fun closePopup() {
        _popup.value?.close()
        _popup.value = null
    }

    /** Bridges a GeckoResult; cancelling the coroutine cancels the GeckoResult (on the main thread). */
    private suspend fun <T> GeckoResult<T>.awaitResult(): T? =
        suspendCancellableCoroutine { cont ->
            accept(
                { value -> cont.resume(value) },
                { error -> cont.resumeWithException(error ?: IllegalStateException("GeckoResult failed")) },
            )
            cont.invokeOnCancellation {
                mainHandler.post { runCatching { this@awaitResult.cancel() } }
            }
        }

    private suspend fun findGecko(id: String): WebExtension? =
        controller().list().awaitResult().orEmpty().firstOrNull { it.id == id }

    private fun WebExtension.toInstalled() =
        InstalledExtension(
            id = id,
            version = metaData.version,
            enabled = metaData.enabled,
            disabledByUser = (metaData.disabledFlags and WebExtension.DisabledFlags.USER) != 0,
            disabledReason = disabledReasonOf(metaData.enabled, metaData.disabledFlags),
            optionsPageUrl = metaData.optionsPageUrl,
            name = metaData.name,
            allowedInPrivate = metaData.allowedInPrivateBrowsing,
            permissions = metaData.requiredPermissions.toList(),
            origins = metaData.requiredOrigins.toList(),
        )

    override suspend fun find(id: String): InstalledExtension? =
        withContext(Dispatchers.Main.immediate) { findGecko(id)?.toInstalled() }

    override suspend fun list(): List<InstalledExtension> =
        withContext(Dispatchers.Main.immediate) { controller().list().awaitResult().orEmpty().map { it.toInstalled() } }

    override suspend fun install(xpiUrl: String, expectedId: String): InstalledExtension =
        install(
            xpiUrl,
            expectedId,
            confirm = { true },
            grantPrivate = true,
            curated = false,
            method = WebExtensionController.INSTALLATION_METHOD_ONBOARDING,
        )

    override suspend fun install(
        xpiUrl: String,
        expectedId: String,
        confirm: suspend (AddonAccess) -> Boolean,
    ): InstalledExtension =
        install(
            xpiUrl,
            expectedId,
            confirm,
            grantPrivate = false,
            curated = true,
            method = WebExtensionController.INSTALLATION_METHOD_MANAGER,
        )

    private suspend fun install(
        xpiUrl: String,
        expectedId: String,
        confirm: suspend (AddonAccess) -> Boolean,
        grantPrivate: Boolean,
        curated: Boolean,
        method: String,
    ): InstalledExtension =
        withContext(Dispatchers.Main.immediate) {
            if (pending != null) {
                throw ExtensionInstallException(ExtensionInstallError.UNKNOWN, "an install is already in flight")
            }
            pending = PendingInstall(expectedId, confirm, grantPrivate, curated, this)
            promptDenial = null
            try {
                val result: WebExtension? =
                    try {
                        controller()
                            .install(xpiUrl, method)
                            .awaitResult()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: WebExtension.InstallException) {
                        var error = mapInstallError(e.code)
                        val denial = promptDenial
                        if (error == ExtensionInstallError.CANCELLED && denial != null) {
                            error = denial
                        }
                        debugLog.log("Extensions", "install failed: code=${e.code} -> $error")
                        throw ExtensionInstallException(error, "install failed, code ${e.code}")
                    } catch (e: Exception) {
                        debugLog.log("Extensions", "install failed: ${e.javaClass.simpleName}")
                        throw ExtensionInstallException(ExtensionInstallError.UNKNOWN, "install failed: ${e.javaClass.simpleName}")
                    }
                val installed =
                    result
                        ?: throw ExtensionInstallException(ExtensionInstallError.UNKNOWN, "install returned nothing")

                // An AMO install must be exactly the expected add-on, signed by Mozilla (not system/privileged).
                if (installed.id != expectedId) {
                    removeQuietly(installed)
                    throw ExtensionInstallException(ExtensionInstallError.WRONG_EXTENSION, "unexpected id")
                }
                if (installed.metaData.signedState != WebExtension.SignedStateFlags.SIGNED) {
                    removeQuietly(installed)
                    throw ExtensionInstallException(
                        ExtensionInstallError.SIGNATURE,
                        "signedState=${installed.metaData.signedState}",
                    )
                }
                if (curated && installed.metaData.blocklistState != WebExtension.BlocklistStateFlags.NOT_BLOCKED) {
                    removeQuietly(installed)
                    throw ExtensionInstallException(
                        ExtensionInstallError.BLOCKED,
                        "blocklistState=${installed.metaData.blocklistState}",
                    )
                }
                debugLog.log("Extensions", "installed $expectedId ${installed.metaData.version}")
                refreshActions()
                installed.toInstalled()
            } finally {
                pending = null
            }
        }

    /**
     * A flag the engine already shows at the prompt: on the blocklist in any way, or a signature that is not Mozilla's
     * (missing, preliminary, system, privileged). UNKNOWN is not refused here: the strict SIGNED check after the
     * install still applies.
     */
    private fun flaggedBeforeInstall(meta: WebExtension.MetaData): ExtensionInstallError? =
        when {
            meta.blocklistState != WebExtension.BlocklistStateFlags.NOT_BLOCKED -> ExtensionInstallError.BLOCKED
            meta.signedState != WebExtension.SignedStateFlags.SIGNED &&
                meta.signedState != WebExtension.SignedStateFlags.UNKNOWN -> ExtensionInstallError.SIGNATURE
            else -> null
        }

    private suspend fun removeQuietly(extension: WebExtension) {
        try {
            controller().uninstall(extension).awaitResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            debugLog.log("Extensions", "cleanup uninstall of ${extension.id} failed: ${e.javaClass.simpleName}")
        }
    }

    override suspend fun setEnabled(id: String, enabled: Boolean): InstalledExtension =
        withContext(Dispatchers.Main.immediate) {
            val extension = findGecko(id) ?: error("extension not installed")
            val pending =
                if (enabled) {
                    controller().enable(extension, WebExtensionController.EnableSource.USER)
                } else {
                    controller().disable(extension, WebExtensionController.EnableSource.USER)
                }
            val updated = pending.awaitResult() ?: error("enable/disable returned nothing")
            refreshActions()
            updated.toInstalled()
        }

    override suspend fun allowInPrivateBrowsing(id: String) {
        withContext(Dispatchers.Main.immediate) {
            val extension = findGecko(id) ?: return@withContext
            if (extension.metaData.allowedInPrivateBrowsing) return@withContext
            // Gecko stores the permission and restarts the extension only if it is active; a disabled one stays disabled.
            controller().setAllowedInPrivateBrowsing(extension, true).awaitResult()
            debugLog.log("Extensions", "allowed in private browsing: $id")
            refreshActions()
        }
    }

    override suspend fun setAllowedInPrivateBrowsing(id: String, allowed: Boolean): InstalledExtension =
        withContext(Dispatchers.Main.immediate) {
            val extension = findGecko(id) ?: error("extension not installed")
            if (extension.metaData.allowedInPrivateBrowsing == allowed) return@withContext extension.toInstalled()
            val updated =
                controller().setAllowedInPrivateBrowsing(extension, allowed).awaitResult()
                    ?: error("setAllowedInPrivateBrowsing returned nothing")
            debugLog.log("Extensions", "private browsing for $id: $allowed")
            refreshActions()
            updated.toInstalled()
        }

    override suspend fun uninstall(id: String) {
        withContext(Dispatchers.Main.immediate) {
            val extension = findGecko(id) ?: return@withContext
            controller().uninstall(extension).awaitResult()
            debugLog.log("Extensions", "uninstalled $id")
            refreshActions()
        }
    }

    override suspend fun checkForUpdate(id: String): InstalledExtension? =
        withContext(Dispatchers.Main.immediate) {
            val extension = findGecko(id) ?: return@withContext null
            try {
                controller().update(extension).awaitResult()?.toInstalled()?.also { refreshActions() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WebExtension.InstallException) {
                // A denied update (onUpdatePrompt) arrives as code ERROR_USER_CANCELED, as a refused install prompt does.
                val error = mapInstallError(e.code)
                debugLog.log("Extensions", "update failed: code=${e.code} -> $error")
                throw ExtensionInstallException(error, "update failed, code ${e.code}")
            }
        }
}

/** How long after a tap on an add-on's button its panel may still open. */
private const val POPUP_AFTER_TAP_MILLIS = 10_000L

/**
 * One add-on panel: the session the engine loads the panel's page into. It only shows the add-on's own pages: any other
 * load and any new window the panel asks for are refused, and the page's window.close() closes the panel. [pages] is
 * the one own page it may open in a new tab (see GeckoExtensionHost's tab delegate). Main thread.
 */
private class GeckoAddonPopup(
    override val extensionId: String,
    override val title: String,
    private val pages: PanelPages,
    private val session: GeckoSession,
    private val runtime: () -> GeckoRuntime,
    private val debugLog: DebugLog,
    private val onClosed: (GeckoAddonPopup) -> Unit,
) : AddonPopup {
    private var closed = false
    private var attachedTo: GeckoView? = null

    init {
        session.navigationDelegate =
            object : GeckoSession.NavigationDelegate {
                override fun onLoadRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.LoadRequest,
                ): GeckoResult<AllowOrDeny> {
                    val uri = request.uri
                    val own = uri.startsWith("moz-extension://") || uri == "about:blank"
                    if (!own) debugLog.log("Extensions", "panel of $extensionId: load outside the add-on refused")
                    return if (own) GeckoResult.allow() else GeckoResult.deny()
                }

                override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                    debugLog.log("Extensions", "panel of $extensionId: new window refused")
                    return null
                }
            }
        session.contentDelegate =
            object : GeckoSession.ContentDelegate {
                // The panel's own page (window.close): the only close after which its one page may still open.
                override fun onCloseRequest(session: GeckoSession) {
                    debugLog.log("Extensions", "panel of $extensionId asked to close")
                    close(bySelf = true)
                }

                override fun onCrash(session: GeckoSession) = close()

                override fun onKill(session: GeckoSession) = close()
            }
    }

    override fun attachTo(view: View) {
        if (closed) return
        val geckoView = view as? GeckoView ?: error("view must be created by BrowserEngine.createContentView")
        if (geckoView.session === session) return
        try {
            if (geckoView.session != null) geckoView.releaseSession()
            // Not opened by the engine: it waits for a runtime. Opened here with Orbis's own.
            if (!session.isOpen) session.open(runtime())
            geckoView.setSession(session)
            attachedTo = geckoView
        } catch (e: Exception) {
            debugLog.log("Extensions", "panel of $extensionId could not be shown: ${e.javaClass.simpleName}")
            close()
        }
    }

    override fun detachFrom(view: View) {
        val geckoView = view as? GeckoView ?: return
        if (geckoView.session === session) runCatching { geckoView.releaseSession() }
        if (attachedTo === geckoView) attachedTo = null
        if (closed) endSession()
    }

    /** Closed by the user or by Orbis (Close, Back, a tap outside, another tab, the lock, add-on off, crash). */
    override fun close() = close(bySelf = false)

    private fun close(bySelf: Boolean) {
        if (closed) return
        closed = true
        pages.closed(bySelf)
        onClosed(this)
        // While a view still shows it, the session ends once the view lets go of it (detachFrom).
        if (attachedTo == null) endSession()
    }

    private fun endSession() {
        if (session.isOpen) runCatching { session.close() }
        debugLog.log("Extensions", "panel closed for $extensionId")
    }
}

/** Maps the engine's install error code. [WebExtension.InstallException.ErrorCodes.ERROR_USER_CANCELED] is also what a denied update gives. */
internal fun mapInstallError(code: Int): ExtensionInstallError =
    when (code) {
        WebExtension.InstallException.ErrorCodes.ERROR_NETWORK_FAILURE -> ExtensionInstallError.NETWORK
        WebExtension.InstallException.ErrorCodes.ERROR_INCORRECT_HASH,
        WebExtension.InstallException.ErrorCodes.ERROR_CORRUPT_FILE,
        -> ExtensionInstallError.CORRUPT
        WebExtension.InstallException.ErrorCodes.ERROR_SIGNEDSTATE_REQUIRED -> ExtensionInstallError.SIGNATURE
        WebExtension.InstallException.ErrorCodes.ERROR_INCORRECT_ID,
        WebExtension.InstallException.ErrorCodes.ERROR_UNEXPECTED_ADDON_TYPE,
        -> ExtensionInstallError.WRONG_EXTENSION
        WebExtension.InstallException.ErrorCodes.ERROR_BLOCKLISTED,
        WebExtension.InstallException.ErrorCodes.ERROR_SOFT_BLOCKED,
        WebExtension.InstallException.ErrorCodes.ERROR_ADMIN_INSTALL_ONLY,
        -> ExtensionInstallError.BLOCKED
        WebExtension.InstallException.ErrorCodes.ERROR_INCOMPATIBLE,
        WebExtension.InstallException.ErrorCodes.ERROR_UNSUPPORTED_ADDON_TYPE,
        -> ExtensionInstallError.INCOMPATIBLE
        WebExtension.InstallException.ErrorCodes.ERROR_USER_CANCELED -> ExtensionInstallError.CANCELLED
        else -> ExtensionInstallError.UNKNOWN
    }

/** The reason an extension is off, from the engine's disabled flags. Null when enabled. */
internal fun disabledReasonOf(enabled: Boolean, flags: Int): ExtensionDisabledReason? =
    when {
        enabled -> null
        flags and (WebExtension.DisabledFlags.BLOCKLIST or WebExtension.DisabledFlags.SOFT_BLOCKLIST) != 0 -> ExtensionDisabledReason.BLOCKLIST
        flags and WebExtension.DisabledFlags.SIGNATURE != 0 -> ExtensionDisabledReason.SIGNATURE
        flags and WebExtension.DisabledFlags.USER != 0 -> ExtensionDisabledReason.USER
        else -> ExtensionDisabledReason.OTHER
    }

/**
 * Gecko stores every location answer for good (EXPIRE_NEVER) and then never asks us again for that site. Our decision
 * store is the only authority, so every stored location entry is reset to "ask" (VALUE_PROMPT): on runtime start (this
 * clears the silent denies older builds left behind), shortly after each answer (Gecko writes after we answer), after
 * a location system-permission result, at a top-level page start when something is outstanding, and when a stored
 * decision is removed. Only location entries are touched. Main thread; failures log the exception class only.
 */
internal class GeolocationReconciler(
    private val runtime: () -> GeckoRuntime?,
    private val debugLog: DebugLog,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val task = Runnable { reconcile() }
    private var dirty = false

    /** An answer was just given; Gecko is about to write its copy of it. */
    fun markDirty() {
        dirty = true
        postDelayed(ANSWER_DELAY_MS)
    }

    /** Gecko writes a stored deny just after a failed system-permission request. */
    fun afterLocationResult() {
        dirty = true
        postDelayed(PERMISSION_DELAY_MS)
    }

    fun onPageStart() {
        if (dirty) runNow()
    }

    fun runNow() = postDelayed(0L)

    private fun postDelayed(delayMs: Long) {
        handler.removeCallbacks(task)
        handler.postDelayed(task, delayMs)
    }

    private fun reconcile() {
        val rt = runtime() ?: return
        dirty = false
        try {
            val storage = rt.storageController
            storage.getAllPermissions().accept(
                { list ->
                    for (entry in list.orEmpty()) {
                        if (entry.permission != GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION) continue
                        if (entry.value == GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT) continue
                        try {
                            storage.setPermission(entry, GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT)
                        } catch (e: Exception) {
                            dirty = true
                            debugLog.log("GeckoEngine", "geolocation reconcile failed: ${e.javaClass.simpleName}")
                        }
                    }
                },
                { e ->
                    dirty = true
                    debugLog.log("GeckoEngine", "geolocation reconcile failed: ${e?.javaClass?.simpleName}")
                },
            )
        } catch (e: Exception) {
            dirty = true
            debugLog.log("GeckoEngine", "geolocation reconcile failed: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val ANSWER_DELAY_MS = 2_000L
        const val PERMISSION_DELAY_MS = 1_000L
    }
}

private fun EngineLogin.toGecko(): Autocomplete.LoginEntry =
    Autocomplete.LoginEntry.Builder()
        .apply { if (guid != null) guid(guid) }
        .origin(origin)
        .formActionOrigin(formActionOrigin)
        .httpRealm(httpRealm)
        .username(username)
        .password(password)
        .build()

private fun Autocomplete.LoginEntry.toEngine(): EngineLogin =
    EngineLogin(guid, origin, formActionOrigin.orEmpty(), httpRealm.orEmpty(), username, password)

/** The activity behind [context] (a view's context may wrap it), or null. */
internal fun activityOf(context: android.content.Context): android.app.Activity? =
    generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.firstOrNull { it is android.app.Activity } as? android.app.Activity

/** The first byte of a Content-Range header ("bytes 100-999/1000" gives 100), or null. */
internal fun rangeStart(contentRange: String?): Long? =
    contentRange?.trim()?.removePrefix("bytes")?.trim()?.substringBefore('-')?.trim()?.toLongOrNull()?.takeIf { it >= 0 }

internal const val CLEAR_EXIT_KEEP_TIMEOUT_MILLIS = 4_000L
internal const val CLEAR_EXIT_RESTORE_TIMEOUT_MILLIS = 3_000L

/**
 * The built-in clearexit extension (assets/extensions/clearexit): reads and writes back the cookies of the clear-on-exit
 * exceptions. It stays disabled between wipes, so it never runs while the user browses. Enabling it is also what starts
 * its background script at once: at app start Gecko delays an enabled extension's background until the first page
 * window exists, and a wipe runs before any. Cookie values stay inside the extension; only counts come back. Main thread.
 */
internal class ClearExitExtensionHolder(private val log: (String) -> Unit) {
    private val installed = kotlinx.coroutines.CompletableDeferred<WebExtension?>()
    private var ext: WebExtension? = null
    private var livePort: WebExtension.Port? = null
    private var portWaiter: kotlinx.coroutines.CancellableContinuation<WebExtension.Port>? = null
    private val replies = HashMap<Int, kotlinx.coroutines.CancellableContinuation<JSONObject>>()
    private var nextId = 1

    private val delegate =
        object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                val connected = port
                livePort = connected
                connected.setDelegate(
                    object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) = onReply(message)

                        override fun onDisconnect(port: WebExtension.Port) {
                            if (livePort === port) livePort = null
                            val waiting = replies.values.toList()
                            replies.clear()
                            waiting.forEach { if (it.isActive) it.resume(JSONObject().put("type", "error")) }
                        }
                    },
                )
                portWaiter?.let { waiter ->
                    portWaiter = null
                    if (waiter.isActive) waiter.resume(connected)
                }
            }
        }

    fun install(runtime: GeckoRuntime) {
        val controller = runtime.webExtensionController
        controller.ensureBuiltIn(CLEAR_EXIT_ASSET_URL, CLEAR_EXIT_ID).accept(
            { found ->
                if (found == null) {
                    installed.complete(null)
                    return@accept
                }
                attach(found)
                if (!found.metaData.enabled) {
                    installed.complete(found)
                    return@accept
                }
                controller.disable(found, WebExtensionController.EnableSource.APP).accept(
                    { disabled ->
                        disabled?.let(::attach)
                        installed.complete(disabled ?: found)
                    },
                    { installed.complete(found) },
                )
            },
            {
                log("clear on exit extension install failed: ${it?.javaClass?.simpleName}")
                installed.complete(null)
            },
        )
    }

    private fun attach(extension: WebExtension) {
        ext = extension
        runCatching { extension.setMessageDelegate(delegate, CLEAR_EXIT_NATIVE_APP) }
            .onFailure { log("clear on exit extension delegate failed: ${it.javaClass.simpleName}") }
    }

    /** Enables the extension and has it keep the cookies of [domains]. Their count, or null without an answer. */
    suspend fun snapshot(runtime: GeckoRuntime, domains: Set<String>): Int? {
        val found = installed.await() ?: return null
        attach(found)
        val enabled =
            suspendCancellableCoroutine<WebExtension?> { cont ->
                runtime.webExtensionController.enable(found, WebExtensionController.EnableSource.APP).accept(
                    { if (cont.isActive) cont.resume(it) },
                    { if (cont.isActive) cont.resume(null) },
                )
            }
        enabled?.let(::attach)
        val connected = livePort ?: suspendCancellableCoroutine { cont ->
            portWaiter = cont
            cont.invokeOnCancellation { if (portWaiter === cont) portWaiter = null }
        }
        val reply = request(connected, JSONObject().put("type", "snapshot").put("domains", org.json.JSONArray(domains.toList())))
        return if (reply.optString("type") == "snapshot") reply.optInt("count", 0) else null
    }

    /** Writes the kept cookies back: (written, refused), or null without an answer. */
    suspend fun restore(): Pair<Int, Int>? {
        val connected = livePort ?: return null
        val reply = request(connected, JSONObject().put("type", "restore"))
        return if (reply.optString("type") == "restored") reply.optInt("ok", 0) to reply.optInt("failed", 0) else null
    }

    /** Disables the extension again; anything it still holds in memory goes with its background script. */
    fun stop(runtime: GeckoRuntime) {
        val current = ext ?: return
        runCatching {
            runtime.webExtensionController.disable(current, WebExtensionController.EnableSource.APP).accept(
                { disabled -> disabled?.let(::attach) },
                { log("clear on exit extension disable failed") },
            )
        }.onFailure { log("clear on exit extension disable failed: ${it.javaClass.simpleName}") }
    }

    private suspend fun request(to: WebExtension.Port, message: JSONObject): JSONObject =
        suspendCancellableCoroutine { cont ->
            val id = nextId++
            replies[id] = cont
            cont.invokeOnCancellation { replies.remove(id) }
            try {
                to.postMessage(message.put("id", id))
            } catch (e: Exception) {
                replies.remove(id)
                if (cont.isActive) cont.resume(JSONObject().put("type", "error"))
            }
        }

    private fun onReply(message: Any) {
        val json = message as? JSONObject ?: (message as? String)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        if (!json.has("id")) return
        val waiter = replies.remove(json.optInt("id", -1)) ?: return
        if (waiter.isActive) waiter.resume(json)
    }
}

internal const val CLEAR_EXIT_ID = "clearexit@orbis.shibi.com"
internal const val CLEAR_EXIT_ASSET_URL = "resource://android/assets/extensions/clearexit/"
internal const val CLEAR_EXIT_NATIVE_APP = "orbisClearExit"

/** The built-in Force dark extension (assets/extensions/darken). */
internal const val DARKEN_ID = "darken@orbis.shibi.com"
internal const val DARKEN_ASSET_URL = "resource://android/assets/extensions/darken/"

/** What a Gecko block event counts as on the dashboard; null for one that is not counted (safe browsing, unknown). */
internal fun blockKindOf(antiTracking: Int, cookieBehavior: Int): com.shibi.orbis.stats.BlockKind? {
    return when {
        antiTracking and ContentBlocking.AntiTracking.AD != 0 -> com.shibi.orbis.stats.BlockKind.AD
        antiTracking and ContentBlocking.AntiTracking.FINGERPRINTING != 0 -> com.shibi.orbis.stats.BlockKind.FINGERPRINTER
        antiTracking and ContentBlocking.AntiTracking.CRYPTOMINING != 0 -> com.shibi.orbis.stats.BlockKind.CRYPTOMINER
        antiTracking and ContentBlocking.AntiTracking.SOCIAL != 0 || antiTracking and ContentBlocking.AntiTracking.STP != 0 -> com.shibi.orbis.stats.BlockKind.SOCIAL
        antiTracking and (ContentBlocking.AntiTracking.ANALYTIC or ContentBlocking.AntiTracking.CONTENT or ContentBlocking.AntiTracking.EMAIL or ContentBlocking.AntiTracking.TEST) != 0 -> com.shibi.orbis.stats.BlockKind.TRACKER
        cookieBehavior != ContentBlocking.CookieBehavior.ACCEPT_ALL -> com.shibi.orbis.stats.BlockKind.COOKIE
        else -> null
    }
}
