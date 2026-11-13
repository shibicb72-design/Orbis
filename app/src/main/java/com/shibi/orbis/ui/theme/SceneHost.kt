package com.shibi.orbis.ui.theme

import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.flow.collectLatest

/** What a scene surface shows. */
enum class SceneMode { Hidden, Static, Paused, Running }

/** The activity-wide inputs that stop a scene. */
data class SceneAmbient(
    val motionEnabled: Boolean,
    val resumed: Boolean,
    val inPip: Boolean,
    val fullScreen: Boolean,
    val mediaPlaying: Boolean,
)

/**
 * Every gate, in one place. No scene or a private tab: nothing at all. The user set the theme to Off or the system
 * animator scale is 0: the still frame. Not resumed, picture-in-picture, a fullscreen page, or media playing in any
 * tab (audio too): the last frame, frozen. Otherwise it moves.
 */
internal fun sceneMode(hasScene: Boolean, userAnimated: Boolean, privateTab: Boolean, ambient: SceneAmbient): SceneMode =
    when {
        !hasScene || privateTab -> SceneMode.Hidden
        !userAnimated || !ambient.motionEnabled -> SceneMode.Static
        !ambient.resumed || ambient.inPip || ambient.fullScreen || ambient.mediaPlaying -> SceneMode.Paused
        else -> SceneMode.Running
    }

/** The clock is written at most once per this many nanoseconds: a 30 fps cap. Skipped frames invalidate nothing. */
internal const val SCENE_MIN_FRAME_NS = 32_000_000L

/** The shown time wraps once an hour, so a Float keeps millisecond precision. A scene may jump once an hour. */
private const val SCENE_WRAP_NS = 3_600_000_000_000L

/**
 * The activity's one scene clock. Every surface reads [elapsedSec] in its draw phase only, so a frame invalidates
 * drawing and never recomposes. The frame loop runs only while some surface is [SceneMode.Running].
 */
@Stable
class SceneHost internal constructor() {
    private val elapsed = mutableFloatStateOf(0f)

    /** Seconds of motion so far. Read inside draw lambdas only. */
    internal val elapsedSec: FloatState get() = elapsed

    /** Set by [ProvideSceneHost]. */
    var ambient: SceneAmbient by mutableStateOf(SceneAmbient(motionEnabled = true, resumed = true, inPip = false, fullScreen = false, mediaPlaying = false))

    /** Set by the browser screen while its page is fullscreen; false once it leaves. */
    var browserFullScreen: Boolean by mutableStateOf(false)

    /** [ambient] with [browserFullScreen] folded in: what every surface's mode comes from. */
    internal val gates: SceneAmbient by derivedStateOf {
        val a = ambient
        if (browserFullScreen && !a.fullScreen) a.copy(fullScreen = true) else a
    }

    private var holds by mutableIntStateOf(0)

    /** A surface in [SceneMode.Running] holds one. */
    internal fun retain() {
        holds++
    }

    internal fun release() {
        if (holds > 0) holds--
    }

    /** 0 or 1. */
    internal var activeLoops: Int = 0
        private set

    /** How many times [elapsedSec] was written. */
    internal var frameWrites: Int = 0
        private set

    private val loopWanted: Boolean
        get() = holds > 0 && sceneMode(hasScene = true, userAnimated = true, privateTab = false, ambient = gates) == SceneMode.Running

    private var elapsedNanos = 0L
    private var writtenNanos = 0L
    private var lastFrameNanos = -1L
    private val onFrame: (Long) -> Unit = ::frame

    private fun frame(now: Long) {
        val last = lastFrameNanos
        // A gate can close before the loop's coroutine hears of it: then nothing moves, and the next open frame
        // starts a new origin.
        if (!loopWanted) {
            lastFrameNanos = -1L
            return
        }
        lastFrameNanos = now
        if (last < 0L) return // the first frame after a start only sets the origin, so time continues without a jump
        elapsedNanos += now - last
        if (elapsedNanos - writtenNanos >= SCENE_MIN_FRAME_NS) {
            writtenNanos = elapsedNanos
            elapsed.floatValue = ((elapsedNanos % SCENE_WRAP_NS) / 1e9).toFloat()
            frameWrites++
        }
    }

    /** Runs for the host's lifetime: at most one frame loop, and only while it is wanted. */
    internal suspend fun runLoop() {
        snapshotFlow { loopWanted }.collectLatest { wanted ->
            if (!wanted) return@collectLatest
            activeLoops++
            try {
                while (true) withFrameNanos(onFrame)
            } finally {
                activeLoops--
                lastFrameNanos = -1L
            }
        }
    }

