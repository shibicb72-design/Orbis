package com.shibi.orbis.ui.browser

import android.graphics.Bitmap
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.shibi.orbis.cast.CastTrustPrompt
import com.shibi.orbis.cast.CastTrustReason
import com.shibi.orbis.settings.AppTheme
import com.shibi.orbis.ui.passTapGuard
import com.shibi.orbis.ui.theme.OrbisTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * F-01: the Cast sheet's trust question. Each reason shows its own text and both buttons; a changed key is drawn in
 * the error colour on the sheet's container with "Cancel" filled; the others use the content colour with "Trust and
 * cast" filled. Light and dark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CastTrustSheetTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private var error = 0
    private var onSurface = 0
    private var primary = 0
    private var confirms = 0
    private var cancels = 0

    private fun show(theme: AppTheme, reason: CastTrustReason) {
        rule.setContent {
            OrbisTheme(theme) {
                error = MaterialTheme.colorScheme.error.toArgb() and RGB
                onSurface = MaterialTheme.colorScheme.onSurface.toArgb() and RGB
                primary = MaterialTheme.colorScheme.primary.toArgb() and RGB
                // The sheet's own container, as CastSheet sets it.
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, contentColor = MaterialTheme.colorScheme.onSurface) {
                    CastTrustQuestion(
                        CastTrustPrompt("Living Room", reason),
                        onConfirm = { confirms++ },
                        onCancel = { cancels++ },
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun check(theme: AppTheme, reason: CastTrustReason, expectedText: String) {
        show(theme, reason)
        rule.onNodeWithTag(CAST_TRUST_TEXT_TAG).assertIsDisplayed().assertTextEquals(expectedText)
        rule.onNodeWithTag(CAST_TRUST_CONFIRM_TAG).assertIsDisplayed()
        rule.onNodeWithText("Trust and cast").assertIsDisplayed()
        rule.onNodeWithTag(CAST_TRUST_CANCEL_TAG).assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()

        val name = "${reason.name.lowercase()}-${theme.name.lowercase()}"
        val text = rule.onNodeWithTag(CAST_TRUST_TEXT_TAG).captureToImage().also { save(it, "trust-text-$name.png") }
        val warning = reason == CastTrustReason.KEY_CHANGED
        val textColour = if (warning) error else onSurface
        val otherColour = if (warning) onSurface else error
        assertTrue("$name: text is drawn in ${hex(textColour)}", count(text, textColour) > 0)
        assertEquals("$name: no text pixel in ${hex(otherColour)}", 0, count(text, otherColour))

        val confirm = rule.onNodeWithTag(CAST_TRUST_CONFIRM_TAG).captureToImage().also { save(it, "trust-confirm-$name.png") }
        val cancel = rule.onNodeWithTag(CAST_TRUST_CANCEL_TAG).captureToImage().also { save(it, "trust-cancel-$name.png") }
        val filled = if (warning) cancel else confirm
        val plain = if (warning) confirm else cancel
        assertTrue("$name: the filled button is mostly primary", share(filled, primary) > 0.5)
        assertTrue("$name: the text button is not filled", share(plain, primary) < 0.2)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun firstUseIsNeutralInLight() {
        val text = "First time casting to “Living Room”. Orbis will remember this device and warn you if a different one uses its name."
        check(AppTheme.LIGHT, CastTrustReason.FIRST_USE, text)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun firstUseIsNeutralInDark() {
        val text = "First time casting to “Living Room”. Orbis will remember this device and warn you if a different one uses its name."
        check(AppTheme.DARK, CastTrustReason.FIRST_USE, text)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aChangedKeyIsAWarningInLight() {
        check(AppTheme.LIGHT, CastTrustReason.KEY_CHANGED, CHANGED)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aChangedKeyIsAWarningInDark() {
        check(AppTheme.DARK, CastTrustReason.KEY_CHANGED, CHANGED)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aRenameIsNeutralInLight() {
        check(AppTheme.LIGHT, CastTrustReason.RENAMED, RENAMED)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aRenameIsNeutralInDark() {
        check(AppTheme.DARK, CastTrustReason.RENAMED, RENAMED)
    }

    @Test
    fun theButtonsAnswerTheQuestion() {
        show(AppTheme.LIGHT, CastTrustReason.KEY_CHANGED)
        rule.onNodeWithTag(CAST_TRUST_CANCEL_TAG).performClick()
        rule.waitForIdle()
        assertEquals(0, confirms)
        assertEquals(1, cancels)
        // At once the confirm button does nothing (F-15 tap guard); after the delay it answers.
        rule.onNodeWithTag(CAST_TRUST_CONFIRM_TAG).performClick()
        rule.waitForIdle()
        assertEquals(0, confirms)
        passTapGuard()
        rule.onNodeWithTag(CAST_TRUST_CONFIRM_TAG).performClick()
        rule.waitForIdle()
        assertEquals(1, confirms)
        assertEquals(1, cancels)
    }

    private fun count(image: ImageBitmap, rgb: Int): Int {
        val map = image.toPixelMap()
        var n = 0
        for (x in 0 until map.width) for (y in 0 until map.height) if (map[x, y].toArgb() and RGB == rgb) n++
        return n
    }

    private fun share(image: ImageBitmap, rgb: Int): Double = count(image, rgb).toDouble() / (image.width * image.height)

    private fun hex(rgb: Int): String = "#%06x".format(rgb)

    /** Evidence for the PR: written under app/build/reports/cast-trust (the test's working directory is app/). */
    private fun save(image: ImageBitmap, name: String) {
        val dir = File("build/reports/cast-trust").apply { mkdirs() }
        File(dir, name).outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val RGB = 0xFFFFFF
        const val CHANGED =
            "“Living Room” is not the device Orbis remembers under this name. That is normal after a TV was replaced or " +
                "reset. If nothing changed, another device on this Wi-Fi may be pretending to be your TV, and it would " +
                "receive the video address."
        const val RENAMED = "This device was trusted under another name and is now called “Living Room”."
    }
}
