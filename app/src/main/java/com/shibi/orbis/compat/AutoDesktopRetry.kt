package com.shibi.orbis.compat

sealed interface Action {
    /** Turn Desktop on for the host and reload once. */
    data object Retry : Action

    /** The retry worked: persist the choice and show the notice. */
    data object Keep : Action

    /** The retry was blocked too: turn Desktop off, reload once, record declined. */
    data object Revert : Action

    data object None : Action
}

/** Retry state for ONE tab. No I/O: the caller supplies facts and performs the actions. */
class AutoDesktopRetry {
    private val attempted = mutableSetOf<String>()
    private var retrying: String? = null

    fun onBlockedPage(
        host: String,
        desktopMode: Boolean,
        alwaysDesktop: Boolean,
        declined: Boolean,
        userChoseDesktop: Boolean, // intentionally unused: blocked with Desktop on already returns None
    ): Action {
        if (retrying == host) {
            retrying = null
            return Action.Revert
        }
        if (host in attempted) return Action.None
        if (alwaysDesktop || declined) return Action.None
        if (desktopMode) return Action.None
        attempted.add(host)
        retrying = host
        return Action.Retry
    }

    fun onCleanPage(host: String): Action {
        if (retrying != host) return Action.None
        retrying = null
        return Action.Keep
    }

    fun onLeft(host: String) {
        if (retrying == host) retrying = null
    }
}
