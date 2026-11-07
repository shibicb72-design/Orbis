package com.shibi.orbis

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.shibi.orbis.downloads.DownloadNotifications
import com.shibi.orbis.engine.externalUrlFrom
import com.shibi.orbis.privacy.PrivacyWindowController
import com.shibi.orbis.privacy.PrivacyWindowOps
import com.shibi.orbis.ui.OrbisRoot
import com.shibi.orbis.ui.theme.OrbisTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as OrbisApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Follows whether any private tab is open (the tab switcher lists them even with a normal tab selected) for the
        // whole life of the activity, not only while started, so the window is already right when the system takes its
        // Recents snapshot.
        lifecycleScope.launch {
            app.container.tabs.tabs.map { list -> list.any { it.isPrivate } }.distinctUntilChanged().collect(::applyPrivacyWindow)
        }
        // A recreated activity (savedInstanceState != null) or one resumed from Recents still carries the intent
        // that started it; reading it again would reopen a link the user already dealt with.
        if (savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
            takeViewIntent(intent)
        }
        setContent {
            OrbisTheme {
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

    private fun applyPrivacyWindow(anyPrivateTabOpen: Boolean) {
        privacyWindow.apply(Build.VERSION.SDK_INT, anyPrivateTabOpen)
    }

    /**
     * Only ACTION_VIEW with an http(s) data string is accepted; extras are never read. The intent is replaced afterwards
     * so a later recreate cannot see it again. The URL is not loaded here: it waits in the container until the browser
     * screen exists.
     */
    private fun takeViewIntent(incoming: Intent) {
        externalUrlFrom(incoming.action, incoming.dataString)?.let { app.container.pendingExternalUrl.value = it }
        // A download notification tap: only a flag, no extras or data are read, so it exposes nothing to a caller.
        if (incoming.action == DownloadNotifications.ACTION_SHOW_DOWNLOADS) app.container.pendingShowDownloads.value = true
        setIntent(Intent())
    }
}
