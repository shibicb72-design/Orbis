package com.shibi.orbis.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.shibi.orbis.bookmarks.Bookmark
import com.shibi.orbis.home.TopSite
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class StartPageComposeTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val opened = mutableListOf<String>()
    private var searches = 0
    private var allBookmarks = 0

    private fun start(sites: List<TopSite>, bookmarks: List<Bookmark>) {
        rule.setContent {
            MaterialTheme {
                StartPage(sites, bookmarks, onSearch = { searches++ }, onOpen = { opened += it }, onAllBookmarks = { allBookmarks++ })
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun tilesOpenTheirPagesAndReadAsTitleAndHost() {
        start(
            listOf(TopSite("https://news.test/b", "News", "news.test"), TopSite("https://x.test/", "x.test", "x.test")),
            listOf(Bookmark(1, "https://docs.test/start", "Docs", 1)),
        )
        rule.onNodeWithContentDescription("News, news.test").performClick()
        rule.onNodeWithContentDescription("x.test").performClick()
        rule.onNodeWithContentDescription("Docs, docs.test").performScrollTo().performClick()
        assertEquals(listOf("https://news.test/b", "https://x.test/", "https://docs.test/start"), opened)
        rule.onNodeWithText("All bookmarks").performScrollTo().performClick()
        assertEquals(1, allBookmarks)
    }

    @Test
    fun aTilesActionsPinAndRemoveIt() {
        val pinned = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val site = TopSite("https://news.test/b", "News", "news.test")
        rule.setContent {
            MaterialTheme {
                StartPage(
                    listOf(site), emptyList(), onSearch = {}, onOpen = {}, onAllBookmarks = {},
                    onPin = { pinned += it.url }, onRemove = { removed += it.url },
                )
            }
        }
        val node = rule.onNodeWithContentDescription("News, news.test")
        node.customAction("Pin")
        node.customAction("Remove")
        assertEquals(listOf("https://news.test/b"), pinned)
        assertEquals(listOf("https://news.test/b"), removed)
    }

    @Test
    fun aPinnedTileSaysSoAndOffersUnpin() {
        val unpinned = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                StartPage(
                    listOf(TopSite("https://p.test/", "P", "p.test", pinned = true)), emptyList(), onSearch = {}, onOpen = {}, onAllBookmarks = {},
                    onUnpin = { unpinned += it.url },
                )
            }
        }
        rule.onNodeWithContentDescription("P, p.test, pinned").customAction("Unpin")
        assertEquals(listOf("https://p.test/"), unpinned)
    }

    @Test
    fun theSearchBoxHandsFocusToTheAddressBar() {
        start(emptyList(), emptyList())
        rule.onNodeWithTag(START_SEARCH_TAG).performClick()
        assertEquals(1, searches)
    }

    @Test
    fun aFreshProfileShowsTheHintAndNoEmptySections() {
        start(emptyList(), emptyList())
        rule.onNodeWithText("Search or enter an address to start browsing.").assertExists()
        assertEquals(0, rule.onAllNodesWithText("Top sites").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("All bookmarks").fetchSemanticsNodes().size)
    }

    @Test
    fun atMostEightBookmarksAreShown() {
        start(emptyList(), (1L..12L).map { Bookmark(it, "https://b$it.test/", "B$it", it) })
        rule.onNodeWithContentDescription("B8, b8.test").assertExists()
        assertEquals(0, rule.onAllNodesWithText("B9").fetchSemanticsNodes().size)
    }

    private fun SemanticsNodeInteraction.customAction(label: String) {
        val action = fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }
        rule.runOnIdle { action.action() }
    }
}
