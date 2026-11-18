package com.shibi.orbis.ui.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shibi.orbis.downloads.DownloadEntry
import com.shibi.orbis.downloads.DownloadStatus
import com.shibi.orbis.downloads.SavedTo
import com.shibi.orbis.engine.IncomingDownload
import com.shibi.orbis.tabs.PendingDownload
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The messages after a download starts: which one for which state, the timing, the Open button, the skipped dialog. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DownloadFeedbackTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val t0 = 1_000_000L
    private val id = 7L
    private val host = SnackbarHostState()
    private val entries = MutableStateFlow(listOf(entry(DownloadStatus.IN_PROGRESS)))
    private val opened = mutableListOf<Long>()

    private fun entry(
        status: DownloadStatus,
        savedTo: SavedTo = SavedTo.UNKNOWN,
        finishedAt: Long? = null,
        isPrivate: Boolean = false,
    ) =
        DownloadEntry(
            id = id,
            fileName = "a.pdf",
            mimeType = "application/pdf",
            sourceHost = null,
            totalBytes = -1L,
            receivedBytes = 0L,
            status = status,
            filePath = null,
            startedAt = t0,
            isPrivate = isPrivate,
            finishedAt = finishedAt,
            savedTo = savedTo,
        )

    private fun show() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box {
                SnackbarHost(host)
                DownloadFeedback(host, entries, id, onOpen = { opened += it }, now = { t0 + rule.mainClock.currentTime })
            }
        }
        advance(50)
    }

    private fun advance(ms: Long) {
        if (ms > 0) rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun assertMessage(text: String, action: String?) {
        rule.onNodeWithText(text).assertExists()
        if (action != null) rule.onNodeWithText(action).assertExists() else rule.onNodeWithText("Open").assertDoesNotExist()
    }

    private fun assertNoMessage() {
        assertNull(host.currentSnackbarData)
    }

    @Test
    fun start_shows_downloading_without_a_button() {
        show()
        assertMessage("Downloading…", null)
    }

    @Test
    fun a_quick_download_keeps_downloading_for_700_ms_then_saved_with_open() {
        show()
        advance(100)
        entries.value = listOf(entry(DownloadStatus.COMPLETED, SavedTo.DOWNLOADS, finishedAt = t0 + 100))
        advance(500)
        assertMessage("Downloading…", null)
        advance(200)
        assertMessage("Saved to Downloads", "Open")
    }

    @Test
    fun a_file_kept_in_orbis_says_finished_only_after_2_seconds() {
        show()
        advance(1_000)
        entries.value = listOf(entry(DownloadStatus.COMPLETED, SavedTo.ORBIS, finishedAt = t0 + 1_000))
        advance(1_900)
        assertNoMessage()
        advance(200)
        assertMessage("Download finished", "Open")
    }

    @Test
    fun a_copy_made_within_the_wait_says_saved_to_downloads() {
        show()
        advance(1_000)
        entries.value = listOf(entry(DownloadStatus.COMPLETED, SavedTo.ORBIS, finishedAt = t0 + 1_000))
        advance(500)
        entries.value = listOf(entry(DownloadStatus.COMPLETED, SavedTo.DOWNLOADS, finishedAt = t0 + 1_000))
        advance(100)
        assertMessage("Saved to Downloads", "Open")
    }

    @Test
    fun private_failed_and_cancelled() {
        show()
        advance(1_000)
        entries.value = listOf(entry(DownloadStatus.COMPLETED, SavedTo.ORBIS, finishedAt = t0 + 1_000, isPrivate = true))
        advance(100)
        assertMessage("Saved in Orbis until private browsing ends", "Open")
    }

    @Test
    fun failed_has_no_button() {
        show()
        advance(1_000)
        entries.value = listOf(entry(DownloadStatus.FAILED))
        advance(100)
        assertMessage("Download failed", null)
    }

    @Test
    fun cancelled_removes_the_message_and_shows_nothing() {
        show()
        advance(1_000)
        entries.value = listOf(entry(DownloadStatus.CANCELLED))
        advance(100)
        assertNoMessage()
    }

    @Test
    fun downloading_is_dismissed_after_10_seconds_and_the_final_message_still_comes() {
        show()
        advance(10_100)
        assertNoMessage()
        entries.value = listOf(entry(DownloadStatus.COMPLETED, SavedTo.DOWNLOADS, finishedAt = t0 + 10_100))
        advance(100)
        assertMessage("Saved to Downloads", "Open")
    }

    @Test
    fun open_calls_the_open_callback_once_with_the_entry_id_and_nothing_else() {
        show()
        advance(1_000)
        entries.value = listOf(entry(DownloadStatus.COMPLETED, SavedTo.DOWNLOADS, finishedAt = t0 + 1_000))
        advance(100)
        rule.onNodeWithText("Open").performClick()
        advance(100)
        // The composable has one callback only: "Open" never reaches a navigation to the Downloads screen.
        assertEquals(listOf(id), opened)
    }

    private class FakeDownload(override val userRequested: Boolean) : IncomingDownload {
        override val url = "https://example.com/a.pdf"
        override val contentDisposition: String? = null
        override val contentType: String? = "application/pdf"
        override val contentLength = -1L

        override fun openBody(): InputStream? = null

        override fun discard() = Unit
    }

    private fun offer(userRequested: Boolean): Pair<Int, Int> {
        var starts = 0
        var dialogs = 0
        val pending = PendingDownload(FakeDownload(userRequested), 1L)
        rule.setContent {
            DownloadOffer(pending, onStart = { starts++ }, dialog = { dialogs++; Text("Download file?") })
        }
        rule.waitForIdle()
        return starts to dialogs
    }

    @Test
    fun a_user_requested_download_starts_at_once_with_no_dialog() {
        assertEquals(1 to 0, offer(userRequested = true))
    }

    @Test
    fun any_other_download_keeps_the_dialog_and_does_not_start() {
        assertEquals(0 to 1, offer(userRequested = false))
    }
}
