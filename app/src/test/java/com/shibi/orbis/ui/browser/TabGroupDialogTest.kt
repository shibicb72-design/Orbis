package com.shibi.orbis.ui.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.shibi.orbis.tabs.MAX_TAB_GROUP_NAME_CHARS
import com.shibi.orbis.tabs.TabGroupColor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The name and colour dialog of the tab groups, on its own. Opened from inside the tab sheet, a dialog with a text field
 * never goes idle under Robolectric (the colour and close dialogs do), so the text field is tested here and the device
 * check covers the real flow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TabGroupDialogTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val events = mutableListOf<String>()

    private fun settle() = rule.waitForIdle()

    private fun showDialog(askName: Boolean, askColor: Boolean, initialName: String = "", confirmLabel: String = "Go") {
        rule.setContent {
            MaterialTheme {
                TabGroupEditDialog(
                    title = "Edit",
                    confirmLabel = confirmLabel,
                    initialName = initialName,
                    initialColor = TabGroupColor.BLUE,
                    askName = askName,
                    askColor = askColor,
                    onConfirm = { name, color -> events += "confirm:$name:$color" },
                    onDismiss = { events += "dismiss" },
                )
            }
        }
        settle()
    }

    @Test
    fun createIsDisabledForAnEmptyName() {
        showDialog(askName = true, askColor = true, confirmLabel = "Create")
        rule.onNodeWithText("Create").assertIsNotEnabled()
        rule.onNodeWithText("Group name").assertExists()
    }

    @Test
    fun theDialogEnablesConfirmOnlyForAValidNameAndReturnsTheColour() {
        showDialog(askName = true, askColor = true)
        rule.onNodeWithText("Go").assertIsNotEnabled()
        rule.onNodeWithTag(GROUP_NAME_FIELD_TAG).performTextInput("   ")
        settle()
        rule.onNodeWithText("Go").assertIsNotEnabled()
        rule.onNodeWithTag(GROUP_NAME_FIELD_TAG).performTextInput("Reading")
        settle()
        rule.onNodeWithText("Go").assertIsEnabled()
        rule.onNodeWithText("Green").performClick()
        settle()
        rule.onNodeWithText("Go").performClick()
        settle()
        assertEquals(listOf("confirm:   Reading:GREEN"), events)
    }

    @Test
    fun theNameFieldStopsAtTheLimit() {
        showDialog(askName = true, askColor = false)
        rule.onNodeWithTag(GROUP_NAME_FIELD_TAG).performTextInput("x".repeat(MAX_TAB_GROUP_NAME_CHARS + 10))
        settle()
        rule.onNodeWithText("Go").performClick()
        settle()
        assertEquals(listOf("confirm:" + "x".repeat(MAX_TAB_GROUP_NAME_CHARS) + ":BLUE"), events)
    }

    @Test
    fun theColourDialogHasNoNameField() {
        showDialog(askName = false, askColor = true, initialName = "Work")
        rule.onNodeWithTag(GROUP_NAME_FIELD_TAG).assertDoesNotExist()
        rule.onNodeWithText("Go").assertIsEnabled()
        for (name in listOf("Grey", "Blue", "Red", "Yellow", "Green", "Pink", "Purple", "Cyan")) {
            rule.onNodeWithText(name).assertExists()
        }
    }
}
