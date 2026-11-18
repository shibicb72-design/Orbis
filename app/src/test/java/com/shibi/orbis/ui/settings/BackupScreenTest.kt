package com.shibi.orbis.ui.settings

import android.net.Uri
import androidx.compose.material3.MaterialTheme
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
import com.shibi.orbis.backup.BackupRefusedException
import com.shibi.orbis.backup.BackupSealError
import com.shibi.orbis.backup.RestoreResult
import com.shibi.orbis.backup.SectionCounts
import com.shibi.orbis.backup.SectionSkip
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
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

    private val uri = Uri.parse("content://test/backup")

    private var createdWith: BackupOptions? = null
    private var createdName: String? = null
    private var createError: Exception? = null
    private var written: ByteArray? = null
    private var writeOk = true
    private var restoreResult: RestoreResult = RestoreResult.Failed(BackupError.NOT_A_BACKUP)
    private var restoreCalls = 0
    private var fileRead: BackupFileRead = BackupFileRead.Bytes(byteArrayOf(1, 2, 3))
    private var addonsOpened = 0

    private val actions =
        BackupActions(
            create = { password, options ->
                password.fill('\u0000')
                createdWith = options
                createError?.let { throw it }
                byteArrayOf(9, 9)
            },
            restore = { _, password ->
                password.fill('\u0000')
                restoreCalls++
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
                onResult(uri)
            }

            override fun open(onResult: (Uri?) -> Unit) = onResult(uri)
        }

    private fun show() {
        rule.setContent {
            MaterialTheme {
                BackupScreen(
                    actions = actions,
                    onOpenAddons = { addonsOpened++ },
                    onBack = {},
                    files = files,
                    pickers = pickers,
                    today = { LocalDate.of(2026, 11, 18) },
                )
            }
        }
    }

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
}
