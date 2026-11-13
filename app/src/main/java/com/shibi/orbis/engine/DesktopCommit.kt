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
 * choice keeps changing cannot loop. It still gets the right mode for its next load.
 */
internal fun desktopAfterCommit(wanted: Boolean?, current: Boolean, reloadedForLastCommit: Boolean): DesktopCommit =
    when {
        wanted == null || wanted == current -> DesktopCommit.Leave
        else -> DesktopCommit.Apply(wanted, reload = !reloadedForLastCommit)
    }

/** Fresh loads one navigation may start through [redirectNeedsFreshLoad] before redirects are left alone. */
internal const val MAX_DESKTOP_REDIRECT_RESTARTS = 3

/**
 * True when a redirect must be cancelled and its target loaded afresh. Measured on GeckoView 160: switching the session
 * from desktop to mobile in onLoadRequest does not reach the redirected request, which keeps the desktop User-Agent
 * header and navigator.userAgent. Mobile to desktop, and link taps either way, do take effect, so only that case is
 * restarted. [restartsSoFar] bounds it, so a redirect loop between desktop and mobile sites cannot run forever.
 */
internal fun redirectNeedsFreshLoad(isRedirect: Boolean, desktopBefore: Boolean, desktopAfter: Boolean, restartsSoFar: Int): Boolean =
    isRedirect && desktopBefore && !desktopAfter && restartsSoFar < MAX_DESKTOP_REDIRECT_RESTARTS
