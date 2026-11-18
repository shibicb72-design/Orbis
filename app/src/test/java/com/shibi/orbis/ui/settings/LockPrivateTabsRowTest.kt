package com.shibi.orbis.ui.settings

import android.app.KeyguardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.shibi.orbis.privacy.PrivacyPrefs
import com.shibi.orbis.privacy.SharedPreferencesPrivacyPrefs
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LockPrivateTabsRowTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var prefs: PrivacyPrefs

    @Before
    fun setUp() {
        val sp = context.getSharedPreferences(SharedPreferencesPrivacyPrefs.FILE_NAME, Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        prefs = SharedPreferencesPrivacyPrefs(sp)
    }

    private fun screenLock(set: Boolean) {
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(set)
    }

    private fun show() {
        rule.setContent { MaterialTheme { LockPrivateTabsRow(prefs) } }
    }

    @Test(timeout = 60_000)
    fun offByDefault() {
        screenLock(true)
        assertFalse(prefs.lockPrivateTabs)
    }

    @Test(timeout = 60_000)
    fun aTapOnTheWordsTurnsItOn() {
        screenLock(true)
        show()
        rule.onNodeWithText("Lock private tabs").performClick()
        assertTrue(prefs.lockPrivateTabs)
        rule.onNodeWithText("Lock private tabs").performClick()
        assertFalse(prefs.lockPrivateTabs)
    }

    @Test(timeout = 60_000)
    fun theSummaryIsShownWithAScreenLock() {
        screenLock(true)
        show()
        rule.onNodeWithText("Ask for your fingerprint or screen lock when you come back to Orbis.").assertExists()
    }

    @Test(timeout = 60_000)
    fun withNoScreenLockTheSwitchCannotBeTurnedOn() {
        screenLock(false)
        show()
        rule.onNodeWithText("Set a screen lock on this phone to lock private tabs.").assertExists()
        rule.onNode(isToggleable()).assertIsNotEnabled()
        rule.onNodeWithText("Lock private tabs").performClick()
        assertFalse(prefs.lockPrivateTabs)
    }

    @Test(timeout = 60_000)
    fun aStoredOnWithNoScreenLockShowsOff() {
        prefs.lockPrivateTabs = true
        screenLock(false)
        show()
        rule.onNode(isToggleable()).assertIsNotEnabled()
    }
}
