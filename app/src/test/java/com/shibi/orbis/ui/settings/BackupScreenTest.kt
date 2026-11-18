package com.shibi.orbis.ui.settings

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isToggleable
import com.shibi.orbis.backup.BackupAddon
import com.shibi.orbis.backup.BackupError
import com.shibi.orbis.backup.BackupNotReadyException
import com.shibi.orbis.backup.BackupOptions
import com.shibi.orbis.backup.BackupPasswordVault
import com.shibi.orbis.backup.BiometricCipherPrompt
import com.shibi.orbis.backup.FileBackupPasswordVault
import com.shibi.orbis.backup.SoftwareVaultKeys
import com.shibi.orbis.backup.storeForTest
import com.shibi.orbis.backup.BackupRefusedException
import com.shibi.orbis.backup.BackupSealError
import com.shibi.orbis.backup.RestoreResult
import com.shibi.orbis.backup.SectionCounts
import com.shibi.orbis.backup.SectionSkip
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BackupScreenTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    @get:Rule(order = 2)
    val tmp = TemporaryFolder()

    private val uri = Uri.parse("content://test/backup")

    private var createdWith: BackupOptions? = null
    private var createdName: String? = null
    private var createError: Exception? = null
    private var written: ByteArray? = null
    private var writeOk = true
    private var restoreResult: RestoreResult = RestoreResult.Failed(BackupError.NOT_A_BACKUP)
    private var restoreCalls = 0
    private var restoreError: Exception? = null
    private var fileRead: BackupFileRead = BackupFileRead.Bytes(byteArrayOf(1, 2, 3))
    private var addonsOpened = 0
    private var createdPassword: String? = null
    private var restoredPassword: String? = null

    // Fingerprint (feat08-5d): off unless a test turns it on. The vault is the real file vault over a software key.
    private var biometric = false
    private var promptOk = true
    private var prompts = 0
    private val keys = SoftwareVaultKeys()
    private val vaultFile by lazy { File(tmp.root, FileBackupPasswordVault.FILE_NAME) }
    private val vault: BackupPasswordVault by lazy { FileBackupPasswordVault(vaultFile, keys) }
    private val cipherPrompt =
        BiometricCipherPrompt { _, title, subtitle, _, cipher, onResult ->
            assertEquals("Orbis backup", title)
            assertEquals("Confirm it's you", subtitle)
            prompts++
            onResult(if (promptOk) cipher else null)
        }

    private val actions =
        BackupActions(
            create = { password, options ->
                createdPassword = String(password)
                password.fill('\u0000')
                createdWith = options
                createError?.let { throw it }
                byteArrayOf(9, 9)
            },
            restore = { _, password ->
                restoredPassword = String(password)
                password.fill('\u0000')
                restoreCalls++
                restoreError?.let { throw it }
                restoreResult
            },
        )

    private val files =
        object : BackupFiles {
            override suspend fun write(uri: Uri, bytes: ByteArray): Boolean {
                written = bytes
                return writeOk
            }

            override suspend fun read(uri: Uri): BackupFileRead = fileRead
        }

    private val pickers =
        object : BackupPickers {
            override fun create(fileName: String, onResult: (Uri?) -> Unit) {
                createdName = fileName
                if (holdPicker) heldCallback = onResult else onResult(uri)
            }

            override fun open(onResult: (Uri?) -> Unit) = onResult(uri)
        }

    private var lockSet = true
    private var holdPicker = false
    private var heldCallback: ((Uri?) -> Unit)? = null
    private lateinit var lifecycle: LifecycleRegistry

    // Held in a field: the registry keeps only a weak reference to its owner.
    private val owner = object : LifecycleOwner { override val lifecycle get() = this@BackupScreenTest.lifecycle }

    private fun show() {
        lifecycle = LifecycleRegistry.createUnsafe(owner)
        lifecycle.currentState = Lifecycle.State.RESUMED
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            MaterialTheme {
                BackupScreen(
                    actions = actions,
                    onOpenAddons = { addonsOpened++ },
                    onBack = {},
                    files = files,
                    pickers = pickers,
                    today = { LocalDate.of(2026, 11, 18) },
                    screenLockSet = { lockSet },
                    startUnlocked = true,
                    authenticator = { _, _, _, ok -> ok() },
                    vault = vault,
                    strongBiometric = { biometric },
                    cipherPrompt = cipherPrompt,
                )
            }
            }
        }
    }

    private fun lifecycleTo(vararg events: Lifecycle.Event) {
        rule.runOnUiThread { for (e in events) lifecycle.handleLifecycleEvent(e) }
        rule.waitForIdle()
    }

    private fun leaveApp() = lifecycleTo(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP)

    private fun returnToApp() = lifecycleTo(Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME)

    private fun fields() = rule.onAllNodes(hasSetTextAction())

    private fun type(passwordText: String, repeatText: String) {
        fields()[0].performTextInput(passwordText)
        fields()[1].performTextInput(repeatText)
    }

    private fun save(): SemanticsNodeInteraction = rule.onNodeWithText("Save backup file")

    @Test(timeout = 60_000)
    fun nineCharactersKeepSaveDisabled() {
        show()
        type("123456789", "123456789")
        save().assertIsNotEnabled()
    }

    @Test(timeout = 60_000)
    fun twoDifferentPasswordsKeepSaveDisabled() {
        show()
        type("1234567890", "1234567891")
        save().assertIsNotEnabled()
    }

    @Test(timeout = 60_000)
    fun tenMatchingCharactersEnableSave() {
        show()
        type("1234567890", "1234567890")
        save().assertIsEnabled()
    }

    @Test(timeout = 60_000)
    fun lengthIsCountedInCodePoints() {
        assertFalse(backupPasswordOk("12345678😀", "12345678😀"))
        assertTrue(backupPasswordOk("123456789😀", "123456789😀"))
    }

    @Test(timeout = 60_000)
    fun tabsAreTickedAndHistoryIsNotAtFirst() {
        show()
        rule.onNode(isToggleable() and hasText("Include open tabs")).assertIsOn()
        rule.onNode(isToggleable() and hasText("Include browsing history")).assertIsOff()
    }

    @Test(timeout = 60_000)
    fun optionsPassedToTheManagerMatchTheBoxes() {
        show()
        type("1234567890", "1234567890")
        save().performScrollTo().performClick()
        rule.waitUntil(5_000) { createdWith != null }
        assertEquals(BackupOptions(includeTabs = true, includeHistory = false), createdWith)
        assertEquals("orbis-backup-2026-11-18.orbisbackup", createdName)

        createdWith = null
        rule.onNode(isToggleable() and hasText("Include open tabs")).performScrollTo().performClick()
        rule.onNode(isToggleable() and hasText("Include browsing history")).performScrollTo().performClick()
        save().performScrollTo().performClick()
        rule.waitUntil(5_000) { createdWith != null }
        assertEquals(BackupOptions(includeTabs = false, includeHistory = true), createdWith)
    }

    private fun createAndExpect(message: String) {
        show()
        type("1234567890", "1234567890")
        save().performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test(timeout = 60_000)
    fun savedMessage() {
        createAndExpect("Backup saved")
        assertEquals(2, written?.size)
    }

    @Test(timeout = 60_000)
    fun writeFailureMessage() {
        writeOk = false
        createAndExpect("The backup could not be saved.")
    }

    @Test(timeout = 60_000)
    fun managerFailureMessage() {
        createError = IllegalStateException("boom")
        createAndExpect("The backup could not be saved.")
    }

    @Test(timeout = 60_000)
    fun tooLargeMessage() {
        createError = BackupRefusedException(BackupSealError.TOO_LARGE)
        createAndExpect("There is too much data for one backup file. Untick Include browsing history and try again.")
    }

    @Test(timeout = 60_000)
    fun notReadyMessage() {
        createError = BackupNotReadyException("passwords")
        createAndExpect("Orbis is still starting. Try again in a moment.")
    }

    private fun restoreAndExpect(result: RestoreResult, vararg messages: String) {
        restoreResult = result
        show()
        rule.onNodeWithText("Choose backup file").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Backup file chosen.").fetchSemanticsNodes().isNotEmpty() }
        fields().onLast().performTextInput("whatever")
        rule.onNodeWithText("Restore").performScrollTo().performClick()
        rule.onNode(hasText("Restore") and hasAnyAncestor(isDialog())).performClick()
        for (m in messages) {
            rule.waitUntil(5_000) { rule.onAllNodesWithText(m).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test(timeout = 60_000)
    fun restoreFailureMessages() {
        restoreAndExpect(RestoreResult.Failed(BackupError.WRONG_PASSWORD_OR_DAMAGED), "Wrong password, or the file is damaged.")
    }

    @Test(timeout = 60_000)
    fun notABackupMessage() {
        restoreAndExpect(RestoreResult.Failed(BackupError.NOT_A_BACKUP), "This is not an Orbis backup file.")
    }

    @Test(timeout = 60_000)
    fun newerMessage() {
        restoreAndExpect(RestoreResult.Failed(BackupError.UNSUPPORTED), "This backup was made by a newer version of Orbis.")
    }

    @Test(timeout = 60_000)
    fun tooLargeBackupMessage() {
        restoreAndExpect(RestoreResult.Failed(BackupError.TOO_LARGE), "That file is too large to be an Orbis backup.")
    }

    @Test(timeout = 60_000)
    fun tooLargeFileIsReportedWhenPicked() {
        fileRead = BackupFileRead.TooLarge
        show()
        rule.onNodeWithText("Choose backup file").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("That file is too large to be an Orbis backup.").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test(timeout = 60_000)
    fun restoredLineIsBuiltFromTheCountsAndOffersAddons() {
        val result =
            RestoreResult.Restored(
                counts =
                    mapOf(
                        "bookmarks" to SectionCounts(added = 3),
                        "passwords" to SectionCounts(added = 1),
                        "settings" to SectionCounts(replaced = 12),
                    ),
                skippedSections = mapOf("future" to SectionSkip.NEWER),
                failedSections = setOf("tabs"),
                addonsToOffer = listOf(BackupAddon("x", true)),
                offerUblockOrigin = false,
            )
        restoreAndExpect(
            result,
            "Restored: 3 bookmarks, 1 password, settings.",
            "Some parts could not be restored: tabs.",
            "Some parts were made by a newer version of Orbis and were left out.",
            "Add-ons are not restored automatically. Open Add-ons to add them again.",
        )
        rule.onNodeWithText("Open Add-ons").performScrollTo().performClick()
        assertEquals(1, addonsOpened)
    }

    @Test(timeout = 60_000)
    fun noAddonsLineWhenNothingToOffer() {
        val result = RestoreResult.Restored(mapOf("tabs" to SectionCounts(added = 2)), emptyMap(), emptySet(), emptyList(), false)
        restoreAndExpect(result, "Restored: 2 tabs.")
        assertEquals(0, rule.onAllNodesWithText("Open Add-ons").fetchSemanticsNodes().size)
    }

    @Test(timeout = 60_000)
    fun cancelInTheRestoreConfirmationRestoresNothing() {
        show()
        rule.onNodeWithText("Choose backup file").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Backup file chosen.").fetchSemanticsNodes().isNotEmpty() }
        fields().onLast().performTextInput("whatever")
        rule.onNodeWithText("Restore").performScrollTo().performClick()
        rule.onNodeWithText("Restore this backup? Bookmarks and passwords are added to the ones on this phone. Settings are replaced.").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertEquals(0, restoreCalls)
        assertEquals(0, rule.onAllNodesWithText("Restore this backup?", substring = true).fetchSemanticsNodes().size)
    }

    @Test(timeout = 60_000)
    fun restoreButtonIsNotThereBeforeAFileIsChosen() {
        show()
        assertEquals(0, rule.onAllNodesWithText("Restore").fetchSemanticsNodes().size)
        assertNull(written)
    }

    private val noLockText = "Set a screen lock (PIN, pattern or password) on this phone to make or restore a backup."

    @Test(timeout = 60_000)
    fun withoutAScreenLockOnlyTheMessageShows() {
        lockSet = false
        show()
        rule.onNodeWithText(noLockText).assertExists()
        assertEquals(0, rule.onAllNodesWithText("Save backup file").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Choose backup file").fetchSemanticsNodes().size)
        assertEquals(0, fields().fetchSemanticsNodes().size)
    }

    @Test(timeout = 60_000)
    fun aLockSetWhileAwayIsNoticedOnResume() {
        lockSet = false
        show()
        rule.onNodeWithText(noLockText).assertExists()
        lockSet = true
        leaveApp()
        returnToApp()
        // Leaving relocked the screen, so the Unlock state shows rather than the message.
        assertEquals(0, rule.onAllNodesWithText(noLockText).fetchSemanticsNodes().size)
        rule.onNodeWithText("Unlock").assertExists()
    }

    @Test(timeout = 60_000)
    fun leavingWithNoPickerOpenLocksAndClears() {
        show()
        type("1234567890", "1234567890")
        save().assertIsEnabled()
        leaveApp()
        rule.onNodeWithText("Unlock").assertExists()
        assertEquals(0, rule.onAllNodesWithText("Save backup file").fetchSemanticsNodes().size)
        // Unlocking again shows the screen with empty fields.
        returnToApp()
        rule.onNodeWithText("Unlock").performClick()
        save().assertIsNotEnabled()
        assertEquals("", fields()[0].fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        assertEquals("", fields()[1].fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
    }

    @Test(timeout = 60_000)
    fun leavingWithThePickerOpenKeepsFieldsAndUnlockedState() {
        holdPicker = true
        show()
        type("1234567890", "1234567890")
        save().performScrollTo().performClick()
        assertTrue(heldCallback != null)
        leaveApp()
        assertEquals(0, rule.onAllNodesWithText("Unlock").fetchSemanticsNodes().size)
        save().assertIsEnabled()
    }

    @Test(timeout = 60_000)
    fun restoreNotReadyMessage() {
        restoreError = BackupNotReadyException("tabs")
        restoreAndExpect(RestoreResult.Failed(BackupError.NOT_A_BACKUP), "Orbis is still starting. Try again in a moment.")
    }

    // ---- Fingerprint (feat08-5d) ----

    private val switchText = "Use fingerprint for backups on this phone"
    private val invalidatedText = "Fingerprint unlock was turned off because the fingerprints on this phone changed. Type your password."

    private fun count(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun waitFor(text: String) = rule.waitUntil(5_000) { count(text) > 0 }

    @Test(timeout = 60_000)
    fun withoutAFingerprintNoneOfItShows() {
        vault.storeForTest("storedpassword1")
        createAndExpect("Backup saved")
        assertEquals(0, count("Use fingerprint"))
        assertEquals(0, count(switchText))
        assertEquals(0, count("Forget the saved password"))
        // Restore still asks for the typed password.
        rule.onNodeWithText("Choose backup file").performScrollTo().performClick()
        waitFor("Backup file chosen.")
        assertEquals(3, fields().fetchSemanticsNodes().size)
        assertEquals(0, prompts)
    }

    @Test(timeout = 60_000)
    fun theSwitchStoresThePasswordJustUsed() {
        biometric = true
        createAndExpect("Backup saved")
        rule.onNodeWithText(switchText).performScrollTo().assertIsOff().performClick()
        rule.waitUntil(5_000) { vault.hasPassword }
        assertEquals(1, prompts)
        waitFor("Forget the saved password")
        assertEquals(0, count(switchText))
        rule.onNodeWithText("Your password is kept on this phone only and needs your fingerprint. On another phone you type the password.").assertExists()
        // Create with the fingerprint now uses the stored password without typing.
        createdPassword = null
        rule.onNodeWithText("Use fingerprint").performScrollTo().performClick()
        rule.waitUntil(5_000) { createdPassword != null }
        assertEquals("1234567890", createdPassword)
    }

    @Test(timeout = 60_000)
    fun noSwitchBeforeASuccessfulOperation() {
        biometric = true
        show()
        assertEquals(0, count(switchText))
        writeOk = false
        type("1234567890", "1234567890")
        save().performScrollTo().performClick()
        waitFor("The backup could not be saved.")
        assertEquals(0, count(switchText))
    }

    @Test(timeout = 60_000)
    fun aStoredPasswordThatDoesNotOpenTheFileShowsThePasswordField() {
        biometric = true
        vault.storeForTest("storedpassword1")
        restoreResult = RestoreResult.Failed(BackupError.WRONG_PASSWORD_OR_DAMAGED)
        show()
        waitFor("Use fingerprint")
        rule.onNodeWithText("Choose backup file").performScrollTo().performClick()
        waitFor("Backup file chosen.")
        // Only the two create fields: the stored password is tried first.
        assertEquals(2, fields().fetchSemanticsNodes().size)
        rule.onNodeWithText("Restore").performScrollTo().performClick()
        rule.onNode(hasText("Restore") and hasAnyAncestor(isDialog())).performClick()
        waitFor("Wrong password, or the file is damaged.")
        assertEquals("storedpassword1", restoredPassword)
        assertEquals(1, restoreCalls)
        assertEquals(3, fields().fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Restored", substring = true).fetchSemanticsNodes().size)
        rule.onNodeWithText("Backup file chosen.").assertExists()
        rule.onNodeWithText("Restore").assertIsNotEnabled()
    }

    @Test(timeout = 60_000)
    fun aCancelledPromptOnRestoreShowsThePasswordField() {
        biometric = true
        promptOk = false
        vault.storeForTest("storedpassword1")
        show()
        waitFor("Use fingerprint")
        rule.onNodeWithText("Choose backup file").performScrollTo().performClick()
        waitFor("Backup file chosen.")
        rule.onNodeWithText("Restore").performScrollTo().performClick()
        rule.onNode(hasText("Restore") and hasAnyAncestor(isDialog())).performClick()
        rule.waitUntil(5_000) { fields().fetchSemanticsNodes().size == 3 }
        assertEquals(0, restoreCalls)
    }

    @Test(timeout = 60_000)
    fun anInvalidatedKeyDeletesTheFileAndSaysSo() {
        biometric = true
        vault.storeForTest("storedpassword1")
        assertTrue(vaultFile.exists())
        keys.invalidated = true
        show()
        waitFor("Use fingerprint")
        rule.onNodeWithText("Use fingerprint").performScrollTo().performClick()
        waitFor(invalidatedText)
        assertFalse(vaultFile.exists())
        assertFalse(vault.hasPassword)
        assertEquals(0, count("Use fingerprint"))
        assertEquals(0, prompts)
        assertNull(createdPassword)
    }

    @Test(timeout = 60_000)
    fun forgetTheSavedPassword() {
        biometric = true
        vault.storeForTest("storedpassword1")
        show()
        rule.waitUntil(5_000) { count("Forget the saved password") > 0 }
        rule.onNodeWithText("Forget the saved password").performScrollTo().performClick()
        rule.waitUntil(5_000) { count("Forget the saved password") == 0 }
        assertFalse(vault.hasPassword)
        assertEquals(0, count("Use fingerprint"))
    }
}
