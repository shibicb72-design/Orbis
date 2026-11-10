package com.shibi.orbis.about

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RetireGithubUpdateCheckerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun dropsTheUpdatePrefsAndCancelsTheScheduledJob() {
        context.getSharedPreferences(LEGACY_UPDATE_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("auto_check", true)
            .putLong("last_check_millis", 123L)
            .putString("announced_version", "0.4.6")
            .commit()
        val cancelled = mutableListOf<String>()
        retireGithubUpdateChecker(context, UpdateWorkCanceller { cancelled += it })
        val prefs = context.getSharedPreferences(LEGACY_UPDATE_PREFS, Context.MODE_PRIVATE)
        assertFalse(prefs.contains("auto_check"))
        assertFalse(prefs.contains("last_check_millis"))
        assertNull(prefs.getString("announced_version", null))
        assertEquals(listOf(LEGACY_UPDATE_WORK), cancelled)
        assertEquals("com.shibi.orbis.update.check", LEGACY_UPDATE_WORK)
    }

    @Test
    fun theRealCancelDropsPrefsEvenIfWorkManagerIsNotInitialized() {
        context.getSharedPreferences(LEGACY_UPDATE_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("auto_check", true)
            .commit()
        retireGithubUpdateChecker(context)
        val prefs = context.getSharedPreferences(LEGACY_UPDATE_PREFS, Context.MODE_PRIVATE)
        assertFalse(prefs.contains("auto_check"))
    }

    @Test
    fun aFailedCancelStillDropsThePrefs() {
        context.getSharedPreferences(LEGACY_UPDATE_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("auto_check", false)
            .commit()
        retireGithubUpdateChecker(context, UpdateWorkCanceller { error("work manager unavailable") })
        val prefs = context.getSharedPreferences(LEGACY_UPDATE_PREFS, Context.MODE_PRIVATE)
        assertFalse(prefs.contains("auto_check"))
    }
}