    /** Tests: show the scene at `staticTimeSec + sec` (a still at any time). */
    @VisibleForTesting
    internal fun seek(sec: Float) {
        elapsedNanos = (sec * 1e9).toLong()
        writtenNanos = elapsedNanos
        elapsed.floatValue = sec
    }
}

/** The activity's [SceneHost]; null where there is none (Custom Tabs, tests of single components): no scene then. */
val LocalSceneHost = staticCompositionLocalOf<SceneHost?> { null }

/**
 * Creates the activity's one [SceneHost] and its one frame loop. Put it outside the NavHost so every route and sheet
 * shares the clock. [motionEnabled] is the system animator scale; the lifecycle must be RESUMED for motion.
 */
@Composable
fun ProvideSceneHost(motionEnabled: Boolean, inPip: Boolean, mediaPlaying: Boolean, content: @Composable () -> Unit) {
    val host = remember { SceneHost() }
    SceneAmbientInputs(host, motionEnabled, inPip, mediaPlaying)
    ProvideSceneHost(host, content)
}

/** Its own scope, so a lifecycle change recomposes only this. */
@Composable
private fun SceneAmbientInputs(host: SceneHost, motionEnabled: Boolean, inPip: Boolean, mediaPlaying: Boolean) {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    host.ambient =
        SceneAmbient(
            motionEnabled = motionEnabled,
            resumed = state.isAtLeast(Lifecycle.State.RESUMED),
            inPip = inPip,
            fullScreen = false, // the browser screen reports it through browserFullScreen
            mediaPlaying = mediaPlaying,
        )
}

/** [host] with its loop, for tests that set [SceneHost.ambient] themselves. */
@Composable
internal fun ProvideSceneHost(host: SceneHost, content: @Composable () -> Unit) {
    LaunchedEffect(host) { host.runLoop() }
    CompositionLocalProvider(LocalSceneHost provides host, content = content)
}

/**
 * One screen's scene: its theme, mode and budget, where its root sits on screen, and the one [SceneFrame] every
 * layer of the screen draws. Several layers are windows onto that one field, so the scene is continuous across them
 * and at most [MAX_PARTICLES] elements exist on the screen.
 */
@Stable
class SceneSurface internal constructor(internal val host: SceneHost, budget: Int, scene: ChromeScene) {
    internal val budgetLimit: Int = budget.coerceIn(0, MAX_PARTICLES)
    internal var scene: ChromeScene by mutableStateOf(scene)
    internal var dark: Boolean by mutableStateOf(false)
    internal var userAnimated: Boolean by mutableStateOf(true)

    /** Static, Paused or Running (a Hidden surface does not exist). */
    val mode: SceneMode by derivedStateOf { sceneMode(hasScene = true, userAnimated = userAnimated, privateTab = false, ambient = host.gates) }

    internal var rootOnScreen: Offset by mutableStateOf(Offset.Unspecified)
    internal var rootSize: Size by mutableStateOf(Size.Unspecified)

    private var held = false
    private var frame: SceneFrame? = null
    private var frameScene: ChromeScene? = null
    private var frameSize = Size.Unspecified
    private var frameDensity = 0f
    private var frameDark = false

    /** The frame built last; for tests. */
    internal val builtFrame: SceneFrame? get() = frame

    internal fun hold(running: Boolean) {
        if (running == held) return
        held = running
        if (running) host.retain() else host.release()
    }

    /** Built once per scene, size, density and mode; [budgetLimit] is fixed for the surface. */
    internal fun frameFor(size: Size, density: Density): SceneFrame {
        val s = scene
        val d = dark
        val cached = frame
        if (cached != null && frameScene === s && frameSize == size && frameDensity == density.density && frameDark == d) return cached
        val built = s.build(size, density, d, SceneBudget(budgetLimit))
        frame = built
        frameScene = s
        frameSize = size
        frameDensity = density.density
        frameDark = d
        return built
    }

    /** The time to draw: the still frame, or the still frame plus the host's clock. Read in the draw phase only. */
    internal fun timeSec(): Float {
        val s = scene
        return if (mode == SceneMode.Static) s.staticTimeSec else s.staticTimeSec + host.elapsedSec.floatValue
    }

    internal fun groupAlpha(region: SceneRegion): Float = (scene.coverage(dark) * region.opacity).coerceIn(0f, 1f)
}

/**
 * The scene for one screen, or null when there is no host or nothing to show (the theme has no scene, or a private
 * tab is selected). Null in the same composition the tab becomes private, so a private tab never shows a frame.
 */
