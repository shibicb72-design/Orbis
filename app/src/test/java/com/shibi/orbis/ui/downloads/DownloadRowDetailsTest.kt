package com.shibi.orbis.ui.downloads

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.shibi.orbis.downloads.DownloadEntry
import com.shibi.orbis.downloads.DownloadStatus
import com.shibi.orbis.downloads.SavedTo
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The finished-file row shows the kind of file, the size, when it was downloaded and where it is saved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DownloadRowDetailsTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private fun entry(savedTo: SavedTo, started: Long, finished: Long?, isPrivate: Boolean = false) =
        DownloadEntry(
            id = 1, fileName = "report.pdf", mimeType = "application/pdf", sourceHost = "example.org", totalBytes = 251_000,
            receivedBytes = 251_000, status = DownloadStatus.COMPLETED, filePath = "/x/report.pdf", startedAt = started,
            isPrivate = isPrivate, finishedAt = finished, savedTo = savedTo,
        )

    private fun show(e: DownloadEntry) {
        rule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                DownloadRow(e, motionEnabled = false, onCancel = {}, onPause = null, onResume = {}, onOpen = {}, onShare = {}, onDelete = {})
            }
        }
    }

    @Test
    fun aFinishedDownloadShowsTypeSizeTimeAndPlace() {
        show(entry(SavedTo.DOWNLOADS, started = 1_794_939_000_000L, finished = 1_794_939_120_000L))
        rule.onNodeWithText("PDF document · ", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Saved in Downloads", substring = true).assertIsDisplayed()
        rule.onNodeWithText(" 2026, ", substring = true).assertIsDisplayed()
    }

    @Test
    fun anOldDownloadShowsItsStartDateAndNoPlace() {
        show(entry(SavedTo.UNKNOWN, started = 1_794_939_000_000L, finished = null))
        rule.onNodeWithText(" 2026, ", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText("Saved in", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("Kept in Orbis", substring = true).assertCountEquals(0)
    }

    @Test
    fun aPrivateDownloadSaysItIsKeptUntilPrivateBrowsingEnds() {
        show(entry(SavedTo.ORBIS, started = 1_794_939_000_000L, finished = 1_794_939_120_000L, isPrivate = true))
        rule.onNodeWithText("Kept in Orbis until private browsing ends", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText("Saved in", substring = true).assertCountEquals(0)
    }

    @Test
    fun aDownloadSavedInOrbisOnlySaysSo() {
        show(entry(SavedTo.ORBIS, started = 1_794_939_000_000L, finished = 1_794_939_120_000L))
        rule.onNodeWithText("Saved in Orbis only", substring = true).assertIsDisplayed()
    }

    @Test
    fun anEntryWithNeitherTimeNorPlaceHasNoSecondLine() {
        show(entry(SavedTo.UNKNOWN, started = 0, finished = null))
        rule.onNodeWithText("PDF document · ", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText(" 2026, ", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("Saved in", substring = true).assertCountEquals(0)
    }
}