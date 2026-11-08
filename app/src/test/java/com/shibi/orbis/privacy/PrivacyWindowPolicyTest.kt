package com.shibi.orbis.privacy

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivacyWindowPolicyTest {
    private val allowed = PrivacyWindowDecision(recentsScreenshotEnabled = true, flagSecure = false)

    private class FakeOps : PrivacyWindowOps {
        val calls = mutableListOf<String>()

        override fun setRecentsScreenshot(enabled: Boolean) {
            calls += "recents=$enabled"
        }

        override fun setFlagSecure(secure: Boolean) {
            calls += "secure=$secure"
        }
    }

    @Test
    fun noPrivateTabLeavesWindowOpenOnEveryApi() {
        for (api in listOf(29, 30, 32, 33, 34, 36, 37)) {
            assertEquals("api $api", allowed, PrivacyWindowPolicy.decide(api, anyPrivateTabOpen = false))
        }
    }

    @Test
    fun anyPrivateTabSetsFlagSecureAndDisablesRecentsOnEveryApi() {
        // Also the case of a private tab open while a normal tab is selected (the switcher lists it).
        for (api in listOf(29, 30, 32, 33, 34, 35, 36, 37)) {
            assertEquals(
                "api $api",
                PrivacyWindowDecision(recentsScreenshotEnabled = false, flagSecure = true),
                PrivacyWindowPolicy.decide(api, anyPrivateTabOpen = true),
            )
        }
    }

    @Test
    fun noPrivateTabClearsFlagSecureAndEnablesRecentsOnEveryApi() {
        for (api in listOf(29, 30, 32, 33, 34, 35, 36, 37)) {
            assertEquals(
                "api $api",
                PrivacyWindowDecision(recentsScreenshotEnabled = true, flagSecure = false),
                PrivacyWindowPolicy.decide(api, anyPrivateTabOpen = false),
            )
        }
    }

    @Test
    fun controllerOnApi34And35DrivesRecentsAndFlagSecureAcrossOpenAndClose() {
        for (api in listOf(34, 35)) {
            val ops = FakeOps()
            val controller = PrivacyWindowController(ops)
            controller.apply(api, anyPrivateTabOpen = false)
            assertEquals("api $api", listOf("recents=true", "secure=false"), ops.calls)
            ops.calls.clear()
            controller.apply(api, anyPrivateTabOpen = true)
            assertEquals("api $api", listOf("recents=false", "secure=true"), ops.calls)
            ops.calls.clear()
            controller.apply(api, anyPrivateTabOpen = false)
            assertEquals("api $api", listOf("recents=true", "secure=false"), ops.calls)
        }
    }

    @Test
    fun controllerOnApi33IsTheFirstToUseTheRecentsSwitch() {
        val ops = FakeOps()
        PrivacyWindowController(ops).apply(33, anyPrivateTabOpen = true)
        assertEquals(listOf("recents=false", "secure=true"), ops.calls)
    }

    @Test
    fun controllerBelowApi33NeverTouchesRecentsAndSetsThenClearsFlagSecure() {
        for (api in listOf(29, 32)) {
            val ops = FakeOps()
            val controller = PrivacyWindowController(ops)
            controller.apply(api, anyPrivateTabOpen = false)
            assertEquals("api $api", listOf("secure=false"), ops.calls)
            ops.calls.clear()
            controller.apply(api, anyPrivateTabOpen = true)
            assertEquals("api $api", listOf("secure=true"), ops.calls)
            ops.calls.clear()
            controller.apply(api, anyPrivateTabOpen = false)
            assertEquals("api $api", listOf("secure=false"), ops.calls)
        }
    }
}
