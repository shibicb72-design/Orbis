package com.shibi.orbis.ui.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shibi.orbis.suggest.Suggestion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SuggestionListComposeTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    @Test
    fun pickingARowHandsItsSuggestionBack() {
        val picked = mutableListOf<Suggestion>()
        val page = Suggestion.Page("https://weather.gov/", "National Weather Service", bookmarked = true)
        val search = Suggestion.Search("weather radar")
        rule.setContent { MaterialTheme { SuggestionList(listOf(page, search), onPick = { picked += it }) } }
        rule.onNodeWithText("National Weather Service").performClick()
        rule.onNodeWithText("weather radar").performClick()
        assertEquals(listOf(page, search), picked)
        rule.onNodeWithText("https://weather.gov/").assertExists()
    }

    @Test
    fun nothingIsDrawnForNoSuggestions() {
        rule.setContent { MaterialTheme { SuggestionList(emptyList(), onPick = {}) } }
        assertEquals(0, rule.onAllNodesWithTag(SUGGESTIONS_TAG).fetchSemanticsNodes().size)
    }
}
