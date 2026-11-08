package com.shibi.orbis.ui.browser

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.ExternalResource
import org.robolectric.Shadows.shadowOf

/**
 * Registers the plain ComponentActivity that the Compose test rule launches, test-side only. The shipped manifests are
 * untouched (no ui-test-manifest in any APK). Must run before the compose rule: use RuleChain or `order = 0`.
 */
class ComposeActivityRegistration : ExternalResource() {
    override fun before() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
    }
}
