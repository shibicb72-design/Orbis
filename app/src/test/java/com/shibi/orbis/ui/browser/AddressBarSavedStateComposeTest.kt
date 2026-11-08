package com.shibi.orbis.ui.browser

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [rememberAddressBarTypedState] is the exact state choice BrowserScreen's address bar uses. StateRestorationTester drives
 * the real SaveableStateRegistry through an emulated save + recreate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AddressBarSavedStateComposeTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val marker = "https://private-marker.example/secret"

    private fun typedThenRecreated(isPrivate: Boolean): String {
        val restoration = StateRestorationTester(rule)
        var state: MutableState<String>? = null
        restoration.setContent {
            key(isPrivate) { state = rememberAddressBarTypedState(isPrivate) }
        }
        rule.runOnIdle { state!!.value = marker }
        rule.runOnIdle { assertEquals("sanity: the write took effect before saving", marker, state!!.value) }
        restoration.emulateSavedInstanceStateRestore()
        return rule.runOnIdle { state!!.value }
    }

    @Test
    fun normalTabTypedTextIsRestored() {
        // Positive control: the harness really saves and restores, so the private "" below is not a vacuous result.
        assertEquals(marker, typedThenRecreated(isPrivate = false))
    }

    @Test
    fun privateTabTypedTextIsNotRestored() {
        assertEquals("", typedThenRecreated(isPrivate = true))
    }

    /** What a SaveableStateRegistry would write out (the content of the saved-state bundle) after the marker is typed. */
    private fun savedValuesAfterTyping(isPrivate: Boolean): String {
        val registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })
        var state: MutableState<String>? = null
        rule.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                state = rememberAddressBarTypedState(isPrivate)
            }
        }
        rule.runOnIdle { state!!.value = marker }
        return rule.runOnIdle { registry.performSave().toString() }
    }

    @Test
    fun normalTabTypedTextIsInTheSavedBundle() {
        // Positive control for the inspection itself: the same probe does see the marker for a normal tab.
        assertTrue(savedValuesAfterTyping(isPrivate = false).contains(marker))
    }

    @Test
    fun privateTabTypedTextNeverReachesTheSavedBundle() {
        assertFalse(savedValuesAfterTyping(isPrivate = true).contains(marker))
    }
}
