package com.shibi.orbis.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateTabsLockTest {
    @Test(timeout = 10_000)
    fun startsUnlocked() {
        assertFalse(PrivateTabsLock().locked.value)
    }

    @Test(timeout = 10_000)
    fun settingOffLeavesItUnlocked() {
        val lock = PrivateTabsLock()
        lock.onAppHidden(hasPrivateTabs = true, settingOn = false)
        assertFalse(lock.locked.value)
    }

    @Test(timeout = 10_000)
    fun noPrivateTabLeavesItUnlocked() {
        val lock = PrivateTabsLock()
        lock.onAppHidden(hasPrivateTabs = false, settingOn = true)
        assertFalse(lock.locked.value)
    }

    @Test(timeout = 10_000)
    fun settingOnWithAPrivateTabLocks() {
        val lock = PrivateTabsLock()
        lock.onAppHidden(hasPrivateTabs = true, settingOn = true)
        assertTrue(lock.locked.value)
    }

    @Test(timeout = 10_000)
    fun unlockingClearsIt() {
        val lock = PrivateTabsLock()
        lock.onAppHidden(hasPrivateTabs = true, settingOn = true)
        lock.onUnlocked()
        assertFalse(lock.locked.value)
    }

    @Test(timeout = 10_000)
    fun endingPrivateBrowsingClearsIt() {
        val lock = PrivateTabsLock()
        lock.onAppHidden(hasPrivateTabs = true, settingOn = true)
        lock.onPrivateBrowsingEnded()
        assertFalse(lock.locked.value)
    }

    @Test(timeout = 10_000)
    fun hidingAgainWithoutTheConditionsDoesNotUnlock() {
        val lock = PrivateTabsLock()
        lock.onAppHidden(hasPrivateTabs = true, settingOn = true)
        lock.onAppHidden(hasPrivateTabs = false, settingOn = false)
        assertTrue(lock.locked.value)
    }

    @Test(timeout = 10_000)
    fun onlyASelectedPrivateTabHidesThePage() {
        assertTrue(pageHiddenByLock(locked = true, selectedTabIsPrivate = true))
        assertFalse(pageHiddenByLock(locked = true, selectedTabIsPrivate = false))
        assertFalse(pageHiddenByLock(locked = false, selectedTabIsPrivate = true))
    }
}
