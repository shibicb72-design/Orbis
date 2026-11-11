package com.shibi.orbis.ui.passwords

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import com.shibi.orbis.ui.passTapGuard
import com.shibi.orbis.ui.theme.OrbisTheme
import java.security.KeyStoreException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A store whose reads fail (like a Keystore key that stays unusable) while [unreadable], until it is set aside. */
private class FailingStore(var unreadable: Boolean) : LoginStore {
    var asides = 0

    override fun load(): List<SavedLogin> {
        if (unreadable) throw KeyStoreException("key unusable")
        return emptyList()
    }

    override fun save(items: List<SavedLogin>) {}

    override fun setAside() {
        asides++
        unreadable = false
    }
}

private class NoNever : NeverSavePrefs {
    override var neverSave: Set<String> = emptySet()
}

/** Settings > Passwords offers the reset only when the saved logins could not be read, and only after a confirmation. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PasswordsResetComposeTest {
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

    private fun loadedManager(store: LoginStore): LoginManager =
        LoginManager(store, NoNever(), scope, log = {}, now = { 0L }, io = dispatcher).also {
            it.load()
            scheduler.advanceUntilIdle()
        }

    private fun show(manager: LoginManager, unlocked: Boolean = true) {
        rule.setContent {
            OrbisTheme(AppTheme.LIGHT) {
                PasswordsScreen(manager = manager, offerSaveFlow = MutableStateFlow(true), onSetOfferSave = {}, onBack = {}, startUnlocked = unlocked)
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun noResetWhenTheStoreLoaded() {
        val m = loadedManager(FailingStore(unreadable = false))
        assertFalse(m.loadFailed.value)
        show(m)
        rule.onNodeWithTag(PASSWORDS_RESET_TAG).assertDoesNotExist()
        rule.onNodeWithText(app.getString(R.string.passwords_unreadable)).assertDoesNotExist()
        rule.onNodeWithText(app.getString(R.string.passwords_empty)).assertIsDisplayed()
    }

    @Test
    fun noResetBeforeTheScreenIsUnlocked() {
        val m = loadedManager(FailingStore(unreadable = true))
        assertTrue(m.loadFailed.value)
        show(m, unlocked = false)
        rule.onNodeWithTag(PASSWORDS_RESET_TAG).assertDoesNotExist()
    }

    @Test
    fun resetShowsWhenUnreadableAndAsksBeforeResetting() {
        val store = FailingStore(unreadable = true)
        val m = loadedManager(store)
        show(m)
        rule.onNodeWithText(app.getString(R.string.passwords_unreadable)).assertIsDisplayed()
        rule.onNodeWithTag(PASSWORDS_RESET_TAG).assertIsDisplayed().performClick()
        rule.onNodeWithText(app.getString(R.string.passwords_reset_warning)).assertIsDisplayed()
        scheduler.advanceUntilIdle()
        assertEquals("nothing happens before the confirmation", 0, store.asides)

        rule.onNodeWithText(app.getString(R.string.prompt_cancel)).performClick()
        rule.onNodeWithText(app.getString(R.string.passwords_reset_warning)).assertDoesNotExist()
        scheduler.advanceUntilIdle()
        assertEquals("cancel does not reset", 0, store.asides)

        rule.onNodeWithTag(PASSWORDS_RESET_TAG).performClick()
        rule.waitForIdle()
        rule.passTapGuard()
        rule.onNodeWithTag(PASSWORDS_RESET_CONFIRM_TAG).performClick()
        scheduler.advanceUntilIdle()
        assertEquals(1, store.asides)
        assertTrue(m.loaded)
        assertFalse(m.loadFailed.value)
        rule.waitForIdle()
        rule.onNodeWithTag(PASSWORDS_RESET_TAG).assertDoesNotExist()
        rule.onNodeWithText(app.getString(R.string.passwords_empty)).assertIsDisplayed()
    }
}
