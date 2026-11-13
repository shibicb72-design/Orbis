package com.shibi.orbis.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.shibi.orbis.ui.theme.AppThemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Themes keep every stored choice (the legacy "app_theme" key and its values) and never touch web pages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ThemeSettingsTest {
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("theme_settings_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun open() = BrowserSettings(SharedPreferencesBrowserPrefs(prefs))

    @Test
    fun anExistingDarkChoiceWithNoThemeIdReadsDarkAndTealAndWritesNothing() {
        prefs.edit().putString("app_theme", "DARK").commit()
        val before = prefs.all.toMap()
        val s = open()
        assertEquals(DarkMode.DARK, s.darkMode.value)
        assertEquals("orbis_teal", s.themeId.value)
        assertEquals("orbis_teal", AppThemes.byId(s.themeId.value).id)
        assertEquals("upgrading must not write anything", before, prefs.all.toMap())
    }

    @Test
    fun everyStoredDarkModeValueStillReadsBack() {
        for (m in DarkMode.entries) {
            prefs.edit().putString("app_theme", m.name).commit()
            assertEquals(m, open().darkMode.value)
        }
        assertEquals(listOf("SYSTEM", "LIGHT", "DARK"), DarkMode.entries.map { it.name })
    }

    @Test
    fun garbageInTheLegacyKeyReadsSystem() {
        prefs.edit().putString("app_theme", "garbage").commit()
        assertEquals(DarkMode.SYSTEM, open().darkMode.value)
    }

    @Test
    fun aFreshInstallIsSystemAndTeal() {
        val s = open()
        assertEquals(DarkMode.SYSTEM, s.darkMode.value)
        assertEquals("orbis_teal", AppThemes.byId(s.themeId.value).id)
    }

    @Test
    fun anUnknownThemeIdReadsTealButStaysStored() {
        prefs.edit().putString("theme_id", "from_a_newer_build").commit()
        val s = open()
        assertEquals("orbis_teal", AppThemes.byId(s.themeId.value).id)
        assertEquals("from_a_newer_build", prefs.getString("theme_id", null))
        // Choosing a dark mode in the meantime must not drop it either.
        s.setDarkMode(DarkMode.LIGHT)
        assertEquals("from_a_newer_build", prefs.getString("theme_id", null))
    }

    @Test
    fun themeIdSurvivesReopening() {
        val s = open()
        s.setThemeId("harbor")
        val again = open()
        assertEquals("harbor", again.themeId.value)
        assertEquals("harbor", prefs.getString("theme_id", null))
    }

    @Test
    fun aRemovedAnimatedThemeIdReadsBackAsStoredAndShowsTeal() {
        prefs.edit().putString("theme_id", "aurora").commit()
        val before = prefs.all.toMap()
        val s = open()
        assertEquals("aurora", s.themeId.value)
        assertEquals("orbis_teal", AppThemes.byId(s.themeId.value).id)
        assertEquals("reading must not write anything", before, prefs.all.toMap())
    }

    @Test
    fun darkModeStillWritesTheLegacyKey() {
        open().setDarkMode(DarkMode.DARK)
        assertEquals("DARK", prefs.getString("app_theme", null))
    }

    @Test
    fun aThemeChangeNeverChangesTheWebsiteColourScheme() {
        val s = open()
        val seen = mutableListOf<Boolean?>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { s.websiteColorScheme().collect { seen += it } }
        assertEquals(listOf<Boolean?>(null), seen)
        for (id in AppThemes.all.map { it.id }) s.setThemeId(id)
        assertEquals("theme changes must not reach the engine", listOf<Boolean?>(null), seen)
        s.setDarkMode(DarkMode.DARK)
        assertEquals(listOf<Boolean?>(null, true), seen)
        s.setThemeId("paper")
        assertEquals(listOf<Boolean?>(null, true), seen)
        s.setDarkMode(DarkMode.LIGHT)
        assertEquals(listOf<Boolean?>(null, true, false), seen)
        s.setDarkWebsites(true)
        assertEquals(listOf<Boolean?>(null, true, false, true), seen)
        job.cancel()
    }

    @Test
    fun websiteSchemeFollowsOnlyDarkModeAndDarkWebsites() {
        val s = open()
        assertNull(firstValue(s))
        s.setDarkMode(DarkMode.LIGHT)
        assertFalse(firstValue(s)!!)
        s.setDarkMode(DarkMode.SYSTEM)
        s.setDarkWebsites(true)
        assertTrue(firstValue(s)!!)
    }

    private fun firstValue(s: BrowserSettings): Boolean? = runBlocking { s.websiteColorScheme().first() }
}
