package com.shibi.orbis.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import com.shibi.orbis.debug.DebugLog
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import org.mozilla.geckoview.WebRequestError

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

    private val extensionHost =
        GeckoExtensionHost(debugLog) {
            warmUp()
            requireRuntime()
        }

    override val extensions: ExtensionHost get() = extensionHost

    override fun warmUp() {
        synchronized(this) {
            if (runtime != null) return
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
            val settings =
                GeckoRuntimeSettings.Builder()
                    .aboutConfigEnabled(false)
                    .contentBlocking(contentBlocking)
                    .build()
            val created = GeckoRuntime.create(appContext, settings)
            // Without a prompt delegate, WebExtensionController.install() hangs forever.
            created.webExtensionController.setPromptDelegate(extensionHost.promptDelegate)
            runtime = created
            debugLog.log(
                "GeckoEngine",
                "runtime created; ETP level=STRICT category=STRICT antiTracking=STRICT cookies=isolate-others",
            )
        }
    }

    override fun createSession(): BrowserSession {
        warmUp()
        val sessionSettings =
            GeckoSessionSettings.Builder()
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                // Defaults to false: without this ETP does nothing on the session.
                .useTrackingProtection(true)
                .build()
        val session = GeckoBrowserSession(requireRuntime(), GeckoSession(sessionSettings), debugLog)
        session.open()
        debugLog.log("GeckoEngine", "session opened; useTrackingProtection=true")
        return session
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

private fun isWebUrl(url: String): Boolean =
    url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

private class GeckoBrowserSession(
    private val runtime: GeckoRuntime,
    private val session: GeckoSession,
    private val debugLog: DebugLog,
) : BrowserSession {
    private val _state = MutableStateFlow(SessionState())
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    init {
        // Delegates must be set before open().
        session.navigationDelegate = navigationDelegate()
        session.progressDelegate = progressDelegate()
        session.contentDelegate = contentDelegate()
    }

    fun open() {
        session.open(runtime)
    }

    private fun navigationDelegate() =
        object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                // The only writer of state.url. A load that is pending, halted or failed leaves the OLD page
                // displayed, so showing its address would let a hostile page spoof the address bar.
                if (url != null) _state.update { it.copy(url = url) }
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
                // Single-session shell: target=_blank / window.open links load here instead of failing.
                // Only with a user gesture, so a page cannot replace itself through a gesture-less window.open.
                if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                    if (request.hasUserGesture && isWebUrl(request.uri)) {
                        session.loadUri(request.uri)
                    }
                    return GeckoResult.deny()
                }
                return null
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
                _state.update {
                    it.copy(
                        // url is deliberately untouched: the old page is still displayed.
                        failedUrl = uri?.takeIf { failed -> isWebUrl(failed) },
                        loadFailed = true,
                        isLoading = false,
                        progress = 0,
                    )
                }
                // Null halts the load; the shell shows its own banner.
                return null
            }
        }

    private fun progressDelegate() =
        object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                _state.update {
                    // url is not written here (see onLocationChange).
                    it.copy(isLoading = true, progress = 0, loadFailed = false, failedUrl = null, crashed = false)
                }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                _state.update { it.copy(isLoading = false, progress = if (success) 100 else 0) }
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                _state.update { it.copy(progress = progress) }
            }
        }

    private fun contentDelegate() =
        object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                _state.update { it.copy(title = title) }
            }

            override fun onCrash(session: GeckoSession) = onContentProcessGone("crash")

            override fun onKill(session: GeckoSession) = onContentProcessGone("kill")
        }

    private fun onContentProcessGone(kind: String) {
        debugLog.log("GeckoSession", "content process $kind; reopening session without reload")
        // The session is closed after a crash/kill; reopen it but do not reload (avoids crash loops).
        runCatching { session.open(runtime) }
            .onFailure { debugLog.log("GeckoSession", "reopen after $kind failed: ${it.message}") }
        _state.update {
            it.copy(
                crashed = true,
                isLoading = false,
                progress = 0,
                canGoBack = false,
                canGoForward = false,
            )
        }
    }

    override fun attachTo(contentView: View) {
        val geckoView = contentView as? GeckoView
            ?: error("contentView must be created by BrowserEngine.createContentView")
        geckoView.setSession(session)
    }

    override fun detachFrom(contentView: View) {
        val geckoView = contentView as? GeckoView ?: return
        if (geckoView.session === session) geckoView.releaseSession()
    }

    override fun loadUrl(url: String) {
        // Defense in depth: the address bar already normalizes to http(s) or a search URL.
        if (!isWebUrl(url)) {
            debugLog.log("GeckoSession", "refused non-web load")
            return
        }
        session.loadUri(url)
        // No URL or search term in the persisted log: it would record browsing history.
        debugLog.log("GeckoSession", "load requested")
    }

    override fun goBack(): Boolean {
        if (!_state.value.canGoBack) return false
        session.goBack()
        return true
    }

    override fun goForward(): Boolean {
        if (!_state.value.canGoForward) return false
        session.goForward()
        return true
    }

    override fun reload() {
        val current = _state.value
        // After a crash there is no page to reload; load the last committed url. After a halted load,
        // retry the url that failed. Otherwise a plain reload.
        val target =
            when {
                current.crashed && current.url.isNotBlank() -> current.url
                current.loadFailed -> current.failedUrl
                else -> null
            }
        _state.update { it.copy(crashed = false, loadFailed = false, failedUrl = null) }
        if (target != null) session.loadUri(target) else session.reload()
    }

    override fun stop() {
        session.stop()
    }

    override fun close() {
        runCatching { session.close() }
            .onFailure { debugLog.log("GeckoSession", "close failed: ${it.message}") }
    }
}

