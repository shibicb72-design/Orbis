package com.shibi.orbis.tabs

/** Where the tab layer reports pages the user visited. All calls arrive on the main thread. */
interface VisitSink {
    /** A page was visited. [title] may be empty (it can arrive later through [onTitle]). */
    fun onVisit(url: String, title: String)

    /** The title of the page at [url] changed after it was reported. Never creates a visit. */
    fun onTitle(url: String, title: String)

    /** Records nothing. */
    object None : VisitSink {
        override fun onVisit(url: String, title: String) = Unit

        override fun onTitle(url: String, title: String) = Unit
    }
}
