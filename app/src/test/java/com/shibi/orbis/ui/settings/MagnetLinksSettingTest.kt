package com.shibi.orbis.ui.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.shibi.orbis.engine.SearchEngine
import com.shibi.orbis.settings.BrowserSettings
import com.shibi.orbis.settings.MagnetHandler
import com.shibi.orbis.settings.SharedPreferencesBrowserPrefs
import com.shibi.orbis.ubo.UboState
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MagnetLinksSettingTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    @Test
    fun theMagnetLinksRowPersistsTheChoice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shared = context.getSharedPreferences(SharedPreferencesBrowserPrefs.FILE_NAME, Context.MODE_PRIVATE)
        val site = BrowserSettings(SharedPreferencesBrowserPrefs(shared))
        assertEquals(MagnetHandler.ORBIS, site.magnetHandler.value)
        assertTrue(site.magnetsToOrbis())
        rule.setContent {
            MaterialTheme {
                SettingsScreen(
                    uboStateFlow = MutableStateFlow(UboState.NotInstalled),
                    searchEngineFlow = MutableStateFlow(SearchEngine.DEFAULT),
                    onSelectSearchEngine = {},
                    homepageFlow = MutableStateFlow(null),
                    onSetHomepage = {},
                    searchSuggestionsFlow = MutableStateFlow(true),
                    onSetSearchSuggestions = {},
                    textZoomFlow = MutableStateFlow(100),
                    onSetTextZoom = {},
                    forceZoomFlow = MutableStateFlow(false),
                    onSetForceZoom = {},
                    onBack = {},
                    onOpenErrorLog = {},
                    onOpenUbo = {},
                    onOpenSitePermissions = {},
                    onOpenClearData = {},
                    site = site,
                    versionName = "0.5.0",
                    onOpenPlayListing = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Magnet links").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Open in another app").performClick()
        rule.waitForIdle()
        assertEquals(MagnetHandler.OTHER_APP, site.magnetHandler.value)
        assertFalse(site.magnetsToOrbis())
        // Written through: a fresh settings object reads the choice back.
        assertEquals(MagnetHandler.OTHER_APP, BrowserSettings(SharedPreferencesBrowserPrefs(shared)).magnetHandler.value)
        assertEquals("OTHER_APP", shared.getString("magnet_handler", null))
        // The row shows the choice; back to Orbis.
        rule.onNodeWithText("Open in another app").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Download with Orbis").performClick()
        rule.waitForIdle()
        assertEquals(MagnetHandler.ORBIS, BrowserSettings(SharedPreferencesBrowserPrefs(shared)).magnetHandler.value)
    }
}
