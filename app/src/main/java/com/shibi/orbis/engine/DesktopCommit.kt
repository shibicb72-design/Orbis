package com.shibi.orbis.engine

/** What a committed page needs done about Desktop site mode. */
internal sealed interface DesktopCommit {
    data object Leave : DesktopCommit

    /** Switch the session to [desktop]; [reload] once so the page is fetched again with the right user agent. */
    data class Apply(val desktop: Boolean, val reload: Boolean) : DesktopCommit
}

/**
 * Desktop mode for a commit that may have skipped onLoadRequest (Back, Forward). [wanted] is the site's choice (null
 * when there is none to apply: no policy, not a web page), [current] the session's mode. [reloadedForLastCommit] is
 * true when the previous commit already reloaded for this: the reload's own commit never reloads again, so a site whose
 * choice keeps changing cannot loop. It still gets the right mode for its next load. [pageStarted] is false for a
 * same-document commit (a hash link, history.pushState): nothing was requested, so the mode is left alone until the
 * next real load, which applies the site policy itself.
 */
internal fun desktopAfterCommit(
    wanted: Boolean?,
    current: Boolean,
    reloadedForLastCommit: Boolean,
    pageStarted: Boolean,
): DesktopCommit =
    when {
        wanted == null || wanted == current || !pageStarted -> DesktopCommit.Leave
        else -> DesktopCommit.Apply(wanted, reload = !reloadedForLastCommit)
    }

/**
 * The entry a Back ([offset] -1) or Forward (+1) step will load, or null when the back/forward list Gecko last reported
 * is not current: its current entry is not [committedUrl]. GeckoView reports the list some time after a navigation, and
 * a stale list names the wrong entry (and so the wrong desktop mode); the commit fallback then decides instead.
 */
internal fun historyStepTarget(uris: List<String>, currentIndex: Int, offset: Int, committedUrl: String): String? {
    if (uris.getOrNull(currentIndex) != committedUrl) return null
    return uris.getOrNull(currentIndex + offset)
}

/**
 * The entry a Back ([offset] -1) or Forward (+1) step will load: from [tracked] when it knows the list, else from the
 * list Gecko last reported ([reported], current entry [reportedIndex]) if that one is current (see [historyStepTarget]).
 * Null: nobody knows, and the commit fallback ([desktopAfterCommit]) decides.
 */
internal fun historyStepUri(
    tracked: TabHistoryTracker,
    offset: Int,
    reported: List<String>?,
    reportedIndex: Int,
    committedUrl: String,
): String? =
    if (tracked.isKnown) {
        tracked.stepTarget(offset)
    } else {
        reported?.let { historyStepTarget(it, reportedIndex, offset, committedUrl) }
    }

/**
 * Orbis's own copy of a tab's back/forward list, kept from its commits. GeckoView reports the list only after about 10 s
 * without a navigation (measured on GeckoView 160), too late for a quick Back or Forward; a list kept here is current at
 * every commit. Main thread only.
 *
 * - A commit Orbis's own Back or Forward started moves the index; any other commit to a new address drops the forward
 *   entries and adds one. A redirect commits only its final address, so the entry it adds is already the final one.
 * - A same-document commit (hash link, pushState) adds an entry, as Gecko does; replaceState, which Gecko does not add,
 *   cannot be told apart and is counted too. Those entries share the page's host, so the count does not change the
 *   desktop mode a step targets unless replaceState was used.
 * - A commit of the address already current (a reload) changes nothing.
 * - A restore (a saved tab, or a page whose content process died) makes the list unknown, ignores the reopened
 *   session's blank document and Gecko's lists, and decides at the restored page's first commit: the list held before
 *   the restore, else the list Gecko last reported, if its current entry is that page; otherwise unknown until Gecko
 *   reports a current list. A step that ends without a commit, or commits another address than its entry, also makes
 *   the list unknown.
 *
 * Gecko's list replaces this one whenever it is current: its current entry is the page shown and no step is on its way.
 * A wrong list costs what an unknown one does: the commit fallback corrects the mode with one reload.
 */
internal class TabHistoryTracker {
    private class Snapshot(val uris: List<String>, val index: Int) {
        fun shows(url: String): Boolean = uris.getOrNull(index) == url
    }

    /** Null while the list is unknown. */
    private var uris: List<String>? = emptyList()
    private var index = -1

    /** Sum of the steps Orbis started whose commit has not arrived. */
    private var pendingStep = 0

    /** The content process died or a saved tab is being restored: Gecko's lists are not taken until the page commits. */
    private var awaitingRestoredCommit = false

    /** The list before the restore; checked against the restored page's first commit. */
    private var heldForRestore: Snapshot? = null

    /** The list Gecko last reported outside a restore. */
    private var lastReported: Snapshot? = null

    val isKnown: Boolean get() = uris != null

    /** The entry [offset] steps away, or null when unknown, out of range, or another step is still on its way. */
    fun stepTarget(offset: Int): String? {
        val list = uris ?: return null
        if (pendingStep != 0) return null
        return list.getOrNull(index + offset)
    }

    /** Orbis is about to call goBack ([offset] -1) or goForward (+1). */
    fun onStepStarted(offset: Int) {
        if (uris != null) pendingStep += offset
    }

    /** A top-level commit of [url] (onLocationChange). */
    fun onCommit(url: String) {
        if (awaitingRestoredCommit) {
            // The reopened session's blank document is not the restored page.
            if (url == "about:blank") return
            awaitingRestoredCommit = false
            val known = listOfNotNull(heldForRestore, lastReported).firstOrNull { it.shows(url) }
            heldForRestore = null
            if (known != null) {
                uris = known.uris
                index = known.index
            }
            return
        }
        val list = uris ?: return
        if (pendingStep != 0) {
            val target = index + pendingStep
            pendingStep = 0
            if (list.getOrNull(target) == url) index = target else forget()
            return
        }
        if (list.getOrNull(index) == url) return
        uris = list.take(index + 1) + url
        index += 1
    }

    /** Gecko reported its list ([reported], current entry [reportedIndex]); taken when it shows [committedUrl]. */
    fun onReportedList(reported: List<String>, reportedIndex: Int, committedUrl: String) {
        // During a restore the list may be the reopened session's blank one.
        if (awaitingRestoredCommit) return
        val report = Snapshot(reported, reportedIndex)
        lastReported = report
        if (pendingStep != 0 || !report.shows(committedUrl)) return
        uris = reported
        index = reportedIndex
    }

    /** A load ended without a commit (an error, a cancelled leave): a step on its way went nowhere this list knows. */
    fun onLoadEndedWithoutCommit() {
        if (pendingStep != 0) forget()
    }

    /** The content process died, or a saved tab is restored: the list is unknown until the page is back. */
    fun onRestoreStarted() {
        // Called again when the restore itself starts: the list held at the first call stays.
        uris?.let { heldForRestore = Snapshot(it, index) }
        forget()
        awaitingRestoredCommit = true
    }

    private fun forget() {
        uris = null
        index = -1
        pendingStep = 0
    }
}
