package com.shibi.orbis.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The auto-desktop choices: stored like the other per-site desktop choices, private ones in memory only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AutoDesktopSettingsTest {
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("auto_desktop_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun open() = BrowserSettings(SharedPreferencesBrowserPrefs(prefs))

    @Test
    fun keptAndDeclinedSitesSurviveANewInstance() {
        val s = open()
        s.rememberAutoDesktop("https://Gate.example/check", isPrivate = false)
        s.declineAutoDesktop("https://blocked.example/", isPrivate = false)

        val again = open()
        assertTrue(again.isAutoDesktop("https://gate.example/other", isPrivate = false))
        assertTrue(again.desktopFor("https://gate.example/", isPrivate = false))
        assertFalse(again.isAutoDesktopDeclined("https://gate.example/", isPrivate = false))
        assertTrue(again.isAutoDesktopDeclined("https://blocked.example/x", isPrivate = false))
        assertFalse(again.isAutoDesktop("https://blocked.example/", isPrivate = false))
        assertFalse(again.desktopFor("https://blocked.example/", isPrivate = false))
        assertEquals(setOf("gate.example"), prefs.getStringSet("desktop_auto_sites", null))
        assertEquals(setOf("blocked.example"), prefs.getStringSet("desktop_auto_declined_sites", null))
        assertEquals(setOf("gate.example"), prefs.getStringSet("desktop_on_sites", null))
    }

    @Test
    fun unTickingDesktopOnAnAutoSiteDeclinesIt() {
        val s = open()
        s.rememberAutoDesktop("https://gate.example/", isPrivate = false)
        s.rememberDesktop("https://gate.example/page", on = false, isPrivate = false)

        assertFalse(s.desktopFor("https://gate.example/", isPrivate = false))
        assertFalse(s.isAutoDesktop("https://gate.example/", isPrivate = false))
        assertTrue(s.isAutoDesktopDeclined("https://gate.example/", isPrivate = false))
        val again = open()
        assertTrue(again.isAutoDesktopDeclined("https://gate.example/", isPrivate = false))
        assertEquals(emptySet<String>(), prefs.getStringSet("desktop_auto_sites", null))
    }

    @Test
    fun unTickingDesktopOnAUserChosenSiteDoesNotDecline() {
        val s = open()
        s.rememberDesktop("https://mine.example/", on = true, isPrivate = false)
        s.rememberDesktop("https://mine.example/", on = false, isPrivate = false)
        assertFalse(s.isAutoDesktopDeclined("https://mine.example/", isPrivate = false))
        assertFalse(s.desktopFor("https://mine.example/", isPrivate = false))
    }

    @Test
    fun tickingDesktopClearsTheDecline() {
        val s = open()
        s.declineAutoDesktop("https://gate.example/", isPrivate = false)
        s.rememberDesktop("https://gate.example/", on = true, isPrivate = false)

        assertFalse(s.isAutoDesktopDeclined("https://gate.example/", isPrivate = false))
        assertTrue(s.desktopFor("https://gate.example/", isPrivate = false))
        assertFalse(open().isAutoDesktopDeclined("https://gate.example/", isPrivate = false))
        assertEquals(emptySet<String>(), prefs.getStringSet("desktop_auto_declined_sites", null))
    }

    @Test
    fun privateChoicesNeverReachPrefsAndEndWithForgetPrivate() {
        val s = open()
        val before = prefs.all.toMap()
        s.rememberAutoDesktop("https://gate.example/", isPrivate = true)
        s.declineAutoDesktop("https://blocked.example/", isPrivate = true)
        s.rememberDesktop("https://other.example/", on = true, isPrivate = true)

        assertEquals(before, prefs.all.toMap())
        assertTrue(s.isAutoDesktop("https://gate.example/", isPrivate = true))
        assertTrue(s.desktopFor("https://gate.example/", isPrivate = true))
        assertTrue(s.isAutoDesktopDeclined("https://blocked.example/", isPrivate = true))
        // Normal tabs never see them.
        assertFalse(s.isAutoDesktop("https://gate.example/", isPrivate = false))
        assertFalse(s.desktopFor("https://gate.example/", isPrivate = false))
        assertFalse(s.isAutoDesktopDeclined("https://blocked.example/", isPrivate = false))
        // Untick in the private tab: declined there only.
        s.rememberDesktop("https://gate.example/", on = false, isPrivate = true)
        assertTrue(s.isAutoDesktopDeclined("https://gate.example/", isPrivate = true))
        assertEquals(before, prefs.all.toMap())

        s.forgetPrivate()
        assertFalse(s.isAutoDesktop("https://gate.example/", isPrivate = true))
        assertFalse(s.isAutoDesktopDeclined("https://gate.example/", isPrivate = true))
        assertFalse(s.isAutoDesktopDeclined("https://blocked.example/", isPrivate = true))
        assertFalse(s.desktopFor("https://gate.example/", isPrivate = true))
        assertFalse(s.desktopFor("https://other.example/", isPrivate = true))
        assertEquals(before, prefs.all.toMap())
    }

    @Test
    fun privateTabsReadTheNormalChoices() {
        val s = open()
        s.rememberAutoDesktop("https://gate.example/", isPrivate = false)
        s.declineAutoDesktop("https://blocked.example/", isPrivate = false)
        assertTrue(s.isAutoDesktop("https://gate.example/", isPrivate = true))
        assertTrue(s.isAutoDesktopDeclined("https://blocked.example/", isPrivate = true))
    }
}
