package com.shibi.orbis.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.shibi.orbis.downloads.DownloadManager
import com.shibi.orbis.downloads.DownloadStore
import com.shibi.orbis.engine.SearchEngine
import com.shibi.orbis.settings.BrowserSettings
import com.shibi.orbis.settings.SharedPreferencesBrowserPrefs
import com.shibi.orbis.torrent.SharedPreferencesTorrentPrefs
import com.shibi.orbis.torrent.TorrentSettingsStore
import com.shibi.orbis.ubo.UboState
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import com.shibi.orbis.ui.downloads.DownloadsScreen
import com.shibi.orbis.ui.theme.AppThemes
import com.shibi.orbis.ui.theme.AuroraTheme
import com.shibi.orbis.ui.theme.SCENE_LAYER_TAG
import com.shibi.orbis.ui.theme.SceneHost
import com.shibi.orbis.ui.theme.SceneScreen
import com.shibi.orbis.ui.theme.SceneTestHost
import com.shibi.orbis.ui.theme.argb
import com.shibi.orbis.ui.theme.contrastOf
import com.shibi.orbis.ui.theme.frames
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The secondary screens that existing tests already know how to compose (Settings, Downloads, Licences, Torrent
 * settings), wrapped in SceneScreen as in OrbisRoot: an animated theme shows one moving scene layer, Orbis Teal shows
 * none and draws what it drew before, and dark mode never leaves dark text on a dark background.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SecondarySceneTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    @get:Rule(order = 2)
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val host = SceneHost()
    private val scopes = mutableListOf<CoroutineScope>()
    private lateinit var browser: BrowserSettings
    private lateinit var torrentStore: TorrentSettingsStore

    @Before
    fun setUp() {
        val browserPrefs = context.getSharedPreferences("secondary_scene_test_browser", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val torrentPrefs = context.getSharedPreferences("secondary_scene_test_torrent", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        browser = BrowserSettings(SharedPreferencesBrowserPrefs(browserPrefs))
        torrentStore = TorrentSettingsStore(torrentPrefs)
        rule.mainClock.autoAdvance = false
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private enum class Screen { Settings, Downloads, Licences, Torrents }

    private fun downloads(): DownloadManager {
        val store = DownloadStore(File(tmp.root, "downloads_index.json"))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher()).also { scopes += it }
        return DownloadManager(
            dir = { File(tmp.root, "downloads") },
            store = store,
            scope = scope,
            io = StandardTestDispatcher(),
            log = {},
            now = { 0L },
        ).also { it.restore() }
    }

    @Composable
    private fun TheScreen(screen: Screen, manager: DownloadManager) {
        when (screen) {
            Screen.Settings ->
                SettingsScreen(
                    uboStateFlow = MutableStateFlow(UboState.NotInstalled),
                    searchEngineFlow = MutableStateFlow(SearchEngine.DEFAULT),
                    onSelectSearchEngine = {},
                    homepageFlow = MutableStateFlow(null),
                    onSetHomepage = {},
                    searchSuggestionsFlow = MutableStateFlow(true),
                    onSetSearchSuggestions = {},
                    textZoomFlow = MutableStateFlow(100),
                    onSetTextZoom = {},
                    forceZoomFlow = MutableStateFlow(false),
                    onSetForceZoom = {},
                    onBack = {},
                    onOpenErrorLog = {},
                    onOpenUbo = {},
                    onOpenSitePermissions = {},
                    onOpenClearData = {},
                    versionName = "0.5.0",
                    onOpenLicences = {},
                )
            Screen.Downloads -> DownloadsScreen(manager = manager, torrents = null, onBack = {})
            Screen.Licences -> LicencesScreen(onBack = {})
            Screen.Torrents -> TorrentSettingsScreen(store = torrentStore, browser = browser, onBack = {})
        }
    }

    private var wrapped by mutableStateOf(true)

    private fun show(screen: Screen, theme: com.shibi.orbis.ui.theme.AppTheme, dark: Boolean) {
        val manager = downloads()
        rule.setContent {
            SceneTestHost(host, theme, dark) {
                Box(Modifier.fillMaxSize().testTag(ROOT)) {
                    if (wrapped) {
                        SceneScreen { TheScreen(screen, manager) }
                    } else {
                        // What the route was before: the screen's own Scaffold painted the background.
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { TheScreen(screen, manager) }
                    }
                }
            }
        }
        rule.frames(3)
    }

    private fun layers() = rule.onAllNodesWithTag(SCENE_LAYER_TAG).fetchSemanticsNodes().size

    private fun root(): IntArray = rule.onNodeWithTag(ROOT).captureToImage().argb()

    private fun movesOn(screen: Screen) {
        show(screen, AuroraTheme, dark = true)
        assertEquals("$screen: one scene layer", 1, layers())
        val a = root()
        rule.frames(40)
        val b = root()
        assertFalse("$screen: the scene moves behind the screen", a.contentEquals(b))
    }

    private fun tealShowsNoScene(screen: Screen) {
        show(screen, AppThemes.Default, dark = false)
        assertEquals("$screen: no scene layer in Orbis Teal", 0, layers())
        val a = root()
        rule.frames(40)
        assertArrayEquals("$screen: nothing moves in Orbis Teal", a, root())
    }

    /**
     * No text of the screen is dark on dark: in each Text node the pixel farthest from the node's own background keeps
     * 3:1 against it (anti-aliasing keeps small glyphs short of full strength; dark on dark would sit near 1:1).
     */
    private fun noDarkTextOnDark(screen: Screen, theme: com.shibi.orbis.ui.theme.AppTheme) {
        show(screen, theme, dark = true)
        rule.frames(5)
        val rootBounds = rule.onNodeWithTag(ROOT).fetchSemanticsNode().boundsInRoot
        val texts =
            rule.onAllNodes(SemanticsMatcher("has text") { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.isNotBlank() } == true })
                .fetchSemanticsNodes()
                .filter { it.boundsInRoot.width > 4f && it.boundsInRoot.height > 4f }
                .filter { rootBounds.contains(it.boundsInRoot.topLeft) && rootBounds.contains(it.boundsInRoot.bottomRight) }
        assertTrue("$screen shows text", texts.isNotEmpty())
        val whole = rule.onNodeWithTag(ROOT).captureToImage().toPixelMapForTest()
        for (n in texts) {
            val b = n.boundsInRoot
            val pixels = ArrayList<Color>()
            for (y in b.top.toInt() until b.bottom.toInt()) for (x in b.left.toInt() until b.right.toInt()) pixels += whole(x, y)
            val bg = pixels.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
            val far = pixels.maxByOrNull { contrastOf(it, bg) }!!
            val shown = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            assertTrue("$screen: text \"$shown\" is ${contrastOf(far, bg)} against $bg", contrastOf(far, bg) >= 3.0)
        }
    }

    private fun androidx.compose.ui.graphics.ImageBitmap.toPixelMapForTest(): (Int, Int) -> Color {
        val map = toPixelMap()
        return { x, y -> map[x, y] }
    }

    @Test fun settingsMovesOnAnAnimatedTheme() = movesOn(Screen.Settings)

    @Test fun downloadsMovesOnAnAnimatedTheme() = movesOn(Screen.Downloads)

    @Test fun licencesMovesOnAnAnimatedTheme() = movesOn(Screen.Licences)

    @Test fun torrentsMovesOnAnAnimatedTheme() = movesOn(Screen.Torrents)

    @Test fun settingsShowsNoSceneInTeal() = tealShowsNoScene(Screen.Settings)

    @Test fun downloadsShowsNoSceneInTeal() = tealShowsNoScene(Screen.Downloads)

    @Test fun licencesShowsNoSceneInTeal() = tealShowsNoScene(Screen.Licences)

    @Test fun torrentsShowsNoSceneInTeal() = tealShowsNoScene(Screen.Torrents)

    /** The wrapper adds nothing for a static theme: the same pixels with and without it. */
    private fun tealPixelsMatchWithoutTheWrapper(screen: Screen) {
        show(screen, AppThemes.Default, dark = false)
        val withWrapper = root()
        wrapped = false
        rule.frames(3)
        assertArrayEquals("$screen", withWrapper, root())
    }

    @Test fun settingsTealPixelsAreUnchanged() = tealPixelsMatchWithoutTheWrapper(Screen.Settings)

    @Test fun downloadsTealPixelsAreUnchanged() = tealPixelsMatchWithoutTheWrapper(Screen.Downloads)

    @Test fun licencesTealPixelsAreUnchanged() = tealPixelsMatchWithoutTheWrapper(Screen.Licences)

    @Test fun torrentsTealPixelsAreUnchanged() = tealPixelsMatchWithoutTheWrapper(Screen.Torrents)

    @Test fun settingsKeepsReadableTextInDarkMode() = noDarkTextOnDark(Screen.Settings, AuroraTheme)

    @Test fun downloadsKeepsReadableTextInDarkMode() = noDarkTextOnDark(Screen.Downloads, AuroraTheme)

    @Test fun licencesKeepsReadableTextInDarkMode() = noDarkTextOnDark(Screen.Licences, AuroraTheme)

    @Test fun torrentsKeepsReadableTextInDarkMode() = noDarkTextOnDark(Screen.Torrents, AuroraTheme)

    /** Bare text in the wrapper, and in a transparent Scaffold like the ten screens use, is onBackground-coloured. */
    @Test
    fun bareTextOnTheSceneIsOnBackgroundColouredInDarkMode() {
        val scheme = AuroraTheme.dark.scheme
        rule.setContent {
            SceneTestHost(host, AuroraTheme, dark = true) {
                Box(Modifier.fillMaxSize().testTag(ROOT)) {
                    SceneScreen {
                        Scaffold(
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onBackground,
                        ) { padding -> Text("Hello", fontSize = 60.sp, modifier = Modifier.padding(padding)) }
                    }
                }
            }
        }
        rule.frames(3)
        val want = scheme.onBackground.toArgb()
        fun near(p: Int) = listOf(16, 8, 0).all { abs(((p ushr it) and 0xFF) - ((want ushr it) and 0xFF)) <= 3 }
        assertTrue("some text pixel is onBackground", root().any { near(it) })
        assertTrue("onBackground is light on this theme's dark background", contrastOf(scheme.onBackground, scheme.background) >= 4.5)
    }

    private companion object {
        const val ROOT = "secondary_root"
    }
}