private class GeckoExtensionHost(
    private val debugLog: DebugLog,
    private val runtimeProvider: () -> GeckoRuntime,
) : ExtensionHost {
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Id of the install currently in flight (main thread). The prompt only grants for this id. */
    @Volatile
    private var installingId: String? = null

    /** True when the prompt for this install was denied because the extension id did not match. */
    @Volatile
    private var deniedForeignId = false

    val promptDelegate =
        object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>,
            ): GeckoResult<WebExtension.PermissionPromptResponse> {
                val granted = installingId != null && extension.id == installingId
                if (granted) {
                    debugLog.log(
                        "Extensions",
                        "install prompt granted for ${extension.id}: " +
                            "${permissions.size} permissions, ${origins.size} origins, " +
                            "${dataCollectionPermissions.size} data permissions (not granted)",
                    )
                } else {
                    deniedForeignId = true
                    debugLog.log("Extensions", "install prompt denied for unexpected extension ${extension.id}")
                }
                // Private mode and data collection stay off (v1 has no private mode).
                return GeckoResult.fromValue(WebExtension.PermissionPromptResponse(granted, false, false))
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
        )

    override suspend fun find(id: String): InstalledExtension? =
        withContext(Dispatchers.Main.immediate) { findGecko(id)?.toInstalled() }

    override suspend fun install(xpiUrl: String, expectedId: String): InstalledExtension =
        withContext(Dispatchers.Main.immediate) {
            if (installingId != null) {
                throw ExtensionInstallException(ExtensionInstallError.UNKNOWN, "an install is already in flight")
            }
            installingId = expectedId
            deniedForeignId = false
            try {
                val result: WebExtension? =
                    try {
                        controller()
                            .install(xpiUrl, WebExtensionController.INSTALLATION_METHOD_ONBOARDING)
                            .awaitResult()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: WebExtension.InstallException) {
                        var error = mapInstallError(e.code)
                        if (error == ExtensionInstallError.CANCELLED && deniedForeignId) {
                            error = ExtensionInstallError.WRONG_EXTENSION
                        }
                        debugLog.log("Extensions", "install failed: code=${e.code} -> $error")
                        throw ExtensionInstallException(error, "install failed, code ${e.code}")
                    } catch (e: Exception) {
                        debugLog.log("Extensions", "install failed: ${e.javaClass.simpleName}")
                        throw ExtensionInstallException(ExtensionInstallError.UNKNOWN, e.message)
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
                debugLog.log("Extensions", "installed $expectedId ${installed.metaData.version}")
                installed.toInstalled()
            } finally {
                installingId = null
            }
        }

    private suspend fun removeQuietly(extension: WebExtension) {
        try {
            controller().uninstall(extension).awaitResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            debugLog.log("Extensions", "cleanup uninstall of ${extension.id} failed: ${e.message}")
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
            updated.toInstalled()
        }

    override suspend fun uninstall(id: String) {
        withContext(Dispatchers.Main.immediate) {
            val extension = findGecko(id) ?: return@withContext
            controller().uninstall(extension).awaitResult()
            debugLog.log("Extensions", "uninstalled $id")
        }
    }

    override suspend fun checkForUpdate(id: String): InstalledExtension? =
        withContext(Dispatchers.Main.immediate) {
            val extension = findGecko(id) ?: return@withContext null
            controller().update(extension).awaitResult()?.toInstalled()
        }

    private fun mapInstallError(code: Int): ExtensionInstallError =
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
}
