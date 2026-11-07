package com.shibi.orbis.privacy

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivacyWindowPolicyTest {
    private val allowed = PrivacyWindowDecision(recentsScreenshotEnabled = true, flagSecure = false)

    @Test
    fun noPrivateTabLeavesWindowOpenOnEveryApi() {
        for (api in listOf(29, 30, 32, 33, 34, 36, 37)) {
            assertEquals("api $api", allowed, PrivacyWindowPolicy.decide(api, anyPrivateTabOpen = false))
        }
    }

    @Test
    fun anyPrivateTabOnApi33PlusDisablesRecentsScreenshotOnly() {
        // Also the case of a private tab open while a normal tab is selected (the switcher lists it).
        for (api in listOf(33, 34, 36, 37)) {
            assertEquals(
                "api $api",
                PrivacyWindowDecision(recentsScreenshotEnabled = false, flagSecure = false),
                PrivacyWindowPolicy.decide(api, anyPrivateTabOpen = true),
            )
        }
    }

    @Test
    fun anyPrivateTabBelowApi33UsesFlagSecure() {
        for (api in listOf(29, 30, 31, 32)) {
            assertEquals(
                "api $api",
                PrivacyWindowDecision(recentsScreenshotEnabled = true, flagSecure = true),
                PrivacyWindowPolicy.decide(api, anyPrivateTabOpen = true),
            )
        }
    }

    @Test
    fun closingTheLastPrivateTabRestoresTheWindow() {
        for (api in listOf(29, 32, 33, 36)) {
            val whileOpen = PrivacyWindowPolicy.decide(api, anyPrivateTabOpen = true)
            val afterClose = PrivacyWindowPolicy.decide(api, anyPrivateTabOpen = false)
            assertEquals("api $api", false, whileOpen == allowed)
            assertEquals("api $api", allowed, afterClose)
        }
    }
}
