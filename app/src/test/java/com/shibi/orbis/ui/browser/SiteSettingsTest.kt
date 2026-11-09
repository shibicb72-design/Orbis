package com.shibi.orbis.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class SiteSettingsTest {
    @Test
    fun storedAnswersMapToChoices() {
        assertEquals(PermissionChoice.ALLOW, permissionChoiceOf(true))
        assertEquals(PermissionChoice.BLOCK, permissionChoiceOf(false))
        assertEquals(PermissionChoice.ASK, permissionChoiceOf(null))
    }
}
