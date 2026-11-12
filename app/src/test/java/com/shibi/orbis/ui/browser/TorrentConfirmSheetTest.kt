package com.shibi.orbis.ui.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shibi.orbis.engine.IncomingDownload
import com.shibi.orbis.tabs.PendingTorrentIntake
import com.shibi.orbis.torrent.IntakeRefusal
import com.shibi.orbis.torrent.IntakeRequest
import com.shibi.orbis.torrent.TorrentIntakeReader
import com.shibi.orbis.torrent.TorrentSource
import com.shibi.orbis.torrent.bencode
import com.shibi.orbis.ui.passTapGuard
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TorrentConfirmSheetTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val magnet = "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel"
    private val note = "Torrent downloads connect directly to other people. They, and the trackers, can see your IP address. Orbis sends nothing else."
    private val reminder = "Peers can see your IP address"
    private val privateLine = "From a private tab: uses the torrent's trackers only. The files stay inside Orbis, not in your Downloads, and are deleted when private browsing ends unless you save them."

    private class Added(val source: TorrentSource, val isPrivate: Boolean, val noteShown: Boolean)

    /** A .torrent response whose body arrives only once [gate] opens. */
    private class GatedResponse(private val bytes: ByteArray) : IncomingDownload {
        val gate = CountDownLatch(1)

        @Volatile var opens = 0

        @Volatile var discards = 0
        override val url = "https://example.com/sintel.torrent"
        override val contentDisposition: String? = null
        override val contentType: String? = "application/x-bittorrent"
        override val contentLength = bytes.size.toLong()

        override fun openBody(): InputStream {
            opens++
            gate.await()
            return ByteArrayInputStream(bytes)
        }

        override fun discard() {
            discards++
        }
    }

    private val added = CopyOnWriteArrayList<Added>()
    private val logs = CopyOnWriteArrayList<String>()
    private var cancels = 0

    private fun show(request: IntakeRequest, ipNoteSeen: Boolean = false) {
        val pending = PendingTorrentIntake(request, sourceTabId = 1L)
        rule.setContent {
            MaterialTheme {
                TorrentIntakeSheet(
                    pending = pending,
                    ipNoteSeen = ipNoteSeen,
                    reader = TorrentIntakeReader(Dispatchers.IO),
                    readUri = { TorrentIntakeReader.Result.Failed(IntakeRefusal.READ_FAILED) },
                    onDownload = { source, isPrivate, noteShown -> added += Added(source, isPrivate, noteShown) },
                    onCancel = { cancels++ },
                    log = { logs += it },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun aMagnetShowsItsNameAndTheFirstUseNoteThenDownloads() {
        show(IntakeRequest.Magnet(magnet, isPrivate = false))
        rule.onNodeWithText("Download torrent?").assertExists()
        rule.onNodeWithText("Sintel").assertExists()
        rule.onNodeWithText(note).assertExists()
        rule.onAllNodesWithText(reminder).assertCountEquals(0)
        rule.onAllNodesWithText(privateLine).assertCountEquals(0)
        rule.passTapGuard()
        rule.onNodeWithText("Download").assertIsEnabled().performClick()
        rule.waitForIdle()
        val a = added.single()
        assertTrue(a.source is TorrentSource.Magnet)
        assertEquals(magnet, (a.source as TorrentSource.Magnet).uri)
        assertEquals(false, a.isPrivate)
        assertEquals(true, a.noteShown)
    }

    @Test
    fun afterTheFirstTorrentTheNoteIsAOneLineReminderAndPrivateIsCarried() {
        show(IntakeRequest.Magnet(magnet, isPrivate = true), ipNoteSeen = true)
        rule.onNodeWithText(reminder).assertExists()
        rule.onAllNodesWithText(note).assertCountEquals(0)
        rule.onNodeWithText(privateLine).assertExists()
        rule.passTapGuard()
        rule.onNodeWithText("Download").performClick()
        rule.waitForIdle()
        assertEquals(true, added.single().isPrivate)
        assertEquals(false, added.single().noteShown)
    }

    @Test
    fun aResponseIsDisabledUntilItsBytesArriveThenShowsThePreview() {
        val torrent = bencode(mapOf("info" to mapOf("name" to "Sintel", "files" to listOf(mapOf("length" to 1L, "path" to listOf("a")), mapOf("length" to 2L, "path" to listOf("b"))))))
        val response = GatedResponse(torrent)
        show(IntakeRequest.TorrentResponse(response, isPrivate = true))
        rule.onNodeWithText("Torrent file").assertExists()
        rule.onNodeWithText("Download").assertIsNotEnabled()
        response.gate.countDown()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Sintel").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("2 files", substring = true).assertExists()
        rule.passTapGuard()
        rule.onNodeWithText("Download").assertIsEnabled().performClick()
        rule.waitForIdle()
        val a = added.single()
        assertArrayEquals(torrent, (a.source as TorrentSource.TorrentFile).bytes)
        assertEquals(true, a.isPrivate)
        assertEquals(0, response.discards)
    }

    @Test
    fun cancelDiscardsTheBodyOnce() {
        val response = GatedResponse(ByteArray(10))
        show(IntakeRequest.TorrentResponse(response, isPrivate = false))
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        response.gate.countDown()
        rule.waitForIdle()
        assertEquals(1, cancels)
        assertEquals(1, response.discards)
        assertTrue(added.isEmpty())
        assertTrue("intake cancelled" in logs)
    }

    @Test
    fun anOversizedResponseShowsTheErrorAndStaysDisabled() {
        val response = GatedResponse(ByteArray(com.shibi.orbis.torrent.TorrentLimits.MAX_TORRENT_FILE_BYTES + 1))
        response.gate.countDown()
        show(IntakeRequest.TorrentResponse(response, isPrivate = false))
        rule.waitUntil(5_000) { rule.onAllNodesWithText("This torrent file is too large").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Download").assertIsNotEnabled()
        assertEquals(1, response.discards)
        assertTrue("intake refused TOO_LARGE" in logs)
    }
}
