package com.shibi.orbis.ui.downloads

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.shibi.orbis.torrent.PauseReason
import com.shibi.orbis.torrent.TorrentEntry
import com.shibi.orbis.torrent.TorrentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The second line of a torrent row while it looks for peers (tor10-4): which line for which state, and its exact words. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TorrentSearchLineTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun entry(
        status: TorrentStatus = TorrentStatus.DOWNLOADING,
        found: Int = 0,
        connected: Int = 0,
        down: Long = 0,
        seconds: Int = 0,
        dht: Boolean = true,
        trackers: Int = 0,
        failed: Int = 0,
        private: Boolean = false,
    ) = TorrentEntry(
        id = "0123456789abcdef0123456789abcdef", displayName = "Sintel", totalBytes = 100, doneBytes = 0, status = status,
        addedAt = 1, saveDir = "/x", isPrivate = private, hasMetadata = true, downBps = down, peers = connected,
        peersFound = found, trackersTotal = trackers, trackersFailed = failed, dhtInUse = dht, searchSeconds = seconds,
    )

    private fun text(e: TorrentEntry): String? = searchLine(TorrentRowModel.from(e)!!)?.let { searchText(context, it) }

    @Test(timeout = 20_000)
    fun nothingFoundYetUnderTwentySecondsLooks() {
        assertEquals("Looking for peers…", text(entry(seconds = 5)))
        assertEquals("Looking for peers…", text(entry(status = TorrentStatus.FETCHING_METADATA, seconds = 19)))
    }

    @Test(timeout = 20_000)
    fun foundButNoneConnectedSaysConnecting() {
        assertEquals("Found 4 peers, connecting…", text(entry(found = 4)))
        assertEquals("Found 1 peer, connecting…", text(entry(found = 1)))
    }

    @Test(timeout = 20_000)
    fun connectedSaysHowManyOfHowMany() {
        assertEquals("Connected to 2 of 9 peers", text(entry(found = 9, connected = 2)))
        assertEquals("Connected to 1 peer", text(entry(found = 1, connected = 1)))
        assertEquals("Connected to 1 of 3 peers", text(entry(found = 3, connected = 1)))
        // Connected more than found (peer exchange): never "of" a smaller number.
        assertEquals("Connected to 3 of 3 peers", text(entry(found = 1, connected = 3)))
    }

    @Test(timeout = 20_000)
    fun nothingAfterSixtySecondsSaysSo() {
        assertEquals("No peers found yet. This torrent may have few or no seeders.", text(entry(seconds = 60)))
        assertEquals("No peers found yet. This torrent may have few or no seeders.", text(entry(status = TorrentStatus.FETCHING_METADATA, seconds = 60)))
    }

    @Test(timeout = 20_000)
    fun aTorrentThatMayOnlyAskItsTrackersSaysThatAndOnlyThen() {
        val only = "No peers found yet. This torrent can only ask its own trackers."
        assertEquals(only, text(entry(seconds = 60, dht = false)))
        // The same wording is for no other torrent: a normal one and a private-tab one that may use the DHT.
        assertNotEquals(only, text(entry(seconds = 60, dht = true)))
        assertNotEquals(only, text(entry(seconds = 60, dht = true, private = true)))
        // A private-tab torrent is told the same as a normal one.
        assertEquals(text(entry(seconds = 60)), text(entry(seconds = 60, private = true)))
        // Before the minute it is the plain search line.
        assertEquals("Looking for peers…", text(entry(seconds = 10, dht = false)))
    }

    @Test(timeout = 20_000)
    fun allTrackersFailedIsAddedOnlyWhenTheDhtIsNoHelp() {
        assertEquals(
            "No peers found yet. This torrent can only ask its own trackers. None of this torrent's 3 trackers answered.",
            text(entry(seconds = 60, dht = false, trackers = 3, failed = 3)),
        )
        assertEquals("Looking for peers… None of this torrent's 2 trackers answered.", text(entry(seconds = 8, dht = false, trackers = 2, failed = 2)))
        assertEquals("Looking for peers… This torrent's tracker did not answer.", text(entry(seconds = 8, dht = false, trackers = 1, failed = 1)))
        // One tracker still may answer, none are known, or the DHT is in use: nothing is added.
        assertEquals("Looking for peers…", text(entry(seconds = 8, dht = false, trackers = 3, failed = 2)))
        assertEquals("Looking for peers…", text(entry(seconds = 8, dht = false, trackers = 0, failed = 0)))
        assertEquals("No peers found yet. This torrent may have few or no seeders.", text(entry(seconds = 60, dht = true, trackers = 3, failed = 3)))
        // Peers are known: the trackers do not matter.
        assertEquals("Found 1 peer, connecting…", text(entry(found = 1, dht = false, trackers = 3, failed = 3)))
    }

    @Test(timeout = 20_000)
    fun waitingForWifiKeepsItsLineAndAddsTheOptInNote() {
        val e = entry(status = TorrentStatus.WAITING_FOR_WIFI)
        assertEquals("You turned on Wi-Fi only. Change it in Settings > Torrents.", text(e))
        assertEquals(SearchLine.Kind.WIFI_ONLY_NOTE, searchLine(TorrentRowModel.from(e)!!)?.kind)
    }

    @Test(timeout = 20_000)
    fun everyOtherStateKeepsItsOwnLine() {
        // Bytes flow: the speed line (with its peer count) shows, not a search line.
        assertNull(text(entry(down = 2048, connected = 3, found = 3)))
        assertNull(text(entry(status = TorrentStatus.CHECKING)))
        assertNull(text(entry(status = TorrentStatus.PAUSED)))
        assertNull(text(entry(status = TorrentStatus.PAUSED).copy(pauseReason = PauseReason.QUEUED)))
        assertNull(text(entry(status = TorrentStatus.SEEDING)))
        assertNull(text(entry(status = TorrentStatus.COMPLETED)))
    }

    @Test(timeout = 20_000)
    fun theLineIsAlwaysThereWhileATorrentSearches() {
        for (status in listOf(TorrentStatus.FETCHING_METADATA, TorrentStatus.DOWNLOADING)) {
            for (found in 0..2) for (connected in 0..2) for (seconds in listOf(0, 30, 60)) {
                assertNotEquals(null, text(entry(status = status, found = found, connected = connected, seconds = seconds)))
            }
        }
    }
}
