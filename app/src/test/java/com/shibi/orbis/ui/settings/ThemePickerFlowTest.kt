package com.shibi.orbis.ui.settings

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.runtime.CompositionLocalProvider
import android.provider.Settings
import com.shibi.orbis.ui.theme.MAX_PARTICLES
import com.shibi.orbis.ui.theme.OpenAmbient
import com.shibi.orbis.ui.theme.ProvideSceneHost
import com.shibi.orbis.ui.theme.SceneHost
import com.shibi.orbis.ui.theme.SceneSurface
import com.shibi.orbis.ui.theme.argb
import com.shibi.orbis.ui.theme.frames
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.robolectric.Shadows.shadowOf
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.shibi.orbis.settings.BrowserSettings
import com.shibi.orbis.settings.DarkMode
import com.shibi.orbis.settings.SharedPreferencesBrowserPrefs
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import com.shibi.orbis.ui.theme.AppTheme
import com.shibi.orbis.ui.theme.AppThemes
import com.shibi.orbis.ui.theme.OrbisTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** The theme picker end to end: cards, selection, persistence, previews and the TalkBack semantics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w412dp-h1600dp")
class ThemePickerFlowTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("theme_picker_flow_test", Context.MODE_PRIVATE)
    private lateinit var settings: BrowserSettings

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        settings = BrowserSettings(SharedPreferencesBrowserPrefs(prefs))
    }

    private fun show(initial: BrowserSettings = settings) {
        rule.setContent {
            val darkMode by initial.darkMode.collectAsStateWithLifecycle()
            val themeId by initial.themeId.collectAsStateWithLifecycle()
            // Like MainActivity: the picker itself is drawn in the chosen theme.
            OrbisTheme(darkMode, AppThemes.byId(themeId)) {
                ThemePickerScreen(settings = initial, onBack = {})
            }
        }
        rule.waitForIdle()
    }

    private val cardTag = SemanticsMatcher("is a theme card") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("theme_card_") == true
    }

    private fun cardIds(): List<String> =
        rule.onAllNodes(cardTag).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.TestTag].removePrefix("theme_card_") }

    private fun selectedCardIds(): List<String> =
        rule.onAllNodes(cardTag).fetchSemanticsNodes()
            .filter { it.config.getOrNull(SemanticsProperties.Selected) == true }
            .map { it.config[SemanticsProperties.TestTag].removePrefix("theme_card_") }

    private fun theme(id: String): AppTheme = AppThemes.byId(id)

    @Test
    fun showsEveryThemeInOrderWithExactlyOneSelected() {
        show()
        assertEquals(AppThemes.all.map { it.id }, cardIds())
        assertEquals(listOf("orbis_teal"), selectedCardIds())
    }

    @Test
    fun anUnknownStoredIdShowsTealSelected() {
        prefs.edit().putString("theme_id", "from_a_newer_build").commit()
        settings = BrowserSettings(SharedPreferencesBrowserPrefs(prefs))
        show()
        assertEquals(listOf("orbis_teal"), selectedCardIds())
    }

    @Test
    fun tappingACardSavesItAndSurvivesReopening() {
        show()
        rule.onNodeWithTag("theme_card_harbor").performClick()
        rule.waitForIdle()
        assertEquals(listOf("harbor"), selectedCardIds())
        assertEquals("harbor", settings.themeId.value)
        assertEquals("harbor", prefs.getString("theme_id", null))
        assertEquals("harbor", BrowserSettings(SharedPreferencesBrowserPrefs(prefs)).themeId.value)
    }

    @Test
    fun pickingAThemeNeverChangesTheDarkModeChoice() {
        settings.setDarkMode(DarkMode.DARK)
        show()
        rule.onNodeWithTag("theme_card_fern").performClick()
        rule.waitForIdle()
        assertEquals(DarkMode.DARK, settings.darkMode.value)
        assertEquals("DARK", prefs.getString("app_theme", null))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [35], application = android.app.Application::class, qualifiers = "w412dp-h915dp")
    fun previewsAreDrawnInEachThemesOwnColoursAndFollowDarkMode() {
        show()
        for (t in AppThemes.all) assertPreview(t.id, t.light.scheme)
        rule.onNodeWithContentDescription("Appearance: Dark").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(DarkMode.DARK, settings.darkMode.value)
        for (t in AppThemes.all) assertPreview(t.id, t.dark.scheme)
        rule.onNodeWithContentDescription("Appearance: Light").performScrollTo().performClick()
        rule.waitForIdle()
        for (t in AppThemes.all) assertPreview(t.id, t.light.scheme)
    }

    private fun assertPreview(id: String, scheme: ColorScheme) {
        rule.onNodeWithTag("theme_card_$id").performScrollTo()
        val map = rule.onNodeWithTag("theme_preview_$id", useUnmergedTree = true).captureToImage().toPixelMap()
        val d = rule.density.density
        // Inside the preview's padding, below the content, clear of the rounded top corners.
        val background = map[4, map.height - 4]
        assertTrue("$id preview background $background, expected ${scheme.background}", close(background, scheme.background))
        // The tab row: scan across the chips' vertical centre. The private chip's plum and the normal chip's own colour must both be there.
        val y = (54 * d).toInt()
        val row = (0 until map.width).map { map[it, y] }
        assertTrue("$id private chip colour ${scheme.tertiaryContainer} missing from the tab row", row.any { close(it, scheme.tertiaryContainer) })
        assertTrue("$id normal chip colour ${scheme.surfaceContainer} missing from the tab row", row.any { close(it, scheme.surfaceContainer) })
        assertTrue("$id private and normal chip colours must differ", !close(scheme.tertiaryContainer, scheme.surfaceContainer))
    }

    private fun close(a: Color, b: Color): Boolean {
        fun ch(c: Color, shift: Int) = (c.toArgb() ushr shift) and 0xFF
        return listOf(16, 8, 0).all { abs(ch(a, it) - ch(b, it)) <= 1 }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [35], application = android.app.Application::class, qualifiers = "w412dp-h915dp")
    fun pickingAThemeRepaintsThePickerItself() {
        show()
        rule.onNodeWithTag("theme_card_ember").performScrollTo()
        val before = rule.onNodeWithTag("theme_card_ember").captureToImage().toPixelMap()
        rule.onNodeWithTag("theme_card_ember").performClick()
        rule.waitForIdle()
        val after = rule.onNodeWithTag("theme_card_ember").captureToImage().toPixelMap()
        // The card's own surface is drawn in the chosen theme (Ember's container is not Teal's).
        val corner = { m: androidx.compose.ui.graphics.PixelMap -> m[m.width / 2, m.height - 3] }
        assertTrue(corner(before) != corner(after))
    }

    @Test
    fun everyCardAnnouncesItsNameDescriptionRoleAndSelectedState() {
        show()
        for (t in AppThemes.all) {
            val name = context.getString(t.nameRes)
            val description = context.getString(t.descriptionRes)
            val expectedState = if (t.id == "orbis_teal") "Selected" else "Not selected"
            val node = rule.onNodeWithContentDescription("$name. $description")
            node.assert(hasTestTag("theme_card_${t.id}"))
            node.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            node.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expectedState))
            node.assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, t.id == "orbis_teal"))
        }
        rule.onNodeWithContentDescription("Orbis Teal. ${context.getString(theme("orbis_teal").descriptionRes)}").assertIsSelected()
    }

    @Test
    fun theSelectedStateMovesWithTheChoice() {
        show()
        rule.onNodeWithContentDescription("Paper. ${context.getString(theme("paper").descriptionRes)}").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("theme_card_paper")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        rule.onNodeWithTag("theme_card_orbis_teal")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not selected"))
    }

    @Test
    fun darkModeChoiceIsAtTheTopWithSelectedStatesAndDescriptions() {
        show()
        rule.onNodeWithContentDescription("Appearance: System default").assertIsSelected()
        rule.onNodeWithContentDescription("Appearance: Light").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(DarkMode.LIGHT, settings.darkMode.value)
        rule.onNodeWithContentDescription("Appearance: Light").assertIsSelected()
        rule.onNodeWithContentDescription("Appearance: Dark").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(DarkMode.DARK, settings.darkMode.value)
        assertEquals("DARK", prefs.getString("app_theme", null))
        rule.onNodeWithContentDescription("Appearance: Dark").assertIsSelected()
    }

    @Test
    fun headingsAreMarkedForScreenReaders() {
        show()
        for (text in listOf("Dark mode", "Color theme")) {
            rule.onAllNodesWithText(text)
                .filterToHeadings()
        }
    }

    @Test
    fun staticThemesHaveNoAnimatedControl() {
        show()
        for (t in AppThemes.all.filter { !it.animated }) {
            val name = context.getString(t.nameRes)
            rule.onAllNodesWithContentDescription("Animated background for $name").assertCountEquals(0)
            rule.onAllNodesWithContentDescription("No animation for $name").assertCountEquals(0)
        }
    }

    // ---- Animated/Off toggle and live previews (a scene host, like the app) ----

    private val host = SceneHost()
    private val animatedThemes get() = AppThemes.all.filter { it.animated }
    private val resolver get() = context.contentResolver
    private val surfaces = mutableMapOf<String, SceneSurface?>()
    private val cardCompositions = mutableMapOf<String, Int>()

    @After
    fun resetAnimatorScale() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    private fun showWithHost(initial: BrowserSettings = settings, holdClock: Boolean = false) {
        // With the clock running, the app is never idle: toggle tests pause the scene so waitForIdle can return.
        if (holdClock) rule.mainClock.autoAdvance = false else host.ambient = OpenAmbient.copy(resumed = false)
        val probe =
            object : PickerProbe {
                override fun cardComposed(id: String) {
                    cardCompositions[id] = (cardCompositions[id] ?: 0) + 1
                }

                override fun previewSurface(id: String, surface: SceneSurface?) {
                    surfaces[id] = surface
                }
            }
        rule.setContent {
            val darkMode by initial.darkMode.collectAsStateWithLifecycle()
            val themeId by initial.themeId.collectAsStateWithLifecycle()
            OrbisTheme(darkMode, AppThemes.byId(themeId)) {
                ProvideSceneHost(host) {
                    CompositionLocalProvider(LocalPickerProbe provides probe) {
                        ThemePickerScreen(settings = initial, onBack = {})
                    }
                }
            }
        }
        if (holdClock) rule.frames(3) else rule.waitForIdle()
    }

    private fun animated(t: AppTheme) = "Animated background for ${context.getString(t.nameRes)}"

    private fun off(t: AppTheme) = "No animation for ${context.getString(t.nameRes)}"

    @Test
    @Config(sdk = [35], application = android.app.Application::class, qualifiers = "w412dp-h4000dp")
    fun exactlyThreeAnimatedControlsEachUnderItsOwnCard() {
        showWithHost()
        val controls =
            rule.onAllNodes(
                SemanticsMatcher("an animation control") {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)
                        ?.any { d -> d.startsWith("Animated background for ") || d.startsWith("No animation for ") } == true
                },
            )
        controls.assertCountEquals(6)
        rule.onAllNodesWithText("Animated").assertCountEquals(3)
        rule.onAllNodesWithText("Off").assertCountEquals(3)
        for (t in animatedThemes) {
            val card = rule.onNodeWithTag(themeCardTag(t.id)).fetchSemanticsNode().boundsInRoot
            for (d in listOf(animated(t), off(t))) {
                val b = rule.onNodeWithContentDescription(d).fetchSemanticsNode().boundsInRoot
                assertTrue("$d sits under its card", b.top >= card.bottom - 1f && b.top < card.bottom + 64f)
                assertTrue("$d sits inside its card's column", b.left >= card.left - 1f && b.right <= card.right + 1f)
            }
        }
        for (t in AppThemes.all.filter { !it.animated }) {
            rule.onAllNodesWithContentDescription(animated(t)).assertCountEquals(0)
            rule.onAllNodesWithContentDescription(off(t)).assertCountEquals(0)
        }
    }

    @Test
    @Config(sdk = [35], application = android.app.Application::class, qualifiers = "w412dp-h4000dp")
    fun byDefaultAnimatedIsSelectedAndNothingIsStored() {
        showWithHost()
        for (t in animatedThemes) {
            rule.onNodeWithContentDescription(animated(t)).assertIsSelected()
            rule.onNodeWithContentDescription(off(t)).assertIsNotSelected()
        }
        assertFalse(prefs.contains("theme_static_ids"))
    }

    @Test
    @Config(sdk = [35], application = android.app.Application::class, qualifiers = "w412dp-h4000dp")
    fun turningAuroraOffStoresOnlyAuroraAndTurningItOnRemovesIt() {
        settings.setThemeId("harbor")
        showWithHost()
        val aurora = theme("aurora")
        rule.onNodeWithContentDescription(off(aurora)).performClick()
        rule.waitForIdle()
        assertEquals(setOf("aurora"), settings.staticThemeIds.value)
        assertEquals(setOf("aurora"), BrowserSettings(SharedPreferencesBrowserPrefs(prefs)).staticThemeIds.value)
        assertEquals("harbor", settings.themeId.value)
        assertEquals("harbor", prefs.getString("theme_id", null))
        rule.onNodeWithContentDescription(off(aurora)).assertIsSelected()
        rule.onNodeWithContentDescription(animated(aurora)).assertIsNotSelected()
        for (other in animatedThemes.filter { it.id != "aurora" }) rule.onNodeWithContentDescription(animated(other)).assertIsSelected()
        assertEquals(listOf("harbor"), selectedCardIds())

        rule.onNodeWithContentDescription(animated(aurora)).performClick()
        rule.waitForIdle()
        assertEquals(emptySet<String>(), settings.staticThemeIds.value)
        assertEquals(emptySet<String>(), BrowserSettings(SharedPreferencesBrowserPrefs(prefs)).staticThemeIds.value)
        rule.onNodeWithContentDescription(animated(aurora)).assertIsSelected()
        assertEquals("harbor", settings.themeId.value)
    }

    @Test
    @Config(sdk = [35], application = android.app.Application::class, qualifiers = "w412dp-h4000dp")
    fun theControlsDescribeThemselvesAndTheCardKeepsItsOwnDescription() {
        showWithHost()
        for (t in animatedThemes) {
            val name = context.getString(t.nameRes)
            rule.onNodeWithContentDescription("Animated background for $name")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            rule.onNodeWithContentDescription("No animation for $name")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
            val card = rule.onNodeWithTag(themeCardTag(t.id))
            card.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("$name. ${context.getString(t.descriptionRes)}")))
            card.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        }
    }

    @Test
    @Config(sdk = [35], application = android.app.Application::class, qualifiers = "w412dp-h4000dp")
    fun theSystemAnimationsNoteShowsAtScaleZeroAndNotAtOne() {
        val note = "System animations are off, so this theme stays still."
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        showWithHost()
        rule.onAllNodesWithText(note).assertCountEquals(0)
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        rule.waitForIdle()
        rule.onAllNodesWithText(note).assertCountEquals(3)
        // The row stays usable.
        rule.onNodeWithContentDescription(off(theme("aurora"))).performClick()
        rule.waitForIdle()
        assertEquals(setOf("aurora"), settings.staticThemeIds.value)
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        rule.waitForIdle()
        rule.onAllNodesWithText(note).assertCountEquals(0)
    }

    private fun preview(id: String): IntArray =
        rule.onNodeWithTag(themePreviewTag(id), useUnmergedTree = true).captureToImage().argb()

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [35], application = android.app.Application::class, qualifiers = "w412dp-h4000dp")
    fun animatedPreviewsMoveAndStaticOnesDoNot() {
        showWithHost(holdClock = true)
        assertEquals(1, host.activeLoops)
        val before = AppThemes.all.associate { it.id to preview(it.id) }
        rule.frames(70)
        assertEquals(1, host.activeLoops)
        val after = AppThemes.all.associate { it.id to preview(it.id) }
        for (t in AppThemes.all) {
            if (t.animated) {
                assertFalse("${t.id} preview moves", before.getValue(t.id).contentEquals(after.getValue(t.id)))
            } else {
                assertArrayEquals("${t.id} preview is still", before.getValue(t.id), after.getValue(t.id))
            }
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [35], application = android.app.Application::class, qualifiers = "w412dp-h4000dp")
    fun aPreviewSwitchedOffIsFrozenWhileTheOthersMove() {
        settings.setThemeAnimated("aurora", false)
        showWithHost(holdClock = true)
        assertEquals(1, host.activeLoops)
        val before = animatedThemes.associate { it.id to preview(it.id) }
        repeat(3) {
            rule.frames(25)
            assertEquals(1, host.activeLoops)
        }
        val after = animatedThemes.associate { it.id to preview(it.id) }
        assertArrayEquals("aurora is at its still frame", before.getValue("aurora"), after.getValue("aurora"))
        for (id in listOf("night_sky", "fair_weather")) {
            assertFalse("$id moves", before.getValue(id).contentEquals(after.getValue(id)))
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [35], application = android.app.Application::class, qualifiers = "w412dp-h4000dp")
    fun eachPreviewDrawsAtMostItsShareOfTheParticleCap() {
        showWithHost(holdClock = true)
        rule.frames(5)
        assertEquals(13, PREVIEW_BUDGET)
        for (t in animatedThemes) {
            preview(t.id) // a capture forces the draw
            val surface = surfaces[t.id]
            assertNotNull("${t.id} has a surface", surface)
            val frame = surface!!.builtFrame
            assertNotNull("${t.id} built a frame", frame)
            assertTrue("${t.id} draws ${frame!!.elements}", frame.elements in 1..PREVIEW_BUDGET)
        }
        for (t in AppThemes.all.filter { !it.animated }) assertNull("${t.id} has no surface", surfaces[t.id])
        assertTrue(animatedThemes.sumOf { surfaces.getValue(it.id)!!.builtFrame!!.elements } <= MAX_PARTICLES)
    }

    @Test
    @Config(sdk = [35], application = android.app.Application::class, qualifiers = "w412dp-h4000dp")
    fun previewFramesDoNotRecomposeCards() {
        showWithHost(holdClock = true)
        rule.frames(5)
        val cardsBefore = cardCompositions.toMap()
        assertEquals(AppThemes.all.map { it.id }.toSet(), cardsBefore.keys)
        val writesBefore = host.frameWrites
        rule.frames(120)
        assertTrue("frames were written", host.frameWrites > writesBefore + 30)
        assertEquals(cardsBefore, cardCompositions.toMap())
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.filterToHeadings() {
        val nodes = fetchSemanticsNodes()
        assertTrue("expected a heading", nodes.any { SemanticsProperties.Heading in it.config })
    }
}
