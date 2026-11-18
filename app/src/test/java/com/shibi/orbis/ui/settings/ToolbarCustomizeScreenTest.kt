package com.shibi.orbis.ui.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.shibi.orbis.settings.SharedPreferencesToolbarPrefs
import com.shibi.orbis.settings.ToolbarButton
import com.shibi.orbis.settings.ToolbarSettings
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import com.shibi.orbis.ui.browser.DEFAULT_MENU_ORDER
import com.shibi.orbis.ui.browser.MenuAction
import org.junit.Assert.assertEquals
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
class ToolbarCustomizeScreenTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var settings: ToolbarSettings

    @Before
    fun setUp() {
        val sp = context.getSharedPreferences(SharedPreferencesToolbarPrefs.FILE_NAME, Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        settings = ToolbarSettings(SharedPreferencesToolbarPrefs(sp))
        rule.setContent { MaterialTheme { ToolbarCustomizeScreen(settings = settings, onBack = {}) } }
        rule.waitForIdle()
    }

    private fun tap(text: String) {
        rule.onNodeWithText(text).performScrollTo().performClick()
        rule.waitForIdle()
    }

    /** A dialog is not inside the scrolling list. */
    private fun tapDialog(text: String) {
        rule.onNodeWithText(text).performClick()
        rule.waitForIdle()
    }

    private fun tapDescription(description: String) {
        rule.onNodeWithContentDescription(description).performScrollTo().performClick()
        rule.waitForIdle()
    }

    @Test
    fun showsTheTitleTheToolbarButtonRowAndOneRowPerEntry() {
        rule.onNodeWithText("Menu and toolbar").assertExists()
        rule.onNodeWithText("Toolbar button").assertExists()
        rule.onNodeWithText("None").assertExists()
        for (action in DEFAULT_MENU_ORDER.drop(4)) rule.onNodeWithTag(menuRowTag(action)).assertExists()
        rule.onNodeWithText("Reset to default").assertExists()
    }

    @Test
    fun moveDownOnTheFirstRowSwapsRowsOneAndTwo() {
        tapDescription("Move Print or save as PDF down")
        val order = settings.menuOrder.value.drop(4)
        assertEquals(MenuAction.CAST, order[0])
        assertEquals(MenuAction.PRINT, order[1])
        assertEquals(DEFAULT_MENU_ORDER.drop(6), order.drop(2))
        // The screen follows: Print is now the second row, so its Move up is enabled and Cast's is not.
        rule.onNodeWithContentDescription("Move Print or save as PDF up").performScrollTo().assertIsEnabled()
        rule.onNodeWithContentDescription("Move Cast up").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun theFirstRowCannotMoveUpAndTheLastMovableRowCannotMoveDown() {
        rule.onNodeWithContentDescription("Move Print or save as PDF up").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithContentDescription("Move Print or save as PDF down").performScrollTo().assertIsEnabled()
        rule.onNodeWithContentDescription("Move Downloads down").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithContentDescription("Move Downloads up").performScrollTo().assertIsEnabled()
        // Settings is pinned last: it cannot move at all, and it cannot be hidden.
        rule.onNodeWithContentDescription("Move Settings up").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithContentDescription("Move Settings down").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(menuShowTag(MenuAction.SETTINGS)).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun theShowCheckboxHidesAndRestoresAnEntry() {
        rule.onNodeWithTag(menuShowTag(MenuAction.HISTORY)).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(setOf(MenuAction.HISTORY), settings.menuHidden.value)
        rule.onNodeWithTag(menuShowTag(MenuAction.HISTORY)).performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(settings.menuHidden.value.isEmpty())
    }

    @Test
    fun theToolbarButtonRowOffersNoneAndTheTenChoices() {
        tap("Toolbar button")
        val labels = listOf(
            "None", "New tab", "Reload", "Share", "Bookmark this page", "Find in page", "Home", "Forward", "Reader view", "Desktop site", "Duplicate tab",
        )
        for (label in labels) assertTrue(label, rule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("Reload").performClick()
        rule.waitForIdle()
        assertEquals(ToolbarButton.RELOAD, settings.toolbarButton.value)
        // The dialog is closed and the row names the choice.
        rule.onNodeWithText("Reload").assertExists()
        rule.onAllNodesWithText("Share").assertCountEquals(0)
    }

    @Test
    fun resetAsksFirstAndThenRestoresTheDefaults() {
        settings.moveDown(MenuAction.PRINT)
        settings.setShown(MenuAction.CAST, false)
        settings.setToolbarButton(ToolbarButton.SHARE)
        rule.waitForIdle()
        tap("Reset to default")
        rule.onNodeWithText("Reset the menu and toolbar?").assertExists()
        tapDialog("Cancel")
        assertEquals(ToolbarButton.SHARE, settings.toolbarButton.value)
        tap("Reset to default")
        tapDialog("Reset")
        assertEquals(DEFAULT_MENU_ORDER, settings.menuOrder.value)
        assertTrue(settings.menuHidden.value.isEmpty())
        assertNull(settings.toolbarButton.value)
    }
}
