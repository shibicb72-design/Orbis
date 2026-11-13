package com.shibi.orbis.ui.browser

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.shibi.orbis.R
import com.shibi.orbis.home.TopSite
import com.shibi.orbis.tabs.TabUi
import com.shibi.orbis.ui.OrbisEmptyState
import com.shibi.orbis.ui.home.StartPage
import com.shibi.orbis.ui.theme.AnimatedThemes
import com.shibi.orbis.ui.theme.AppTheme
import com.shibi.orbis.ui.theme.NightSkyTheme
import com.shibi.orbis.ui.theme.OpenAmbient
import com.shibi.orbis.ui.theme.SCENE_LAYER_TAG
import com.shibi.orbis.ui.theme.SceneHost
import com.shibi.orbis.ui.theme.SceneTestHost
import com.shibi.orbis.ui.theme.argb
import com.shibi.orbis.ui.theme.contrastOf
import com.shibi.orbis.ui.theme.frames
import com.shibi.orbis.ui.theme.rememberSceneSurface
import com.shibi.orbis.ui.theme.sceneSurfaceRoot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The real [BrowserFrame] with the real [TabStrip] and [StartPage]. A solid #FF00FF box stands in for the engine
 * view; a toolbar row like the browser's stands in for its chrome. The window is 1200 dp wide; a phone is a 412 dp
 * frame inside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w1200dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserFrameSceneTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val host = SceneHost()
    private var theme by mutableStateOf<AppTheme>(NightSkyTheme)
    private var dark by mutableStateOf(true)
    private var userAnimated by mutableStateOf(true)
    private var privateTab by mutableStateOf(false)
    private var showChrome by mutableStateOf(true)
    private var toolbarBottom by mutableStateOf(false)
    private var widthDp by mutableIntStateOf(1200)

    /** False: the same layout with no scene passed down (the reference for what the bars look like without ink). */
    private var withScene by mutableStateOf(true)
    private var startPage by mutableStateOf(false)
    private var tabs by mutableStateOf(
        listOf(
            TabUi(1, "Alpha", "https://alpha.example/", selected = true),
            TabUi(2, "Normal", "https://normal.example/", selected = false),
            TabUi(3, "Secret", "https://secret.example/", selected = false, isPrivate = true),
        ),
    )

    private var chromeCompositions = 0
    private var dashboardCompositions = 0
    private var strings: CountingResources? = null

    @Before
    fun holdTheClock() {
        rule.mainClock.autoAdvance = false
    }

    private fun frames(n: Int) = rule.frames(n)

    private fun show(sheet: Boolean = false) {
        rule.setContent {
            SceneTestHost(host, theme, dark, animate = userAnimated) {
                val base = LocalResources.current
                val counting = remember(base) { CountingResources(base).also { strings = it } }
                CompositionLocalProvider(LocalResources provides counting) { Frame(sheet) }
            }
        }
        frames(5)
    }

    @Composable
    private fun Frame(sheet: Boolean) {
        val scene = rememberSceneSurface(privateTab = privateTab)
        val shown = if (withScene) scene else null
        Box(Modifier.width(widthDp.dp).fillMaxHeight()) {
            Surface(
                Modifier.fillMaxSize().sceneSurfaceRoot(scene),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
                BrowserFrame(
                    scene = shown,
                    showChrome = showChrome,
                    showStrip = showChrome && showsTabStrip(widthDp),
                    toolbarBottom = toolbarBottom,
                    modifier = Modifier.testTag(FRAME_TAG),
                    tabStrip = { TabStrip(tabs = tabs, onSelect = {}, onClose = {}, onNewTab = {}, onDropOpen = {}) },
                    chrome = {
                        SideEffect { chromeCompositions++ }
                        Toolbar()
                    },
                    page = {
                        when {
                            privateTab && startPage -> OrbisEmptyState("Private", Icons.Filled.VisibilityOff)
                            startPage ->
                                StartPage(
                                    topSites = SITES,
                                    bookmarks = emptyList(),
                                    onSearch = {},
                                    onOpen = {},
                                    onAllBookmarks = {},
                                    dashboard = {
                                        SideEffect { dashboardCompositions++ }
                                        Text("Dashboard")
                                    },
                                    scene = shown,
                                )
                            else -> Box(Modifier.fillMaxSize().background(ENGINE).testTag(ENGINE_TAG))
                        }
                    },
                )
            }
        }
        if (sheet) {
            TabSwitcher(tabs = tabs, onSelect = {}, onClose = {}, onNewTab = {}, onNewPrivateTab = {}, onClosePrivateTabs = {}, onDismiss = {}, scene = shown)
        }
    }

    /** Like the browser's toolbar row: Back, an opaque address field, the tab count and the menu, then the 4 dp progress slot. */
    @Composable
    private fun Toolbar() {
        Row(
            Modifier.fillMaxWidth().testTag(TOOLBAR_TAG).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(28.dp), modifier = Modifier.weight(1f).height(48.dp)) {}
            Text("3", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp))
            IconButton(onClick = {}) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
        }
        Box(Modifier.fillMaxWidth().height(4.dp))
    }

    private fun capture(tag: String): IntArray = rule.onNodeWithTag(tag).captureToImage().argb()

    private fun layerNodes() = rule.onAllNodesWithTag(SCENE_LAYER_TAG, useUnmergedTree = true).fetchSemanticsNodes()

    private fun layers() = layerNodes().size

    private fun openEveryGate() {
        userAnimated = true
        privateTab = false
        host.browserFullScreen = false
        host.browserPageShowing = false
        host.ambient = OpenAmbient
    }

    @Test
    fun theEngineAreaIsNeverDrawnOn() {
        show()
        for (t in AnimatedThemes) {
            for (w in listOf(412, 1200)) {
                for (bottom in listOf(false, true)) {
                    theme = t
                    widthDp = w
                    toolbarBottom = bottom
                    openEveryGate()
                    frames(3)
                    val where = "${t.id} w${w}dp toolbar ${if (bottom) "bottom" else "top"}"
                    assertEquals("$where: the scene runs", 1, host.activeLoops)
                    val engine = rule.onNodeWithTag(ENGINE_TAG).fetchSemanticsNode().boundsInRoot
                    val layerBounds = layerNodes().map { it.boundsInRoot }
                    assertEquals("$where: one layer per bar", if (showsTabStrip(w)) 2 else 1, layerBounds.size)
                    for (b in layerBounds) assertFalse("$where: layer $b overlaps the engine $engine", b.overlaps(engine))
                    val writes = host.frameWrites
                    repeat(60) { f ->
                        frames(1)
                        val off = capture(ENGINE_TAG).count { it != ENGINE_ARGB }
                        assertEquals("$where, frame $f: engine pixels that are not #FF00FF", 0, off)
                    }
                    assertTrue("$where: the clock ran meanwhile", host.frameWrites - writes >= 25)
                }
            }
        }
    }

    @Test
    fun fullscreenAndPipShowNoBands() {
        show()
        for (t in AnimatedThemes) {
            for (pip in listOf(false, true)) {
                theme = t
                openEveryGate()
                showChrome = true
                frames(3)
                val where = "${t.id} ${if (pip) "PiP" else "fullscreen"}"
                assertEquals("$where: strip and toolbar before", 2, layers())
                assertEquals(1, host.activeLoops)
                // What BrowserContent does: no chrome, and the gate reported to the host.
                showChrome = false
                if (pip) host.ambient = OpenAmbient.copy(inPip = true) else host.browserFullScreen = true
                frames(3)
                assertEquals("$where: no band", 0, layers())
                assertEquals("$where: no loop", 0, host.activeLoops)
                val frame = rule.onNodeWithTag(FRAME_TAG).fetchSemanticsNode().boundsInRoot
                assertEquals("$where: the page has the whole frame", frame, rule.onNodeWithTag(ENGINE_TAG).fetchSemanticsNode().boundsInRoot)
            }
        }
    }

    @Test
    fun aPrivateTabHasNoSceneAnywhere() {
        tabs =
            listOf(
                TabUi(1, "Selected secret", "https://selected.example/", selected = true, isPrivate = true),
                TabUi(2, "Normal", "https://normal.example/", selected = false),
                TabUi(3, "Secret", "https://secret.example/", selected = false, isPrivate = true),
            )
        startPage = true
        show(sheet = true)
        for (t in AnimatedThemes) {
            for (d in listOf(false, true)) {
                theme = t
                dark = d
                val where = "${t.id} ${if (d) "dark" else "light"}"
                // The same screen with a normal tab selected has a layer in the strip, toolbar, start page and sheet.
                privateTab = false
                frames(5)
                assertEquals("$where, normal tab: strip, toolbar, start page and sheet", 4, layers())
                privateTab = true
                frames(5)
                assertEquals("$where: no layer anywhere", 0, layers())
                assertEquals("$where: no loop", 0, host.activeLoops)
                rule.onNodeWithTag(TAB_STRIP_TAG).assertExists()
                rule.onNodeWithTag(TOOLBAR_TAG).assertExists()
                rule.onNodeWithTag(tabRowTag(2)).assertExists()
                val scheme = t.mode(d).scheme
                val normal = rule.onNodeWithTag(tabChipTag(2)).captureToImage().toPixelMap().let { it[4, it.height / 2] }
                val private = rule.onNodeWithTag(tabChipTag(3)).captureToImage().toPixelMap().let { it[4, it.height / 2] }
                assertEquals("$where: private chip", Integer.toHexString(scheme.tertiaryContainer.toArgb()), Integer.toHexString(private.toArgb()))
                assertEquals("$where: normal chip", Integer.toHexString(scheme.surfaceContainer.toArgb()), Integer.toHexString(normal.toArgb()))
            }
        }
    }

    @Test
    fun eachGateFreezesTheBarsAndStartPage() {
        startPage = true
        show()
        val gates: List<Pair<String, (Boolean) -> Unit>> =
            listOf(
                "user Off" to { closed -> userAnimated = !closed },
                "animator scale 0" to { closed -> host.ambient = OpenAmbient.copy(motionEnabled = !closed) },
                "not resumed" to { closed -> host.ambient = OpenAmbient.copy(resumed = !closed) },
                "picture-in-picture" to { closed -> host.ambient = OpenAmbient.copy(inPip = closed) },
                "page fullscreen" to { closed -> host.browserFullScreen = closed },
                "web page showing" to { closed -> host.browserPageShowing = closed },
                "media playing" to { closed -> host.ambient = OpenAmbient.copy(mediaPlaying = closed) },
                "private tab" to { closed -> privateTab = closed },
            )
        for (t in AnimatedThemes) {
            theme = t
            openEveryGate()
            frames(5)
            val a = capture(FRAME_TAG)
            frames(ONE_SECOND)
            assertFalse("${t.id}: with every gate open the bars and start page move", a.contentEquals(capture(FRAME_TAG)))
            for ((name, close) in gates) {
                close(true)
                frames(2)
                val before = capture(FRAME_TAG)
                frames(ONE_SECOND)
                assertArrayEquals("${t.id}, $name: identical 1 s apart", before, capture(FRAME_TAG))
                close(false)
                frames(5)
            }
        }
    }

    /** What BrowserContent reports while a web page shows: the bars keep a still frame and no frame loop runs. */
    @Test
    fun aPageShowingFreezesTheBars() {
        show()
        val bars = listOf(TAB_STRIP_TAG, TOOLBAR_TAG)
        for (t in AnimatedThemes) {
            theme = t
            openEveryGate()
            frames(5)
            assertEquals("${t.id}: the scene runs", 1, host.activeLoops)
            host.browserPageShowing = true
            frames(2)
            val before = bars.map { capture(it) }
            frames(ONE_SECOND)
            for ((i, tag) in bars.withIndex()) assertArrayEquals("${t.id}: $tag identical 1 s apart", before[i], capture(tag))
            assertEquals("${t.id}: the still frame is drawn, not hidden", 2, layers())
            assertEquals("${t.id}: no loop", 0, host.activeLoops)
            host.browserPageShowing = false
            frames(5)
            assertEquals("${t.id}: the loop runs again", 1, host.activeLoops)
            val moving = bars.map { capture(it) }
            frames(ONE_SECOND)
            assertFalse("${t.id}: the bars move again", bars.indices.all { moving[it].contentEquals(capture(bars[it])) })
        }
    }

    @Test
    fun framesDoNotRecomposeChrome() {
        startPage = true
        show()
        frames(10)
        val res = checkNotNull(strings)
        fun counts() =
            listOf(
                "chrome lambda" to chromeCompositions,
                "start page dashboard" to dashboardCompositions,
                "start page content" to res.count(R.string.address_bar_hint),
                "tab chips" to res.count(R.string.tabs_close_named) + res.count(R.string.tabs_close_private_named),
            )
        val before = counts()
        for ((name, n) in before) assertTrue("$name composed", n > 0)
        val pixels = capture(FRAME_TAG)
        frames(120)
        assertFalse("pixels changed", pixels.contentEquals(capture(FRAME_TAG)))
        assertEquals("no recomposition per frame", before, counts())
        assertTrue(host.frameWrites >= 50)
    }

    @Test
    fun barInkKeepsIconsReadable() {
        show()
        for (t in AnimatedThemes) {
            for (d in listOf(false, true)) {
                theme = t
                dark = d
                openEveryGate()
                val where = "${t.id} ${if (d) "dark" else "light"}"
                val scheme = t.mode(d).scheme
                val bg = scheme.background.toArgb()
                withScene = false
                frames(3)
                // Plain bar: the pixels that show the background when there is no scene. Chips, the field, icons
                // and text are left out; everything else is where ink lands behind the icons.
                val bars = listOf(TAB_STRIP_TAG, TOOLBAR_TAG)
                val plain = bars.map { tag -> capture(tag).let { px -> BooleanArray(px.size) { px[it] == bg } } }
                for ((i, tag) in bars.withIndex()) assertTrue("$where: $tag has bar background", plain[i].count { it } > plain[i].size / 20)
                withScene = true
                frames(3)
                val seen = HashMap<Int, Double>()
                var inked = 0
                repeat(20) {
                    frames(3)
                    for ((i, tag) in bars.withIndex()) {
                        val px = capture(tag)
                        val mask = plain[i]
                        for (j in px.indices) {
                            if (!mask[j]) continue
                            val p = px[j]
                            if (p != bg) inked++
                            val c = seen.getOrPut(p) { contrastOf(Color(p), scheme.onBackground) }
                            if (c < 4.5) throw AssertionError("$where: $tag pixel ${Integer.toHexString(p)} has $c:1 with onBackground")
                        }
                    }
                }
                assertTrue("$where: some ink on the bars", inked > 0)
            }
        }
    }

    /** Counts string lookups by id: a chip or the start page that recomposes looks its strings up again. */
    @Suppress("DEPRECATION")
    private class CountingResources(base: Resources) : Resources(base.assets, base.displayMetrics, base.configuration) {
        private val counts = HashMap<Int, Int>()

        fun count(id: Int): Int = counts[id] ?: 0

        override fun getString(id: Int): String {
            counts[id] = count(id) + 1
            return super.getString(id)
        }

        override fun getString(id: Int, vararg formatArgs: Any?): String {
            counts[id] = count(id) + 1
            return super.getString(id, *formatArgs)
        }
    }

    private companion object {
        const val FRAME_TAG = "browser_frame"
        const val ENGINE_TAG = "engine"
        const val TOOLBAR_TAG = "toolbar_row"
        const val ONE_SECOND = 63 // frames of 16 ms
        val ENGINE = Color(0xFFFF00FF)
        val ENGINE_ARGB = ENGINE.toArgb()
        val SITES = listOf(TopSite("https://news.example/", "News", "news.example"), TopSite("https://docs.example/", "Docs", "docs.example"))
    }
}
