package com.shibi.orbis.customtabs

import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent

/** The extra every Custom Tabs launch carries (androidx.browser CustomTabsIntent.EXTRA_SESSION). */
internal const val EXTRA_CUSTOM_TABS_SESSION = CustomTabsIntent.EXTRA_SESSION

/** Orbis's own extras on the (non-exported) custom tab activity. Never read from a caller's intent. */
internal const val EXTRA_ORBIS_TOOLBAR_COLOR = "com.shibi.orbis.customtabs.TOOLBAR_COLOR"
internal const val EXTRA_ORBIS_SHOW_TITLE = "com.shibi.orbis.customtabs.SHOW_TITLE"

/** True when [intent] is an app asking for a Custom Tab (it carries the session extra, even a null one). */
internal fun isCustomTabRequest(intent: Intent): Boolean = runCatching { intent.hasExtra(EXTRA_CUSTOM_TABS_SESSION) }.getOrDefault(false)

/**
 * What Orbis takes from a calling app's Custom Tabs intent: the toolbar color (light scheme, ARGB) and whether to show
 * the page title. Everything else (action buttons, menu items, pending intents, animations, headers) is ignored. The
 * extras come from another app: a malformed bundle gives the defaults.
 */
internal data class CustomTabLook(val toolbarColor: Int?, val showTitle: Boolean)

internal fun customTabLookOf(intent: Intent): CustomTabLook =
    runCatching {
        val params = CustomTabsIntent.getColorSchemeParams(intent, CustomTabsIntent.COLOR_SCHEME_LIGHT)
        val title = intent.getIntExtra(CustomTabsIntent.EXTRA_TITLE_VISIBILITY_STATE, CustomTabsIntent.NO_TITLE) == CustomTabsIntent.SHOW_PAGE_TITLE
        CustomTabLook(params.toolbarColor, title)
    }.getOrDefault(CustomTabLook(null, false))

/** Black or white content for a toolbar of [argb]: whichever contrasts more (WCAG relative luminance). */
internal fun contentColorOn(argb: Int): Int {
    fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }
    val r = channel((argb shr 16) and 0xFF)
    val g = channel((argb shr 8) and 0xFF)
    val b = channel(argb and 0xFF)
    val l = 0.2126 * r + 0.7152 * g + 0.0722 * b
    // Contrast with black (l + 0.05) / 0.05 against white 1.05 / (l + 0.05).
    return if ((l + 0.05) / 0.05 >= 1.05 / (l + 0.05)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
}
