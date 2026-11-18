package com.shibi.orbis.ui.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shibi.orbis.engine.AddonAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The page menu's "Add-ons" entry and the list it opens, wired as BrowserScreen wires them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AddonsMenuTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val web = MenuPageState(
        hasSession = true,
        sessionIsPrivate = false,
        url = "https://example.com/page",
        isLoading = false,
        canGoForward = false,
        readerable = false,
        translation = null,
        desktopMode = false,
        bookmarked = false,
        casting = false,
    )

    private val twoActions =
        listOf(
            AddonAction("addon@darkreader.org", "Dark Reader", enabled = true, badgeText = null),
            AddonAction("sponsorBlocker@ajay.app", "SponsorBlock", enabled = true, badgeText = "3"),
        )

    private val clicked = mutableListOf<String>()

    private fun show(actions: List<AddonAction>, page: MenuPageState = web) {
        rule.setContent {
            var menuOpen by remember { mutableStateOf(true) }
            var addonsOpen by remember { mutableStateOf(false) }
            MaterialTheme {
                Surface {
                    BrowserMenu(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        order = DEFAULT_MENU_ORDER,
                        stateOf = { action, window -> menuItemState(action, page.copy(hasAddonActions = actions.isNotEmpty()), window) },
                        onAction = { if (it == MenuAction.ADDONS) addonsOpen = true },
                    )
                    AddonActionsMenu(
                        expanded = addonsOpen,
                        actions = actions,
                        onDismiss = { addonsOpen = false },
                        onAction = { clicked += it },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test(timeout = 60_000)
    fun withNoActionsTheMenuHasNoAddonsEntry() {
        show(emptyList())
        rule.onNodeWithText("Desktop site").assertExists()
        rule.onNodeWithText("Add-ons").assertDoesNotExist()
    }

    @Test(timeout = 60_000)
    fun withTwoActionsTheListShowsBothNamesAndATapClicksOneAndClosesTheList() {
        show(twoActions)
        rule.onNodeWithText("Add-ons").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Dark Reader").assertExists()
        rule.onNodeWithText("SponsorBlock").assertExists()
        rule.onNodeWithText("3").assertExists()
        rule.onNodeWithText("SponsorBlock").performClick()
        rule.waitForIdle()
        assertEquals(listOf("sponsorBlocker@ajay.app"), clicked)
        rule.onNodeWithTag(ADDON_ACTIONS_TAG).assertDoesNotExist()
    }

    @Test(timeout = 60_000)
    fun theEntryIsDisabledWithoutASessionAsOnALockedPrivateTab() {
        show(twoActions, page = web.copy(hasSession = false, url = ""))
        rule.onNodeWithText("Add-ons").assertIsNotEnabled()
    }
}
