package com.shibi.orbis.engine

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build

/** Schemes Gecko loads itself (or refuses itself): never handed to another app. */
private val ENGINE_SCHEMES =
    setOf("http", "https", "about", "data", "blob", "javascript", "file", "content", "resource", "moz-extension", "view-source", "chrome", "ws", "wss")

/** The scheme of [uri], lower-cased, or null when it has none. */
internal fun schemeOf(uri: String): String? {
    val i = uri.indexOf(':')
    if (i <= 0) return null
    val s = uri.substring(0, i)
    return if (s.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' } && s.first().isLetter()) s.lowercase() else null
}

/** Whether a top-level load of [uri] is for another app (tel:, mailto:, intent:, market:, an app's own scheme). */
internal fun isExternalScheme(uri: String): Boolean {
    val s = schemeOf(uri) ?: return false
    return s !in ENGINE_SCHEMES
}

/** An app that can open a link. [label] is null when the system would ask the user to pick one. */
internal data class AppTarget(val intent: Intent, val label: String?, val fallbackUrl: String?, val resolvable: Boolean)

/**
 * Finds the app for a link and starts it. intent: links are cleaned the way Chrome does: no explicit component or
 * selector (a page must not start a private activity of some app), BROWSABLE category, no permission grant flags.
 */
internal class ExternalApps(private val context: Context) {
    private val pm: PackageManager get() = context.packageManager

    fun forExternalScheme(uri: String): AppTarget? {
        val scheme = schemeOf(uri) ?: return null
        val intent: Intent
        var fallback: String? = null
        if (scheme == "intent") {
            intent = runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return null
            fallback = intent.getStringExtra("browser_fallback_url")?.takeIf { isWebUrl(it) }
            intent.component = null
            intent.selector = null
            intent.flags = 0
            intent.replaceExtras(null as android.os.Bundle?)
            // A page cannot send an intent back into Orbis itself.
            if (intent.`package` == context.packageName) return null
        } else {
            intent = Intent(Intent.ACTION_VIEW, uri.toUri())
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        val resolved = runCatching { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) }.getOrNull()
        val own = resolved?.activityInfo?.packageName == context.packageName
        return AppTarget(intent, labelOf(resolved), fallback, resolvable = resolved != null && !own)
    }

    /** A web link that an installed app (not a browser, not Orbis) opens, or null. */
    fun forWebLink(url: String): AppTarget? {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        val handlers = runCatching { pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY) }.getOrNull().orEmpty()
        if (handlers.isEmpty()) return null
        val browsers =
            runCatching {
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_VIEW, "https://orbis-browser-check.invalid/".toUri()).addCategory(Intent.CATEGORY_BROWSABLE),
                    PackageManager.MATCH_DEFAULT_ONLY,
                )
            }.getOrNull().orEmpty().map { it.activityInfo.packageName }.toSet()
        val apps = handlers.filter { it.activityInfo.packageName != context.packageName && it.activityInfo.packageName !in browsers }
        if (apps.isEmpty()) return null
        val chosen = apps.distinctBy { it.activityInfo.packageName }
        if (chosen.size == 1) intent.setPackage(chosen[0].activityInfo.packageName)
        return AppTarget(intent, if (chosen.size == 1) labelOf(chosen[0]) else null, null, resolvable = true)
    }

    /** Starts the app. False when none could (no handler, or the app refused). */
    fun launch(target: AppTarget): Boolean {
        val intent = Intent(target.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && target.intent.scheme in setOf("http", "https")) {
            // Never bounce a web link to another browser: if no app takes it, it stays here.
            intent.addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun labelOf(info: android.content.pm.ResolveInfo?): String? {
        val pkg = info?.activityInfo?.packageName ?: return null
        // The system chooser ("android") is not an app name.
        if (pkg == "android") return null
        return runCatching { info.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
