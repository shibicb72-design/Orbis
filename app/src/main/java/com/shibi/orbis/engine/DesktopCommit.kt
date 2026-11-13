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
 * True when a redirect leaves a desktop page for a mobile site. Measured on GeckoView 160: switching the session from
 * desktop to mobile in onLoadRequest does not reach the redirected request, which keeps the desktop User-Agent header
 * and navigator.userAgent. Mobile to desktop, and link taps either way, do take effect.
 */
internal fun redirectKeepsDesktop(isRedirect: Boolean, desktopBefore: Boolean, desktopAfter: Boolean): Boolean =
    isRedirect && desktopBefore && !desktopAfter
