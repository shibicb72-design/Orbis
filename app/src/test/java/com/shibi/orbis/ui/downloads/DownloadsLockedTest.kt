package com.shibi.orbis.ui.downloads

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.shibi.orbis.engine.IncomingDownload
import com.shibi.orbis.downloads.DownloadManager
import com.shibi.orbis.downloads.DownloadStatus
import com.shibi.orbis.downloads.DownloadStore
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** While private tabs are locked, a private download shows no name and cannot be opened or shared. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DownloadsLockedTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    @get:Rule(order = 2)
    val tmp = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val scopes = ArrayList<CoroutineScope>()
    private var locked by mutableStateOf(true)

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private class Incoming(
        override val url: String,
        private val name: String,
        private val data: ByteArray,
    ) : IncomingDownload {
        override val contentDisposition: String = "attachment; filename=\"$name\""
        override val contentType: String = "application/pdf"
        override val contentLength: Long = data.size.toLong()
        override val validator: String? = null

        override fun openBody(): InputStream = ByteArrayInputStream(data)

        override fun discard() {}
    }

    private var tick = 0L

    /** A real manager with one private and one normal download, both finished. The normal one is newer, so it is listed first. */
    private fun manager(): DownloadManager {
        val store = DownloadStore(File(tmp.root, "downloads_index.json"))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler)).also { scopes += it }
        val m =
            DownloadManager(
                dir = { File(tmp.root, "downloads") }, store = store, scope = scope, io = StandardTestDispatcher(scheduler),
                log = {}, now = { ++tick * 1000 },
            )
        m.start(Incoming("https://hidden.example/a", "secret-plans.pdf", ByteArray(5_000)), isPrivate = true)
        scheduler.advanceUntilIdle()
        m.start(Incoming("https://news.example/b", "holiday.pdf", ByteArray(5_000)))
        scheduler.advanceUntilIdle()
        return m
    }

    private fun show() {
        val files = manager()
        rule.setContent {
            MaterialTheme { DownloadsScreen(manager = files, torrents = null, onBack = {}, privateLocked = locked) }
        }
        scheduler.advanceUntilIdle()
        rule.waitForIdle()
    }

    @Test(timeout = 60_000)
    fun lockedAPrivateRowShowsPrivateDownloadNotTheFileName() {
        show()
        rule.onNodeWithText("Private download").assertExists()
        rule.onAllNodesWithText("secret-plans", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("hidden.example", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("PDF document", substring = true).assertCountEquals(1)
        rule.onNodeWithText("Kept in Orbis until private browsing ends", substring = true).assertExists()
    }

    @Test(timeout = 60_000)
    fun lockedAPrivateRowsOpenAndShareAreDisabledAndTheNormalRowWorks() {
        show()
        // Index 0 is the normal row, index 1 the private one.
        rule.onAllNodesWithContentDescription("Open")[0].assertIsEnabled()
        rule.onAllNodesWithContentDescription("Share")[0].assertIsEnabled()
        rule.onAllNodesWithContentDescription("Open")[1].assertIsNotEnabled()
        rule.onAllNodesWithContentDescription("Share")[1].assertIsNotEnabled()
        rule.onAllNodesWithContentDescription("Delete")[1].assertIsEnabled()
        rule.onNodeWithText("holiday.pdf").assertExists()
        rule.onNodeWithText("From news.example", substring = true).assertExists()
    }

    @Test(timeout = 60_000)
    fun unlockedThePrivateRowShowsItsNameAndItsActionsWork() {
        locked = false
        show()
        rule.onNodeWithText("secret-plans.pdf").assertExists()
        rule.onAllNodesWithText("Private download").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Open")[1].assertIsEnabled()
        rule.onAllNodesWithContentDescription("Share")[1].assertIsEnabled()
    }
}
