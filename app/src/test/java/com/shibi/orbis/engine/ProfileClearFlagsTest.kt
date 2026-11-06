package com.shibi.orbis.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileClearFlagsTest {
    // StorageController.ClearFlags: COOKIES 1, NETWORK_CACHE 2, IMAGE_CACHE 4 (ALL_CACHES 6), AUTH_SESSIONS 16,
    // DOM_STORAGES 32. PERMISSIONS (64), SITE_DATA (128) and SITE_SETTINGS (512) must never be set; 256 is not used.
    private val forbidden = longArrayOf(64, 128, 256, 512)

    @Test
    fun cookiesAndSiteDataAreCookiesAuthSessionsAndDomStorage() {
        assertEquals(49L, profileClearFlags(cookiesAndSiteData = true, cache = false))
    }

    @Test
    fun cacheIsAllCaches() {
        assertEquals(6L, profileClearFlags(cookiesAndSiteData = false, cache = true))
    }

    @Test
    fun bothIsTheUnion() {
        assertEquals(55L, profileClearFlags(cookiesAndSiteData = true, cache = true))
    }

    @Test
    fun neitherIsZeroSoGeckoIsNotCalled() {
        assertEquals(0L, profileClearFlags(cookiesAndSiteData = false, cache = false))
    }

    @Test
    fun permissionsSiteDataAndSiteSettingsAreNeverRequested() {
        for (cookies in listOf(false, true)) {
            for (cache in listOf(false, true)) {
                val flags = profileClearFlags(cookies, cache)
                for (bit in forbidden) assertEquals("cookies=$cookies cache=$cache bit=$bit", 0L, flags and bit)
                // Nothing outside the bits listed above either.
                assertEquals(0L, flags and 55L.inv())
            }
        }
    }
}
