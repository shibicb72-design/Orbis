package com.shibi.orbis.ui.browser

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.shibi.orbis.settings.DarkMode
import com.shibi.orbis.ui.theme.OrbisTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The toolbar Cast icon: visible, teal (theme primary) while connected, disabled with no session. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ToolbarCastButtonComposeTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private var primary = 0
    private var onSurface = 0

    /** The button on a toolbar-like surface that sets the content colour from its container. */
    private fun show(
        theme: DarkMode,
        connected: () -> Boolean,
        enabled: () -> Boolean = { true },
        onClick: () -> Unit = {},
    ) {
        rule.setContent {
            OrbisTheme(theme) {
                primary = MaterialTheme.colorScheme.primary.toArgb()
                onSurface = MaterialTheme.colorScheme.onSurface.toArgb()
                Surface(color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
                    ToolbarCastButton(
                        connected = connected(),
                        enabled = enabled(),
                        deviceName = "Living room TV",
                        onClick = onClick,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun idleStateShowsCastIconEnabled() {
        show(DarkMode.LIGHT, connected = { false })
        val node = rule.onNodeWithTag(CAST_BUTTON_TAG)
        node.assertIsDisplayed()
        node.assertIsEnabled()
        node.assertContentDescriptionEquals("Cast to a TV or speaker")
        val image = node.captureToImage()
        save(image, "cast-idle-light.png")
        assertEquals("idle icon uses the toolbar content colour", onSurface and RGB, iconRgb(image))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun connectedStateIsTintedPrimaryAndNamesDevice() {
        show(DarkMode.LIGHT, connected = { true })
        val node = rule.onNodeWithTag(CAST_BUTTON_TAG)
        node.assertIsEnabled()
        node.assertContentDescriptionEquals("Casting to Living room TV")
        val image = node.captureToImage()
        save(image, "cast-connected-light.png")
        assertEquals("light theme primary is the Orbis teal", TEAL, primary and RGB)
        assertEquals("connected icon is teal", TEAL, iconRgb(image))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun connectedStateOnDarkThemeUsesTheReadableTonalPrimary() {
        show(DarkMode.DARK, connected = { true })
        val image = rule.onNodeWithTag(CAST_BUTTON_TAG).captureToImage()
        save(image, "cast-connected-dark.png")
        val icon = iconRgb(image)
        assertEquals("dark connected icon is the dark theme primary", primary and RGB, icon)
        assertNotEquals("the dark toolbar must not get the light teal", TEAL, icon)
    }

    @Test
    fun tapCallsOnClick() {
        var clicks = 0
        show(DarkMode.LIGHT, connected = { false }, onClick = { clicks++ })
        rule.onNodeWithTag(CAST_BUTTON_TAG).performClick()
        rule.waitForIdle()
        assertEquals(1, clicks)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun noSessionIsDisabledAndIgnoresTaps() {
        var clicks = 0
        var connected by mutableStateOf(false)
        show(DarkMode.LIGHT, connected = { connected }, enabled = { false }, onClick = { clicks++ })
        val node = rule.onNodeWithTag(CAST_BUTTON_TAG)
        node.assertIsDisplayed()
        node.assertIsNotEnabled()
        node.performClick()
        rule.waitForIdle()
        assertEquals("a disabled button ignores taps", 0, clicks)
        assertNotEquals("disabled icon is dimmed, not the full content colour", onSurface and RGB, iconRgb(node.captureToImage()))
        // The connected tint never overrides the disabled look.
        rule.runOnIdle { connected = true }
        rule.waitForIdle()
        val disabledConnected = iconRgb(rule.onNodeWithTag(CAST_BUTTON_TAG).captureToImage())
        assertNotEquals("disabled stays dimmed even while connected", primary and RGB, disabledConnected)
    }

    /**
     * Most common colour in the button that is not the background (the corner pixel). The icon's solid
     * body outnumbers its antialiased edge, so this is the icon tint as drawn.
     */
    private fun iconRgb(image: ImageBitmap): Int {
        val map = image.toPixelMap()
        val background = map[0, 0].toArgb() and RGB
        val counts = HashMap<Int, Int>()
        for (x in 0 until map.width) {
            for (y in 0 until map.height) {
                val rgb = map[x, y].toArgb() and RGB
                if (rgb != background) counts[rgb] = (counts[rgb] ?: 0) + 1
            }
        }
        return counts.maxByOrNull { it.value }?.key ?: error("no icon pixels: the button drew nothing")
    }

    /** Evidence for the PR: written under app/build/reports/cast-toolbar (the test's working directory is app/). */
    private fun save(image: ImageBitmap, name: String) {
        val dir = File("build/reports/cast-toolbar").apply { mkdirs() }
        File(dir, name).outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val RGB = 0xFFFFFF
        const val TEAL = 0x216969
    }
}
