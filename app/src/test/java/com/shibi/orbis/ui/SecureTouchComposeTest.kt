package com.shibi.orbis.ui

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shibi.orbis.engine.SitePermissionAnswer
import com.shibi.orbis.engine.SitePermissionKind
import com.shibi.orbis.engine.SitePrompt
import com.shibi.orbis.torrent.IntakeRequest
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import com.shibi.orbis.ui.browser.SitePromptDialog
import com.shibi.orbis.ui.browser.TorrentBody
import com.shibi.orbis.ui.browser.TorrentConfirmModel
import com.shibi.orbis.ui.browser.TorrentConfirmSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * F-15 (device item 6): a confirm button ignores a tap that comes at once (a second tap of a double tap on the page
 * that opened it), answers one after the delay, and Cancel is never delayed. The dialog's window drops touches that
 * come through another app's window on top.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SecureTouchComposeTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val magnet = "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel"
    private var downloads = 0
    private var cancels = 0
    private val answers = ArrayList<SitePermissionAnswer?>()

    private fun showTorrentSheet() {
        val model = TorrentConfirmModel.of(IntakeRequest.Magnet(magnet, isPrivate = false), TorrentBody.Reading, ipNoteSeen = true)
        rule.setContent { MaterialTheme { TorrentConfirmSheet(model, onDownload = { downloads++ }, onCancel = { cancels++ }) } }
        rule.waitForIdle()
    }

    private fun showPermissionPrompt() {
        val prompt = SitePrompt.SitePermission("https://example.com", null, listOf(SitePermissionKind.CAMERA)) { answers += it }
        rule.setContent { MaterialTheme { SitePromptDialog(prompt) } }
        rule.waitForIdle()
    }

    @Test
    fun theTorrentSheetIgnoresADownloadTapAtOnceAndTakesOneAfterTheDelay() {
        showTorrentSheet()
        rule.onNodeWithText("Download").performClick()
        rule.waitForIdle()
        assertEquals(0, downloads)
        passTapGuard()
        rule.onNodeWithText("Download").performClick()
        rule.waitForIdle()
        assertEquals(1, downloads)
        assertEquals(0, cancels)
    }

    @Test
    fun theTorrentSheetCancelsAtOnce() {
        showTorrentSheet()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertEquals(1, cancels)
        assertEquals(0, downloads)
    }

    @Test
    fun theTorrentSheetWindowDropsObscuredTouches() {
        showTorrentSheet()
        assertTrue(ShadowDialog.getLatestDialog().window!!.decorView.filterTouchesWhenObscured)
    }

    @Test
    fun aPermissionPromptIgnoresAllowAtOnceAndTakesItAfterTheDelay() {
        showPermissionPrompt()
        rule.onNodeWithText("Allow").performClick()
        rule.waitForIdle()
        assertTrue(answers.isEmpty())
        passTapGuard()
        rule.onNodeWithText("Allow").performClick()
        rule.waitForIdle()
        assertEquals(listOf(SitePermissionAnswer(allow = true, remember = false)), answers)
    }

    @Test
    fun aPermissionPromptDeniesAtOnce() {
        showPermissionPrompt()
        rule.onNodeWithText("Deny").performClick()
        rule.waitForIdle()
        assertEquals(listOf(SitePermissionAnswer(allow = false, remember = false)), answers)
    }

    @Test
    fun aPermissionPromptWindowDropsObscuredTouches() {
        showPermissionPrompt()
        assertTrue(ShadowDialog.getLatestDialog().window!!.decorView.filterTouchesWhenObscured)
    }

    @Test
    fun regainingFocusStartsTheDelayAgain() {
        showPermissionPrompt()
        passTapGuard()
        val decor = ShadowDialog.getLatestDialog().window!!.decorView
        windowFocus(decor, false)
        windowFocus(decor, true)
        rule.onNodeWithText("Allow").performClick()
        rule.waitForIdle()
        assertTrue(answers.isEmpty())
        passTapGuard()
        rule.onNodeWithText("Allow").performClick()
        rule.waitForIdle()
        assertEquals(1, answers.size)
    }

    /** What the window's ViewRootImpl does when the window loses or gains focus (Compose listens on the tree observer). */
    private fun windowFocus(decor: View, focused: Boolean) {
        ViewTreeObserver::class.java
            .getDeclaredMethod("dispatchOnWindowFocusChange", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(decor.viewTreeObserver, focused)
        rule.waitForIdle()
    }

    @Test
    fun theFilterHoldsWhileComposedAndRestoresTheWindowAfterwards() {
        var shown by mutableStateOf(false)
        var nested by mutableStateOf(false)
        var root: View? = null
        rule.setContent {
            root = LocalView.current.rootView
            if (shown) {
                ObscuredTouchFilter()
                if (nested) ObscuredTouchFilter()
            }
        }
        rule.waitForIdle()
        val window = root!!
        assertFalse(window.filterTouchesWhenObscured)
        shown = true
        nested = true
        rule.waitForIdle()
        assertTrue(window.filterTouchesWhenObscured)
        // One of two filters leaving keeps the window filtered.
        nested = false
        rule.waitForIdle()
        assertTrue(window.filterTouchesWhenObscured)
        shown = false
        rule.waitForIdle()
        assertFalse(window.filterTouchesWhenObscured)
    }
}
