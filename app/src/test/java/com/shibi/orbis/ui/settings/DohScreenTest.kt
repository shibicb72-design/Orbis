package com.shibi.orbis.ui.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.shibi.orbis.settings.DohProvider
import com.shibi.orbis.settings.DohSettings
import com.shibi.orbis.settings.SharedPreferencesDohPrefs
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DohScreenTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var settings: DohSettings

    @Before
    fun setUp() {
        val sp = context.getSharedPreferences(SharedPreferencesDohPrefs.FILE_NAME, Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        settings = DohSettings(SharedPreferencesDohPrefs(sp))
        rule.setContent { MaterialTheme { DohScreen(settings = settings, onBack = {}) } }
        rule.waitForIdle()
    }

    private fun tap(text: String) {
        rule.onNodeWithText(text).performScrollTo().performClick()
        rule.waitForIdle()
    }

    @Test
    fun startsOffWithTheNoteAndNoChoices() {
        rule.onNodeWithText("Use secure DNS").assertExists()
        rule.onNodeWithText("Off: Orbis uses your phone's DNS", substring = true).assertExists()
        rule.onAllNodesWithText("Quad9").assertCountEquals(0)
        assertNull(settings.config.value.resolverUrl)
    }

    @Test
    fun switchOnShowsFourChoicesAndPickingOneChangesTheResolver() {
        tap("Use secure DNS")
        for (label in listOf("Quad9", "Cloudflare", "Mullvad", "Custom address")) rule.onNodeWithText(label).assertExists()
        assertEquals("https://dns.quad9.net/dns-query", settings.config.value.resolverUrl)
        tap("Mullvad")
        assertEquals("https://dns.mullvad.net/dns-query", settings.config.value.resolverUrl)
    }

    @Test
    fun customAddressIsValidatedOnSave() {
        tap("Use secure DNS")
        tap("Custom address")
        rule.onNode(hasSetTextAction()).performTextReplacement("http://x/dns-query")
        tap("Save")
        rule.onNodeWithText("Enter an address that starts with https://").assertExists()
        assertNull(settings.config.value.customUrl)
        rule.onNode(hasSetTextAction()).performTextReplacement("https://dns.example/dns-query")
        tap("Save")
        rule.onAllNodesWithText("Enter an address that starts with https://").assertCountEquals(0)
        assertEquals("https://dns.example/dns-query", settings.config.value.resolverUrl)
        assertEquals(DohProvider.CUSTOM, settings.config.value.provider)
        assertTrue(settings.config.value.enabled)
        tap("Use secure DNS")
        assertFalse(settings.config.value.enabled)
    }
}
