package com.shibi.orbis.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.shibi.orbis.addons.AddonCatalog
import com.shibi.orbis.addons.AddonManager
import com.shibi.orbis.addons.AddonPrefs
import com.shibi.orbis.engine.AddonAccess
import com.shibi.orbis.engine.ExtensionHost
import com.shibi.orbis.engine.ExtensionInstallError
import com.shibi.orbis.engine.ExtensionInstallException
import com.shibi.orbis.engine.ExtensionDisabledReason
import com.shibi.orbis.engine.InstalledExtension
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val ACCESS = AddonAccess(listOf("storage", "tabs"), listOf("<all_urls>"))
private val DARK_READER = AddonCatalog.entries.first { it.name == "Dark Reader" }

private class FakeHost : ExtensionHost {
    val installed = linkedMapOf<String, InstalledExtension>()
    val calls = mutableListOf<String>()

    /** What `confirm` returned for each install the screen started. */
    val confirmAnswers = mutableListOf<Boolean>()
    var hangAfterConfirm = false
    var failWith: ExtensionInstallError? = null

    override suspend fun find(id: String): InstalledExtension? = installed[id]

    override suspend fun list(): List<InstalledExtension> = installed.values.toList()

    override suspend fun install(xpiUrl: String, expectedId: String): InstalledExtension =
        throw UnsupportedOperationException("the screen must use the confirm path")

    override suspend fun install(
        xpiUrl: String,
        expectedId: String,
        confirm: suspend (AddonAccess) -> Boolean,
    ): InstalledExtension {
        failWith?.let { throw ExtensionInstallException(it) }
        val answer = confirm(ACCESS)
        confirmAnswers += answer
        if (!answer) throw ExtensionInstallException(ExtensionInstallError.CANCELLED)
        if (hangAfterConfirm) awaitCancellation()
        return ext(expectedId).also { installed[it.id] = it }
    }

    override suspend fun setEnabled(id: String, enabled: Boolean): InstalledExtension =
        installed.getValue(id).copy(enabled = enabled).also { installed[id] = it }

    override suspend fun allowInPrivateBrowsing(id: String) = Unit

    override suspend fun setAllowedInPrivateBrowsing(id: String, allowed: Boolean): InstalledExtension =
        installed.getValue(id).copy(allowedInPrivate = allowed).also { installed[id] = it }

    override suspend fun uninstall(id: String) {
        calls += "uninstall $id"
        installed.remove(id)
    }

    override suspend fun checkForUpdate(id: String): InstalledExtension? = null
}

private class ScreenPrefs : AddonPrefs {
    val blocked = mutableSetOf<String>()

    override fun lastUpdateCheckMillis(id: String) = 0L

    override fun setLastUpdateCheckMillis(id: String, millis: Long) = Unit

    override fun updateBlocked(id: String) = id in blocked

    override fun setUpdateBlocked(id: String, blocked: Boolean) {
        if (blocked) this.blocked += id else this.blocked -= id
    }

    override fun clear(id: String) {
        blocked -= id
    }
}

