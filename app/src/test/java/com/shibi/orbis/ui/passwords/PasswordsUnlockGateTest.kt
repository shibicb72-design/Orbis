package com.shibi.orbis.ui.passwords

import android.app.Application
import android.hardware.biometrics.BiometricManager.Authenticators
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.shibi.orbis.R
import com.shibi.orbis.passwords.LoginManager
import com.shibi.orbis.passwords.LoginStore
import com.shibi.orbis.passwords.NeverSavePrefs
import com.shibi.orbis.passwords.SavedLogin
import com.shibi.orbis.settings.AppTheme
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
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the unlock prompt accepts on each Android version. */
class AllowedAuthenticatorsTest {
    @Test
    fun android11AndLaterNeedAStrongBiometricOrTheScreenLock() {
        val strongOrLock = Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
        for (sdk in listOf(30, 31, 33, 35, 36)) assertEquals("sdk $sdk", strongOrLock, allowedAuthenticators(sdk))
    }

    @Test
    fun aWeakBiometricIsNeverEnough() {
        for (sdk in 30..36) {
            val allowed = allowedAuthenticators(sdk)!!
            assertEquals("sdk $sdk", 0, allowed and Authenticators.BIOMETRIC_WEAK and Authenticators.BIOMETRIC_STRONG.inv())
        }
    }

    @Test
    fun android10UsesTheDeviceCredentialFlagInstead() {
        assertNull(allowedAuthenticators(29))
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

    private fun openMenu(unlocked: Boolean) {
        val manager =
            LoginManager(OneLoginStore(), NoNeverSave(), scope, log = {}, now = { 0L }, io = dispatcher).also {
                it.load()
                scheduler.advanceUntilIdle()
            }
        rule.setContent {
            OrbisTheme(AppTheme.LIGHT) {
                PasswordsScreen(manager = manager, offerSaveFlow = MutableStateFlow(true), onSetOfferSave = {}, onBack = {}, startUnlocked = unlocked)
            }
        }
        rule.onNodeWithContentDescription(app.getString(R.string.menu_more)).performClick()
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
}
