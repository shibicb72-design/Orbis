package com.shibi.orbis.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings > Also block content trackers: off by default, also for a settings file from an older version; kept when changed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ContentTrackersSettingTest {
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("content_trackers_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun open() = BrowserSettings(SharedPreferencesBrowserPrefs(prefs))

    @Test
    fun aFreshInstallHasItOff() {
        assertFalse(open().blockContentTrackers.value)
    }

    @Test
    fun aSettingsFileFromAnOlderVersionReadsAsOff() {
        // What 0.8.3 wrote: other settings, no block_content_trackers key.
        prefs.edit()
            .putString("https_only", "ALL")
            .putBoolean("javascript", false)
            .putBoolean("force_dark", true)
            .putString("app_theme", "DARK")
            .commit()
        val s = open()
        assertFalse(s.blockContentTrackers.value)
        assertFalse(s.javaScript.value)
    }

    @Test
    fun turningItOnIsKeptAcrossARestartAndOffAgainToo() {
        open().setBlockContentTrackers(true)
        val on = open()
        assertTrue(on.blockContentTrackers.value)
        assertTrue(prefs.getBoolean("block_content_trackers", false))

        on.setBlockContentTrackers(false)
        assertFalse(open().blockContentTrackers.value)
    }
}
