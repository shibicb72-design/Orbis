package com.shibi.orbis.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The search engines the address bar can use. [searchPrefix] is followed by the URL-encoded query. */
enum class SearchEngine(val searchPrefix: String) {
    DUCKDUCKGO("https://duckduckgo.com/?q="),
    GOOGLE("https://www.google.com/search?q="),
    BING("https://www.bing.com/search?q="),
    BRAVE("https://search.brave.com/search?q="),
    STARTPAGE("https://www.startpage.com/do/search?q="),
    ;

    companion object {
        val DEFAULT = DUCKDUCKGO

        /** A stored [name] back to its engine; anything missing or unknown is the default. */
        fun fromName(name: String?): SearchEngine = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** Where the chosen engine is remembered. */
interface SearchEnginePrefs {
    var engine: SearchEngine
}

/** The current choice, readable by the address bar and changeable from Settings. Applies to normal and private tabs. */
class SearchEngineSettings(private val prefs: SearchEnginePrefs) {
    private val _selected = MutableStateFlow(prefs.engine)
    val selected: StateFlow<SearchEngine> = _selected

    fun select(engine: SearchEngine) {
        prefs.engine = engine
        _selected.value = engine
    }
}
