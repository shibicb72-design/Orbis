package com.shibi.orbis.engine

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.net.toUri

/** Schemes Gecko loads itself (or refuses itself): never handed to another app. */
private val ENGINE_SCHEMES =
    setOf(
        "http",
        "https",
        "about",
        "data",
        "blob",
        "javascript",
        "file",
        "content",
        "resource",
        "moz-extension",
        "view-source",
        "chrome",
        "ws",
        "wss",
    )

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

/** How long after a tapped navigation its redirects may still end in another app (tracking links, store links). */
internal const val EXTERNAL_REDIRECT_GRACE_MS = 5_000L

/**
 * Whether a page may ask to open another app. A tap is required: either this request carries the gesture, or it is a
 * redirect of a navigation the user tapped within [EXTERNAL_REDIRECT_GRACE_MS]. A redirect alone is not enough, or a
 * page could script `location = "/302-to-tel:"` and raise the question with no tap at all.
 * [msSinceGestureNavigation] is null when this session has never seen a tapped navigation.
 */
internal fun externalLaunchAllowed(hasUserGesture: Boolean, isRedirect: Boolean, msSinceGestureNavigation: Long?): Boolean =
    hasUserGesture ||
        (isRedirect && msSinceGestureNavigation != null && msSinceGestureNavigation in 0..EXTERNAL_REDIRECT_GRACE_MS)

/** Data schemes an intent: link may never hand to another app: local files and content providers. */
private val BLOCKED_INTENT_DATA_SCHEMES = setOf("file", "content")

/** True when an intent: link's data points at a local file or a content provider. */
internal fun intentDataBlocked(dataScheme: String?): Boolean = dataScheme?.lowercase() in BLOCKED_INTENT_DATA_SCHEMES

/**
 * Where a navigation goes. [Web.url] is set when an intent: link unwrapped to an http(s) address with no target app;
 * null means the original address is already one the engine loads itself.
 */
internal sealed class ExternalNavigation {
    data class Web(val url: String?) : ExternalNavigation()

    data class App(
        val intent: Intent?,
        val fallbackUrl: String?,
        val marketPackage: String?,
    ) : ExternalNavigation()

    data object Blocked : ExternalNavigation()
}

/**
 * A handler PackageManager can see is confirmed with a prompt. One it cannot see is still launched: on Android 11+
 * resolveActivity returns null for schemes hidden by package visibility (magnet: has no host), while startActivity
 * opens the app. [MISS] means there is nothing safe to launch, so only the fallback chain remains.
 */
internal enum class ExternalOpenChoice { ASK, LAUNCH, MISS }

internal fun externalOpenChoice(hasIntent: Boolean, resolvable: Boolean): ExternalOpenChoice =
    when {
        !hasIntent -> ExternalOpenChoice.MISS
        resolvable -> ExternalOpenChoice.ASK
        else -> ExternalOpenChoice.LAUNCH
    }

/** What to do after startActivity throws ActivityNotFoundException. The web fallback wins over the store link. */
internal sealed class ExternalMiss {
    data class OpenWeb(val url: String) : ExternalMiss()

    data class OpenMarket(val uri: String) : ExternalMiss()

    data object NoHandler : ExternalMiss()
}

internal fun planExternalMiss(fallbackUrl: String?, marketPackage: String?): ExternalMiss {
    if (fallbackUrl != null && isWebUrl(fallbackUrl)) return ExternalMiss.OpenWeb(fallbackUrl)
    val market = marketPackage?.let { marketDetailsUri(it) }
    if (market != null) return ExternalMiss.OpenMarket(market)
    return ExternalMiss.NoHandler
}

private val PACKAGE_NAME = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")

/** Play Store details link for [packageName], or null when it is not a package name. */
internal fun marketDetailsUri(packageName: String): String? =
    packageName.takeIf { PACKAGE_NAME.matches(it) }?.let { "market://details?id=$it" }

/**
 * Routes [uri]. intent: links are parsed with [Intent.URI_INTENT_SCHEME] and stripped the way Chrome does: no explicit
 * component or selector (a page must not start a private activity), no grant flags or extras, BROWSABLE category.
 * Whether an app is installed is not decided here.
 */
