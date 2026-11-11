package com.shibi.orbis.ui.downloads

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shibi.orbis.downloads.DownloadEntry
import com.shibi.orbis.downloads.DownloadManager
import com.shibi.orbis.downloads.DownloadStatus
import com.shibi.orbis.downloads.DownloadStore
import com.shibi.orbis.torrent.EngineState
import com.shibi.orbis.torrent.TorrentFileEntry
import com.shibi.orbis.torrent.TorrentFiles
import com.shibi.orbis.torrent.TorrentSettings
import com.shibi.orbis.torrent.TorrentSource
import com.shibi.orbis.torrent.TorrentStatus
import com.shibi.orbis.torrent.TorrentTestSetup
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import com.shibi.orbis.ui.passTapGuard
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DownloadsScreenTorrentsTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    @get:Rule(order = 2)
    val tmp = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val scopes = ArrayList<CoroutineScope>()
    private val magnet = "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel"
    private val sintel =
        TorrentFiles(
            "Sintel",
            10,
            1,
            listOf(TorrentFileEntry(listOf("Sintel", "movie.mp4"), 8), TorrentFileEntry(listOf("Sintel", "en.srt"), 2)),
        )

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun torrents(freeBytes: Long = Long.MAX_VALUE / 4) =
        TorrentTestSetup(tmp.newFolder(), scheduler, freeBytes).also { scopes += it.scope }

    private fun downloads(vararg stored: DownloadEntry): DownloadManager {
        val store = DownloadStore(File(tmp.root, "downloads_index.json"))
        store.save(stored.toList())
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler)).also { scopes += it }
        return DownloadManager(
            dir = { File(tmp.root, "downloads") },
            store = store,
            scope = scope,
            io = StandardTestDispatcher(scheduler),
            log = {},
            now = { 0L },
        ).also { it.restore() }
    }

    private fun idle() {
        scheduler.advanceUntilIdle()
        rule.waitForIdle()
    }

    private fun show(t: TorrentTestSetup, files: DownloadManager = downloads(), dark: Boolean = false) {
        val colors = if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()
        rule.setContent { MaterialTheme(colorScheme = colors) { DownloadsScreen(manager = files, torrents = t.manager, onBack = {}) } }
        idle()
    }

    /** A torrent past its metadata, 5 of 10 bytes in, at 2 kB/s with 3 peers and 2:05 to go. */
    private fun downloading(t: TorrentTestSetup, isPrivate: Boolean = false): String {
        val id = t.manager.add(TorrentSource.Magnet(magnet), isPrivate)!!
        scheduler.advanceUntilIdle()
        t.engine.emitMetadata(t.lastId(), sintel)
        scheduler.advanceUntilIdle()
        t.engine.advance(t.lastId(), done = 5, total = 10, downBps = 2048, peers = 3, etaSeconds = 125)
        scheduler.advanceUntilIdle()
        return id
    }

    private fun completed(t: TorrentTestSetup, isPrivate: Boolean = false): String {
        val id = downloading(t, isPrivate)
        val dir = File(t.entry(id).saveDir, "Sintel").apply { mkdirs() }
        File(dir, "movie.mp4").writeText("movie123")
        File(dir, "en.srt").writeText("su")
        t.engine.finish(t.lastId())
        scheduler.advanceUntilIdle()
        assertEquals(TorrentStatus.COMPLETED, t.entry(id).status)
        return id
    }

    @Test
    fun aDownloadingRowShowsItsNumbersAndPause() {
        val t = torrents()
        downloading(t)
        show(t)
        rule.onNodeWithText("Sintel").assertExists()
        rule.onNodeWithText("Downloading").assertExists()
        rule.onNodeWithText(" of ", substring = true).assertExists()
        rule.onNodeWithText("/s", substring = true).assertExists()
        rule.onNodeWithText("3 peers", substring = true).assertExists()
        rule.onNodeWithText("02:05 left", substring = true).assertExists()
        rule.onNodeWithText("Pause").assertExists()
        rule.onAllNodesWithText("Resume").assertCountEquals(0)
    }

    @Test
    fun aPausedRowShowsResume() {
        val t = torrents()
        val id = downloading(t)
        show(t)
        rule.onNodeWithText("Pause").performClick()
        idle()
        assertEquals(TorrentStatus.PAUSED, t.entry(id).status)
        rule.onNodeWithText("Paused").assertExists()
        rule.onNodeWithText("Resume").assertExists()
        rule.onAllNodesWithText("Pause").assertCountEquals(0)
    }

    @Test
    fun aRowWithoutSpaceSaysSoAndOffersRetry() {
        val t = torrents(freeBytes = 150L * 1024 * 1024)
        t.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)
        scheduler.advanceUntilIdle()
        t.engine.emitMetadata(t.lastId(), sintel)
        scheduler.advanceUntilIdle()
        show(t)
        rule.onNodeWithText("Not enough space").assertExists()
        rule.onNodeWithText("Retry").assertExists()
    }

    @Test
    fun aCompletedRowSaysWhereAndOffersOpen() {
        val t = torrents()
        completed(t)
        show(t)
        rule.onNodeWithText("Saved in Download/Orbis/Sintel").assertExists()
        rule.onNodeWithContentDescription("Open").assertExists()
        rule.onAllNodesWithText("Pause").assertCountEquals(0)
    }

    @Test
    fun aPrivateRowNeverShowsTheName() {
        val t = torrents()
        downloading(t, isPrivate = true)
        show(t)
        rule.onNodeWithText("Private download").assertExists()
        rule.onAllNodesWithText("Sintel", substring = true).assertCountEquals(0)
        rule.onNodeWithContentDescription("Remove").performClick()
        idle()
        rule.onNodeWithText("Remove this torrent?").assertExists()
        rule.onAllNodesWithText("Sintel", substring = true).assertCountEquals(0)
    }

    @Test
    fun removingAnUnfinishedTorrentDeletesWhatItDownloaded() {
        val t = torrents()
        val id = downloading(t)
        val eid = t.lastId()
        show(t)
        rule.onNodeWithContentDescription("Remove").performClick()
        idle()
        rule.onNodeWithText("Remove this torrent?").assertExists()
        rule.onNodeWithText("The 5 B downloaded so far will be deleted.").assertExists()
        rule.passTapGuard()
        rule.onNodeWithText("Remove").performClick()
        idle()
        assertTrue(t.manager.entries.value.none { it.id == id })
        assertTrue(eid to true in t.engine.removed)
        assertTrue(t.logs.contains("removed DOWNLOADING files=true"))
    }

    @Test
    fun removingACompletedTorrentKeepsItsFiles() {
        val t = torrents()
        val id = completed(t)
        show(t)
        rule.onNodeWithContentDescription("Remove").performClick()
        idle()
        rule.onNodeWithText("Remove from the list?").assertExists()
        rule.onNodeWithText("The files stay in Download/Orbis/Sintel.").assertExists()
        rule.passTapGuard()
        rule.onNodeWithText("Remove").performClick()
        idle()
        assertTrue(t.manager.entries.value.none { it.id == id })
        assertTrue(t.logs.contains("removed COMPLETED files=false"))
        assertTrue(t.unpublished.isEmpty())
    }

    @Test
    fun fileDownloadsAndTorrentsShareOneListNewestFirst() {
        val t = torrents()
        t.clock = 1_000L
        downloading(t)
        val newer = DownloadEntry(5_000L, "report.pdf", "application/pdf", null, 3, 3, DownloadStatus.COMPLETED, File(tmp.root, "report.pdf").path, 2_000L)
        val older = DownloadEntry(5_001L, "old.zip", "application/zip", null, 3, 3, DownloadStatus.COMPLETED, File(tmp.root, "old.zip").path, 500L)
        show(t, downloads(newer, older))
        val top = { text: String -> rule.onNodeWithText(text).getBoundsInRoot().top }
        assertTrue(top("report.pdf") < top("Sintel"))
        assertTrue(top("Sintel") < top("old.zip"))
    }

    /** Finished with "Stop seeding when finished" off: seeding at ratio 0.25, uploading at 1 kB/s. */
    private fun seeding(t: TorrentTestSetup, limit: Float? = null): String {
        t.settings.value = TorrentSettings(stopSeeding = false, seedRatioLimit = limit)
        val id = downloading(t)
        val dir = File(t.entry(id).saveDir, "Sintel").apply { mkdirs() }
        File(dir, "movie.mp4").writeText("movie123")
        File(dir, "en.srt").writeText("su")
        t.engine.finish(t.lastId())
        scheduler.advanceUntilIdle()
        t.engine.advance(t.lastId(), done = 10, total = 10, state = EngineState.SEEDING, downBps = 0, peers = 2, upBps = 1000, ratio = 0.25f)
        scheduler.advanceUntilIdle()
        assertEquals(TorrentStatus.SEEDING, t.entry(id).status)
        return id
    }

    @Test
    fun aSeedingRowShowsItsRatioAndStopSeeding() {
        val t = torrents()
        val id = seeding(t)
        show(t)
        rule.onNodeWithText("Seeding").assertExists()
        rule.onNodeWithText("ratio 0.25", substring = true).assertExists()
        rule.onNodeWithText("/s", substring = true).assertExists()
        rule.onNodeWithText("Pause").assertExists()
        rule.onNodeWithText("Stop seeding").performClick()
        idle()
        assertEquals(TorrentStatus.COMPLETED, t.entry(id).status)
        rule.onNodeWithText("Saved in Download/Orbis/Sintel").assertExists()
        assertTrue(t.logs.contains("stop seeding USER"))
    }

    @Test
    fun aSeedingRowWithALimitSaysWhereItStops() {
        val t = torrents()
        seeding(t, limit = 1.5f)
        show(t)
        rule.onNodeWithText("Seeding, stops at ratio 1.5").assertExists()
    }

    @Test
    fun removingASeedingTorrentOffersToSaveItFirst() {
        val t = torrents()
        val id = seeding(t)
        show(t)
        rule.onNodeWithContentDescription("Remove").performClick()
        idle()
        rule.onNodeWithText("The files are finished but not saved to Download/Orbis/Sintel yet.").assertExists()
        rule.onNodeWithText("Delete").assertExists()
        rule.passTapGuard()
        rule.onNodeWithText("Save and remove").performClick()
        idle()
        assertTrue(t.manager.entries.value.none { it.id == id })
        assertEquals(setOf("movie.mp4", "en.srt"), t.published.map { it.name }.toSet())
    }

    @Test
    fun aPausedFinishedRowOffersSaveAndRemove() {
        val t = torrents()
        val id = seeding(t)
        t.manager.pause(id)
        scheduler.advanceUntilIdle()
        assertEquals(TorrentStatus.PAUSED, t.entry(id).status)
        assertEquals(t.entry(id).totalBytes, t.entry(id).doneBytes)
        show(t)
        rule.onNodeWithContentDescription("Remove").performClick()
        idle()
        rule.onNodeWithText("The files are finished but not saved to Download/Orbis/Sintel yet.").assertExists()
        rule.onNodeWithText("Delete").assertExists()
        rule.passTapGuard()
        rule.onNodeWithText("Save and remove").performClick()
        idle()
        assertTrue(t.manager.entries.value.none { it.id == id })
        assertEquals(setOf("movie.mp4", "en.srt"), t.published.map { it.name }.toSet())
    }

    @Test
    fun withoutTorrentsTheScreenStillWorks() {
        val files = downloads(DownloadEntry(1L, "a.bin", "application/octet-stream", null, 3, 3, DownloadStatus.COMPLETED, File(tmp.root, "a.bin").path, 1L))
        rule.setContent { MaterialTheme { DownloadsScreen(manager = files, torrents = null, onBack = {}) } }
        idle()
        rule.onNodeWithText("a.bin").assertExists()
    }

    // ---- private torrents kept inside Orbis (security PR 9) ----

    private fun keptRowOffersSaveToDownloads(dark: Boolean) {
        val t = torrents()
        val id = completed(t, isPrivate = true)
        val kept = File(t.entry(id).keptDir!!)
        show(t, dark = dark)
        rule.onNodeWithText("Kept inside Orbis, not in your Downloads").assertExists()
        rule.onAllNodesWithText("Sintel", substring = true).assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Open").assertCountEquals(0)
        rule.onNodeWithText("Save to Downloads").assertIsDisplayed().performClick()
        idle()
        rule.onNodeWithText("Saved in your Downloads").assertExists()
        assertEquals(setOf("movie.mp4", "en.srt"), t.published.map { it.name }.toSet())
        assertTrue(!kept.exists())
        rule.onAllNodesWithText("Save to Downloads").assertCountEquals(0)
    }

    @Test
    fun aKeptPrivateRowOffersSaveToDownloads() = keptRowOffersSaveToDownloads(dark = false)

    @Test
    fun aKeptPrivateRowOffersSaveToDownloadsInTheDarkTheme() = keptRowOffersSaveToDownloads(dark = true)

    @Test
    fun removingAKeptPrivateRowSaysItsFilesAreDeleted() {
        val t = torrents()
        val id = completed(t, isPrivate = true)
        val kept = File(t.entry(id).keptDir!!)
        show(t)
        rule.onNodeWithContentDescription("Remove").performClick()
        idle()
        rule.onNodeWithText("The files kept inside Orbis are deleted.").assertExists()
        rule.passTapGuard()
        rule.onNodeWithText("Remove").performClick()
        idle()
        assertTrue(t.manager.entries.value.none { it.id == id })
        assertTrue(!kept.exists())
        assertTrue(t.published.isEmpty())
    }
}
