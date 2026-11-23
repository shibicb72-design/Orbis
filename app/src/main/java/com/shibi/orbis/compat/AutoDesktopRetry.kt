package com.shibi.orbis.compat

sealed interface Action {
    /** Turn Desktop on for the host and reload once. */
    data object Retry : Action

    /** The retry worked: persist the choice and show the notice. */
    data object Keep : Action

    /** The retry was blocked too: turn Desktop off, reload once, record declined. */
    data object Revert : Action

    /** An empty page on a host that is desktop only because auto desktop kept it: mobile for the host, reload once. */
    data object RetryMobile : Action

    /** The mobile retry has content: remove the automatic desktop choice for the host and show the notice. */
    data object KeepMobile : Action

    /** The mobile retry did not help: desktop again for the host (its stored choice was never changed), reload once. */
    data object RestoreDesktop : Action

    data object None : Action
}

/**
 * Retry state for ONE tab. No I/O: the caller supplies facts and performs the actions.
 *
 * Two retries, each at most once per host per tab: a blocked page in mobile mode is tried as desktop ([onBlockedPage]),
 * and an empty page in a desktop mode that only auto desktop chose is tried as mobile ([onEmptyPage]). They cannot
 * ping-pong: a host whose mobile retry was kept is never switched to desktop again in this tab (onBlockedPage reads
 * mobileKept), a host is tried as desktop once (attempted) and as mobile once (emptyTried).
 */
class AutoDesktopRetry {
    private val attempted = mutableSetOf<String>()
    private var retrying: String? = null
    private val emptyTried = mutableSetOf<String>()
    private val mobileKept = mutableSetOf<String>()
    private var retryingMobile: String? = null

    fun onBlockedPage(
        host: String,
        desktopMode: Boolean,
        alwaysDesktop: Boolean,
        declined: Boolean,
        userChoseDesktop: Boolean, // intentionally unused: blocked with Desktop on already returns None
    ): Action {
        // The mobile retry is blocked: mobile is no better, so desktop goes back.
        if (retryingMobile == host) {
            retryingMobile = null
            return Action.RestoreDesktop
        }
        if (retrying == host) {
            retrying = null
            return Action.Revert
        }
        if (host in attempted || host in mobileKept) return Action.None
        if (alwaysDesktop || declined) return Action.None
        if (desktopMode) return Action.None
        attempted.add(host)
        retrying = host
        return Action.Retry
    }

    /** A page that is neither blocked nor empty. */
    fun onCleanPage(host: String): Action {
        if (retryingMobile == host) {
            retryingMobile = null
            mobileKept.add(host)
            return Action.KeepMobile
        }
        if (retrying != host) return Action.None
        retrying = null
        return Action.Keep
    }

    /**
     * A page that is not blocked but shows almost nothing. [desktopByAuto]: desktop mode is on for the host only because
     * auto desktop kept it (not the page menu, the site setting or Always desktop). The caller first ends a pending
     * desktop retry with [onCleanPage] (the page is not blocked) and then asks this with the facts as they are after it.
     */
    fun onEmptyPage(host: String, desktopMode: Boolean, desktopByAuto: Boolean): Action {
        if (retryingMobile == host) {
            retryingMobile = null
            return Action.RestoreDesktop
        }
        if (!desktopMode || !desktopByAuto) return Action.None
        if (host in emptyTried) return Action.None
        emptyTried.add(host)
        retryingMobile = host
        return Action.RetryMobile
    }

    fun onLeft(host: String) {
        if (retrying == host) retrying = null
        if (retryingMobile == host) retryingMobile = null
    }
}
