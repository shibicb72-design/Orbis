package com.shibi.orbis.ui.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shibi.orbis.privacy.pageHiddenByLock
import com.shibi.orbis.settings.ToolbarButton
import com.shibi.orbis.tabs.TabUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings > Lock private tabs, on screen: the tab switcher, the lock panel and the toolbar button. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h2400dp")
class PrivateLockComposeTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val events = mutableListOf<String>()
    private var locked by mutableStateOf(true)
    private val tabs =
        listOf(
            TabUi(1, "Alpha page", "https://alpha.example/", selected = true),
            TabUi(3, "Secret page", "https://secret.example/", selected = false, isPrivate = true),
        )

    /** Robolectric has no native bitmaps, so the stored preview is a stand-in that only has to exist. */
    private class FakeBitmap : ImageBitmap {
        override val width = 8
        override val height = 8
        override val colorSpace get() = androidx.compose.ui.graphics.colorspace.ColorSpaces.Srgb
        override val hasAlpha = false
        override val config = androidx.compose.ui.graphics.ImageBitmapConfig.Argb8888

        override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) {}

        override fun prepareToDraw() {}
    }

    private val thumbnails = mapOf<Long, ImageBitmap>(3L to FakeBitmap(), 1L to FakeBitmap())

    private fun showSwitcher() {
        rule.setContent {
            MaterialTheme {
                TabSwitcher(
                    tabs = tabs,
                    onSelect = { events += "select:$it" },
                    onClose = { events += "close:$it" },
                    onNewTab = {},
                    onNewPrivateTab = {},
                    onClosePrivateTabs = { events += "closePrivate" },
                    onDismiss = {},
                    thumbnails = thumbnails,
                    privateLocked = locked,
                    onUnlockRequest = { events += "unlock:$it" },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test(timeout = 60_000)
    fun lockedSwitcherHidesAPrivateCardsTitleAndAddress() {
        showSwitcher()
        assertEquals(0, rule.onAllNodesWithText("Secret page").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("secret.example", substring = true).fetchSemanticsNodes().size)
        rule.onNodeWithText("Private tab").assertExists()
        // Normal tabs are untouched.
        rule.onNodeWithText("Alpha page").assertExists()
    }

    private fun previewCount() = rule.onAllNodesWithTag(TAB_PREVIEW_IMAGE_TAG, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test(timeout = 60_000)
    fun aLockedPrivateCardWithAStoredThumbnailDrawsNoImage() {
        showSwitcher()
        // Both tabs have a thumbnail; only the normal tab's is drawn.
        assertEquals(1, previewCount())
    }

    @Test(timeout = 60_000)
    fun anUnlockedPrivateCardDrawsItsThumbnail() {
        locked = false
        showSwitcher()
        assertEquals(2, previewCount())
    }

    @Test(timeout = 60_000)
    fun unlockedSwitcherShowsThePrivateTitle() {
        locked = false
        showSwitcher()
        rule.onNodeWithText("Secret page").assertExists()
    }

    @Test(timeout = 60_000)
    fun tappingALockedPrivateCardAsksToUnlockInsteadOfSelecting() {
        showSwitcher()
        rule.onNodeWithTag(tabRowTag(3)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("unlock:3"), events)
    }

    @Test(timeout = 60_000)
    fun closePrivateTabsStillWorksWhileLocked() {
        showSwitcher()
        rule.onNodeWithText("Close private tabs").performClick()
        rule.waitForIdle()
        assertEquals(listOf("closePrivate"), events)
    }

    @Test(timeout = 60_000)
    fun lockPanelShowsItsTextAndButtons() {
        rule.setContent {
            MaterialTheme { PrivateLockPanel(onUnlock = { events += "unlock" }, onClosePrivate = { events += "close" }) }
        }
        rule.onNodeWithText("Private tabs are locked").assertIsDisplayed()
        rule.onNodeWithText("Unlock").assertIsDisplayed()
        rule.onNodeWithText("Close private tabs").assertIsDisplayed()
        rule.onNodeWithText("Unlock").performClick()
        rule.onNodeWithText("Close private tabs").performClick()
        assertEquals(listOf("unlock", "close"), events)
    }

    private val window = MenuWindow(widthDp = 411, inMultiWindow = false)

    /** What BrowserScreen builds for the menu and the toolbar button, for a private page with a session. */
    private fun pageState(locked: Boolean) =
        MenuPageState(
            hasSession = true,
            sessionIsPrivate = true,
            url = "https://secret.example/",
            isLoading = false,
            canGoForward = true,
            readerable = true,
            translation = null,
            desktopMode = false,
            bookmarked = false,
            casting = false,
        ).whileHidden(pageHiddenByLock(locked, selectedTabIsPrivate = true))

    private fun showToolbarButton(button: ToolbarButton, page: MenuPageState) {
        rule.setContent {
            MaterialTheme {
                ToolbarButtonView(
                    button = button,
                    item = toolbarButtonState(button, page, window),
                    onMenuAction = {},
                    onReload = {},
                )
            }
        }
    }

    @Test(timeout = 60_000)
    fun lockedWithAPrivateTabSelectedTheReloadButtonIsDisabled() {
        showToolbarButton(ToolbarButton.RELOAD, pageState(locked = true))
        rule.onNodeWithTag(TOOLBAR_BUTTON_TAG).assertIsNotEnabled()
    }

    @Test(timeout = 60_000)
    fun lockedWithAPrivateTabSelectedShareIsDisabledInTheButtonAndTheMenu() {
        val page = pageState(locked = true)
        showToolbarButton(ToolbarButton.SHARE, page)
        rule.onNodeWithTag(TOOLBAR_BUTTON_TAG).assertIsNotEnabled()
        val pageActions =
            listOf(
                MenuAction.HOME,
                MenuAction.FORWARD,
                MenuAction.BOOKMARK_PAGE,
                MenuAction.SHARE,
                MenuAction.PRINT,
                MenuAction.CAST,
                MenuAction.READER,
                MenuAction.FIND,
                MenuAction.SITE_SETTINGS,
                MenuAction.DESKTOP_SITE,
                MenuAction.DUPLICATE_TAB,
            )
        for (action in pageActions) assertEquals("$action", false, menuItemState(action, page, window).enabled)
        val otherActions =
            listOf(MenuAction.NEW_TAB, MenuAction.BOOKMARKS, MenuAction.HISTORY, MenuAction.DOWNLOADS, MenuAction.SETTINGS)
        for (action in otherActions) assertEquals("$action", true, menuItemState(action, page, window).enabled)
    }

    @Test(timeout = 60_000)
    fun unlockedTheReloadButtonIsEnabled() {
        showToolbarButton(ToolbarButton.RELOAD, pageState(locked = false))
        rule.onNodeWithTag(TOOLBAR_BUTTON_TAG).assertIsEnabled()
    }
}
