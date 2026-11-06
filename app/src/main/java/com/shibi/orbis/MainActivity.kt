package com.shibi.orbis

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.shibi.orbis.engine.externalUrlFrom
import com.shibi.orbis.ui.OrbisRoot
import com.shibi.orbis.ui.theme.OrbisTheme

class MainActivity : ComponentActivity() {
    private val app get() = application as OrbisApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
    }

    override fun onStop() {
        // Invisible: the selected page must not keep running at foreground priority.
        app.container.tabs.setForeground(false)
        // The process can be killed any time after this; make sure the tab list is on its way to disk.
        app.container.tabs.flush()
        super.onStop()
    }

    /**
     * Only ACTION_VIEW with an http(s) data string is accepted; extras are never read. The intent is replaced afterwards
     * so a later recreate cannot see it again. The URL is not loaded here: it waits in the container until the browser
     * screen exists.
     */
    private fun takeViewIntent(incoming: Intent) {
        externalUrlFrom(incoming.action, incoming.dataString)?.let { app.container.pendingExternalUrl.value = it }
        setIntent(Intent())
    }
}