private fun ext(id: String, optionsPageUrl: String? = null, allowedInPrivate: Boolean = false) =
    InstalledExtension(
        id,
        "1.0",
        enabled = true,
        disabledByUser = false,
        optionsPageUrl = optionsPageUrl,
        allowedInPrivate = allowedInPrivate,
    )

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AddonsScreenTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val host = FakeHost()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val prefs = ScreenPrefs()
    private val manager = AddonManager(host, scope, {}, prefs, System::currentTimeMillis)
    private val opened = mutableListOf<String>()

    @After
    fun stop() {
        scope.cancel()
    }

    private fun show() {
        rule.setContent {
            MaterialTheme { AddonsScreen(manager = manager, onBack = {}, onOpenExtensionPage = { opened += it }) }
        }
        rule.waitForIdle()
    }

    private fun tapAddOnFirstRow() {
        rule.onAllNodesWithText("Add")[0].performScrollTo().performClick()
        rule.waitForIdle()
    }

    @Test
    fun five_rows_and_violentmonkey_is_below_the_advanced_header() {
        show()
        val names = listOf("SponsorBlock", "Consent-O-Matic", "ClearURLs", "Dark Reader", "Violentmonkey")
        names.forEach { rule.onAllNodesWithText(it).assertCountEquals(1) }
        val advancedTop = rule.onNodeWithText("Advanced").getUnclippedBoundsInRoot().top
        names.dropLast(1).forEach { assertTrue(rule.onNodeWithText(it).getUnclippedBoundsInRoot().top < advancedTop) }
        assertTrue(rule.onNodeWithText("Violentmonkey").getUnclippedBoundsInRoot().top > advancedTop)
        rule.onAllNodesWithText("Add").assertCountEquals(5)
    }

    @Test
    fun each_access_summary_is_shown_word_for_word() {
        show()
        listOf(
            "Can access: YouTube pages. Contacts sponsor.ajay.app to look up videos.",
            "Can access: every page you open.",
            "Can access: every page you open, and can change or block requests.",
            "Can access: every page you open. Use this or Force dark, not both.",
            "Can access: every page you open, cookies, and can change or block requests. " +
                "Runs scripts you install; only install scripts you trust.",
        ).forEach { rule.onAllNodesWithText(it).assertCountEquals(1) }
    }

    @Test
    fun the_install_dialog_lists_the_reported_access_and_cancel_leaves_the_row_at_add() {
        show()
        tapAddOnFirstRow()
        rule.onNodeWithText("Add SponsorBlock?").assertExists()
        rule.onNodeWithText("It asks for:").assertExists()
        listOf("storage", "tabs", "<all_urls>").forEach { rule.onNodeWithText(it).assertExists() }
        assertTrue(host.installed.isEmpty())

        rule.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()

        rule.onAllNodesWithText("Add SponsorBlock?").assertCountEquals(0)
        rule.onAllNodesWithText("Add").assertCountEquals(5)
        assertTrue(host.installed.isEmpty())
        assertEquals(listOf(false), host.confirmAnswers)
    }

    @Test
    fun adding_in_the_dialog_installs_and_shows_the_installed_controls() {
        show()
        tapAddOnFirstRow()
        rule.onNode(hasText("Add") and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()

        assertEquals(setOf("sponsorBlocker@ajay.app"), host.installed.keys)
        rule.onAllNodesWithText("Add").assertCountEquals(4)
        rule.onNodeWithText("On").assertIsOn()
        rule.onNodeWithText("Allow in private tabs").assertIsOff()
    }

    @Test
    fun an_installed_row_shows_allow_in_private_tabs_unchecked_and_settings_only_with_a_page() {
        host.installed[DARK_READER.id] = ext(DARK_READER.id)
        show()
        rule.onNodeWithText("Allow in private tabs").assertIsOff()
        rule.onAllNodesWithText("Settings").assertCountEquals(0)
        rule.onAllNodesWithText("Add").assertCountEquals(4)

        rule.onNodeWithText("Allow in private tabs").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Allow in private tabs").assertIsOn()
        assertTrue(host.installed.getValue(DARK_READER.id).allowedInPrivate)
    }

    @Test
    fun the_settings_button_opens_the_add_on_page() {
        host.installed[DARK_READER.id] = ext(DARK_READER.id, optionsPageUrl = "moz-extension://x/options.html")
        show()
        rule.onNodeWithText("Settings").performScrollTo().performClick()
        assertEquals(listOf("moz-extension://x/options.html"), opened)
    }

    @Test
    fun remove_asks_first_and_cancel_keeps_the_add_on() {
        host.installed[DARK_READER.id] = ext(DARK_READER.id)
        show()
        rule.onNodeWithText("Remove").performScrollTo().performClick()
        rule.onNodeWithText("Remove Dark Reader?").assertExists()
        rule.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()
        assertTrue(host.installed.containsKey(DARK_READER.id))
        assertTrue(host.calls.isEmpty())

        rule.onNodeWithText("Remove").performScrollTo().performClick()
        rule.onNode(hasText("Remove") and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()
        assertEquals(listOf("uninstall ${DARK_READER.id}"), host.calls)
        rule.onAllNodesWithText("Add").assertCountEquals(5)
    }

    @Test
    fun while_one_install_runs_the_other_add_buttons_are_disabled() {
        host.hangAfterConfirm = true
        show()
        tapAddOnFirstRow()
        rule.onNode(hasText("Add") and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Adding...").assertExists()
        val others = rule.onAllNodesWithText("Add")
        others.assertCountEquals(4)
        (0 until 4).forEach { others[it].assertIsNotEnabled() }
    }

    @Test
    fun leaving_the_screen_with_the_dialog_open_answers_the_question_with_false() {
        var shown by mutableStateOf(true)
        rule.setContent {
            MaterialTheme { if (shown) AddonsScreen(manager = manager, onBack = {}) }
        }
        rule.waitForIdle()
        tapAddOnFirstRow()
        rule.onNodeWithText("Add SponsorBlock?").assertExists()
        assertTrue(host.confirmAnswers.isEmpty())

        shown = false
        rule.waitForIdle()

        assertEquals(listOf(false), host.confirmAnswers)
        assertTrue(host.installed.isEmpty())
        assertEquals(
            com.shibi.orbis.addons.AddonState.NotInstalled,
            manager.states.value.getValue("sponsorBlocker@ajay.app"),
        )
    }

    @Test
    fun an_add_on_with_a_blocked_update_shows_the_remove_and_add_again_line() {
        host.installed[DARK_READER.id] = ext(DARK_READER.id)
        prefs.blocked += DARK_READER.id
        show()
        rule.onNodeWithText("An update needs more access. Remove and add it again to review the new access.")
            .performScrollTo()
            .assertExists()
        rule.onNodeWithText("On").assertIsOn()
        rule.onNodeWithText("Remove").assertExists()
    }

    @Test
    fun an_add_on_the_browser_turned_off_has_the_safety_line_and_no_on_switch() {
        host.installed[DARK_READER.id] =
            ext(DARK_READER.id).copy(enabled = false, disabledByUser = false, disabledReason = ExtensionDisabledReason.BLOCKLIST)
        show()
        rule.onNodeWithText("Turned off by the browser for your safety.").performScrollTo().assertExists()
        rule.onAllNodesWithText("Turned off by the browser.").assertCountEquals(0)
        rule.onAllNodesWithText("On").assertCountEquals(0)
        rule.onAllNodesWithText("Allow in private tabs").assertCountEquals(0)
        rule.onNodeWithText("Remove").assertExists()
    }

    @Test
    fun an_add_on_the_browser_turned_off_by_the_signature_check_has_the_safety_line() {
        host.installed[DARK_READER.id] =
            ext(DARK_READER.id).copy(enabled = false, disabledByUser = false, disabledReason = ExtensionDisabledReason.SIGNATURE)
        show()
        rule.onNodeWithText("Turned off by the browser for your safety.").performScrollTo().assertExists()
        rule.onAllNodesWithText("On").assertCountEquals(0)
    }

    @Test
    fun an_add_on_turned_off_for_another_reason_has_the_plain_line_and_no_on_switch() {
        host.installed[DARK_READER.id] =
            ext(DARK_READER.id).copy(enabled = false, disabledByUser = false, disabledReason = ExtensionDisabledReason.OTHER)
        show()
        rule.onNodeWithText("Turned off by the browser.").performScrollTo().assertExists()
        rule.onAllNodesWithText("Turned off by the browser for your safety.").assertCountEquals(0)
        rule.onAllNodesWithText("On").assertCountEquals(0)
        rule.onAllNodesWithText("Allow in private tabs").assertCountEquals(0)
        rule.onNodeWithText("Remove").assertExists()
    }

    @Test
    fun an_add_on_the_user_turned_off_has_the_switch_off_and_neither_line() {
        host.installed[DARK_READER.id] =
            ext(DARK_READER.id).copy(enabled = false, disabledByUser = true, disabledReason = ExtensionDisabledReason.USER)
        show()
        rule.onNodeWithText("On").performScrollTo().assertIsOff()
        rule.onAllNodesWithText("Turned off by the browser.").assertCountEquals(0)
        rule.onAllNodesWithText("Turned off by the browser for your safety.").assertCountEquals(0)
    }

    @Test
    fun try_again_installs_after_a_failed_install() {
        host.failWith = ExtensionInstallError.UNKNOWN
        show()
        tapAddOnFirstRow()
        rule.onNodeWithText("This add-on could not be installed.").assertExists()

        host.failWith = null
        rule.onNodeWithText("Try again").performScrollTo().assertIsEnabled().performClick()
        rule.waitForIdle()
        rule.onNode(hasText("Add") and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()

        assertEquals(setOf("sponsorBlocker@ajay.app"), host.installed.keys)
        rule.onAllNodesWithText("Try again").assertCountEquals(0)
    }

    @Test
    fun the_back_arrow_with_no_dialog_still_goes_back() {
        var backs = 0
        rule.setContent { MaterialTheme { AddonsScreen(manager = manager, onBack = { backs++ }) } }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
        rule.onAllNodesWithText("Add").assertCountEquals(5)
        rule.onAllNodesWithText("Add")[0].assertIsEnabled()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AddonInstallPromptTest {
    @Test
    fun closing_answers_the_open_question_with_false() = runTest {
        val prompt = AddonInstallPrompt()
        val answer = async(Dispatchers.Unconfined) { prompt.ask("X", ACCESS) }
        assertTrue(prompt.pending != null)
        prompt.close()
        assertFalse(answer.await())
        assertEquals(null, prompt.pending)
    }

    @Test
    fun a_question_asked_after_close_is_answered_false_at_once() = runTest {
        val prompt = AddonInstallPrompt()
        prompt.close()
        assertFalse(prompt.ask("X", ACCESS))
        assertEquals(null, prompt.pending)
    }

    @Test
    fun answering_yes_returns_true_and_clears_the_question() = runTest {
        val prompt = AddonInstallPrompt()
        val answer = async(Dispatchers.Unconfined) { prompt.ask("X", ACCESS) }
        prompt.answer(true)
        assertTrue(answer.await())
        assertEquals(null, prompt.pending)
    }

    @Test
    fun cancelling_the_asker_clears_the_question() = runTest {
        val prompt = AddonInstallPrompt()
        val answer = async(Dispatchers.Unconfined) { prompt.ask("X", ACCESS) }
        answer.cancel()
        assertEquals(null, prompt.pending)
    }
}
