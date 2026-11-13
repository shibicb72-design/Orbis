package com.shibi.orbis.ui.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rules a reviewer would otherwise have to check by hand: one frame loop, time in the draw phase, known call sites. */
class SceneStructureTest {
    private val root = File("src/main/java/com/shibi/orbis")

    private fun sources(): Map<String, String> {
        assertTrue("run from the app module: ${root.absolutePath}", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
    }

    @Test
    fun oneFrameLoopInTheWholeApp() {
        val users = sources().filterValues { "withFrameNanos" in it }.keys
        assertEquals(setOf("ui/theme/SceneHost.kt"), users)
    }

    @Test
    fun scenesNeverUseComposeAnimations() {
        val files = listOf("ui/theme/ThemeParticles.kt", "ui/theme/ChromeScene.kt", "ui/theme/SceneHost.kt", "ui/theme/ThemesAnimated.kt")
        val banned = listOf("animateFloatAsState", "animateColorAsState", "Animatable(", "rememberInfiniteTransition", "updateTransition")
        val all = sources()
        for (f in files) {
            val text = checkNotNull(all[f]) { "missing $f" }
            for (b in banned) assertTrue("$f uses $b", b !in text)
        }
    }

    /** The ink ceiling needs one offscreen group per layer; ModulateAlpha applies the alpha per draw call. */
    @Test
    fun layersCompositeOffscreen() {
        val host = checkNotNull(sources()["ui/theme/SceneHost.kt"])
        assertTrue("compositingStrategy = CompositingStrategy.Offscreen" in host)
        assertTrue("CompositingStrategy.ModulateAlpha" !in host)
    }

    /** A new place that draws a scene must be added here on purpose. Package B and C add theirs. */
    @Test
    fun sceneCallSitesAreTheKnownOnes() {
        val expected = mapOf("ui/theme/SceneHost.kt" to (1 to 0), "ui/settings/ThemePickerScreen.kt" to (1 to 0)) // file to (SceneLayer calls, SceneBand calls)
        val layer = Regex("""(?<!fun )\bSceneLayer\(""")
        val band = Regex("""(?<!fun )\bSceneBand\(""")
        val actual =
            sources().mapValues { (_, text) -> layer.findAll(text).count() to band.findAll(text).count() }
                .filterValues { it != (0 to 0) }
        assertEquals(expected, actual)
    }
}
