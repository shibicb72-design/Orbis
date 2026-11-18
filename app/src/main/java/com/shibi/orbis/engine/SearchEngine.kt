package com.shibi.orbis.engine

import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The built-in search engines. [searchPrefix] is followed by the URL-encoded query; [label] is the name shown. */
enum class SearchEngine(val searchPrefix: String, val label: String) {
    DUCKDUCKGO("https://duckduckgo.com/?q=", "DuckDuckGo"),
    GOOGLE("https://www.google.com/search?q=", "Google"),
    BING("https://www.bing.com/search?q=", "Bing"),
    BRAVE("https://search.brave.com/search?q=", "Brave"),
    STARTPAGE("https://www.startpage.com/do/search?q=", "Startpage"),
    ;

    companion object {
        val DEFAULT = DUCKDUCKGO

        /** A stored [name] back to its engine; anything missing or unknown is the default. */
        fun fromName(name: String?): SearchEngine = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** The query as it goes into a search address: URLEncoder form ("a b&c" is "a+b%26c"), the same for every engine. */
internal fun encodeQuery(query: String): String = URLEncoder.encode(query, "UTF-8")

/** An engine the address bar can search with: a built-in one, or one the user added. */
sealed interface SearchProvider {
    /** What is stored as the choice: the enum name for a built-in engine, `custom:<n>` for an added one. */
    val id: String

    /** The results address for [query]. */
    fun searchUrl(query: String): String

    data class BuiltIn(val engine: SearchEngine) : SearchProvider {
        override val id: String get() = engine.name

        override fun searchUrl(query: String): String = engine.searchPrefix + encodeQuery(query)
    }

    /** [template] holds `%s` exactly once, where the encoded query goes. Only made through [validateCustomEngine]. */
    data class Custom(override val id: String, val name: String, val template: String) : SearchProvider {
        override fun searchUrl(query: String): String {
            val at = template.indexOf(PLACEHOLDER)
            return template.substring(0, at) + encodeQuery(query) + template.substring(at + PLACEHOLDER.length)
        }
    }

    companion object {
        val DEFAULT: SearchProvider = BuiltIn(SearchEngine.DEFAULT)
        val BUILT_INS: List<BuiltIn> = SearchEngine.entries.map { BuiltIn(it) }
    }
}

const val PLACEHOLDER = "%s"
const val CUSTOM_ID_PREFIX = "custom:"
const val MAX_CUSTOM_ENGINES = 10
const val MAX_CUSTOM_NAME = 30
const val MAX_CUSTOM_TEMPLATE = 500

/** Why a custom engine was refused. The settings screen shows one message for each. */
enum class CustomEngineError {
    /** The address does not start with https://. */
    NOT_HTTPS,

    /** `%s` is missing or appears more than once. */
    PLACEHOLDER,

    /** With `%s` filled in, the address is not a web address with a host, or it is over [MAX_CUSTOM_TEMPLATE]. */
    BAD_ADDRESS,

    /** The name is blank or over [MAX_CUSTOM_NAME] characters. */
    NAME_BLANK,

    /** Another engine, built-in or added, already has this name (case is ignored). */
    NAME_USED,

    /** [MAX_CUSTOM_ENGINES] engines are already added. */
    TOO_MANY,
}

sealed interface AddResult {
    data class Added(val engine: SearchProvider.Custom) : AddResult

    data class Refused(val error: CustomEngineError) : AddResult
}

/**
 * The first rule that [name] and [template] break, or null when both are fine. Both are trimmed first. Address rules are
 * checked before name rules, and the engine limit first of all.
 */
fun validateCustomEngine(name: String, template: String, existing: List<SearchProvider.Custom>): CustomEngineError? {
    if (existing.size >= MAX_CUSTOM_ENGINES) return CustomEngineError.TOO_MANY
    val t = template.trim()
    if (!t.lowercase(Locale.ROOT).startsWith("https://")) return CustomEngineError.NOT_HTTPS
    val first = t.indexOf(PLACEHOLDER)
    if (first < 0 || t.indexOf(PLACEHOLDER, first + 1) >= 0) return CustomEngineError.PLACEHOLDER
    if (t.length > MAX_CUSTOM_TEMPLATE || !hasHost(t.replace(PLACEHOLDER, "test"))) return CustomEngineError.BAD_ADDRESS
    val n = name.trim()
    if (n.isEmpty() || n.length > MAX_CUSTOM_NAME) return CustomEngineError.NAME_BLANK
    val taken = SearchEngine.entries.map { it.label } + existing.map { it.name }
    if (taken.any { it.equals(n, ignoreCase = true) }) return CustomEngineError.NAME_USED
    return null
}

private fun hasHost(url: String): Boolean =
    runCatching { URI(url) }.getOrNull()?.let { it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrEmpty() && it.userInfo == null } ?: false

/** Where the choice and the added engines are kept. [selectedId] is the raw stored id; null when nothing is stored. */
interface SearchEnginePrefs {
    var selectedId: String?
    var custom: List<SearchProvider.Custom>

    /** The next `custom:<n>` number. Never goes down, so a removed engine's id is not given to a new one. */
    var nextCustomNumber: Int
}

/** The current choice, readable by the address bar and changeable from Settings. Applies to normal and private tabs. */
class SearchEngineSettings(private val prefs: SearchEnginePrefs) {
    private val _custom = MutableStateFlow(prefs.custom)
    val custom: StateFlow<List<SearchProvider.Custom>> = _custom

    private val _selected = MutableStateFlow(resolve(prefs.selectedId))
    val selected: StateFlow<SearchProvider> = _selected

    /** A stored id back to its engine. Unknown ids (a removed engine, a downgrade, nothing stored) are DuckDuckGo. */
    private fun resolve(id: String?): SearchProvider =
        _custom.value.firstOrNull { it.id == id }
            ?: SearchProvider.BUILT_INS.firstOrNull { it.id == id }
            ?: SearchProvider.DEFAULT

    /** Selects [provider]. A custom engine that is not in [custom] (already removed) is ignored. */
    fun select(provider: SearchProvider) {
        if (provider is SearchProvider.Custom && _custom.value.none { it == provider }) return
        prefs.selectedId = provider.id
        _selected.value = provider
    }

    /** Adds an engine after [validateCustomEngine]. The new engine is not selected. */
    fun addCustom(name: String, template: String): AddResult {
        validateCustomEngine(name, template, _custom.value)?.let { return AddResult.Refused(it) }
        val number = maxOf(prefs.nextCustomNumber, 1)
        val engine = SearchProvider.Custom("$CUSTOM_ID_PREFIX$number", name.trim(), template.trim())
        prefs.nextCustomNumber = number + 1
        val list = _custom.value + engine
        prefs.custom = list
        _custom.value = list
        return AddResult.Added(engine)
    }

    /** Removes an added engine. If it was the selected one, DuckDuckGo is selected. */
    fun removeCustom(id: String) {
        val list = _custom.value.filterNot { it.id == id }
        if (list.size == _custom.value.size) return
        prefs.custom = list
        _custom.value = list
        if (_selected.value.id == id) select(SearchProvider.DEFAULT)
    }
}
