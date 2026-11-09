package com.shibi.orbis

import android.app.Activity
import androidx.core.net.toUri
import android.content.Intent
import android.os.Bundle
import com.shibi.orbis.customtabs.CustomTabActivity
import com.shibi.orbis.customtabs.EXTRA_ORBIS_SHOW_TITLE
import com.shibi.orbis.customtabs.EXTRA_ORBIS_TOOLBAR_COLOR
import com.shibi.orbis.customtabs.customTabLookOf
import com.shibi.orbis.customtabs.isCustomTabRequest
import com.shibi.orbis.engine.externalUrlFrom

/**
 * Every web link from another app arrives here (it owns the VIEW filter) and goes on, as a fresh intent built by Orbis:
 *  - an app asking for a Custom Tab gets [CustomTabActivity], in the calling app's task;
 *  - anything else opens in the browser ([MainActivity]), exactly as before.
 * Only the http(s) address (and, for a Custom Tab, its toolbar color and title flag) is passed on; nothing else from the
 * caller's intent reaches Orbis's activities.
 */
class LinkRouterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = intent
        val url = externalUrlFrom(incoming?.action, incoming?.dataString)
        if (url != null && incoming != null) {
            val next =
                if (isCustomTabRequest(incoming)) {
                    val look = customTabLookOf(incoming)
                    Intent(this, CustomTabActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        data = url.toUri()
                        look.toolbarColor?.let { putExtra(EXTRA_ORBIS_TOOLBAR_COLOR, it) }
                        putExtra(EXTRA_ORBIS_SHOW_TITLE, look.showTitle)
                        // A caller that asked for its own task gets one; otherwise the tab sits on top of the caller.
                        if ((incoming.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                } else {
                    Intent(this, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .setData(url.toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            runCatching { startActivity(next) }
        }
        finish()
    }
}
