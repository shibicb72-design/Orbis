package com.shibi.orbis.torrent

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** tor17-1: a torrent with every byte finishes once, by whichever route reports it first. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TorrentManagerFinishTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private val name = "SecretName"
    private val magnet = "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=$name"

    /** Two real files with a padding file between them, as newer torrent makers write them. */
    private val paddedFiles =
        TorrentFiles(
            name,
            16L,
            4,
            listOf(
                TorrentFileEntry(listOf(name, "movie.mp4"), 8L),
                TorrentFileEntry(listOf(name, ".pad", "6"), 6L, isPadFile = true),
                TorrentFileEntry(listOf(name, "sub", "en.srt"), 2L),
            ),
        )

    private fun setup(scope: TestScope, root: File = tmp.root) = TorrentTestSetup(root, scope.testScheduler).also { scopes += it.scope }

    private fun writePayload(entry: TorrentEntry) {
        File(entry.saveDir, "$name/sub").mkdirs()
        File(entry.saveDir, "$name/movie.mp4").writeText("movie123")
        File(entry.saveDir, "$name/sub/en.srt").writeText("su")
    }

    /** Added, files approved, downloading, every byte written. Returns the entry id. */
    private fun TestScope.downloaded(s: TorrentTestSetup): String {
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)!!
        advanceUntilIdle()
        val eid = s.lastId()
        s.engine.emitMetadata(eid, paddedFiles)
        advanceUntilIdle()
        s.engine.advance(eid, done = 10, total = 10, downBps = 0, peers = 517)
        advanceUntilIdle()
        assertEquals(TorrentStatus.DOWNLOADING, s.entry(id).status)
        writePayload(s.entry(id))
        return id
    }

    private class End(val status: TorrentStatus, val publishedPath: String?, val published: Set<Pair<String, String>>, val removed: Int, val started: Boolean)

    private fun end(s: TorrentTestSetup, id: String) =
        End(s.entry(id).status, s.entry(id).publishedPath, s.published.map { it.dir to it.name }.toSet(), s.engine.removed.size, s.engine.started)

    @Test
    fun aSecondFinishedForATorrentInTheFinishPathChangesNothing() = runTest {
        val s = setup(this)
        val id = downloaded(s)
        val eid = s.lastId()
        // Both routes at once (the alert and the tick in the same second): both events are queued before the manager runs.
        s.engine.finish(eid)
        s.engine.finish(eid)
        advanceUntilIdle()
        assertEquals(TorrentStatus.COMPLETED, s.entry(id).status)
        assertEquals(2, s.published.size)
        assertEquals(listOf(eid to false), s.engine.removed)
        assertEquals(1, s.logs.count { it == "finished" })
        assertEquals(1, s.logs.count { it == "published" })
        // And one more, long after: the torrent left the session, nothing is published again.
        s.engine.finish(eid)
        advanceUntilIdle()
        assertEquals(TorrentStatus.COMPLETED, s.entry(id).status)
        assertEquals(2, s.published.size)
        assertEquals(1, s.logs.count { it == "published" })
    }

    @Test
    fun aSecondFinishedForASeedingTorrentPublishesNothing() = runTest {
        val s = setup(this)
        s.settings.value = TorrentSettings(wifiOnly = true, stopSeeding = false)
        val id = downloaded(s)
        val eid = s.lastId()
        s.engine.finish(eid)
        advanceUntilIdle()
        s.engine.finish(eid)
        advanceUntilIdle()
        assertEquals(TorrentStatus.SEEDING, s.entry(id).status)
        assertTrue(s.published.isEmpty())
        assertTrue(s.engine.removed.isEmpty())
        assertTrue(eid in s.engine.running)
    }

    @Test
    fun aFinishedFromTheTickEndsLikeOneFromTheAlertWithStopSeedingOn() = runTest {
        val byAlert = setup(this, tmp.newFolder("alert"))
        val alertId = downloaded(byAlert)
        byAlert.engine.finish(byAlert.lastId())
        advanceUntilIdle()

        val byTick = setup(this, tmp.newFolder("tick"))
        val tickId = downloaded(byTick)
        assertTrue(byTick.engine.tick(byTick.lastId()))
        advanceUntilIdle()

        val a = end(byAlert, alertId)
        val t = end(byTick, tickId)
        assertEquals(TorrentStatus.COMPLETED, t.status)
        assertEquals(a.status, t.status)
        assertEquals(a.publishedPath, t.publishedPath)
        assertEquals(a.published, t.published)
        // The padding file is never published.
        assertEquals(setOf("Orbis/$name" to "movie.mp4", "Orbis/$name/sub" to "en.srt"), t.published)
        assertEquals(a.removed, t.removed)
        assertEquals(a.started, t.started)
        assertFalse("the session stopped: no notification for a download is left", t.started)
        // The next tick finds nothing: the torrent left the session.
        assertFalse(byTick.engine.tick(byTick.lastId()))
    }

    @Test
    fun aFinishedFromTheTickEndsLikeOneFromTheAlertWithStopSeedingOff() = runTest {
        val keepSeeding = TorrentSettings(wifiOnly = true, stopSeeding = false)
        val byAlert = setup(this, tmp.newFolder("alert"))
        byAlert.settings.value = keepSeeding
        val alertId = downloaded(byAlert)
        byAlert.engine.finish(byAlert.lastId())
        advanceUntilIdle()

        val byTick = setup(this, tmp.newFolder("tick"))
        byTick.settings.value = keepSeeding
        val tickId = downloaded(byTick)
        assertTrue(byTick.engine.tick(byTick.lastId()))
        advanceUntilIdle()

        val a = end(byAlert, alertId)
        val t = end(byTick, tickId)
        assertEquals(TorrentStatus.SEEDING, t.status)
        assertEquals(a.status, t.status)
        assertEquals(a.published, t.published)
        assertTrue(t.published.isEmpty())
        assertEquals(a.removed, t.removed)
        // Seeding goes on, and the tick does not report it again every second.
        repeat(3) { assertFalse(byTick.engine.tick(byTick.lastId())) }
        advanceUntilIdle()
        assertEquals(1, byTick.logs.count { it == "finished, seeding" })
        // "Stop seeding" then saves it, as after the alert.
        byTick.manager.stopSeeding(tickId)
        advanceUntilIdle()
        assertEquals(TorrentStatus.COMPLETED, byTick.entry(tickId).status)
        assertEquals(setOf("Orbis/$name" to "movie.mp4", "Orbis/$name/sub" to "en.srt"), byTick.published.map { it.dir to it.name }.toSet())
    }

    @Test
    fun theTickDoesNotFinishATorrentBeforeItsFilesAreApproved() = runTest {
        val s = setup(this)
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)!!
        advanceUntilIdle()
        // Nothing wanted yet: libtorrent calls it finished. The tick must not.
        assertFalse(s.engine.tick(s.lastId()))
        advanceUntilIdle()
        assertEquals(TorrentStatus.FETCHING_METADATA, s.entry(id).status)
        // Just approved and running: libtorrent's state may still say FINISHED while no byte is wanted yet.
        s.engine.emitMetadata(s.lastId(), paddedFiles)
        advanceUntilIdle()
        assertTrue(s.lastId() in s.engine.running)
        assertFalse(s.engine.tick(s.lastId(), complete = false, finishedOrSeeding = true))
        advanceUntilIdle()
        assertEquals(TorrentStatus.DOWNLOADING, s.entry(id).status)
        assertTrue(s.published.isEmpty())
    }

    @Test
    fun aPausedTorrentIsNotFinishedByTheTickAndFinishesOnceAfterItsResume() = runTest {
        val s = setup(this)
        val id = downloaded(s)
        val eid = s.lastId()
        s.manager.pause(id)
        advanceUntilIdle()
        assertEquals(TorrentStatus.PAUSED, s.entry(id).status)
        assertFalse(s.engine.tick(eid))
        advanceUntilIdle()
        assertEquals(TorrentStatus.PAUSED, s.entry(id).status)
        assertTrue(s.published.isEmpty())

        s.manager.resume(id)
        advanceUntilIdle()
        val again = s.lastId()
        s.engine.emitMetadata(again, paddedFiles)
        advanceUntilIdle()
        assertTrue(again in s.engine.running)
        assertTrue(s.engine.tick(again))
        // Its alert arrives too, late: nothing more happens.
        s.engine.finish(again)
        advanceUntilIdle()
        assertFalse(s.engine.tick(again))
        assertEquals(TorrentStatus.COMPLETED, s.entry(id).status)
        assertEquals(2, s.published.size)
        assertEquals(1, s.logs.count { it == "published" })
    }

    @Test
    fun aTorrentCompleteAtApprovalIsNotReportedAgainByTheTick() = runTest {
        val s = setup(this)
        s.settings.value = TorrentSettings(wifiOnly = true, stopSeeding = false)
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)!!
        advanceUntilIdle()
        val eid = s.lastId()
        s.engine.completeOnApproval += eid
        s.engine.emitMetadata(eid, paddedFiles)
        advanceUntilIdle()
        assertEquals(TorrentStatus.SEEDING, s.entry(id).status)
        assertFalse(s.engine.tick(eid))
        advanceUntilIdle()
        assertEquals(1, s.logs.count { it == "finished, seeding" })
    }
}
