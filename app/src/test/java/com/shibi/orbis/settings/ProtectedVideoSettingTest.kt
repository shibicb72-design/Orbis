package com.shibi.orbis.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.shibi.orbis.engine.ProtectedVideoAnswer
import com.shibi.orbis.engine.protectedVideoAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings > Play protected video (DRM): on by default, also for a settings file from an older version; kept when changed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ProtectedVideoSettingTest {
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("protected_video_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun open() = BrowserSettings(SharedPreferencesBrowserPrefs(prefs))

    @Test
    fun aFreshInstallHasItOn() {
        val s = open()
        assertTrue(s.protectedVideo.value)
        assertTrue(s.protectedVideoOn())
    }

    @Test
    fun aSettingsFileFromAnOlderVersionReadsAsOn() {
        // What 0.8.2 wrote: other settings, no protected_video key.
        prefs.edit()
            .putString("https_only", "ALL")
            .putBoolean("javascript", false)
            .putBoolean("force_dark", true)
            .putString("app_theme", "DARK")
            .commit()
        val s = open()
        assertTrue(s.protectedVideo.value)
        assertTrue(s.protectedVideoOn())
        assertFalse(s.javaScript.value)
    }

    @Test
    fun turningItOffIsKeptAcrossARestartAndOnAgainToo() {
        open().setProtectedVideo(false)
        val off = open()
        assertFalse(off.protectedVideo.value)
        assertFalse(off.protectedVideoOn())
        assertFalse(prefs.getBoolean("protected_video", true))

        off.setProtectedVideo(true)
        assertTrue(open().protectedVideo.value)
    }

    @Test
    fun aSiteAllowedWhileOnIsRefusedOnceTheSettingIsTurnedOff() {
        // Nothing per site: the same site asking again after the switch is answered from the setting alone.
        val s = open()
        assertEquals(ProtectedVideoAnswer.ALLOW, protectedVideoAnswer(s.protectedVideoOn(), isPrivate = false))
        s.setProtectedVideo(false)
        assertEquals(ProtectedVideoAnswer.DENY_SETTING_OFF, protectedVideoAnswer(s.protectedVideoOn(), isPrivate = false))
        s.setProtectedVideo(true)
        assertEquals(ProtectedVideoAnswer.ALLOW, protectedVideoAnswer(s.protectedVideoOn(), isPrivate = false))
    }
}
