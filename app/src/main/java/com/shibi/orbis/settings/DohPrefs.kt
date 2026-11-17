package com.shibi.orbis.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The providers offered in Settings > Secure DNS. [url] is the DNS-over-HTTPS address; CUSTOM has none of its own. */
enum class DohProvider(val url: String?) {
    QUAD9("https://dns.quad9.net/dns-query"),
    CLOUDFLARE("https://cloudflare-dns.com/dns-query"),
    MULLVAD("https://dns.mullvad.net/dns-query"),
    CUSTOM(null),
}

/** What the user chose. Off by default; [resolverUrl] is the one place that turns it into an address for the engine. */
data class DohConfig(
    val enabled: Boolean = false,
    val provider: DohProvider = DohProvider.QUAD9,
    val customUrl: String? = null,
) {
    /** The address lookups go to, or null when secure DNS is off (or Custom has no valid saved address). */
    val resolverUrl: String?
        get() =
            when {
                !enabled -> null
                provider == DohProvider.CUSTOM -> customUrl?.let(::validDohAddress)
                else -> provider.url
            }
}

const val DOH_ADDRESS_MAX_LENGTH = 300

/** The address trimmed, or null when it is not an https address with a host or is longer than 300 characters. */
fun validDohAddress(input: String): String? {
    val text = input.trim()
    if (text.length > DOH_ADDRESS_MAX_LENGTH) return null
    if (!text.startsWith("https://", ignoreCase = true)) return null
    val uri = runCatching { URI(text) }.getOrNull() ?: return null
    // A name and password in the address would be stored in clear text and sent to the provider.
    if (uri.userInfo != null) return null
    return if (uri.host.isNullOrBlank()) null else text
}

interface DohPrefs {
    var enabled: Boolean
    var provider: DohProvider
    var customUrl: String?
}

class SharedPreferencesDohPrefs(private val prefs: SharedPreferences) : DohPrefs {
    override var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    override var provider: DohProvider
        get() = DohProvider.entries.firstOrNull { it.name == prefs.getString(KEY_PROVIDER, null) } ?: DohProvider.QUAD9
        set(value) = prefs.edit { putString(KEY_PROVIDER, value.name) }

    override var customUrl: String?
        get() = prefs.getString(KEY_CUSTOM, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_CUSTOM) else putString(KEY_CUSTOM, value) }

    companion object {
        const val FILE_NAME = "doh_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_CUSTOM = "custom_url"
    }
}

/** Settings > Secure DNS. The engine follows [config]. */
class DohSettings(private val prefs: DohPrefs) {
    private val _config = MutableStateFlow(read())
    val config: StateFlow<DohConfig> = _config.asStateFlow()

    private fun read() = DohConfig(prefs.enabled, prefs.provider, prefs.customUrl)

    fun setEnabled(on: Boolean) {
        prefs.enabled = on
        _config.value = read()
    }

    fun setProvider(provider: DohProvider) {
        prefs.provider = provider
        _config.value = read()
    }

    /** Saves a custom address. Returns false, and saves nothing, when it is not a valid https address. */
    fun setCustomUrl(input: String): Boolean {
        val valid = validDohAddress(input) ?: return false
        prefs.customUrl = valid
        _config.value = read()
        return true
    }
}
