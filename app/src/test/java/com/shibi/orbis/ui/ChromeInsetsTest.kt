package com.shibi.orbis.ui

import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tells [view] (the Compose host) that the display cutout covers [leftPx] pixels on the left and nothing elsewhere. */
internal fun dispatchLeftCutout(view: View, leftPx: Int) {
    val insets = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(leftPx, 0, 0, 0))
        .build()
    ViewCompat.dispatchApplyWindowInsets(view, insets)
}

/** [chromeWindowInsets]: the system bars always, plus the side cutout unless the page is fullscreen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ChromeInsetsTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    @Test
    fun theCutoutIsAvoidedOnlyWhenAsked() {
        lateinit var view: View
        lateinit var density: Density
        lateinit var avoiding: WindowInsets
        lateinit var ignoring: WindowInsets
        rule.setContent {
            view = LocalView.current
            density = LocalDensity.current
            avoiding = chromeWindowInsets(avoidCutout = true)
            ignoring = chromeWindowInsets(avoidCutout = false)
        }
        rule.waitForIdle()
        val cutoutPx = with(density) { 40.dp.roundToPx() }
        rule.runOnUiThread { dispatchLeftCutout(view, cutoutPx) }
        rule.waitForIdle()
        val ltr = LayoutDirection.Ltr
        assertEquals(cutoutPx, avoiding.getLeft(density, ltr))
        assertEquals(0, ignoring.getLeft(density, ltr))
        assertEquals(ignoring.getTop(density), avoiding.getTop(density))
        assertEquals(ignoring.getBottom(density), avoiding.getBottom(density))
        assertEquals(ignoring.getRight(density, ltr), avoiding.getRight(density, ltr))
    }
}
