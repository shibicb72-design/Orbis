package com.shibi.orbis

import android.content.Intent
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
import androidx.lifecycle.lifecycleScope
import com.shibi.orbis.downloads.DownloadNotifications
import com.shibi.orbis.engine.ImportResult
import com.shibi.orbis.engine.externalUrlFrom
import com.shibi.orbis.engine.importedMime
import com.shibi.orbis.engine.importSharedDocument
import com.shibi.orbis.engine.sharedDocumentRequest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.shibi.orbis.privacy.PrivacyWindowController
import com.shibi.orbis.privacy.PrivacyWindowOps
import com.shibi.orbis.ui.OrbisRoot
import com.shibi.orbis.media.pipAspectRatio
import com.shibi.orbis.media.pipShapeOf
import com.shibi.orbis.ui.BackKeyWindowCallback
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
        // Picture-in-picture: a fullscreen video that is playing shrinks into a window when the user leaves (Home).
        lifecycleScope.launch {
            app.container.media.current
                .map { pipShapeOf(it, app.container.tabs.selected.value.session?.state?.value?.fullScreen == true) }
                .distinctUntilChanged()
                .collect { shape ->
                    pipShape = shape
                    applyPipParams()
                }
        }
        // A recreated activity (savedInstanceState != null) or one resumed from Recents still carries the intent
        // that started it; reading it again would reopen a link the user already dealt with.
        if (savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
            takeViewIntent(intent)
        }
        setContent {
            val theme by app.container.browserSettings.appTheme.collectAsStateWithLifecycle()
            OrbisTheme(theme) {
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
        app.container.tabs.setForeground(true)
        app.downloadForeground?.onAppForeground()
    }

    /** Width:height of the video that may go picture-in-picture now, or null. */
    private var pipShape: Pair<Long, Long>? = null

    private fun pipParams(): PictureInPictureParams? {
        val (w, h) = pipShape ?: return null
        // The page fills the window in fullscreen, so the whole window is where the video comes from.
        val source = window.decorView.let { Rect(0, 0, it.width, it.height) }
        return PictureInPictureParams.Builder().setAspectRatio(pipAspectRatio(w, h)).setSourceRectHint(source).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(true)
        }.build()
    }

    private fun applyPipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: entering is automatic while a shape is set; clearing it turns auto-enter off.
            runCatching {
                setPictureInPictureParams(pipParams() ?: PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Before Android 12 the app enters picture-in-picture itself when the user presses Home.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) pipParams()?.let { runCatching { enterPictureInPictureMode(it) } }
    }

    override fun onStop() {
        // Invisible: the selected page must not keep running at foreground priority.
        app.container.tabs.setForeground(false)
        app.downloadForeground?.onAppBackground()
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
     * (content: or file:) or ACTION_SEND (EXTRA_STREAM) with a matching MIME type. The bytes are copied while the
     * temporary read grant is still held; the page is not loaded here. The intent is replaced afterwards so a later
     * recreate cannot see it again.
     */
    private fun takeViewIntent(incoming: Intent) {
        externalUrlFrom(incoming.action, incoming.dataString)?.let { app.container.pendingExternalUrl.value = it }
        takeSharedDocument(incoming)
        // A download notification tap: only a flag, no extras or data are read, so it exposes nothing to a caller.
        if (incoming.action == DownloadNotifications.ACTION_SHOW_DOWNLOADS) app.container.pendingShowDownloads.value = true
        // The search widget: also only a flag.
        if (incoming.action == com.shibi.orbis.widget.ACTION_WIDGET_SEARCH) app.container.pendingWidgetSearch.value = true
        setIntent(Intent())
    }

    /**
     * Copies a shared PDF or HTML file, then asks the browser screen to open the private copy. The URI is captured
     * before [setIntent] clears the activity intent; the grant belongs to this process until we finish.
     */
    private fun takeSharedDocument(incoming: Intent) {
        val stream = if (incoming.action == Intent.ACTION_SEND) sendStreamUri(incoming)?.toString() else null
        val request = sharedDocumentRequest(incoming.action, incoming.dataString, incoming.type, stream)
        if (request == null) {
            // The share sheet can offer us a type we claim with no stream (a link shared as text/html). Say so.
            if (incoming.action == Intent.ACTION_SEND && importedMime(incoming.type) != null) {
                Toast.makeText(applicationContext, R.string.import_open_failed, Toast.LENGTH_LONG).show()
            }
            return
        }
        val uri = Uri.parse(request.uri)
        val roots = privateRoots()
        // The activity that received the grant. The copy runs on the process scope so a rotation does not cancel it.
        val resolver = contentResolver
        app.appScope.launch {
            val result = withContext(Dispatchers.IO) {
                importSharedDocument(app.container.imports, resolver, uri, request.mime, roots)
            }
            when (result) {
                is ImportResult.Ready -> {
                    app.container.debugLog.log("Import", "copied")
                    app.container.pendingImportedFile.value = result.url
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

    /** Directories a crafted file: intent must not be able to read back. */
    private fun privateRoots(): List<File> {
        val roots = ArrayList<File>(2)
        applicationInfo.dataDir?.let { roots += File(it) }
        getExternalFilesDir(null)?.parentFile?.let { roots += it }
        return roots
    }

    private fun sendStreamUri(intent: Intent): Uri? {
        val extra =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        if (extra != null) return extra
        val clip = intent.clipData ?: return null
        if (clip.itemCount < 1) return null
        return clip.getItemAt(0).uri
    }
}