internal fun routeExternalNavigation(uri: String, ownPackage: String): ExternalNavigation {
    val scheme = schemeOf(uri) ?: return ExternalNavigation.Blocked
    if (scheme != "intent") {
        if (!isExternalScheme(uri)) return ExternalNavigation.Web(null)
        val intent =
            Intent(Intent.ACTION_VIEW, uri.toUri())
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addCategory(Intent.CATEGORY_DEFAULT)
        return ExternalNavigation.App(intent, fallbackUrl = null, marketPackage = null)
    }
    val intent = runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return ExternalNavigation.Blocked
    val fallback = intent.getStringExtra("browser_fallback_url")?.takeIf { isWebUrl(it) }
    // A page must not aim an intent back at Orbis, even after the component is stripped.
    if (intent.`package` == ownPackage) return ExternalNavigation.Blocked
    val marketPackage = intent.`package`?.takeIf { marketDetailsUri(it) != null }
    intent.component = null
    intent.selector = null
    intent.clipData = null
    intent.flags = 0
    intent.replaceExtras(null as Bundle?)
    if (intent.action.isNullOrBlank()) intent.action = Intent.ACTION_VIEW
    intent.addCategory(Intent.CATEGORY_BROWSABLE)
    intent.addCategory(Intent.CATEGORY_DEFAULT)
    if (intent.`package` != null && marketDetailsUri(intent.`package`!!) == null) intent.`package` = null
    val dataScheme = intent.data?.scheme?.lowercase()
    if (intentDataBlocked(dataScheme)) return ExternalNavigation.Blocked
    if (dataScheme != null && dataScheme in ENGINE_SCHEMES && dataScheme != "http" && dataScheme != "https") {
        return ExternalNavigation.Blocked
    }
    val data = intent.dataString
    if (data != null && isWebUrl(data) && intent.`package` == null) return ExternalNavigation.Web(data)
    if (intent.data == null && intent.`package`.isNullOrBlank()) {
        return if (fallback != null || marketPackage != null) {
            ExternalNavigation.App(intent = null, fallbackUrl = fallback, marketPackage = marketPackage)
        } else {
            ExternalNavigation.Blocked
        }
    }
    return ExternalNavigation.App(intent, fallback, marketPackage)
}

/** An app that can open a link. [label] is null when the system would ask the user to pick one. */
internal data class AppTarget(
    val intent: Intent?,
    val label: String?,
    val fallbackUrl: String?,
    val marketPackage: String?,
    val resolvable: Boolean,
)

/**
 * Finds the app for a link and starts it. [resolvable] is only a hint for the prompt: a false value does not mean no
 * app is installed. Android 11 hides handlers from resolveActivity unless they are declared in `<queries>`, and
 * QUERY_ALL_PACKAGES stays removed. Call [launch] and treat [ActivityNotFoundException] as the real "no app".
 */
internal class ExternalApps(private val context: Context) {
    private val pm: PackageManager get() = context.packageManager

    fun forExternalScheme(uri: String): AppTarget? {
        val app = routeExternalNavigation(uri, context.packageName) as? ExternalNavigation.App ?: return null
        return targetFor(app)
    }

    fun targetFor(app: ExternalNavigation.App): AppTarget {
        val intent = app.intent
        val resolved = intent?.let { runCatching { pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) }.getOrNull() }
        val own = resolved?.activityInfo?.packageName == context.packageName
        return AppTarget(
            intent = intent,
            label = labelOf(resolved),
            fallbackUrl = app.fallbackUrl,
            marketPackage = app.marketPackage,
            resolvable = resolved != null && !own,
        )
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
        return AppTarget(intent, if (chosen.size == 1) labelOf(chosen[0]) else null, null, null, resolvable = true)
    }

    /** Starts the app. False when none could (no handler, or the app refused). */
    fun launch(target: AppTarget): Boolean {
        val base = target.intent ?: return false
        val intent = Intent(base).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && base.scheme in setOf("http", "https")) {
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
