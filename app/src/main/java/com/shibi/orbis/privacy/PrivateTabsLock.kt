package com.shibi.orbis.privacy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the private tabs are hidden behind the unlock prompt. Memory only: private tabs do not survive a process
 * death, so a new process starts unlocked. Pure state, so the rules are testable without a device; the screens read
 * [locked], MainActivity reports when Orbis leaves the screen, and the unlock prompt reports its success.
 */
class PrivateTabsLock {
    private val state = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = state.asStateFlow()

    /** Orbis left the screen. Locks only when the user turned the lock on and a private tab is open. */
    fun onAppHidden(hasPrivateTabs: Boolean, settingOn: Boolean) {
        if (hasPrivateTabs && settingOn) state.value = true
    }

    /** The user passed the fingerprint or screen-lock prompt. */
    fun onUnlocked() {
        state.value = false
    }

    /** The last private tab is gone: there is nothing left to hide. */
    fun onPrivateBrowsingEnded() {
        state.value = false
    }
}

/** The selected tab's page is replaced by the lock panel: the lock is on and the selected tab is private. */
fun pageHiddenByLock(locked: Boolean, selectedTabIsPrivate: Boolean): Boolean = locked && selectedTabIsPrivate
