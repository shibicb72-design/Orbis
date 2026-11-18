package com.shibi.orbis.ui.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.shibi.orbis.engine.AddResult
import com.shibi.orbis.engine.MAX_CUSTOM_ENGINES
import com.shibi.orbis.engine.SearchEngine
import com.shibi.orbis.engine.SearchEngineSettings
import com.shibi.orbis.engine.SearchProvider
import com.shibi.orbis.engine.SharedPreferencesSearchEnginePrefs
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SearchEnginesScreenTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var settings: SearchEngineSettings

    @Before
    fun setUp() {
        val sp = context.getSharedPreferences(SharedPreferencesSearchEnginePrefs.FILE_NAME, Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        settings = SearchEngineSettings(SharedPreferencesSearchEnginePrefs(sp))
    }

    private fun show() {
        rule.setContent { MaterialTheme { SearchEnginesScreen(settings = settings, onBack = {}) } }
        rule.waitForIdle()
    }

    private fun tap(text: String) {
        rule.onNodeWithText(text).performScrollTo().performClick()
        rule.waitForIdle()
    }

    /** A dialog button: the dialog has no scrolling parent. */
    private fun press(text: String) {
        rule.onNodeWithText(text).performClick()
        rule.waitForIdle()
    }

    private fun fill(name: String, address: String) {
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextReplacement(name)
        fields[1].performTextReplacement(address)
        rule.waitForIdle()
    }

    /** Same SharedPreferences file, a new settings object: what a restart of Orbis would read. */
    private fun reread() =
        SearchEngineSettings(
            SharedPreferencesSearchEnginePrefs(
                context.getSharedPreferences(SharedPreferencesSearchEnginePrefs.FILE_NAME, Context.MODE_PRIVATE),
            ),
        )

    @Test
    fun listsTheBuiltInsWithTheNoteAndPickingOneSelectsIt() {
        show()
        for (label in listOf("DuckDuckGo", "Google", "Bing", "Brave", "Startpage")) rule.onNodeWithText(label).assertExists()
        rule.onNodeWithText("A search engine you add receives everything you search for from the address bar.", substring = true)
            .assertExists()
        rule.onAllNodesWithText("Remove").assertCountEquals(0)
        tap("Bing")
        assertEquals(SearchProvider.BuiltIn(SearchEngine.BING), settings.selected.value)
        assertEquals(SearchProvider.BuiltIn(SearchEngine.BING), reread().selected.value)
    }

    @Test
    fun addSelectAndRemoveACustomEngine() {
        show()
        tap("Add search engine")
        rule.onNodeWithText("Address with %s in place of the search words").assertExists()
        fill("My search", "https://s.example/?q=%s")
        press("Add")
        rule.onNodeWithText("My search").assertExists()
        rule.onNodeWithText("https://s.example/?q=%s").assertExists()
        // Adding does not change the choice.
        assertEquals(SearchProvider.DEFAULT, settings.selected.value)
        tap("My search")
        rule.onNodeWithText("My search").assertIsSelected()
        val stored = reread().selected.value
        assertTrue(stored is SearchProvider.Custom)
        assertEquals("https://s.example/?q=a+b%26c", stored.searchUrl("a b&c"))
        tap("Remove")
        rule.onAllNodesWithText("My search").assertCountEquals(0)
        assertEquals(SearchProvider.DEFAULT, settings.selected.value)
        assertEquals(SearchProvider.DEFAULT, reread().selected.value)
    }

    @Test
    fun theDialogShowsEachErrorAndAddsNothing() {
        show()
        tap("Add search engine")
        val cases =
            listOf(
                Triple("Mine", "http://s.example/?q=%s", "The address must start with https://"),
                Triple("Mine", "https://s.example/?q=", "Put %s once where the search words go"),
                Triple("Mine", "https://s.example/?q=%s&r=%s", "Put %s once where the search words go"),
                Triple("", "https://s.example/?q=%s", "Enter a name"),
                Triple("Google", "https://s.example/?q=%s", "That name is already used"),
            )
        for ((name, address, message) in cases) {
            fill(name, address)
            press("Add")
            rule.onNodeWithText(message).assertExists()
        }
        assertTrue(settings.custom.value.isEmpty())
        press("Cancel")
        rule.onAllNodesWithText("Name").assertCountEquals(0)
        assertTrue(settings.custom.value.isEmpty())
    }

    @Test
    fun atTheLimitTheAddButtonIsOffAndSaysWhy() {
        repeat(MAX_CUSTOM_ENGINES) { assertTrue(settings.addCustom("E$it", "https://e$it.example/?q=%s") is AddResult.Added) }
        show()
        rule.onNodeWithText("Add search engine").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("You can add up to 10 search engines").performScrollTo().assertExists()
    }
}
