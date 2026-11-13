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
import androidx.compose.ui.test.assertIsSelected
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
        rule.onAllNodesWithText("Animated").assertCountEquals(0)
        rule.onAllNodesWithText("Off").assertCountEquals(0)
        rule.onAllNodes(SemanticsMatcher("an animated control") {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.contains("nimat") } == true
        }).assertCountEquals(0)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.filterToHeadings() {
        val nodes = fetchSemanticsNodes()
        assertTrue("expected a heading", nodes.any { SemanticsProperties.Heading in it.config })
    }
}
