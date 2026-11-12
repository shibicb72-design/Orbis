package com.shibi.orbis.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.shibi.orbis.engine.SitePermissionAnswer
import com.shibi.orbis.engine.SitePermissionKind
import com.shibi.orbis.engine.SitePrompt
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import com.shibi.orbis.ui.browser.SitePromptDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * F-15: Android 12+ still passes taps through sufficiently translucent overlays (at or below 80% opacity). A real
 * touch on the site-permission prompt's Allow button, sent through the dialog window as Android would, is dropped
 * when it carries FLAG_WINDOW_IS_OBSCURED and answers when it does not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 35], application = android.app.Application::class)
class ObscuredTouchTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val answers = ArrayList<SitePermissionAnswer?>()

    private fun showPermissionPrompt(): View {
        val prompt = SitePrompt.SitePermission("https://example.com", null, listOf(SitePermissionKind.CAMERA)) { answers += it }
        rule.setContent { MaterialTheme { SitePromptDialog(prompt) } }
        rule.waitForIdle()
        rule.passTapGuard()
        return ShadowDialog.getLatestDialog().window!!.decorView
    }

    /** A finger down and up at the centre of [text], through the window's root view. */
    private fun tap(decor: View, text: String, obscured: Boolean): Boolean {
        val centre = rule.onNodeWithText(text).fetchSemanticsNode().boundsInWindow.center
        val flags = if (obscured) MotionEvent.FLAG_WINDOW_IS_OBSCURED else 0
        val down = SystemClock.uptimeMillis()
        var handled = true
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { x = centre.x; y = centre.y; pressure = 1f; size = 1f })
            val event = MotionEvent.obtain(down, down + 50, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags)
            handled = decor.dispatchTouchEvent(event) && handled
            event.recycle()
        }
        rule.waitForIdle()
        return handled
    }

    @Test
    fun theDialogWindowFiltersObscuredTouches() {
        assertTrue(showPermissionPrompt().filterTouchesWhenObscured)
    }

    @Test
    fun anObscuredTapOnAllowIsDroppedAndAClearOneAnswers() {
        val decor = showPermissionPrompt()
        tap(decor, "Allow", obscured = true)
        assertTrue(answers.isEmpty())
        tap(decor, "Allow", obscured = false)
        assertEquals(listOf(SitePermissionAnswer(allow = true, remember = false)), answers)
    }
}
