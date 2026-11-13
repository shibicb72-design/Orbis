package com.shibi.orbis.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SceneHostTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val host = SceneHost()
    private var theme by mutableStateOf<AppTheme>(AuroraTheme)
    private var userAnimated by mutableStateOf(true)
    private var privateTab by mutableStateOf(false)

    @Before
    fun holdTheClock() {
        rule.mainClock.autoAdvance = false
    }

    private fun frames(n: Int) = rule.frames(n)

    /** One 200×120 dp surface with one Page layer. */
    private fun showHarness() {
        rule.setContent {
            SceneTestHost(host, theme, dark = true, animate = userAnimated) {
                val surface = rememberSceneSurface(privateTab = privateTab)
                Box(Modifier.size(200.dp, 120.dp).background(MaterialTheme.colorScheme.background).sceneSurfaceRoot(surface).testTag("harness")) {
                    SceneLayer(surface, SceneRegion.Page, Modifier.matchParentSize())
                }
            }
        }
        frames(3)
    }

    private fun capture(): IntArray = rule.onNodeWithTag("harness").captureToImage().argb()

    private fun layers() = rule.onAllNodesWithTag(SCENE_LAYER_TAG).fetchSemanticsNodes().size

    private fun bit(n: Int, b: Int) = (n shr b) and 1 == 1

    @Test
    fun everyGateCombinationFreezesOrMoves() {
        showHarness()
        host.showStill(AuroraScene, AuroraScene.staticTimeSec)
        frames(2)
        val still = capture()
        host.ambient = OpenAmbient
        frames(2)
        for (n in 0 until 256) {
            val hasScene = bit(n, 7)
            val private = bit(n, 6)
            val user = bit(n, 5)
            val ambient = SceneAmbient(motionEnabled = bit(n, 4), resumed = bit(n, 3), inPip = bit(n, 2), fullScreen = bit(n, 1), mediaPlaying = bit(n, 0))
            val expected = sceneMode(hasScene, user, private, ambient)
            val where = "combination ${n.toString(2).padStart(8, '0')} ($expected)"
            var beforeClose: IntArray? = null
            if (expected == SceneMode.Paused) {
                // Moving first, so "frozen" means frozen where it was, not at some old frame.
                theme = AuroraTheme
                privateTab = false
                userAnimated = true
                host.ambient = OpenAmbient
                frames(10)
                beforeClose = capture()
            }
            theme = if (hasScene) AuroraTheme else AppThemes.Default
            privateTab = private
            userAnimated = user
            host.ambient = ambient
            frames(2)
            if (expected == SceneMode.Hidden) {
                assertEquals("$where: no layer", 0, layers())
            } else {
                assertEquals("$where: one layer", 1, layers())
            }
            val a = capture()
            frames(30)
            val b = capture()
            when (expected) {
                SceneMode.Running -> assertFalse("$where: moves", a.contentEquals(b))
                SceneMode.Hidden -> assertArrayEquals(where, a, b)
                SceneMode.Static -> {
                    assertArrayEquals("$where: the still frame", still, a)
                    assertArrayEquals(where, a, b)
                }
                SceneMode.Paused -> {
                    assertArrayEquals("$where: frozen where it was when the gate closed", beforeClose, a)
                    assertArrayEquals(where, a, b)
                }
            }
            assertEquals("$where: loops", if (expected == SceneMode.Running) 1 else 0, host.activeLoops)
        }
    }

    @Test
    fun pausingKeepsTheFrameAndResumingContinues() {
        showHarness()
        frames(20)
        assertEquals(1, host.activeLoops)
        host.ambient = OpenAmbient.copy(resumed = false)
        frames(1)
        val before = host.elapsedSec.floatValue
        val writes = host.frameWrites
        rule.mainClock.advanceTimeBy(5_000)
        assertEquals(writes, host.frameWrites)
        frames(2)
        assertEquals("nothing moves while paused", before, host.elapsedSec.floatValue)
        assertEquals(writes, host.frameWrites)
        assertEquals(0, host.activeLoops)
        host.ambient = OpenAmbient
        var guard = 0
        while (host.frameWrites == writes && guard++ < 10) frames(1)
        val after = host.elapsedSec.floatValue
        assertTrue("moved on: $before -> $after", after > before)
        assertTrue("by about one frame, not by the 5 s pause: $before -> $after", after - before < 0.05f)
    }

    @Test
    fun framesDoNotRecompose() {
        var hostContent = 0
        var owner = 0
        var sibling = 0
        rule.setContent {
            SceneTestHost(host, AuroraTheme, dark = true) {
                SideEffect { hostContent++ }
                Column {
                    SurfaceOwner(onCompose = { owner++ })
                    Sibling(onCompose = { sibling++ })
                }
            }
        }
        frames(5)
        val counts = Triple(hostContent, owner, sibling)
        val before = capture()
        frames(120)
        assertFalse("pixels changed", before.contentEquals(capture()))
        assertEquals("no recomposition per frame", counts, Triple(hostContent, owner, sibling))
        assertTrue(host.frameWrites >= 50)
    }

    @Composable
    private fun SurfaceOwner(onCompose: () -> Unit) {
        SideEffect(onCompose)
        val surface = rememberSceneSurface()
        Box(Modifier.size(200.dp, 120.dp).sceneSurfaceRoot(surface).testTag("harness")) {
            SceneLayer(surface, SceneRegion.Page, Modifier.matchParentSize())
        }
    }

    @Composable
    private fun Sibling(onCompose: () -> Unit) {
        SideEffect(onCompose)
        Box(Modifier.size(20.dp))
    }

    @Test
    fun oneLoopForManySurfaces() {
        rule.setContent {
            SceneTestHost(host, AuroraTheme, dark = false) {
                Column {
                    for ((t, budget, layerCount) in listOf(Triple(NightSkyTheme, 40, 3), Triple(AuroraTheme, 13, 2), Triple(FairWeatherTheme, 13, 2), Triple(NightSkyTheme, 13, 2))) {
                        val surface = rememberSceneSurface(theme = t, budget = budget)
                        Row(Modifier.size(300.dp, 60.dp).sceneSurfaceRoot(surface)) {
                            repeat(layerCount) { SceneLayer(surface, SceneRegion.Bar, Modifier.size(60.dp)) }
                        }
                    }
                }
            }
        }
        frames(5)
        assertEquals(9, layers())
        assertEquals(1, host.activeLoops)
        val writes = host.frameWrites
        frames(10)
        assertTrue(host.frameWrites > writes)

        host.ambient = OpenAmbient.copy(inPip = true) // all Paused
        frames(3)
        assertEquals(0, host.activeLoops)
        val paused = host.frameWrites
        frames(30)
        assertEquals(paused, host.frameWrites)

        host.ambient = OpenAmbient.copy(motionEnabled = false) // all Static
        frames(3)
        assertEquals(0, host.activeLoops)
        val still = host.frameWrites
        frames(30)
        assertEquals(still, host.frameWrites)

        host.ambient = OpenAmbient
        frames(5)
        assertEquals(1, host.activeLoops)
    }

    @Test
    fun writesAtMostThirtyTimesASecond() {
        showHarness()
        frames(5)
        assertEquals(1, host.activeLoops)
        val start = host.frameWrites
        frames(60) // 60 frames of 16 ms
        val writes = host.frameWrites - start
        assertTrue("$writes writes in 960 ms", writes in 29..31)
    }

    /** The public host reads the real inputs: RESUMED from the lifecycle, PiP, media, and the browser's fullscreen. */
    @Test
    fun provideSceneHostFollowsTheLifecycleAndItsInputs() {
        val owner =
            object : LifecycleOwner {
                val registry = LifecycleRegistry.createUnsafe(this)
                override val lifecycle: Lifecycle get() = registry
            }
        owner.registry.currentState = Lifecycle.State.RESUMED
        var inPip by mutableStateOf(false)
        var media by mutableStateOf(false)
        var motion by mutableStateOf(true)
        var seen: SceneHost? = null
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                ProvideSceneHost(motionEnabled = motion, inPip = inPip, mediaPlaying = media) {
                    seen = LocalSceneHost.current
                }
            }
        }
        frames(2)
        val h = checkNotNull(seen)
        assertEquals(OpenAmbient, h.gates)
        owner.registry.currentState = Lifecycle.State.STARTED
        frames(2)
        assertEquals(OpenAmbient.copy(resumed = false), h.gates)
        owner.registry.currentState = Lifecycle.State.RESUMED
        inPip = true
        frames(2)
        assertEquals(OpenAmbient.copy(inPip = true), h.gates)
        inPip = false
        media = true
        motion = false
        frames(2)
        assertEquals(OpenAmbient.copy(mediaPlaying = true, motionEnabled = false), h.gates)
        media = false
        motion = true
        h.browserFullScreen = true
        frames(2)
        assertEquals(OpenAmbient.copy(fullScreen = true), h.gates)
        h.browserFullScreen = false
        frames(1)
        assertEquals(OpenAmbient, h.gates)
    }

    @Test
    fun withoutAHostThereIsNoScene() {
        var surface: SceneSurface? = SceneSurface(host, MAX_PARTICLES, AuroraScene)
        rule.setContent {
            OrbisTheme(theme = AuroraTheme, animate = true) {
                surface = rememberSceneSurface()
                SceneLayer(surface, SceneRegion.Page, Modifier.size(50.dp))
            }
        }
        frames(2)
        assertEquals(null, surface)
        assertEquals(0, layers())
    }
}
