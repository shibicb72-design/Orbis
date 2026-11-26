package com.shibi.orbis.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.shibi.orbis.tabs.TabSleepAfter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings > Put unused tabs to sleep: "After 1 hour" by default, also for a settings file from an older version; kept when changed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TabSleepSettingTest {
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("tab_sleep_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun open() = BrowserSettings(SharedPreferencesBrowserPrefs(prefs))

    @Test
    fun aFreshInstallSleepsTabsAfterOneHour() {
        assertEquals(TabSleepAfter.AFTER_1_HOUR, open().tabSleepAfter.value)
    }

    @Test
    fun aSettingsFileFromAnOlderVersionReadsAsAfterOneHour() {
        // What 0.8.4 wrote: other settings, no tab_sleep_after key.
        prefs.edit()
            .putString("https_only", "ALL")
            .putBoolean("javascript", false)
            .putString("app_theme", "DARK")
            .commit()
        val s = open()
        assertEquals(TabSleepAfter.AFTER_1_HOUR, s.tabSleepAfter.value)
        assertFalse(s.javaScript.value)
    }

    @Test
    fun anUnknownStoredValueReadsAsAfterOneHour() {
        prefs.edit().putString("tab_sleep_after", "AFTER_1_WEEK").commit()
        assertEquals(TabSleepAfter.AFTER_1_HOUR, open().tabSleepAfter.value)
    }

    @Test
    fun eachChoiceIsKeptAcrossARestart() {
        for (choice in TabSleepAfter.entries) {
            open().setTabSleepAfter(choice)
            assertEquals(choice, open().tabSleepAfter.value)
            assertEquals(choice.name, prefs.getString("tab_sleep_after", null))
        }
    }
}
