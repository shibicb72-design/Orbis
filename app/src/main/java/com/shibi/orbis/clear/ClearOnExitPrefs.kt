package com.shibi.orbis.clear

import android.content.SharedPreferences
import androidx.core.content.edit

/** The most sites the exceptions list holds. */
const val CLEAR_ON_EXIT_MAX_EXCEPTIONS = 200

private val DOMAIN_LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

/**
 * [input] as an exceptions-list entry: lower case, no scheme, path, port or leading "www.", at least two labels of
 * letters, digits and hyphens. Null when it is not a domain. Without a public suffix list the user's entry is taken as
 * the registrable domain (the site); every subdomain of it is covered too.
 */
fun clearOnExitDomain(input: String): String? {
    var text = input.trim().lowercase()
    text = text.substringAfter("://")
    text = text.substringBefore('/').substringBefore('?').substringBefore('#')
    text = text.substringAfterLast('@').substringBefore(':')
    text = text.trim('.')
    if (text.startsWith("www.")) text = text.removePrefix("www.")
    if (text.length > 253) return null
    val labels = text.split('.')
    if (labels.size < 2 || labels.any { !DOMAIN_LABEL.matches(it) }) return null
    // A top-level label is never all digits: this refuses IP addresses.
    if (labels.last().all { it.isDigit() }) return null
    return text
}

/** Settings > Clear on exit. Its own file, so nothing else in Settings is touched by it. */
interface ClearOnExitPrefs {
    /** Off by default. */
    var enabled: Boolean

    /** Sites whose cookies are kept (see [clearOnExitDomain]); at most [CLEAR_ON_EXIT_MAX_EXCEPTIONS]. */
    var exceptions: Set<String>
}

/** Drops entries that are not domains and keeps the first [CLEAR_ON_EXIT_MAX_EXCEPTIONS] in sorted order. */
fun cleanExceptions(raw: Collection<String>): Set<String> =
    raw.mapNotNull(::clearOnExitDomain).toSortedSet().take(CLEAR_ON_EXIT_MAX_EXCEPTIONS).toSet()

class SharedPreferencesClearOnExitPrefs(private val prefs: SharedPreferences) : ClearOnExitPrefs {
    override var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    override var exceptions: Set<String>
        get() = cleanExceptions(prefs.getStringSet(KEY_EXCEPTIONS, null).orEmpty())
        set(value) = prefs.edit { putStringSet(KEY_EXCEPTIONS, cleanExceptions(value)) }

    companion object {
        const val FILE_NAME = "clear_exit_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_EXCEPTIONS = "exceptions"
    }
}
