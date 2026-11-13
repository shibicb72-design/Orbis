package com.shibi.orbis.ui.downloads

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.shibi.orbis.downloads.DownloadEntry
import com.shibi.orbis.downloads.DownloadStatus
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import com.shibi.orbis.ui.theme.frames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The determinate bar of a file row and of a torrent row glides, snaps on a restart and with motion off, and a frame
 * does not recompose the row. The bar's value is read from its progress semantics, which call the same `progress` lambda
 * the indicator draws with. The clock is held.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DownloadsProgressMotionTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private var done by mutableLongStateOf(100)
    private var motion by mutableStateOf(true)
    private var feederRuns = 0

    private fun torrent(doneBytes: Long) =
        TorrentRowModel(
            id = "t1", title = "Sintel", isPrivate = false, doneBytes = doneBytes, totalBytes = 1000,
            percent = (doneBytes / 10).toInt(), speedBps = 2048, peers = 3, seeds = 1, etaSeconds = 60,
            state = RowState.Downloading, actions = setOf(RowAction.PAUSE, RowAction.REMOVE),
        )

    private fun file(receivedBytes: Long) =
        DownloadEntry(
            id = 7, fileName = "movie.mp4", mimeType = "video/mp4", sourceHost = "example.org", totalBytes = 1000,
            receivedBytes = receivedBytes, status = DownloadStatus.IN_PROGRESS, filePath = "/x/movie.mp4", startedAt = 0,
        )

    @Composable
    private fun TorrentFeeder() {
        val d = done
        SideEffect { feederRuns++ }
        TorrentRow(torrent(d), motion, onPause = {}, onResume = {}, onRetry = {}, onOpen = {}, onRemove = {})
    }

    @Composable
    private fun FileFeeder() {
        val d = done
        SideEffect { feederRuns++ }
        DownloadRow(file(d), motion, onCancel = {}, onPause = null, onResume = {}, onOpen = {}, onShare = {}, onDelete = {})
    }

    private fun show(torrentRow: Boolean, motionOn: Boolean = true) {
        motion = motionOn
        rule.mainClock.autoAdvance = false
        rule.setContent { MaterialTheme(colorScheme = lightColorScheme()) { if (torrentRow) TorrentFeeder() else FileFeeder() } }
        frame()
    }

    private fun frame(n: Int = 1) = rule.frames(n)

    private fun shown(): Float =
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun glides(torrentRow: Boolean) {
        show(torrentRow)
        assertEquals("starts at its value, not at zero", 0.1f, shown(), 0.001f)
        done = 800
        frame()
        val runsAfterChange = feederRuns
        val values = List(40) { frame(); shown() }
        assertTrue("grows over several frames: ${values.take(6)}", values.count { it > 0.1f && it < 0.79f } >= 3)
        values.zipWithNext().forEach { (a, b) -> assertTrue("went back: $a then $b", b >= a - EPS) }
        assertEquals(0.8f, values.last(), 0.02f)
        assertEquals("the frames did not recompose the row", runsAfterChange, feederRuns)
    }

    private fun restartShowsAtOnce(torrentRow: Boolean) {
        show(torrentRow)
        done = 800
        frame(40)
        done = 50
        frame()
        assertEquals(0.05f, shown(), 0.001f)
    }

    private fun motionOffIsImmediate(torrentRow: Boolean) {
        show(torrentRow, motionOn = false)
        done = 800
        frame()
        assertEquals(0.8f, shown(), 0.001f)
        done = 300
        frame()
        assertEquals(0.3f, shown(), 0.001f)
    }

    @Test fun aFileRowGlides() = glides(torrentRow = false)

    @Test fun aTorrentRowGlides() = glides(torrentRow = true)

    @Test fun aFileRowRestartShowsOnTheNextFrame() = restartShowsAtOnce(torrentRow = false)

    @Test fun aTorrentRowRestartShowsOnTheNextFrame() = restartShowsAtOnce(torrentRow = true)

    @Test fun aFileRowWithMotionOffShowsOnTheNextFrame() = motionOffIsImmediate(torrentRow = false)

    @Test fun aTorrentRowWithMotionOffShowsOnTheNextFrame() = motionOffIsImmediate(torrentRow = true)

    private companion object {
        /** Float rounding of the spring on its first frame; far below one pixel. */
        const val EPS = 1e-5f
    }
}
