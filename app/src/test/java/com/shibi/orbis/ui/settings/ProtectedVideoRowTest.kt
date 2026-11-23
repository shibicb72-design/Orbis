package com.shibi.orbis.ui.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.shibi.orbis.settings.BrowserSettings
import com.shibi.orbis.settings.SharedPreferencesBrowserPrefs
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings > Privacy > Play protected video (DRM): exact words, on by default, the reload line after a change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ProtectedVideoRowTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private lateinit var settings: BrowserSettings

    private val title = "Play protected video (DRM)"
    private val summary =
        "Lets sites play video that uses DRM, such as streaming services. Uses Android's DRM system. " +
            "Turn off to refuse it for all sites. Sites that use it may be able to recognise this phone."
    private val reload = "Reload open pages to apply."

    @Before
    fun setUp() {
        val sp = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("protected_video_row_test", Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        settings = BrowserSettings(SharedPreferencesBrowserPrefs(sp))
        rule.setContent { MaterialTheme { ProtectedVideoRow(settings) } }
    }

    @Test(timeout = 60_000)
    fun theExactWordsAndOnByDefault() {
        rule.onNodeWithText(title).assertExists()
        rule.onNodeWithText(summary).assertExists()
        rule.onNodeWithText(reload).assertDoesNotExist()
        rule.onNode(isToggleable()).assertIsOn()
    }

    @Test(timeout = 60_000)
    fun aTapTurnsItOffAndSaysToReloadThenOnAgain() {
        rule.onNodeWithText(title).performClick()
        rule.waitForIdle()
        assertFalse(settings.protectedVideo.value)
        rule.onNode(isToggleable()).assertIsOff()
        rule.onNodeWithText(reload).assertExists()

        rule.onNodeWithText(title).performClick()
        rule.waitForIdle()
        assertTrue(settings.protectedVideo.value)
        rule.onNode(isToggleable()).assertIsOn()
        rule.onNodeWithText(reload).assertExists()
    }
}
