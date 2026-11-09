package com.shibi.orbis.customtabs

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.browser.customtabs.CustomTabsService
import androidx.browser.customtabs.CustomTabsSessionToken
import com.shibi.orbis.OrbisApp

/**
 * Lets apps find Orbis as a Custom Tabs browser and warm it up. Orbis does not prefetch (mayLaunchUrl is accepted and
 * ignored: no request leaves before the user opens the link), does not exchange messages with the app, and does not
 * verify app/site relationships.
 */
class OrbisCustomTabsService : CustomTabsService() {
    private val main = Handler(Looper.getMainLooper())

    override fun warmup(flags: Long): Boolean {
        main.post { runCatching { (application as OrbisApp).container.engine.warmUp() } }
        return true
    }

    override fun newSession(sessionToken: CustomTabsSessionToken): Boolean = true

    override fun mayLaunchUrl(sessionToken: CustomTabsSessionToken, url: Uri?, extras: Bundle?, otherLikelyBundles: MutableList<Bundle>?): Boolean = true

    override fun extraCommand(commandName: String, args: Bundle?): Bundle? = null

    override fun updateVisuals(sessionToken: CustomTabsSessionToken, bundle: Bundle?): Boolean = false

    override fun requestPostMessageChannel(sessionToken: CustomTabsSessionToken, postMessageOrigin: Uri): Boolean = false

    override fun postMessage(sessionToken: CustomTabsSessionToken, message: String, extras: Bundle?): Int = RESULT_FAILURE_DISALLOWED

    override fun validateRelationship(sessionToken: CustomTabsSessionToken, relation: Int, origin: Uri, extras: Bundle?): Boolean = false

    override fun receiveFile(sessionToken: CustomTabsSessionToken, uri: Uri, purpose: Int, extras: Bundle?): Boolean = false

    override fun cleanUpSession(sessionToken: CustomTabsSessionToken): Boolean = true
}
