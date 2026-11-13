package com.shibi.orbis.ui.passwords

import android.app.Application
import android.hardware.biometrics.BiometricManager.Authenticators
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.shibi.orbis.R
import com.shibi.orbis.passwords.LoginManager
import com.shibi.orbis.passwords.LoginStore
import com.shibi.orbis.passwords.NeverSavePrefs
import com.shibi.orbis.passwords.SavedLogin
import com.shibi.orbis.settings.DarkMode
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import com.shibi.orbis.ui.theme.OrbisTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the unlock prompt accepts. */
class AllowedAuthenticatorsTest {
    @Test
    fun aStrongBiometricOrTheScreenLockIsNeeded() {
        assertEquals(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL, allowedAuthenticators())
    }

    @Test
    fun aWeakBiometricIsNeverEnough() {
        assertEquals(0, allowedAuthenticators() and Authenticators.BIOMETRIC_WEAK and Authenticators.BIOMETRIC_STRONG.inv())
    }
}

private class OneLoginStore : LoginStore {
    override fun load(): List<SavedLogin> =
        listOf(SavedLogin(guid = "g1", origin = "https://example.com", username = "u", password = "p", timeCreated = 0L, timeLastUsed = 0L))

    override fun save(items: List<SavedLogin>) {}

    override fun setAside() {}
}

private class NoNeverSave : NeverSavePrefs {
    override var neverSave: Set<String> = emptySet()
}

/** Import, like Export, needs the screen unlocked: a borrowed phone cannot plant logins. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PasswordsUnlockGateTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() = scope.cancel()

    /** The screen's lifecycle, so a test can send the app to the background (ON_STOP) and back. */
    private val owner =
        object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }.also { it.registry.currentState = Lifecycle.State.RESUMED }

    private fun show(unlocked: Boolean) {
        val manager =
            LoginManager(OneLoginStore(), NoNeverSave(), scope, log = {}, now = { 0L }, io = dispatcher).also {
                it.load()
                scheduler.advanceUntilIdle()
            }
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                OrbisTheme(DarkMode.LIGHT) {
                    PasswordsScreen(manager = manager, offerSaveFlow = MutableStateFlow(true), onSetOfferSave = {}, onBack = {}, startUnlocked = unlocked)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun openMenu(unlocked: Boolean) {
        show(unlocked)
        rule.onNodeWithContentDescription(app.getString(R.string.menu_more)).performClick()
        rule.waitForIdle()
    }

    /** Home and back: the screen stops (ON_STOP) and resumes. */
    private fun leaveAndReturn() {
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.waitForIdle()
    }

    @Test
    fun importAndExportAreDisabledWhileLocked() {
        openMenu(unlocked = false)
        rule.onNodeWithText(app.getString(R.string.passwords_import)).assertIsNotEnabled()
        rule.onNodeWithText(app.getString(R.string.passwords_export)).assertIsNotEnabled()
    }

    @Test
    fun importAndExportAreEnabledOnceUnlocked() {
        openMenu(unlocked = true)
        rule.onNodeWithText(app.getString(R.string.passwords_import)).assertIsEnabled()
        rule.onNodeWithText(app.getString(R.string.passwords_export)).assertIsEnabled()
    }

    @Test
    fun theExportWarningDoesNotSurviveTheRelock() {
        openMenu(unlocked = true)
        rule.onNodeWithText(app.getString(R.string.passwords_export)).performClick()
        rule.onNodeWithText(app.getString(R.string.passwords_export_warning)).assertIsDisplayed()
        leaveAndReturn()
        rule.onNodeWithText(app.getString(R.string.passwords_export_warning)).assertDoesNotExist()
        // Relocked: the list is hidden (Robolectric has no screen lock, so the "no lock" text replaces it).
        rule.onNodeWithText("example.com").assertDoesNotExist()
        rule.onNodeWithContentDescription(app.getString(R.string.menu_more)).performClick()
        rule.onNodeWithText(app.getString(R.string.passwords_export)).assertIsNotEnabled()
    }

    @Test
    fun theDeleteConfirmationDoesNotSurviveTheRelock() {
        show(unlocked = true)
        rule.onNodeWithText("example.com").performClick()
        rule.onNodeWithText(app.getString(R.string.passwords_delete)).performClick()
        val confirm = app.getString(R.string.passwords_delete_confirm, "example.com")
        rule.onNodeWithText(confirm).assertIsDisplayed()
        leaveAndReturn()
        rule.onNodeWithText(confirm).assertDoesNotExist()
        // Relocked: the list is hidden (Robolectric has no screen lock, so the "no lock" text replaces it).
        rule.onNodeWithText("example.com").assertDoesNotExist()
    }
}