@Composable
fun rememberSceneSurface(
    theme: AppTheme = LocalAppTheme.current,
    userAnimated: Boolean = LocalThemeAnimate.current,
    dark: Boolean = LocalThemeDark.current,
    privateTab: Boolean = false,
    budget: Int = MAX_PARTICLES,
): SceneSurface? {
    val host = LocalSceneHost.current ?: return null
    val scene = theme.scene
    if (scene == null || privateTab) return null
    val surface = remember(host, budget) { SceneSurface(host, budget, scene) }
    surface.scene = scene
    surface.dark = dark
    surface.userAnimated = userAnimated
    DisposableEffect(surface) { onDispose { surface.hold(false) } }
    LaunchedEffect(surface) { snapshotFlow { surface.mode == SceneMode.Running }.collect { surface.hold(it) } }
    return surface
}

/** On the screen root: records its size and position on screen for the surface's layers. Draws nothing. */
fun Modifier.sceneSurfaceRoot(surface: SceneSurface?): Modifier = if (surface == null) this else this then SceneRootElement(surface)

private class SceneRootElement(val surface: SceneSurface) : ModifierNodeElement<SceneRootNode>() {
    override fun create() = SceneRootNode(surface)

    override fun update(node: SceneRootNode) = node.update(surface)

    override fun InspectorInfo.inspectableProperties() {
        name = "sceneSurfaceRoot"
    }

    override fun equals(other: Any?) = other is SceneRootElement && other.surface === surface

    override fun hashCode() = System.identityHashCode(surface)
}

private class SceneRootNode(private var surface: SceneSurface) : Modifier.Node(), GlobalPositionAwareModifierNode {
    private var coordinates: LayoutCoordinates? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        push()
    }

    fun update(next: SceneSurface) {
        if (next === surface) return
        surface = next
        push()
    }

    private fun push() {
        val c = coordinates ?: return
        if (!c.isAttached) return
        surface.rootOnScreen = c.positionOnScreen()
        surface.rootSize = c.size.toSize()
    }
}

/** Test tag on every scene layer. */
const val SCENE_LAYER_TAG = "scene_layer"

/**
 * A window onto [surface]'s scene, the size of [modifier]. A leaf with its own offscreen graphics layer: the whole
 * scene composites once at `coverage × region.opacity`, so ink at any pixel never passes that, however many elements
 * overlap. Never put a scene on a container that has content: its drawing would be re-recorded every frame.
 */
@Composable
fun SceneLayer(surface: SceneSurface?, region: SceneRegion, modifier: Modifier = Modifier) {
    if (surface == null) return
    Spacer(
        modifier
            .testTag(SCENE_LAYER_TAG)
            .graphicsLayer {
                alpha = surface.groupAlpha(region)
                // Not ModulateAlpha: that applies the alpha per draw call, and overlaps would pass the ceiling.
                compositingStrategy = CompositingStrategy.Offscreen
                clip = true
            }
            .then(SceneLayerElement(surface)),
    )
}

/** [content] with the scene behind it, the band's own size. With no surface, just [content] in the same Box. */
@Composable
fun SceneBand(surface: SceneSurface?, region: SceneRegion, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier) {
        if (surface != null) SceneLayer(surface, region, Modifier.matchParentSize())
        content()
    }
}

private class SceneLayerElement(val surface: SceneSurface) : ModifierNodeElement<SceneLayerNode>() {
    override fun create() = SceneLayerNode(surface)

    override fun update(node: SceneLayerNode) {
        node.surface = surface
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "sceneLayer"
    }

    override fun equals(other: Any?) = other is SceneLayerElement && other.surface === surface

    override fun hashCode() = System.identityHashCode(surface)
}

private class SceneLayerNode(var surface: SceneSurface) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    /** Where the layer was on screen when it last drew its slice of the field. */
    private var drawnAt = Offset.Unspecified

    /**
     * A layer that moves (a sheet sliding in, a bar after a resize) is placed again but not redrawn, so it would keep
     * the slice from where it was. Draw again whenever the position on screen differs from the one last drawn at.
     */
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.positionOnScreen() != drawnAt) invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        val me = requireLayoutCoordinates().positionOnScreen()
        drawnAt = me
        val s = surface
        val rootSize = s.rootSize
        val rootOnScreen = s.rootOnScreen
        val fieldSize: Size
        var dx = 0f
        var dy = 0f
        if (rootSize.isSpecified && rootOnScreen.isSpecified && rootSize.width > 0f && rootSize.height > 0f) {
            fieldSize = rootSize
            dx = rootOnScreen.x - me.x
            dy = rootOnScreen.y - me.y
        } else {
            fieldSize = size // no root recorded: the layer is its own field
        }
        if (fieldSize.width <= 0f || fieldSize.height <= 0f) return
        val frame = s.frameFor(fieldSize, this)
        val t = s.timeSec()
        translate(dx, dy) { with(frame) { draw(t) } }
    }
}
