package com.shibi.orbis.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.shibi.orbis.debug.DebugLog
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
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
import org.mozilla.geckoview.Autocomplete
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
import org.mozilla.geckoview.WebResponse

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

    override fun createSession(init: SessionInit): BrowserSession {
        val session = buildSession(init)
        session.start(init)
        debugLog.log("GeckoEngine", "session opened; useTrackingProtection=true")
        return session
    }

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
                .build()
        return GeckoBrowserSession(
            runtime = requireRuntime(),
            appContext = appContext,
            session = GeckoSession(sessionSettings),
            initialDesktopMode = init.desktopMode,
            debugLog = debugLog,
            // A popup child inherits the opener's desktop/mobile choice and is never opened by us.
            newPopupChild = { opener -> buildSession(SessionInit(desktopMode = opener.state.value.desktopMode)) },
        )
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

private typealias Response = GeckoSession.PromptDelegate.PromptResponse
private typealias AuthFlags = GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.Flags

private class GeckoBrowserSession(
    private val runtime: GeckoRuntime,
    private val appContext: Context,
    private val session: GeckoSession,
    initialDesktopMode: Boolean,
    private val debugLog: DebugLog,
    private val newPopupChild: (GeckoBrowserSession) -> GeckoBrowserSession,
) : BrowserSession {
    private val _state = MutableStateFlow(SessionState(desktopMode = initialDesktopMode))
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    private val queue = PromptQueue()
    override val prompts: StateFlow<SitePrompt?> = queue.head

    override var host: SessionHost? = null

    private val abuseGuard = DialogAbuseGuard(now = { SystemClock.elapsedRealtime() })

    /** Set once [close] has run; every later call is ignored (composition may still touch a closed session). */
    @Volatile
    private var closed = false

    /** What the tab layer last asked for. Applied once the session is open, which a popup child is not yet. */
    private var wantActive = false

    private var latestSessionState: GeckoSession.SessionState? = null
    private var findGeneration = 0

    val geckoSession: GeckoSession get() = session

    init {
        // Delegates must be set before open().
        session.navigationDelegate = navigationDelegate()
        session.progressDelegate = progressDelegate()
        session.contentDelegate = contentDelegate()
        session.promptDelegate = promptDelegate()
        session.permissionDelegate = permissionDelegate()
    }

    /** Opens the session (never called for a popup child: Gecko opens that one) and applies [init]. */
    fun start(init: SessionInit) {
        session.open(runtime)
        val blob = init.restoredState
        var restored = false
        if (blob != null) {
            val parsed = runCatching { GeckoSession.SessionState.fromString(blob) }.getOrNull()
            if (parsed != null) {
                restored = runCatching { session.restoreState(parsed) }.isSuccess
            }
            debugLog.log("GeckoSession", if (restored) "restored from saved state" else "saved state unusable")
        }
        // The fallback is only for "no state" or "state would not parse"; it never runs on top of a restore.
        val fallback = init.fallbackUrl
        if (!restored && fallback != null && isWebUrl(fallback)) session.loadUri(fallback)
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
                // The only writer of state.url. A load that is pending, halted or failed leaves the OLD page
                // displayed, so showing its address would let a hostile page spoof the address bar.
                if (url != null) _state.update { it.copy(url = url) }
                applyActive()
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
                val child = newPopupChild(this@GeckoBrowserSession)
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
                // A new top-level page ends any dialog-spam lockout and invalidates the find highlight.
                abuseGuard.onPageStart()
                applyActive()
                _state.update {
                    // url is not written here (see onLocationChange).
                    it.copy(
                        isLoading = true,
                        progress = 0,
                        loadFailed = false,
                        failedUrl = null,
                        crashed = false,
                        findResult = null,
                    )
                }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                _state.update { it.copy(isLoading = false, progress = if (success) 100 else 0) }
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                _state.update { it.copy(progress = progress) }
            }

            override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
                // Kept as the engine object; serialised only when somebody asks (see savedState()).
                latestSessionState = sessionState
                host?.onSessionStateChanged(this@GeckoBrowserSession)
            }
        }

    private fun contentDelegate() =
        object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                _state.update { it.copy(title = title) }
            }

            override fun onCloseRequest(session: GeckoSession) {
                host?.onCloseRequest(this@GeckoBrowserSession)
            }

            override fun onContextMenu(
                session: GeckoSession,
                screenX: Int,
                screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement,
            ) {
                val link = element.linkUri ?: return
                if (isWebUrl(link)) host?.onLinkContextMenu(link, this@GeckoBrowserSession)
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

            override fun onCrash(session: GeckoSession) = onContentProcessGone("crash")

            override fun onKill(session: GeckoSession) = onContentProcessGone("kill")
        }

    private fun onContentProcessGone(kind: String) {
        if (closed) return
        debugLog.log("GeckoSession", "content process $kind; reopening session without reload")
        // Prompts belong to the dead page; answer them so no GeckoResult is left waiting on a gone process.
        queue.dismissAll()
        // The session is closed after a crash/kill; reopen it but do not reload (avoids crash loops).
        // `crashed` is per session: a background tab keeps it until it is selected, then its banner shows.
        runCatching { session.open(runtime) }
            .onFailure { debugLog.log("GeckoSession", "reopen after $kind failed: ${it.message}") }
        applyActive()
        _state.update {
            it.copy(
                crashed = true,
                isLoading = false,
                progress = 0,
                canGoBack = false,
                canGoForward = false,
                findResult = null,
            )
        }
    }

    // ---- prompts -------------------------------------------------------------------------------------------

    private fun complete(result: GeckoResult<Response>, response: Response?) {
        runCatching { result.complete(response) }
    }

    /** Gecko-side dismissal response; null if the prompt can no longer produce one. */
    private fun dismissResponse(prompt: GeckoSession.PromptDelegate.BasePrompt): Response? =
        runCatching { prompt.dismiss() }.getOrNull()

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
            ): GeckoResult<Response> =
                present(prompt) { result ->
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

            override fun onFilePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FilePrompt,
            ): GeckoResult<Response> {
                // Folder upload and camera capture are not offered; the page just sees "nothing chosen".
                if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.FOLDER) return skip("file-folder", prompt)
                val multiple = prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE
                return present(prompt) { result ->
                    SitePrompt.FilePick(
                        originHost(),
                        prompt.mimeTypes.orEmpty().filterNotNull(),
                        multiple,
                    ) { uris ->
                        complete(
                            result,
                            when {
                                uris == null -> dismissResponse(prompt)
                                multiple -> prompt.confirm(appContext, uris.toTypedArray())
                                else -> prompt.confirm(appContext, uris.first())
                            },
                        )
                    }
                }
            }

            // ---- SKIP: no UI for these; each is answered explicitly so no GeckoResult is left pending. ----

            override fun onDateTimePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.DateTimePrompt,
            ): GeckoResult<Response> = skip("datetime", prompt)

            override fun onColorPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ColorPrompt,
            ): GeckoResult<Response> = skip("color", prompt)

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
            ): GeckoResult<Response> = skip("login-save", request)

            override fun onLoginSelect(
                session: GeckoSession,
                request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.LoginSelectOption>,
            ): GeckoResult<Response> = skip("login-select", request)

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

    /**
     * Deny by default. Only inaudible autoplay is allowed; location, notifications and the rest are denied, and no
     * Android runtime permission is ever requested (the manifest declares none for them), so there is no dialog.
     */
    private fun permissionDelegate() =
        object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission,
            ): GeckoResult<Int> {
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
            ) {
                debugLog.log("GeckoSession", "android permission request rejected")
                callback.reject()
            }

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback,
            ) {
                debugLog.log("GeckoSession", "camera/microphone request rejected")
                callback.reject()
            }
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
        session.loadUri(url)
        // No URL or search term in the persisted log: it would record browsing history.
        debugLog.log("GeckoSession", "load requested")
    }

    override fun loadExternalUrl(url: String) {
        if (closed) return
        if (!isWebUrl(url)) {
            debugLog.log("GeckoSession", "refused non-web external load")
            return
        }
        session.load(
            GeckoSession.Loader()
                .uri(url)
                .flags(GeckoSession.LOAD_FLAGS_EXTERNAL),
        )
        debugLog.log("GeckoSession", "external load requested")
    }

    override fun setDesktopMode(enabled: Boolean) {
        if (closed || _state.value.desktopMode == enabled) return
        session.settings.userAgentMode =
            if (enabled) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        session.settings.viewportMode =
            if (enabled) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        _state.update { it.copy(desktopMode = enabled) }
        // Settings only take effect for the next load.
        if (session.isOpen && _state.value.url.isNotEmpty()) reload()
    }

    override fun goBack(): Boolean {
        if (closed || !_state.value.canGoBack) return false
        session.goBack()
        return true
    }

    override fun goForward(): Boolean {
        if (closed || !_state.value.canGoForward) return false
        session.goForward()
        return true
    }

    override fun reload() {
        if (closed) return
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
        if (!closed) session.stop()
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
    }

    override fun savedState(): String? = latestSessionState?.toString()

    override fun close() {
        if (closed) return
        // Answer everything first: a pending GeckoResult must not outlive its session.
        queue.dismissAll()
        closed = true
        host = null
        runCatching { session.close() }
            .onFailure { debugLog.log("GeckoSession", "close failed: ${it.message}") }
    }
}

/**
 * A WebResponse the engine will not render. The body is claimed by exactly one of openBody / discard; after that the
 * other is a no-op, so the stream is never opened twice nor left unclosed.
 */
private class GeckoIncomingDownload(private val response: WebResponse) : IncomingDownload {
    private val claimed = AtomicBoolean(false)

    private fun header(name: String): String? =
        response.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    override val url: String get() = response.uri
    override val contentDisposition: String? get() = header("content-disposition")
    override val contentType: String? get() = header("content-type")
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