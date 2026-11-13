package com.shibi.orbis.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
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
import com.shibi.orbis.ui.theme.AuroraTheme
import com.shibi.orbis.ui.theme.SCENE_LAYER_TAG
import com.shibi.orbis.ui.theme.SceneHost
import com.shibi.orbis.ui.theme.SceneTestHost
import com.shibi.orbis.ui.theme.argb
import com.shibi.orbis.ui.theme.frames
import com.shibi.orbis.ui.theme.rememberSceneSurface
import com.shibi.orbis.ui.theme.sceneSurfaceRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun withASceneTheStartPageHasOneMovingLayerInsideIt() {
        rule.mainClock.autoAdvance = false
        val host = SceneHost()
        rule.setContent {
            SceneTestHost(host, AuroraTheme, dark = true) {
                val surface = rememberSceneSurface()
                Box(Modifier.fillMaxSize().sceneSurfaceRoot(surface)) {
                    StartPage(SITES, emptyList(), onSearch = {}, onOpen = {}, onAllBookmarks = {}, modifier = Modifier.testTag(PAGE), scene = surface)
                }
            }
        }
        rule.frames(5)
        val layers = rule.onAllNodesWithTag(SCENE_LAYER_TAG, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, layers.size)
        val page = rule.onNodeWithTag(PAGE).fetchSemanticsNode().boundsInRoot
        val layer = layers.single().boundsInRoot
        assertTrue("layer $layer inside the start page $page", layer.left >= page.left && layer.top >= page.top && layer.right <= page.right && layer.bottom <= page.bottom)
        assertTrue("the layer is not empty: $layer", layer.width > 0f && layer.height > 0f)
        val before = rule.onNodeWithTag(PAGE).captureToImage().argb()
        rule.frames(30)
        assertFalse("the scene moves", before.contentEquals(rule.onNodeWithTag(PAGE).captureToImage().argb()))
    }

    @Test
    fun withoutASceneTheStartPageHasNoLayer() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SceneTestHost(SceneHost(), AuroraTheme, dark = true) {
                StartPage(SITES, emptyList(), onSearch = {}, onOpen = {}, onAllBookmarks = {})
            }
        }
        rule.frames(5)
        rule.onNodeWithTag(START_SEARCH_TAG).assertExists()
        assertEquals(0, rule.onAllNodesWithTag(SCENE_LAYER_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    private fun SemanticsNodeInteraction.customAction(label: String) {
        val action = fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }
        rule.runOnIdle { action.action() }
    }

    private companion object {
        const val PAGE = "start_page"
        val SITES = listOf(TopSite("https://news.test/b", "News", "news.test"))
    }
}
