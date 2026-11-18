package com.shibi.orbis.ui.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.shibi.orbis.clear.CLEAR_ON_EXIT_MAX_EXCEPTIONS
import com.shibi.orbis.clear.ClearOnExitPrefs
import com.shibi.orbis.clear.SharedPreferencesClearOnExitPrefs
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import org.junit.Assert.assertEquals
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
class ClearOnExitScreenTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var prefs: ClearOnExitPrefs

    @Before
    fun setUp() {
        val sp = context.getSharedPreferences(SharedPreferencesClearOnExitPrefs.FILE_NAME, Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        prefs = SharedPreferencesClearOnExitPrefs(sp)
    }

    private fun show() {
        rule.setContent { MaterialTheme { ClearOnExitScreen(prefs = prefs, onBack = {}) } }
    }

    private fun typeAndAdd(text: String) {
        rule.onNode(hasSetTextAction()).performTextInput(text)
        rule.onNodeWithText("Add").performClick()
    }

    @Test
    fun urlIsStoredAsTheBareDomainInLowerCase() {
        val result = addClearOnExitSite(emptySet(), "https://Www.Example.com/path?x=1")
        assertEquals(AddSiteResult.Added("example.com", setOf("example.com")), result)
    }

    @Test
    fun notAHostNameIsRefused() {
        for (input in listOf("not a site", "", "   ", "http://", "192.168.0.1", "localhost")) {
            assertEquals("input '$input'", AddSiteResult.Invalid, addClearOnExitSite(emptySet(), input))
        }
    }

    @Test
    fun duplicateIsIgnoredAndFullListRefusesNewSites() {
        assertEquals(AddSiteResult.Duplicate, addClearOnExitSite(setOf("example.com"), "EXAMPLE.com"))
        val full = (1..CLEAR_ON_EXIT_MAX_EXCEPTIONS).map { "site$it.com" }.toSet()
        assertEquals(AddSiteResult.Full, addClearOnExitSite(full, "new.com"))
        assertEquals(AddSiteResult.Duplicate, addClearOnExitSite(full, "site1.com"))
    }

    @Test
    fun switchIsOffOnAFreshInstall() {
        assertFalse(prefs.enabled)
        show()
        rule.onNodeWithText("Clear browsing data when Orbis is closed").assertExists()
        assertFalse(prefs.enabled)
    }

    @Test
    fun switchStoresTheChoice() {
        show()
        rule.onNode(isToggleable()).performClick()
        assertTrue(prefs.enabled)
    }

    @Test
    fun addingTheSameSiteTwiceLeavesOneRow() {
        show()
        typeAndAdd("https://Www.Example.com/path?x=1")
        typeAndAdd("example.com")
        rule.onAllNodesWithText("example.com").assertCountEquals(1)
        assertEquals(setOf("example.com"), prefs.exceptions)
    }

    @Test
    fun badInputShowsTheErrorAndStoresNothing() {
        show()
        typeAndAdd("not a site")
        rule.onNodeWithText("Enter a site name, like example.com").assertExists()
        assertTrue(prefs.exceptions.isEmpty())
    }

    @Test
    fun removeButtonNamesTheSiteAndRemovesIt() {
        prefs.exceptions = setOf("example.com", "other.org")
        show()
        rule.onNodeWithContentDescription("Remove example.com").performClick()
        rule.onAllNodesWithText("example.com").assertCountEquals(0)
        assertEquals(setOf("other.org"), prefs.exceptions)
    }

    @Test
    fun fullListDisablesAddAndSaysSo() {
        prefs.exceptions = (1..CLEAR_ON_EXIT_MAX_EXCEPTIONS).map { "site$it.com" }.toSet()
        show()
        rule.onNodeWithText("Add").assertIsNotEnabled()
        rule.onNodeWithText("The list is full.").assertExists()
    }

    @Test
    fun noteUnderSitesToKeepIsExact() {
        show()
        rule.onNodeWithText(
            "Orbis keeps these sites' cookies, so you usually stay signed in. Their other stored data is still cleared.",
        ).assertExists()
    }
}
