package com.shibi.orbis.ui.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.shibi.orbis.engine.TranslationInfo
import com.shibi.orbis.settings.ToolbarButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The page menu as the user sees it. The expected lists were written from the inline menu in BrowserScreen.kt at
 * 1551ad9 (entry order, strings, conditions), not generated from the new code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BrowserMenuTest {
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
        readerable = true,
        translation = null,
        desktopMode = false,
        bookmarked = false,
        casting = false,
    )
    private val startPage = web.copy(url = "", readerable = false)
    private val privateTab = web.copy(sessionIsPrivate = true)

    private val narrow = MenuWindow(widthDp = 400, inMultiWindow = false)

    private fun show(
        page: MenuPageState,
        window: MenuWindow = narrow,
        order: List<MenuAction> = DEFAULT_MENU_ORDER,
        hidden: Set<MenuAction> = emptySet(),
        onAction: (MenuAction) -> Unit = {},
    ) {
        rule.setContent {
            MaterialTheme {
                Surface {
                    BrowserMenu(
                        expanded = true,
                        onDismiss = {},
                        order = order,
                        hidden = hidden,
                        stateOf = { action, _ -> menuItemState(action, page, window) },
                        onAction = onAction,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    /** The text of every text entry, top to bottom. */
    private fun entryTexts(): List<String> =
        rule.onAllNodes(SemanticsMatcher("has text") { it.config.getOrNull(SemanticsProperties.Text) != null })
            .fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }

    private fun iconDescriptions(): List<String> =
        rule.onAllNodes(SemanticsMatcher("has description") { it.config.getOrNull(SemanticsProperties.ContentDescription) != null })
            .fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.ContentDescription].joinToString("") }

    private val pageEntries = listOf(
        "Print or save as PDF",
        "Cast",
        "Add to Home screen",
        "Reader view",
        "Find in page",
        "Site settings",
        "Generate password",
        "Desktop site",
    )
    private val restEntries = listOf(
        "New tab",
        "New private tab",
        "Duplicate tab",
        "Scan QR code",
        "Bookmarks",
        "History",
        "Downloads",
        "Settings",
    )

    @Test
    fun webPageMenu() {
        show(web)
        assertEquals(pageEntries + restEntries, entryTexts())
        assertEquals(listOf("Home", "Forward", "Add bookmark", "Share"), iconDescriptions())
    }

    @Test
    fun startPageMenu() {
        show(startPage)
        assertEquals(pageEntries + restEntries, entryTexts())
        rule.onNodeWithText("Print or save as PDF").assertIsNotEnabled()
        rule.onNodeWithText("Site settings").assertIsNotEnabled()
        rule.onNodeWithText("Reader view").assertIsNotEnabled()
        rule.onNodeWithText("Find in page").assertIsEnabled()
        rule.onNodeWithText("Duplicate tab").assertIsNotEnabled()
    }

    @Test
    fun duplicateTabIsEnabledOnlyForAWebPageWithASession() {
        show(web)
        rule.onNodeWithText("Duplicate tab").assertIsEnabled()
        assertFalse(menuItemState(MenuAction.DUPLICATE_TAB, web.copy(hasSession = false), narrow).enabled)
        assertFalse(menuItemState(MenuAction.DUPLICATE_TAB, web.copy(url = "file:///x.pdf"), narrow).enabled)
        assertTrue(menuItemState(MenuAction.DUPLICATE_TAB, privateTab, narrow).enabled)
    }

    @Test
    fun privateTabMenu() {
        show(privateTab)
        assertEquals(pageEntries + restEntries, entryTexts())
        rule.onNodeWithText("Add to Home screen").assertIsNotEnabled()
        rule.onNodeWithText("Print or save as PDF").assertIsEnabled()
    }

    @Test
    fun splitScreenAndTranslateEntriesAppearInPlace() {
        val translatable = web.copy(
            translation = TranslationInfo(pageLanguage = "fr", userLanguage = "en", pageLanguageSupported = true),
        )
        show(translatable, window = MenuWindow(widthDp = 800, inMultiWindow = false))
        assertEquals(
            listOf(
                "Print or save as PDF",
                "Cast",
                "Add to Home screen",
                "Open in split screen",
                "Reader view",
                "Translate page",
                "Find in page",
                "Site settings",
                "Generate password",
                "Desktop site",
            ) + restEntries,
            entryTexts(),
        )
    }

    @Test
    fun translatedPageOffersShowOriginal() {
        val translated = web.copy(
            translation = TranslationInfo("fr", "en", pageLanguageSupported = true, translatedTo = "en"),
        )
        show(translated)
        assertTrue("Show original" in entryTexts())
        assertFalse("Translate page" in entryTexts())
    }

    @Test
    fun choosingAnEntryReportsItsAction() {
        val chosen = mutableListOf<MenuAction>()
        show(web, onAction = { chosen += it })
        rule.onNodeWithText("History").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(listOf(MenuAction.HISTORY), chosen)
    }

    @Test
    fun splitScreenIsHiddenOnANarrowWindowOutsideMultiWindow() {
        assertFalse(menuItemState(MenuAction.SPLIT_SCREEN, web, MenuWindow(400, inMultiWindow = false)).visible)
        assertTrue(menuItemState(MenuAction.SPLIT_SCREEN, web, MenuWindow(400, inMultiWindow = true)).visible)
        assertTrue(menuItemState(MenuAction.SPLIT_SCREEN, web, MenuWindow(600, inMultiWindow = false)).visible)
    }

    @Test
    fun translateIsHiddenWhenThePageCannotBeTranslated() {
        assertFalse(menuItemState(MenuAction.TRANSLATE, web, narrow).visible)
        val sameLanguage = web.copy(translation = TranslationInfo("en", "en", pageLanguageSupported = true))
        assertFalse(menuItemState(MenuAction.TRANSLATE, sameLanguage, narrow).visible)
        val noSession = web.copy(
            hasSession = false,
            translation = TranslationInfo("fr", "en", pageLanguageSupported = true),
        )
        assertFalse(menuItemState(MenuAction.TRANSLATE, noSession, narrow).visible)
    }

    @Test
    fun forwardIsDisabledWhenThereIsNothingToGoForwardTo() {
        assertFalse(menuItemState(MenuAction.FORWARD, web, narrow).enabled)
        assertTrue(menuItemState(MenuAction.FORWARD, web.copy(canGoForward = true), narrow).enabled)
    }

    @Test
    fun aHiddenActionIsNotInTheMenu() {
        show(web, hidden = setOf(MenuAction.HISTORY, MenuAction.PRINT))
        assertEquals((pageEntries + restEntries) - setOf("History", "Print or save as PDF"), entryTexts())
    }

    @Test
    fun settingsIsShownLastEvenIfHiddenAndEvenIfTheOrderPutsItFirst() {
        val order = listOf(MenuAction.SETTINGS) + DEFAULT_MENU_ORDER.take(4) + DEFAULT_MENU_ORDER.drop(4).filter { it != MenuAction.SETTINGS }.reversed()
        show(web, order = order, hidden = setOf(MenuAction.SETTINGS))
        assertEquals(DEFAULT_MENU_ORDER.last(), pinnedMenuOrder(order).last())
        assertEquals("Settings", entryTexts().last())
        // The icon row stays first and unchanged.
        assertEquals(listOf("Home", "Forward", "Add bookmark", "Share"), iconDescriptions())
        assertEquals((pageEntries + restEntries).reversed().filter { it != "Settings" } + "Settings", entryTexts())
    }

    @Test
    fun theMenuFollowsTheGivenOrder() {
        val swapped = DEFAULT_MENU_ORDER.toMutableList().also {
            val a = it.indexOf(MenuAction.HISTORY)
            it[a] = MenuAction.DOWNLOADS
            it[a + 1] = MenuAction.HISTORY
        }
        show(web, order = swapped)
        assertEquals(pageEntries + restEntries.map { if (it == "History") "Downloads" else if (it == "Downloads") "History" else it }, entryTexts())
    }

    @Test
    fun dividersAreAsTodayForTheShippedOrderAndTwoForAnyOther() {
        val shipped = menuBlocks(DEFAULT_MENU_ORDER, shippedOrder = true)
        assertEquals(
            listOf(MenuGroup.QUICK, MenuGroup.PAGE, MenuGroup.TABS, MenuGroup.LIBRARY, MenuGroup.SETTINGS),
            shipped.map { it.first().group },
        )
        val reversed = pinnedMenuOrder(DEFAULT_MENU_ORDER.reversed())
        val custom = menuBlocks(reversed, shippedOrder = false)
        assertEquals(3, custom.size)
        assertEquals(DEFAULT_MENU_ORDER.take(4).toSet(), custom[0].toSet())
        assertEquals(listOf(MenuAction.SETTINGS), custom[2])
        // Everything hidden but one entry: icon row, entry, Settings; and with no entries left only one divider.
        assertEquals(3, menuBlocks(listOf(MenuAction.HOME, MenuAction.FIND, MenuAction.SETTINGS), shippedOrder = false).size)
        assertEquals(2, menuBlocks(listOf(MenuAction.HOME, MenuAction.SETTINGS), shippedOrder = false).size)
    }

    @Test
    fun theReloadToolbarButtonCallsTheReloadHandlerOnce() {
        var reloads = 0
        val actions = mutableListOf<MenuAction>()
        rule.setContent {
            MaterialTheme {
                ToolbarButtonView(
                    button = ToolbarButton.RELOAD,
                    item = toolbarButtonState(ToolbarButton.RELOAD, web, narrow),
                    onMenuAction = { actions += it },
                    onReload = { reloads++ },
                )
            }
        }
        rule.onNodeWithTag(TOOLBAR_BUTTON_TAG).performClick()
        rule.waitForIdle()
        assertEquals(1, reloads)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun aMenuToolbarButtonRunsTheMenuEntry() {
        var reloads = 0
        val actions = mutableListOf<MenuAction>()
        rule.setContent {
            MaterialTheme {
                ToolbarButtonView(
                    button = ToolbarButton.DUPLICATE_TAB,
                    item = toolbarButtonState(ToolbarButton.DUPLICATE_TAB, web, narrow),
                    onMenuAction = { actions += it },
                    onReload = { reloads++ },
                )
            }
        }
        rule.onNodeWithTag(TOOLBAR_BUTTON_TAG).performClick()
        rule.waitForIdle()
        assertEquals(listOf(MenuAction.DUPLICATE_TAB), actions)
        assertEquals(0, reloads)
    }

    @Test
    fun theToolbarButtonUsesTheMenuEntrysEnabledRule() {
        for (button in ToolbarButton.entries) {
            val action = button.menuAction ?: continue
            for (page in listOf(web, startPage, privateTab, web.copy(hasSession = false), web.copy(canGoForward = true))) {
                assertEquals(
                    "$button",
                    menuItemState(action, page, narrow).enabled,
                    toolbarButtonState(button, page, narrow).enabled,
                )
            }
        }
        assertTrue(toolbarButtonState(ToolbarButton.RELOAD, startPage, narrow).enabled)
        assertFalse(toolbarButtonState(ToolbarButton.RELOAD, web.copy(hasSession = false), narrow).enabled)
        assertFalse(toolbarButtonState(ToolbarButton.FORWARD, web, narrow).enabled)
    }

    @Test
    fun theToolbarButtonIsLeftOutOnlyWhenTheToolbarWrapsOnANarrowWindow() {
        assertFalse(showsToolbarButton(twoRow = true, windowWidthDp = 359))
        assertTrue(showsToolbarButton(twoRow = true, windowWidthDp = 360))
        assertTrue(showsToolbarButton(twoRow = false, windowWidthDp = 320))
    }
}
