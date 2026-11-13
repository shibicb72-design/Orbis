package com.shibi.orbis.ui.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where animation code may live. "Allowed" means "may contain": the lists name files that later packages fill in.
 * The engine view's hosts, BrowserScreen.kt and CustomTabActivity.kt, contain no animation API at all; they only call
 * components that live in other files.
 */
class MotionStructureTest {
    private val root = File("src/main/java/com/shibi/orbis")

    /** Sources with comments removed, so a KDoc that names an API does not count as using it. */
    private fun sources(): Map<String, String> {
        assertTrue("run from the app module: ${root.absolutePath}", root.isDirectory)
        val block = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val line = Regex("""(?m)//.*$""")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText().replace(block, "").replace(line, "") }
    }

    private val motion = "ui/theme/Motion.kt"
    private val orbisRoot = "ui/OrbisRoot.kt"
    private val themeFade = "ui/theme/ThemeFade.kt"
    private val loadingBar = "ui/browser/LoadingBar.kt"
    private val addressBar = "ui/browser/BrowserAddressBar.kt"
    private val tabStrip = "ui/browser/TabStrip.kt"
    private val tabSwitcher = "ui/browser/TabSwitcher.kt"
    private val engineHosts = listOf("ui/browser/BrowserScreen.kt", "customtabs/CustomTabActivity.kt")

    private val stateAnimations =
        listOf(
            "animateFloatAsState", "animateColorAsState", "animateDpAsState", "Animatable(", "AnimatedVisibility",
            "AnimatedContent", "Crossfade(", "animateContentSize", "updateTransition", "rememberInfiniteTransition",
        )

    private fun filesUsing(pattern: Regex) =
        sources().filterValues { pattern.containsMatchIn(it) }.keys

    private fun literal(name: String) = Regex("""(?<![A-Za-z0-9_])${Regex.escape(name)}""")

    @Test
    fun tweenAndSpringLiteralsLiveInMotionAndTheFourRouteFades() {
        // TabSwitcher.kt still holds its placement tween until the tab package replaces it with motionTweenOrNull.
        val allowed = setOf(motion, orbisRoot, tabSwitcher)
        for (api in listOf("tween(", "spring(")) {
            assertEquals("$api outside the allowed files", emptySet<String>(), filesUsing(literal(api)) - allowed)
        }
        val routeFades = checkNotNull(sources()[orbisRoot])
        assertEquals("the four route fades", 4, literal("tween(").findAll(routeFades).count())
        assertEquals(0, literal("spring(").findAll(routeFades).count())
    }

    @Test
    fun stateAnimationsLiveInTheMotionFiles() {
        val allowed = setOf(motion, themeFade, loadingBar, addressBar)
        for (api in stateAnimations) {
            assertEquals("$api outside the allowed files", emptySet<String>(), filesUsing(literal(api)) - allowed)
        }
    }

    @Test
    fun animateItemLivesInTheTabLists() {
        assertEquals(emptySet<String>(), filesUsing(literal("animateItem(")) - setOf(tabStrip, tabSwitcher))
    }

    @Test
    fun animateContentSizeIsNowhere() {
        assertEquals(emptySet<String>(), filesUsing(literal("animateContentSize")))
    }

    @Test
    fun theEngineHostsContainNoAnimationApiAndNoGraphicsLayer() {
        val all = sources()
        val banned = stateAnimations + listOf("tween(", "spring(", "animateItem(", "graphicsLayer")
        for (f in engineHosts) {
            val text = checkNotNull(all[f]) { "missing $f" }
            for (b in banned) assertTrue("$f uses $b", !literal(b).containsMatchIn(text))
        }
    }
}
