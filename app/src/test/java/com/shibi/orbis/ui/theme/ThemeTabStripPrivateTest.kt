package com.shibi.orbis.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.graphics.toPixelMap
import com.shibi.orbis.settings.DarkMode
import com.shibi.orbis.tabs.TabUi
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import com.shibi.orbis.ui.browser.TabStrip
import com.shibi.orbis.ui.browser.tabChipTag
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** In every theme and mode a private tab chip in the real tab strip looks different from a normal one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w1200dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeTabStripPrivateTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private var theme by mutableStateOf(AppThemes.Default)
    private var mode by mutableStateOf(DarkMode.LIGHT)

    private val tabs =
        listOf(
            TabUi(1, "Alpha", "https://alpha.example/", selected = true),
            TabUi(2, "Normal", "https://normal.example/", selected = false),
            TabUi(3, "Secret", "https://secret.example/", selected = false, isPrivate = true),
        )

    private fun close(a: Color, b: Color): Boolean = listOf(16, 8, 0).all { abs(((a.toArgb() ushr it) and 0xFF) - ((b.toArgb() ushr it) and 0xFF)) <= 1 }

    @Test
    fun privateChipDiffersFromNormalChipInEveryThemeAndMode() {
        rule.setContent {
            OrbisTheme(mode, theme) {
                TabStrip(tabs = tabs, onSelect = {}, onClose = {}, onNewTab = {}, onDropOpen = {})
            }
        }
        for (t in AppThemes.all) {
            for (m in listOf(DarkMode.LIGHT, DarkMode.DARK)) {
                theme = t
                mode = m
                rule.waitForIdle()
                val scheme = t.mode(m == DarkMode.DARK).scheme
                val where = "${t.id}/$m"
                // Left of the icon and title, inside the rounded corners.
                val normal = rule.onNodeWithTag(tabChipTag(2)).captureToImage().toPixelMap().let { it[4, it.height / 2] }
                val private = rule.onNodeWithTag(tabChipTag(3)).captureToImage().toPixelMap().let { it[4, it.height / 2] }
                assertTrue("$where normal chip $normal, expected ${scheme.surfaceContainer}", close(normal, scheme.surfaceContainer))
                assertTrue("$where private chip $private, expected ${scheme.tertiaryContainer}", close(private, scheme.tertiaryContainer))
                assertTrue("$where private and normal chips must differ", !close(private, normal))
                val privateDescriptions = rule.onNodeWithTag(tabChipTag(3)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                assertTrue("$where private tab keeps its icon, got $privateDescriptions", privateDescriptions.any { it == "Private tab" })
                val normalDescriptions = rule.onNodeWithTag(tabChipTag(2)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                assertTrue("$where normal tab has no private icon", normalDescriptions.none { it == "Private tab" })
            }
        }
    }
}
