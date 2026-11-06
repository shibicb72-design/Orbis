package com.shibi.orbis.history

import com.shibi.orbis.engine.isWebUrl

/** A page to add to the history. [title] is empty when none was seen (it may follow as a [TitleUpdate]). */
data class Visit(val url: String, val title: String)

/** The page at [url] got its title after it was reported as a [Visit]. */
data class TitleUpdate(val url: String, val title: String)

/**
 * Decides which engine callbacks are visits. Pure; one per session, fed from the main thread.
 *
 * - A page load is recorded when it stops successfully, for the location committed during it. A failed or stopped load
 *   is not (the old page stays on screen and its address is unchanged).
 * - A location change outside a load (pushState, hash) is recorded at once, at most one per
 *   [sameDocumentMinIntervalMillis], so a script cannot flush the real history. It carries no title: the old page's
 *   title must not be inherited.
 * - The same URL as the last recorded one (reload, crash reload, desktop toggle) adds nothing.
 * - The URL a restored tab was reopened with ([suppress]) is not a visit; that holds once.
 * - Titles count only if seen since the load started, so a page without a title never takes the previous page's.
 *
 * Works whichever of onPageStart and onLocationChange the engine delivers first for a load: in the reverse order the
 * location is recorded as a same-document visit without a title, and the stop then finds it already recorded. Its
 * title is only picked up if a title change arrives after the load.
 */
class VisitTracker(
    private val now: () -> Long,
    private val sameDocumentMinIntervalMillis: Long = 2_000L,
) {
    private var loading = false
    private var loadError = false
    private var currentUrl: String? = null
    private var pageTitle = ""
    private var lastRecorded: String? = null
    private var suppressed: String? = null
    private var lastSameDocumentAt: Long? = null

    /** [url] is the page a restored tab reopens with; its first appearance is not a visit. */
    fun suppress(url: String?) {
        suppressed = url?.takeIf { isWebUrl(it) }
    }

    fun onPageStart() {
        loading = true
        loadError = false
        pageTitle = ""
    }

    fun onLocationChange(url: String?): Visit? {
        if (url == null) return null
        currentUrl = url
        if (loading) return null
        // A location that follows a failed load is that failure, never a visit. Cleared by the next page start.
        if (loadError) return null
        // Not in a load: a same-document navigation (or a restore that reports no load at all).
        if (!isWebUrl(url) || url == lastRecorded) return null
        if (url == suppressed) {
            consumeSuppressed(url)
            return null
        }
        val t = now()
        val last = lastSameDocumentAt
        if (last != null && t - last < sameDocumentMinIntervalMillis) return null
        lastSameDocumentAt = t
        return record(url, "")
    }

    fun onLoadError() {
        loadError = true
    }

    fun onPageStop(success: Boolean): Visit? {
        val wasLoading = loading
        loading = false
        val url = currentUrl
        if (!wasLoading || !success || loadError || url == null || !isWebUrl(url)) return null
        if (url == lastRecorded) return null
        if (url == suppressed) {
            consumeSuppressed(url)
            return null
        }
        return record(url, pageTitle)
    }

    fun onTitleChange(title: String?): TitleUpdate? {
        pageTitle = title.orEmpty()
        if (loading || pageTitle.isBlank()) return null
        // Only the page that is showing: a rate-limited location change leaves the older page as the last recorded.
        val url = lastRecorded?.takeIf { it == currentUrl } ?: return null
        return TitleUpdate(url, pageTitle)
    }

    private fun consumeSuppressed(url: String) {
        suppressed = null
        lastRecorded = url
    }

    private fun record(url: String, title: String): Visit {
        lastRecorded = url
        suppressed = null
        return Visit(url, title)
    }
}
