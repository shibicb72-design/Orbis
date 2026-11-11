package com.shibi.orbis.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.shibi.orbis.engine.SearchEngine
import com.shibi.orbis.ubo.UboState
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AboutSettingsTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    @Test
    fun aboutShowsOnlyTheVersionAndOpensTheListing() {
        var opened = 0
        var licences = 0
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
                    versionName = "0.5.0",
                    onOpenPlayListing = { opened++ },
                    onOpenLicences = { licences++ },
                )
            }
        }
        rule.waitForIdle()
        rule.onAllNodesWithText("Check for updates automatically").assertCountEquals(0)
        rule.onAllNodesWithText("Could not check for updates. Try again later.").assertCountEquals(0)
        rule.onAllNodesWithText("Tap to check for updates").assertCountEquals(0)
        rule.onNodeWithText("Orbis 0.5.0").performScrollTo().performClick()
        assertEquals(1, opened)
        assertEquals(0, licences)
        rule.onNodeWithText("Open-source licences").performScrollTo().performClick()
        assertEquals(1, licences)
        assertEquals(1, opened)
    }
}
