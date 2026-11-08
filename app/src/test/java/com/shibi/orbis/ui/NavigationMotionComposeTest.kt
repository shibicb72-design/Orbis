package com.shibi.orbis.ui

import android.provider.Settings
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import com.shibi.orbis.ui.theme.rememberMotionEnabled
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Slice 4: the motion gate follows the system animator duration scale, live, without a competing app setting. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NavigationMotionComposeTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val resolver get() = ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver
    private val seen = mutableListOf<Boolean>()

    @After
    fun reset() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    @Test
    fun motionTurnsOffAndOnWithTheSystemScale() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        rule.setContent {
            val enabled = rememberMotionEnabled()
            SideEffect { if (seen.lastOrNull() != enabled) seen += enabled }
        }
        rule.waitForIdle()
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        rule.waitForIdle()
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0.5f)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        rule.waitForIdle()
        assertEquals(listOf(true, false, true), seen)
    }
}
