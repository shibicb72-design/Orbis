package com.shibi.orbis.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mozilla.geckoview.WebExtension

class ExtensionErrorMappingTest {

    @Test
    fun theCodeAnUpdateDeniedInOnUpdatePromptCarriesMapsToCancelled() {
        // GeckoView 160: installError 0 + state STATE_CANCELLED + cancelledByUser becomes -100 (ERROR_USER_CANCELED).
        assertEquals(-100, WebExtension.InstallException.ErrorCodes.ERROR_USER_CANCELED)
        assertEquals(ExtensionInstallError.CANCELLED, mapInstallError(-100))
    }

    @Test
    fun otherCodesAreNotCancelled() {
        assertEquals(ExtensionInstallError.NETWORK, mapInstallError(WebExtension.InstallException.ErrorCodes.ERROR_NETWORK_FAILURE))
        assertEquals(ExtensionInstallError.BLOCKED, mapInstallError(WebExtension.InstallException.ErrorCodes.ERROR_BLOCKLISTED))
        assertEquals(ExtensionInstallError.UNKNOWN, mapInstallError(WebExtension.InstallException.ErrorCodes.ERROR_POSTPONED))
        assertEquals(ExtensionInstallError.UNKNOWN, mapInstallError(-1000))
    }

    @Test
    fun anEnabledExtensionHasNoReason() {
        assertNull(disabledReasonOf(enabled = true, flags = 0))
        assertNull(disabledReasonOf(enabled = true, flags = WebExtension.DisabledFlags.USER))
    }

    @Test
    fun eachFlagGivesItsReason() {
        assertEquals(ExtensionDisabledReason.USER, disabledReasonOf(false, WebExtension.DisabledFlags.USER))
        assertEquals(ExtensionDisabledReason.BLOCKLIST, disabledReasonOf(false, WebExtension.DisabledFlags.BLOCKLIST))
        assertEquals(ExtensionDisabledReason.BLOCKLIST, disabledReasonOf(false, WebExtension.DisabledFlags.SOFT_BLOCKLIST))
        assertEquals(ExtensionDisabledReason.SIGNATURE, disabledReasonOf(false, WebExtension.DisabledFlags.SIGNATURE))
        assertEquals(ExtensionDisabledReason.OTHER, disabledReasonOf(false, WebExtension.DisabledFlags.APP))
        assertEquals(ExtensionDisabledReason.OTHER, disabledReasonOf(false, WebExtension.DisabledFlags.APP_VERSION))
        assertEquals(ExtensionDisabledReason.OTHER, disabledReasonOf(false, 0))
    }

    @Test
    fun severalFlagsFollowBlocklistSignatureUserOther() {
        val all = WebExtension.DisabledFlags.USER or WebExtension.DisabledFlags.BLOCKLIST or
            WebExtension.DisabledFlags.SIGNATURE or WebExtension.DisabledFlags.APP
        assertEquals(ExtensionDisabledReason.BLOCKLIST, disabledReasonOf(false, all))
        assertEquals(
            ExtensionDisabledReason.SIGNATURE,
            disabledReasonOf(false, WebExtension.DisabledFlags.USER or WebExtension.DisabledFlags.SIGNATURE or WebExtension.DisabledFlags.APP),
        )
        assertEquals(
            ExtensionDisabledReason.USER,
            disabledReasonOf(false, WebExtension.DisabledFlags.USER or WebExtension.DisabledFlags.APP),
        )
    }
}
