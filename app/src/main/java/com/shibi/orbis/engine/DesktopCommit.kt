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
