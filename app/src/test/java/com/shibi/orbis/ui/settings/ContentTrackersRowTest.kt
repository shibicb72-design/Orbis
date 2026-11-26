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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ContentTrackersRowTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private lateinit var site: BrowserSettings

    @Before
    fun setUp() {
        val sp = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("content_trackers_row_test", Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        site = BrowserSettings(SharedPreferencesBrowserPrefs(sp))
        rule.setContent { MaterialTheme { ContentTrackersRow(site) } }
    }

    @Test(timeout = 60_000)
    fun theRowSaysExactlyWhatTheSwitchDoes() {
        rule.onNodeWithText("Also block content trackers").assertExists()
        rule.onNodeWithText(
            "Blocks scripts, fonts and video players that large companies host for other sites. More private, but many " +
                "pages break: empty pages, missing videos, failed 'are you human' checks.",
        ).assertExists()
    }

    @Test(timeout = 60_000)
    fun offByDefaultAndATapOnTheWordsTurnsItOnAndOff() {
        rule.onNode(isToggleable()).assertIsOff()
        rule.onNodeWithText("Also block content trackers").performClick()
        assertTrue(site.blockContentTrackers.value)
        rule.onNode(isToggleable()).assertIsOn()
        rule.onNodeWithText("Also block content trackers").performClick()
        assertFalse(site.blockContentTrackers.value)
    }
}
