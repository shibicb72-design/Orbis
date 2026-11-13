package com.shibi.orbis.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateBrowsingPermissionTest {
    @Test
    fun allowedAndWantedAllowedNeedsNoCall() = assertFalse(privateBrowsingChangeNeeded(current = true, wanted = true))

    @Test
    fun notAllowedAndWantedNotAllowedNeedsNoCall() = assertFalse(privateBrowsingChangeNeeded(current = false, wanted = false))

    @Test
    fun notAllowedButWantedAllowedNeedsCall() = assertTrue(privateBrowsingChangeNeeded(current = false, wanted = true))

    @Test
    fun allowedButWantedNotAllowedNeedsCall() = assertTrue(privateBrowsingChangeNeeded(current = true, wanted = false))
}
