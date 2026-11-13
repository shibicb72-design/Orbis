package com.shibi.orbis

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.app.PictureInPictureParams
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.shibi.orbis.downloads.DownloadNotifications
import com.shibi.orbis.engine.ImportResult
import com.shibi.orbis.engine.ProviderDeadline
import com.shibi.orbis.engine.externalUrlFrom
import com.shibi.orbis.engine.importSharedDocument
import com.shibi.orbis.engine.importedMime
import com.shibi.orbis.engine.mimeForSharedDocument
import com.shibi.orbis.engine.providerCall
import com.shibi.orbis.engine.schemeOf
import com.shibi.orbis.engine.sharedDocumentRequest
import com.shibi.orbis.engine.streamUriFrom
import com.shibi.orbis.torrent.IntakeLog
import com.shibi.orbis.torrent.TorrentIntake
import com.shibi.orbis.torrent.TorrentLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.shibi.orbis.privacy.PrivacyWindowController
import com.shibi.orbis.privacy.PrivacyWindowOps
import com.shibi.orbis.ui.OrbisRoot
import com.shibi.orbis.engine.BrowserSession
import com.shibi.orbis.media.PipWindowExit
import com.shibi.orbis.media.onPipWindowClosed
import com.shibi.orbis.media.pipAspectRatio
import com.shibi.orbis.media.pipShapes
import com.shibi.orbis.ui.BackKeyWindowCallback
import com.shibi.orbis.ui.theme.AppThemes
import com.shibi.orbis.ui.theme.OrbisTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    private val app get() = application as OrbisApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Back reaches the app before any view (GeckoView swallowed it once a page was touched); see routeBackKey.
        window.callback = BackKeyWindowCallback(window.callback) { onBackPressedDispatcher.onBackPressed() }
        enableEdgeToEdge()
        // Follows whether any private tab is open (the tab switcher lists them even with a normal tab selected) for the
        // whole life of the activity, not only while started, so the window is already right when the system takes its
        // Recents snapshot.
        lifecycleScope.launch {
            combine(
                app.container.tabs.tabs.map { list -> list.any { it.isPrivate } },
                app.container.sensitiveScreens.map { it > 0 },
            ) { anyPrivate, sensitive -> anyPrivate to sensitive }
                .distinctUntilChanged()
                .collect { (anyPrivate, sensitive) -> applyPrivacyWindow(anyPrivate, sensitive) }
        }
        // Picture-in-picture: a fullscreen page playing media shrinks into a window when the user leaves (Home).
        lifecycleScope.launch {
            pipShapes(app.container.media.current, app.container.tabs.selected.map { it.session }.distinctUntilChanged())
                .collect { shape ->
                    pipShape = shape
                    applyPipParams()
                }
        }
        // Fullscreen can rotate and resize the window after the shape was set; the source hint follows the new size.
        window.decorView.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            val resized = r - l != oldR - oldL || b - t != oldB - oldT
            if (resized && pipShape != null && !isInPictureInPictureMode) applyPipParams()
        }
        // A recreated activity (savedInstanceState != null) or one resumed from Recents still carries the intent
        // that started it; reading it again would reopen a link the user already dealt with.
        if (savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
            takeViewIntent(intent)
        }
        setContent {
            val settings = app.container.browserSettings
            val darkMode by settings.darkMode.collectAsStateWithLifecycle()
            val themeId by settings.themeId.collectAsStateWithLifecycle()
            val staticIds by settings.staticThemeIds.collectAsStateWithLifecycle()
            val theme = AppThemes.byId(themeId)
            OrbisTheme(darkMode, theme, animate = theme.animatesWith(staticIds)) {
                OrbisRoot(container = app.container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeViewIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Also set here, so a missed mode callback cannot leave the toolbar hidden.
        app.container.pictureInPicture.value = isInPictureInPictureMode
        app.startedActivities.onStart()
        // No-op once loaded; retries a load that failed (e.g. Keystore briefly unavailable).
        app.container.logins.load()
    }

    override fun onResume() {
        super.onResume()
        pipExit.onResume()
        app.container.pictureInPicture.value = isInPictureInPictureMode
        app.container.sensitiveClip.foreground = hasWindowFocus()
        clearSensitiveClipIfDue()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android lets only the focused app read the clipboard, so focus, more than resume, is when the check can see it.
        app.container.sensitiveClip.foreground = hasFocus
        if (hasFocus) clearSensitiveClipIfDue()
    }

    override fun onPause() {
        app.container.sensitiveClip.foreground = false
        super.onPause()
    }

    private fun clearSensitiveClipIfDue() {
        getSystemService(android.content.ClipboardManager::class.java)?.let { app.container.sensitiveClip.clearIfDue(it) }
    }

    /** The user closed the picture-in-picture window: that ends playback, it does not move it to the background. */
    /** The tab whose media played in the picture-in-picture window; set when the window opens. */
    private var pipSession: BrowserSession? = null

    private val pipExit =
        PipWindowExit {
            onPipWindowClosed(
                noneStarted = app.startedActivities.noneStarted,
                pausePipMedia = { pipSession?.mediaPause() },
                sleepSelectedTab = { app.container.tabs.setForeground(false, keepPlayingMedia = false) },
            )
            pipSession = null
        }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        app.container.pictureInPicture.value = isInPictureInPictureMode
        if (isInPictureInPictureMode) pipSession = app.container.media.current.value?.session
        val state = lifecycle.currentState
        val activity =
            when {
                state.isAtLeast(Lifecycle.State.RESUMED) -> PipWindowExit.Activity.RESUMED
                state.isAtLeast(Lifecycle.State.STARTED) -> PipWindowExit.Activity.STARTED
                else -> PipWindowExit.Activity.STOPPED
            }
        pipExit.onPipModeChanged(isInPictureInPictureMode, activity)
    }

    /** Width:height of the video that may go picture-in-picture now, or null. */
    private var pipShape: Pair<Long, Long>? = null

    private fun pipParams(): PictureInPictureParams? {
        val (w, h) = pipShape ?: return null
        // The page fills the window in fullscreen, so the whole window is where the video comes from.
        val source = window.decorView.let { Rect(0, 0, it.width, it.height) }
        return PictureInPictureParams.Builder().setAspectRatio(pipAspectRatio(w, h)).setSourceRectHint(source)
            .setAutoEnterEnabled(true)
            .build()
    }

    private fun applyPipParams() {
        // Entering is automatic while a shape is set; clearing it turns auto-enter off.
        runCatching {
            setPictureInPictureParams(pipParams() ?: PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
        }
    }

    override fun onStop() {
        // Invisible: the selected page must not keep running at foreground priority, unless another instance is
        // started (a link opened right after Back starts one before this one stops; see StartedActivities). First, so
        // a closed PiP window below already knows whether another instance is started.
        app.startedActivities.onStop()
        pipExit.onStop()
        // The process can be killed any time after this; make sure the tab list is on its way to disk.
        app.container.tabs.flush()
        app.container.bookmarks.flush()
        app.container.history.flush()
        super.onStop()
    }

    private val privacyWindow =
        PrivacyWindowController(
            object : PrivacyWindowOps {
                override fun setRecentsScreenshot(enabled: Boolean) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(enabled)
                }

                override fun setFlagSecure(secure: Boolean) {
                    if (secure) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            },
        )

    private fun applyPrivacyWindow(anyPrivateTabOpen: Boolean, sensitiveScreenOpen: Boolean) {
        privacyWindow.apply(Build.VERSION.SDK_INT, anyPrivateTabOpen, sensitiveScreenOpen)
    }

    /**
     * Web links are http(s) ACTION_VIEW only; their extras are never read. A PDF or HTML document is ACTION_VIEW
     * (`content:`) or ACTION_SEND (EXTRA_STREAM) with a matching MIME type. The bytes are copied while the
     * temporary read grant is still held; the page is not loaded here. The intent is replaced afterwards so a later
     * recreate cannot see it again.
     */
    private fun takeViewIntent(incoming: Intent) {
        externalUrlFrom(incoming.action, incoming.dataString)?.let { app.container.pendingExternalUrl.value = it }
        takeSharedDocument(incoming)
        takeSharedTorrent(incoming)
        // A download notification tap: only a flag, no extras or data are read, so it exposes nothing to a caller.
        if (incoming.action == DownloadNotifications.ACTION_SHOW_DOWNLOADS) app.container.pendingShowDownloads.value = true
        // The search widget: also only a flag.
        if (incoming.action == com.shibi.orbis.widget.ACTION_WIDGET_SEARCH) app.container.pendingWidgetSearch.value = true
        if (incoming.action == com.shibi.orbis.cast.CastNotifications.ACTION_SHOW) app.container.pendingShowCast.value = true
        setIntent(Intent())
    }

    /**
     * Copies a shared PDF or HTML file, then queues the private copy for a new tab. The URI is captured before
     * [setIntent] clears the activity intent; the grant belongs to this process until the copy finishes. A second
     * share that arrives while the first is still queued is kept, not dropped.
     */
    private fun takeSharedDocument(incoming: Intent) {
        val stream = if (incoming.action == Intent.ACTION_SEND) streamUriFrom(incoming)?.toString() else null
        val explicit = incoming.type?.takeIf { it.isNotBlank() }
        val request = sharedDocumentRequest(incoming.action, incoming.dataString, explicit, stream)
        if (request != null) {
            openCopiedDocument(request.uri, request.mime)
            return
        }
        // The share sheet can offer us a type we claim with no stream (a link shared as text/html). Say so.
        if (explicit != null) {
            if (incoming.action == Intent.ACTION_SEND && importedMime(explicit) != null) {
                Toast.makeText(applicationContext, R.string.import_open_failed, Toast.LENGTH_LONG).show()
            }
            return
        }
        // A VIEW (or SEND) with no type still names a content: URI. The provider's type is the one that counts.
        val raw = when (incoming.action) {
            Intent.ACTION_VIEW -> incoming.dataString
            Intent.ACTION_SEND -> stream ?: incoming.dataString
            else -> null
        } ?: return
        if (schemeOf(raw) != "content") return
        val resolver = contentResolver
        val providerLog = providerLog()
        app.appScope.launch {
            // Null (timed out, failed or no type) means "type unknown": nothing opens.
            val queried = providerCall(log = providerLog) { resolver.getType(Uri.parse(raw)) }
            val mime = mimeForSharedDocument(null, queried) ?: return@launch
            val resolved = sharedDocumentRequest(incoming.action, incoming.dataString, mime, stream) ?: return@launch
            openCopiedDocument(resolved.uri, resolved.mime)
        }
    }

    /**
     * A .torrent from another app (VIEW `content:` or SEND EXTRA_STREAM, type application/x-bittorrent). Only the URI
     * string is kept, captured before [setIntent] clears the intent; the read grant stays with this activity, and the
     * confirm sheet reads the file. Not private.
     */
    private fun takeSharedTorrent(incoming: Intent) {
        val action = incoming.action
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) return
        val stream = if (action == Intent.ACTION_SEND) streamUriFrom(incoming)?.toString() else null
        val data = incoming.dataString
        val explicit = incoming.type?.takeIf { it.isNotBlank() }
        val pkg = packageName
        val pm = packageManager
        val resolver = contentResolver
        val log = TorrentLog.sink(app.container.debugLog)
        val providerLog = providerLog()
        app.appScope.launch {
            // A VIEW without a type names its file only: the provider's type is the one that counts. Null (timed out,
            // failed or no type) means "type unknown", which check() treats as not a torrent.
            val type = explicit ?: data?.takeIf { action == Intent.ACTION_VIEW && schemeOf(it) == "content" }
                ?.let { providerCall(log = providerLog) { resolver.getType(Uri.parse(it)) } }
            val check =
                withContext(Dispatchers.IO) {
                    // The authority as ContentResolver will read it, for the same URI check() picks.
                    val picked = if (action == Intent.ACTION_VIEW) data else stream
                    val platformAuthority = picked?.let { runCatching { Uri.parse(it).authority }.getOrNull() }
                    TorrentIntake.check(action, data, type, stream, platformAuthority, pkg, setOf("$pkg.downloads")) { authority ->
                        pm.resolveContentProvider(authority, 0)?.packageName
                    }
                }
            when (check) {
                is TorrentIntake.Check.Ok -> app.container.pendingTorrentIntake.value = check.request
                is TorrentIntake.Check.Refused -> log(IntakeLog.refused(check.reason))
                TorrentIntake.Check.NotTorrent -> Unit
            }
        }
    }

    private fun providerLog(): (String) -> Unit {
        val debugLog = app.container.debugLog
        return { debugLog.log(ProviderDeadline.LOG_TAG, it) }
    }

    private fun openCopiedDocument(uriString: String, mime: String) {
        val uri = Uri.parse(uriString)
        val pkg = packageName
        val pm = packageManager
        // The activity that received the grant. The copy runs on the process scope so a rotation does not cancel it.
        val resolver = contentResolver
        app.appScope.launch {
            val result = withContext(Dispatchers.IO) {
                importSharedDocument(app.container.imports, resolver, uri, mime, pkg) { authority ->
                    pm.resolveContentProvider(authority, 0)?.packageName
                }
            }
            when (result) {
                is ImportResult.Ready -> {
                    app.container.debugLog.log("Import", "copied")
                    app.container.pendingImports.add(result.url)
                }
                ImportResult.TooLarge -> {
                    app.container.debugLog.log("Import", "too large")
                    Toast.makeText(applicationContext, R.string.import_too_large, Toast.LENGTH_LONG).show()
                }
                ImportResult.Failed -> {
                    app.container.debugLog.log("Import", "failed")
                    Toast.makeText(applicationContext, R.string.import_open_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
