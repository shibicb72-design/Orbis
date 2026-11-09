package com.shibi.orbis.ui.browser

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import com.shibi.orbis.engine.SessionState
import com.shibi.orbis.engine.afterContentProcessGone
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P1.2: page fullscreen keeps the screen on only while it lasts; a crashed page is never left fullscreen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FullScreenComposeTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    @Test
    fun keepScreenOnFollowsFullScreen() {
        var fullScreen by mutableStateOf(false)
        lateinit var view: View
        rule.setContent {
            view = LocalView.current
            FullScreenWindowEffect(fullScreen)
        }
        rule.waitForIdle()
        assertFalse(view.keepScreenOn)
        rule.runOnIdle { fullScreen = true }
        rule.waitForIdle()
        assertTrue(view.keepScreenOn)
        rule.runOnIdle { fullScreen = false }
        rule.waitForIdle()
        assertFalse(view.keepScreenOn)
    }

    @Test
    fun aContentProcessCrashEndsFullScreen() {
        assertFalse(SessionState(fullScreen = true).afterContentProcessGone().fullScreen)
    }
}
