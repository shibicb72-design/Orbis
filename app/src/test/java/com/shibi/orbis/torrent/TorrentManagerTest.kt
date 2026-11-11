package com.shibi.orbis.torrent

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TorrentManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private val hash = "08ada5a7a6183aae1e09d831df6748d566095a10"
    private val secretName = "SecretName"
    private val magnet = "magnet:?xt=urn:btih:$hash&dn=$secretName&tr=udp%3A%2F%2Ftracker.example.org%3A1337"

    private fun setup(scope: TestScope, freeBytes: Long = Long.MAX_VALUE / 4) =
        TorrentTestSetup(tmp.root, scope.testScheduler, freeBytes).also { scopes += it.scope }

    private fun files(name: String = secretName, vararg paths: Pair<List<String>, Long>) =
        TorrentFiles(name, paths.sumOf { it.second }, 4, paths.map { TorrentFileEntry(it.first, it.second) })

    private val sintelFiles = files(secretName, listOf(secretName, "movie.mp4") to 8L, listOf(secretName, "sub", "en.srt") to 2L)

    // Sizes match sintelFiles (8 and 2 bytes).
    private fun writePayload(entry: TorrentEntry) {
        File(entry.saveDir, "$secretName/sub").mkdirs()
        File(entry.saveDir, "$secretName/movie.mp4").writeText("movie123")
        File(entry.saveDir, "$secretName/sub/en.srt").writeText("su")
    }

    @Test
    fun addFetchesMetadataDownloadsAndPublishes() = runTest {
        val s = setup(this)
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)!!
        advanceUntilIdle()
        assertEquals(32, id.length)
        assertFalse(id.contains(hash))
        assertTrue(s.engine.started)
        assertEquals(TorrentStatus.FETCHING_METADATA, s.only().status)
        assertEquals(secretName, s.only().displayName)
        val eid = s.lastId()
        // A fresh magnet gets resume data at once, so a restart does not need the magnet.
        assertArrayEquals("resume-1".toByteArray(), s.store.readResume(id))

        s.engine.emitMetadata(eid, sintelFiles)
        advanceUntilIdle()
        assertEquals(TorrentStatus.DOWNLOADING, s.only().status)
        assertEquals(10L, s.only().totalBytes)
        assertTrue(eid in s.engine.approved)
        assertTrue(s.engine.calls.indexOf("approve ${eid.value}") < s.engine.calls.lastIndexOf("resume ${eid.value}"))
        assertTrue(eid in s.engine.running)
        assertNotNull(s.store.readTorrent(id))

        s.engine.advance(eid, done = 5, total = 10)
        advanceUntilIdle()
        assertEquals(5L, s.only().doneBytes)

        writePayload(s.only())
        s.engine.finish(eid)
        advanceUntilIdle()
        val e = s.only()
        assertEquals(TorrentStatus.COMPLETED, e.status)
        assertEquals("Download/Orbis/$secretName", e.publishedPath)
        val targets = s.published.map { it.dir to it.name }.toSet()
        assertEquals(setOf("Orbis/$secretName" to "movie.mp4", "Orbis/$secretName/sub" to "en.srt"), targets)
        assertEquals("movie123", s.published.first { it.name == "movie.mp4" }.content)
        assertTrue(s.engine.removed.contains(eid to false))
        assertFalse("private copy deleted", File(e.saveDir).exists())
        assertNull(s.store.readResume(id))
        assertNull(s.store.readTorrent(id))
        // Nothing runs any more: the session is gone.
        assertFalse(s.engine.started)
        assertEquals(TorrentStatus.COMPLETED, s.store.load().entries.single().status)
    }

    @Test
    fun finishedBeforeTheFileListIsCheckedIsIgnored() = runTest {
        val s = setup(this)
        s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)
        advanceUntilIdle()
        // A magnet runs on its own until its metadata arrives (no stop_when_ready, which kept it paused).
        assertTrue(s.engine.calls.none { it.startsWith("pause") })
        s.engine.finish(s.lastId())
        advanceUntilIdle()
        assertEquals(TorrentStatus.FETCHING_METADATA, s.only().status)
        assertTrue(s.published.isEmpty())
        assertTrue(s.engine.removed.isEmpty())
    }

    @Test
    fun aTorrentThatFinishesWhileBeingPausedIsPublished() = runTest {
        val s = setup(this)
        val paused = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)!!
        s.clock += 1
        s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)
        advanceUntilIdle()
        val eid = s.engine.added.first { it.saveDir.name == paused }.id
        s.engine.emitMetadata(eid, sintelFiles)
        advanceUntilIdle()
        // The other torrent keeps the session up, so the paused one stays in it.
        s.manager.pause(paused)
        advanceUntilIdle()
        assertTrue(s.engine.started)
        writePayload(s.manager.entries.value.first { it.id == paused })
        s.engine.finish(eid)
        advanceUntilIdle()
        val e = s.manager.entries.value.first { it.id == paused }
        assertEquals(TorrentStatus.COMPLETED, e.status)
        assertEquals(2, s.published.size)
    }

    @Test
    fun aTorrentCompleteOnDiskFinishesWhenResumed() = runTest {
        val s = setup(this)
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)!!
        advanceUntilIdle()
        s.engine.emitMetadata(s.lastId(), sintelFiles)
        advanceUntilIdle()
        // Paused right as the last piece landed: the engine stopped, the resume data is complete.
        s.manager.pause(id)
        advanceUntilIdle()
        assertFalse(s.engine.started)
        writePayload(s.only())
        s.manager.resume(id)
        advanceUntilIdle()
        val again = s.engine.added.last().id
        s.engine.completeOnApproval += again
        s.engine.emitMetadata(again, sintelFiles)
        advanceUntilIdle()
        assertEquals(TorrentStatus.COMPLETED, s.only().status)
        assertEquals("Download/Orbis/$secretName", s.only().publishedPath)
        assertFalse(s.engine.started)
    }

    @Test
    fun missingOrShortFilesAreNeverPublished() = runTest {
        val s = setup(this)
        s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)
        advanceUntilIdle()
        s.engine.emitMetadata(s.lastId(), sintelFiles)
        advanceUntilIdle()
        // Only one of the two files, and that one short: a finish that was not one.
        File(s.only().saveDir, "$secretName").mkdirs()
        File(s.only().saveDir, "$secretName/movie.mp4").writeText("mov")
        s.engine.finish(s.lastId())
        advanceUntilIdle()
        assertEquals(TorrentStatus.FAILED, s.only().status)
        assertEquals(TorrentErrorKind.PUBLISH_FAILED, s.only().errorKind)
        assertTrue(s.published.isEmpty())
    }

    @Test
    fun publishFailureKeepsThePayload() = runTest {
        val s = setup(this)
        s.publishOk = false
        s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)
        advanceUntilIdle()
        s.engine.emitMetadata(s.lastId(), sintelFiles)
        advanceUntilIdle()
        writePayload(s.only())
        s.engine.finish(s.lastId())
        advanceUntilIdle()
        assertEquals(TorrentStatus.FAILED, s.only().status)
        assertEquals(TorrentErrorKind.PUBLISH_FAILED, s.only().errorKind)
        assertTrue(File(s.only().saveDir, "$secretName/movie.mp4").isFile)
    }

    @Test
    fun pauseSavesResumeDataAndResumeReAddsFromIt() = runTest {
        val s = setup(this)
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)!!
        advanceUntilIdle()
        val eid = s.lastId()
        s.engine.emitMetadata(eid, sintelFiles)
        advanceUntilIdle()
        s.engine.resumeBytes = { "paused-state".toByteArray() }

        s.manager.pause(id)
        advanceUntilIdle()
        assertEquals(TorrentStatus.PAUSED, s.only().status)
        assertEquals(PauseReason.USER, s.only().pauseReason)
        assertTrue("pause ${eid.value}" in s.engine.calls)
        assertArrayEquals("paused-state".toByteArray(), s.store.readResume(id))
        assertFalse("idle engine stopped", s.engine.started)

        s.manager.resume(id)
        advanceUntilIdle()
        assertTrue(s.engine.started)
        val again = s.engine.added.last()
        assertTrue(again.source is TorrentSource.TorrentFile)
        assertArrayEquals("paused-state".toByteArray(), again.resumeData)
        // The file list is checked again before it may download.
        assertFalse(again.id in s.engine.running)
        s.engine.emitMetadata(again.id, sintelFiles)
        advanceUntilIdle()
        assertTrue(again.id in s.engine.running)
        assertEquals(TorrentStatus.DOWNLOADING, s.only().status)
    }

    @Test
    fun removeWithFilesDeletesThePayload() = runTest {
        val s = setup(this)
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)!!
        advanceUntilIdle()
        val eid = s.lastId()
        s.engine.emitMetadata(eid, sintelFiles)
        advanceUntilIdle()
        writePayload(s.only())
        val dir = File(s.only().saveDir)

        s.manager.remove(id, deleteFiles = true)
        advanceUntilIdle()
        assertTrue(s.manager.entries.value.isEmpty())
        assertTrue(s.engine.removed.contains(eid to true))
        assertFalse(dir.exists())
        assertNull(s.store.readResume(id))
        assertTrue(s.store.load().entries.isEmpty())
    }

    @Test
    fun removeWithoutFilesKeepsThePayload() = runTest {
        val s = setup(this)
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)!!
        advanceUntilIdle()
        val eid = s.lastId()
        s.engine.emitMetadata(eid, sintelFiles)
        advanceUntilIdle()
        writePayload(s.only())
        val dir = File(s.only().saveDir)

        s.manager.remove(id, deleteFiles = false)
        advanceUntilIdle()
        assertTrue(s.manager.entries.value.isEmpty())
        assertTrue(s.engine.removed.contains(eid to false))
        assertTrue(File(dir, "$secretName/movie.mp4").isFile)
        assertNull(s.store.readResume(id))
    }

    @Test
    fun restoreReAddsDownloadingEntriesWithTheirResumeData() = runTest {
        val first = setup(this)
        val running = first.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)!!
        first.clock = 2_000L
        val paused = first.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)!!
        advanceUntilIdle()
        first.engine.emitMetadata(first.engine.added[0].id, sintelFiles)
        first.engine.emitMetadata(first.engine.added[1].id, sintelFiles)
        advanceUntilIdle()
        first.engine.resumeBytes = { "state-${it.value}".toByteArray() }
        first.manager.pause(paused)
        advanceUntilIdle()
        first.manager.onServiceStopping()
        advanceUntilIdle()

        // A new process: same files, a fresh engine.
        val s = setup(this)
        assertTrue(s.manager.entries.value.isEmpty())
        s.manager.restore()
        advanceUntilIdle()
        assertEquals(2, s.manager.entries.value.size)
        assertEquals(TorrentStatus.PAUSED, s.manager.entries.value.first { it.id == paused }.status)
        assertEquals(1, s.engine.added.size)
        val re = s.engine.added.single()
        assertTrue(re.source is TorrentSource.TorrentFile)
        val runningEid = first.engine.added.first { it.saveDir.name == running }.id
        assertArrayEquals("state-${runningEid.value}".toByteArray(), re.resumeData)
        assertEquals(File(s.manager.entries.value.first { it.id == running }.saveDir), re.saveDir)
    }

    @Test
    fun restoreWithAnEmptyListNeverStartsTheEngine() = runTest {
        val s = setup(this)
        s.manager.restore()
        advanceUntilIdle()
        assertEquals(0, s.engine.startCount)
        assertTrue(s.manager.entries.value.isEmpty())
    }

    @Test
    fun corruptIndexIsSetAside() = runTest {
        val s = setup(this)
        s.stateDir.mkdirs()
        File(s.stateDir, "index.json").writeText("{ not json")
        s.manager.restore()
        advanceUntilIdle()
        assertTrue(s.manager.entries.value.isEmpty())
        assertTrue(File(s.stateDir, "index.json.corrupt").isFile)
        assertTrue(s.logs.any { it.contains("set aside") })
    }

    @Test
    fun privateTorrentWritesNothingAndLeavesWithPrivateBrowsing() = runTest {
        val s = setup(this)
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = true)!!
        advanceUntilIdle()
        val eid = s.lastId()
        s.engine.emitMetadata(eid, sintelFiles)
        advanceUntilIdle()
        writePayload(s.only())
        s.manager.pause(id)
        advanceUntilIdle()
        s.manager.onServiceStopping()
        advanceUntilIdle()
        assertTrue(s.store.load().entries.isEmpty())
        assertTrue(s.store.storedIds().isEmpty())
        assertFalse(s.stateDir.listFiles().orEmpty().any { it.name.endsWith(".fastresume") || it.name.endsWith(".torrent") })
        val dir = File(s.only().saveDir)
        assertTrue(dir.isDirectory)

        // Resumed within the process: from memory, never from disk.
        s.manager.resume(id)
        advanceUntilIdle()
        assertArrayEquals("resume-${eid.value}".toByteArray(), s.engine.added.last().resumeData)
        assertTrue(s.engine.added.last().source === TorrentSource.FromResumeData)

        s.manager.forgetPrivate()
        advanceUntilIdle()
        assertTrue(s.manager.entries.value.isEmpty())
        assertFalse(dir.exists())
        assertTrue(s.engine.removed.contains(s.lastId() to true))
        assertFalse(s.engine.started)
    }

    @Test
    fun privateLeftoversOfADeadProcessAreDeletedOnRestore() = runTest {
        val s = setup(this)
        val leftover = File(s.payloadRoot, "private/0123456789abcdef0123456789abcdef/x")
        leftover.mkdirs()
        File(leftover, "part").writeText("x")
        s.manager.restore()
        advanceUntilIdle()
        assertFalse(leftover.parentFile!!.exists())
    }

    @Test
    fun wifiGateHoldsTorrentsUntilWifiReturns() = runTest {
        val s = setup(this)
        s.network.kind.value = NetworkKind.OTHER
        s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)
        advanceUntilIdle()
        assertEquals(TorrentStatus.WAITING_FOR_WIFI, s.only().status)
        assertEquals(0, s.engine.startCount)

        s.network.kind.value = NetworkKind.WIFI_OR_ETHERNET
        advanceUntilIdle()
        assertEquals(TorrentStatus.FETCHING_METADATA, s.only().status)
        assertTrue(s.engine.started)

        s.engine.emitMetadata(s.lastId(), sintelFiles)
        advanceUntilIdle()
        s.network.kind.value = NetworkKind.NONE
        advanceUntilIdle()
        assertEquals(TorrentStatus.WAITING_FOR_WIFI, s.only().status)
        assertFalse(s.engine.started)

        // Without "Wi-Fi only" any network will do.
        s.settings.value = TorrentSettings(wifiOnly = false)
        advanceUntilIdle()
        assertEquals(TorrentStatus.DOWNLOADING, s.only().status)
    }

    @Test
    fun userPauseIsNotUndoneByWifiComingBack() = runTest {
        val s = setup(this)
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)!!
        advanceUntilIdle()
        s.engine.emitMetadata(s.lastId(), sintelFiles)
        advanceUntilIdle()
        s.manager.pause(id)
        advanceUntilIdle()
        s.network.kind.value = NetworkKind.OTHER
        advanceUntilIdle()
        s.network.kind.value = NetworkKind.WIFI_OR_ETHERNET
        advanceUntilIdle()
        assertEquals(TorrentStatus.PAUSED, s.only().status)
        assertEquals(PauseReason.USER, s.only().pauseReason)
        assertFalse(s.engine.started)
    }

    @Test
    fun unsafeMetadataFailsAndDeletesEverything() = runTest {
        val s = setup(this)
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)!!
        advanceUntilIdle()
        val eid = s.lastId()
        s.engine.emitMetadata(eid, files(secretName, listOf(secretName, "..", "evil.sh") to 1L))
        advanceUntilIdle()
        assertEquals(TorrentStatus.FAILED, s.only().status)
        assertEquals(TorrentErrorKind.UNSAFE_METADATA, s.only().errorKind)
        assertTrue(s.engine.removed.contains(eid to true))
        assertFalse(eid in s.engine.running)
        assertNull(s.store.readResume(id))
    }

    @Test
    fun unsafeNamesAreRenamedBeforeDownloading() = runTest {
        val s = setup(this)
        s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)
        advanceUntilIdle()
        val eid = s.lastId()
        s.engine.emitMetadata(eid, files("CON", listOf("CON", "a?b") to 1L, listOf("CON", "a*b") to 1L))
        advanceUntilIdle()
        assertEquals(mapOf(0 to "_CON/a_b", 1 to "_CON/a_b (1)"), s.engine.renames[eid])
        assertEquals("_CON", s.only().displayName)
    }

    @Test
    fun notEnoughSpaceFails() = runTest {
        val s = setup(this, freeBytes = 150L * 1024 * 1024)
        s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)
        advanceUntilIdle()
        s.engine.emitMetadata(s.lastId(), files(secretName, listOf("big.bin") to 10L))
        advanceUntilIdle()
        assertEquals(TorrentErrorKind.NO_SPACE, s.only().errorKind)
    }

    @Test
    fun fileErrorFailsTheEntryButKeepsThePartialDownload() = runTest {
        val s = setup(this)
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)!!
        advanceUntilIdle()
        val eid = s.lastId()
        s.engine.emitMetadata(eid, sintelFiles)
        advanceUntilIdle()
        writePayload(s.only())
        s.engine.resumeBytes = { "before-error".toByteArray() }
        s.engine.fail(eid, TorrentErrorKind.FILE_ERROR)
        advanceUntilIdle()
        assertEquals(TorrentStatus.FAILED, s.only().status)
        assertEquals(TorrentErrorKind.FILE_ERROR, s.only().errorKind)
        assertTrue(s.engine.removed.contains(eid to false))
        assertTrue(File(s.only().saveDir, "$secretName/movie.mp4").isFile)
        assertArrayEquals("before-error".toByteArray(), s.store.readResume(id))
        assertFalse(s.engine.started)
    }

    @Test
    fun removingAPayloadNeverFollowsASymlink() = runTest {
        val s = setup(this)
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)!!
        advanceUntilIdle()
        val outside = File(tmp.root, "outside").apply { mkdirs() }
        val keep = File(outside, "keep.txt").apply { writeText("mine") }
        val dir = File(s.only().saveDir).apply { mkdirs() }
        val link = File(dir, "link")
        val linked =
            runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        // Windows without developer mode cannot create symlinks; Linux CI always can.
        org.junit.Assume.assumeTrue(linked)
        s.manager.remove(id, deleteFiles = true)
        advanceUntilIdle()
        assertFalse(dir.exists())
        assertTrue(keep.isFile)
    }

    @Test
    fun oversizedSourceIsRefusedBeforeTheEngine() = runTest {
        val s = setup(this)
        s.manager.add(TorrentSource.Magnet("magnet:?xt=urn:btih:$hash&dn=" + "x".repeat(TorrentLimits.MAX_MAGNET_CHARS)), isPrivate = false)
        advanceUntilIdle()
        assertEquals(TorrentStatus.FAILED, s.only().status)
        assertEquals(TorrentErrorKind.UNSAFE_METADATA, s.only().errorKind)
        assertEquals(0, s.engine.startCount)
    }

    @Test
    fun atMostFiveRunAndTheRestQueue() = runTest {
        val s = setup(this)
        val ids =
            (1..6).map {
                s.clock += 1
                s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)!!
            }
        advanceUntilIdle()
        val byId = s.manager.entries.value.associateBy { it.id }
        assertEquals(5, ids.take(5).count { byId.getValue(it).status == TorrentStatus.FETCHING_METADATA })
        assertEquals(TorrentStatus.PAUSED, byId.getValue(ids[5]).status)
        assertEquals(PauseReason.QUEUED, byId.getValue(ids[5]).pauseReason)
        assertEquals(5, s.engine.added.size)

        s.manager.remove(ids[0], deleteFiles = true)
        advanceUntilIdle()
        assertEquals(TorrentStatus.FETCHING_METADATA, s.manager.entries.value.first { it.id == ids[5] }.status)
        assertEquals(6, s.engine.added.size)
    }

    @Test
    fun timeoutSavesAndPausesEverything() = runTest {
        val s = setup(this)
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)!!
        advanceUntilIdle()
        s.engine.emitMetadata(s.lastId(), sintelFiles)
        advanceUntilIdle()
        s.engine.resumeBytes = { "at-timeout".toByteArray() }
        s.manager.onTimeout()
        advanceUntilIdle()
        assertEquals(TorrentStatus.PAUSED, s.only().status)
        assertEquals(PauseReason.TIMEOUT, s.only().pauseReason)
        assertArrayEquals("at-timeout".toByteArray(), s.store.readResume(id))
        assertFalse(s.engine.started)
    }

    @Test
    fun checkpointEveryMinuteWhileDownloading() = runTest {
        val s = setup(this)
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)!!
        advanceUntilIdle()
        val eid = s.lastId()
        s.engine.emitMetadata(eid, sintelFiles)
        advanceUntilIdle()
        s.engine.resumeBytes = { "minute".toByteArray() }
        s.clock += TorrentLimits.CHECKPOINT_INTERVAL_MS
        s.engine.advance(eid, done = 3, total = 10)
        advanceUntilIdle()
        assertArrayEquals("minute".toByteArray(), s.store.readResume(id))
        assertEquals(3L, s.store.load().entries.single().doneBytes)
    }

    /** Added, metadata checked, payload written and finished: the publish ran once. */
    private fun TestScope.finishedTorrent(s: TorrentTestSetup): String {
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)!!
        advanceUntilIdle()
        s.engine.emitMetadata(s.lastId(), sintelFiles)
        advanceUntilIdle()
        writePayload(s.entry(id))
        s.engine.finish(s.lastId())
        advanceUntilIdle()
        return id
    }

    @Test
    fun retryAfterPublishFailedRepublishesAndCompletes() = runTest {
        val s = setup(this)
        s.publishOk = false
        val id = finishedTorrent(s)
        assertEquals(TorrentErrorKind.PUBLISH_FAILED, s.only().errorKind)
        s.publishOk = true
        s.manager.retry(id)
        advanceUntilIdle()
        assertEquals(TorrentStatus.COMPLETED, s.only().status)
        assertNull(s.only().errorKind)
        assertEquals(setOf("movie.mp4", "en.srt"), s.published.map { it.name }.toSet())
        assertFalse(File(s.only().saveDir).exists())
    }

    @Test
    fun retryAfterARestartRebuildsThePlanFromTheStoredTorrent() = runTest {
        val first = setup(this)
        first.publishOk = false
        val id = finishedTorrent(first)
        assertNotNull(first.store.readTorrent(id))

        val s = setup(this)
        s.engine.parsedFiles = sintelFiles
        s.manager.restore()
        advanceUntilIdle()
        assertEquals(TorrentErrorKind.PUBLISH_FAILED, s.only().errorKind)
        s.manager.retry(id)
        advanceUntilIdle()
        assertTrue("readFiles" in s.engine.calls)
        assertEquals(TorrentStatus.COMPLETED, s.only().status)
        assertEquals(setOf("Orbis/$secretName" to "movie.mp4", "Orbis/$secretName/sub" to "en.srt"), s.published.map { it.dir to it.name }.toSet())
    }

    @Test
    fun retryAfterFileErrorReAddsWithResumeData() = runTest {
        val s = setup(this)
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)!!
        advanceUntilIdle()
        s.engine.emitMetadata(s.lastId(), sintelFiles)
        advanceUntilIdle()
        s.engine.resumeBytes = { "before-error".toByteArray() }
        s.engine.fail(s.lastId(), TorrentErrorKind.FILE_ERROR)
        advanceUntilIdle()
        assertFalse(s.engine.started)
        val addsBefore = s.engine.added.size

        s.manager.retry(id)
        advanceUntilIdle()
        assertEquals(addsBefore + 1, s.engine.added.size)
        val again = s.engine.added.last()
        assertArrayEquals("before-error".toByteArray(), again.resumeData)
        assertTrue(again.source is TorrentSource.TorrentFile)
        assertEquals(TorrentStatus.DOWNLOADING, s.only().status)
        assertNull(s.only().errorKind)
        s.engine.emitMetadata(again.id, sintelFiles)
        advanceUntilIdle()
        assertTrue(again.id in s.engine.running)
    }

    @Test
    fun retryDoesNothingForAFailureWithNothingLeft() = runTest {
        val s = setup(this)
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)!!
        advanceUntilIdle()
        s.engine.emitMetadata(s.lastId(), files(secretName, listOf(secretName, "..", "evil.sh") to 1L))
        advanceUntilIdle()
        val adds = s.engine.added.size
        s.manager.retry(id)
        advanceUntilIdle()
        assertEquals(TorrentErrorKind.UNSAFE_METADATA, s.only().errorKind)
        assertEquals(adds, s.engine.added.size)
    }

    @Test
    fun aFailedPublishDeletesTheCopiesOfThatAttempt() = runTest {
        val s = setup(this)
        s.failPublishAt = 2
        val id = finishedTorrent(s)
        assertEquals(TorrentStatus.FAILED, s.only().status)
        assertEquals(TorrentErrorKind.PUBLISH_FAILED, s.only().errorKind)
        val first = s.published.single()
        assertEquals(listOf(first.handle), s.unpublished)
        assertTrue("payload kept", File(s.only().saveDir, "$secretName/movie.mp4").isFile)

        // The retry starts clean and keeps what it copies.
        s.manager.retry(id)
        advanceUntilIdle()
        assertEquals(TorrentStatus.COMPLETED, s.only().status)
        assertEquals(listOf(first.handle), s.unpublished)
    }

    @Test
    fun aFullListRefusesTheAdd() = runTest {
        val s = setup(this)
        repeat(TorrentLimits.MAX_ENTRIES) { assertNotNull(s.manager.add(TorrentSource.Magnet(magnet), isPrivate = it % 2 == 0)) }
        assertNull(s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false))
        assertNull(s.manager.add(TorrentSource.TorrentFile(ByteArray(10)), isPrivate = true))
        assertEquals(TorrentLimits.MAX_ENTRIES, s.manager.entries.value.size)
        assertEquals(2, s.logs.count { it == "add refused, list full" })
    }

    @Test
    fun aRollbackAfterAReuseLeavesTheReusedCopy() = runTest {
        val s = setup(this)
        // movie.mp4 was already published by an earlier, interrupted attempt; en.srt fails this time.
        s.existingCopies += "movie.mp4"
        s.failPublishAt = 2
        finishedTorrent(s)
        assertEquals(TorrentErrorKind.PUBLISH_FAILED, s.only().errorKind)
        assertTrue("the reused copy was not deleted", s.unpublished.isEmpty())
        assertFalse(s.logs.any { it.startsWith("publish rolled back") })
    }

    @Test
    fun aRollbackDeletesOnlyTheCopiesThisAttemptMade() = runTest {
        val s = setup(this)
        val third = files(secretName, listOf(secretName, "a") to 1L, listOf(secretName, "b") to 1L, listOf(secretName, "c") to 1L)
        s.existingCopies += "a"
        s.failPublishAt = 3
        s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)
        advanceUntilIdle()
        s.engine.emitMetadata(s.lastId(), third)
        advanceUntilIdle()
        File(s.only().saveDir, secretName).mkdirs()
        listOf("a", "b", "c").forEach { File(s.only().saveDir, "$secretName/$it").writeText("x") }
        s.engine.finish(s.lastId())
        advanceUntilIdle()
        // a was reused (kept), b was copied now (deleted again), c failed.
        assertEquals(listOf("b"), s.published.map { it.name })
        assertEquals(listOf(s.published.single().handle), s.unpublished)
        assertTrue(s.logs.contains("publish rolled back 1"))
    }

    @Test
    fun aHalfWrittenCopyIsDeletedBeforeCopyingAgain() = runTest {
        val s = setup(this)
        s.pendingCopies += "movie.mp4"
        finishedTorrent(s)
        assertEquals(TorrentStatus.COMPLETED, s.only().status)
        assertEquals(listOf("movie.mp4"), s.pendingDeleted)
        assertEquals(setOf("movie.mp4", "en.srt"), s.published.map { it.name }.toSet())
    }

    @Test
    fun aTorrentOfOnlyEmptyFilesCompletes() = runTest {
        val s = setup(this)
        s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)
        advanceUntilIdle()
        val eid = s.lastId()
        s.engine.completeOnApproval += eid
        s.engine.emitMetadata(eid, files(secretName, listOf(secretName, "a.txt") to 0L, listOf(secretName, "b.txt") to 0L))
        advanceUntilIdle()
        assertEquals(TorrentStatus.COMPLETED, s.only().status)
        assertEquals(setOf("a.txt", "b.txt"), s.published.map { it.name }.toSet())
        assertTrue(s.published.all { it.content.isEmpty() && it.dir == "Orbis/$secretName" })
    }

    @Test
    fun orphanPayloadsAreReapedAtRestoreAndForeignFoldersStay() = runTest {
        val s = setup(this)
        val orphan = File(s.payloadRoot, "0123456789abcdef0123456789abcdef/x").apply { mkdirs() }
        File(orphan, "part").writeText("x")
        val keep = File(s.payloadRoot, "keep").apply { mkdirs() }
        File(keep, "mine.txt").writeText("mine")
        s.manager.restore()
        advanceUntilIdle()
        assertFalse(orphan.parentFile!!.exists())
        assertTrue(File(keep, "mine.txt").isFile)
    }

    @Test
    fun aListedTorrentsPayloadIsNotReaped() = runTest {
        val first = setup(this)
        val id = first.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)!!
        advanceUntilIdle()
        first.engine.emitMetadata(first.lastId(), sintelFiles)
        advanceUntilIdle()
        writePayload(first.entry(id))
        first.manager.pause(id)
        advanceUntilIdle()

        val s = setup(this)
        s.manager.restore()
        advanceUntilIdle()
        assertTrue(File(s.entry(id).saveDir, "$secretName/movie.mp4").isFile)
    }

    @Test
    fun forgetPrivateReturnsTheIdsThatLeave() = runTest {
        val s = setup(this)
        val normal = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)!!
        val priv = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = true)!!
        advanceUntilIdle()
        assertEquals(listOf(priv), s.manager.forgetPrivate())
        advanceUntilIdle()
        assertEquals(listOf(normal), s.manager.entries.value.map { it.id })
        assertEquals(emptyList<String>(), s.manager.forgetPrivate())
    }

    @Test
    fun logsCarryStatusOnly() = runTest {
        val s = setup(this)
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)!!
        val priv = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = true)!!
        advanceUntilIdle()
        s.engine.emitMetadata(s.engine.added[0].id, sintelFiles)
        s.engine.emitMetadata(s.engine.added[1].id, files(secretName, listOf("..") to 1L))
        advanceUntilIdle()
        s.engine.advance(s.engine.added[0].id, 5, 10)
        s.manager.pause(id)
        advanceUntilIdle()
        s.manager.resume(id)
        advanceUntilIdle()
        s.engine.emitMetadata(s.engine.added.last().id, sintelFiles)
        advanceUntilIdle()
        writePayload(s.manager.entries.value.first { it.id == id })
        s.engine.finish(s.engine.added.last().id)
        advanceUntilIdle()
        s.manager.remove(priv, deleteFiles = true)
        s.manager.forgetPrivate()
        advanceUntilIdle()
        // A failed publish rolled back, then retried.
        s.failPublishAt = s.published.size + 2
        s.clock += 1
        val again = finishedTorrent(s)
        s.manager.retry(again)
        advanceUntilIdle()
        // Seeding with stop seeding off and a ratio limit; a speed cap applied while it runs.
        s.settings.value = TorrentSettings(stopSeeding = false, seedRatioLimit = 1f)
        s.clock += 1
        val seeder = finishedTorrent(s)
        assertEquals(TorrentStatus.SEEDING, s.entry(seeder).status)
        s.settings.value = s.settings.value.copy(downBps = 512_000, upBps = 256_000)
        advanceUntilIdle()
        s.engine.advance(s.lastId(), 10, 10, EngineState.SEEDING, ratio = 1f)
        advanceUntilIdle()
        assertEquals(TorrentStatus.COMPLETED, s.entry(seeder).status)
        assertTrue(s.logs.contains("stop seeding RATIO"))
        assertTrue(s.logs.contains("settings applied"))
        // Reaping at the next start.
        File(s.payloadRoot, "0123456789abcdef0123456789abcdef").mkdirs()
        File(s.payloadRoot, "keep").mkdirs()
        val next = TorrentTestSetup(tmp.root, testScheduler).also { scopes += it.scope }
        next.manager.restore()
        advanceUntilIdle()
        // Intake lines: a magnet offered to the sheet and an oversized response refused by the reader.
        val intakeTabs =
            com.shibi.orbis.tabs.TabManager({ error("no sessions") }, object : com.shibi.orbis.tabs.TabStore {
                override fun load(): com.shibi.orbis.tabs.TabsSnapshot? = null
                override fun save(snapshot: com.shibi.orbis.tabs.TabsSnapshot) = Unit
            }, s.scope, {}, torrentLog = { s.logs += it })
        intakeTabs.offerTorrentIntake(IntakeRequest.Magnet(magnet, isPrivate = false))
        intakeTabs.offerTorrentIntake(IntakeRequest.Magnet(magnet, isPrivate = true))
        val oversized =
            object : com.shibi.orbis.engine.IncomingDownload {
                override val url = "https://tracker.example.org/$secretName.torrent"
                override val contentDisposition: String? = "attachment; filename=\"$secretName.torrent\""
                override val contentType: String? = "application/x-bittorrent"
                override val contentLength = TorrentLimits.MAX_TORRENT_FILE_BYTES + 1L
                override fun openBody(): java.io.InputStream? = null
                override fun discard() = Unit
            }
        val refused = kotlinx.coroutines.runBlocking { TorrentIntakeReader().readResponse(oversized) }
        s.logs += IntakeLog.refused((refused as TorrentIntakeReader.Result.Failed).reason)
        assertTrue(s.logs.contains("intake magnet false"))
        assertTrue(s.logs.contains("intake busy"))
        assertTrue(s.logs.contains("intake refused TOO_LARGE"))
        assertTrue(s.logs.any { it.startsWith("retry") })
        assertTrue(s.logs.any { it.startsWith("publish rolled back") })
        assertTrue(next.logs.any { it.startsWith("orphan payloads reaped") })
        s.logs += next.logs

        assertTrue(s.logs.isNotEmpty())
        val hex40 = Regex("[0-9a-fA-F]{40}")
        for (line in s.logs) {
            assertFalse(line, hex40.containsMatchIn(line))
            // "intake magnet" names the kind; a link itself would carry "magnet:".
            assertFalse(line, line.contains("magnet:", ignoreCase = true))
            assertFalse(line, line.contains("btih", ignoreCase = true))
            assertFalse(line, line.contains(secretName, ignoreCase = true))
            assertFalse(line, line.contains("tracker", ignoreCase = true))
            assertFalse(line, line.contains(id))
            assertFalse(line, line.contains(tmp.root.path))
            // Caps are settings, not log lines.
            assertFalse(line, line.contains("512000") || line.contains("256000"))
        }
        // Entries print without their name too.
        assertFalse(s.manager.entries.value.toString().contains(secretName))
    }

    // ---- seeding and settings (PR 4) ----

    private val keepSeeding = TorrentSettings(stopSeeding = false)

    /** Added, metadata checked, payload written and finished with stop seeding off: SEEDING. */
    private fun TestScope.seedingTorrent(s: TorrentTestSetup, isPrivate: Boolean = false): String {
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = isPrivate)!!
        advanceUntilIdle()
        val eid = s.lastId()
        s.engine.emitMetadata(eid, sintelFiles)
        advanceUntilIdle()
        writePayload(s.entry(id))
        s.engine.finish(eid)
        advanceUntilIdle()
        assertEquals(TorrentStatus.SEEDING, s.entry(id).status)
        return id
    }

    @Test
    fun withStopSeedingOffAFinishedTorrentSeedsAndPublishesNothing() = runTest {
        val s = setup(this)
        s.settings.value = keepSeeding
        val id = seedingTorrent(s)
        val eid = s.lastId()
        assertTrue(s.published.isEmpty())
        assertTrue(s.engine.removed.isEmpty())
        assertTrue(eid in s.engine.running)
        assertTrue(File(s.entry(id).saveDir, "$secretName/movie.mp4").isFile)
        assertEquals(TorrentStatus.SEEDING, s.store.load().entries.single().status)
        // The ratio is mirrored from the engine (live only).
        s.engine.advance(eid, 10, 10, EngineState.SEEDING, ratio = 0.4f)
        advanceUntilIdle()
        assertEquals(0.4f, s.entry(id).ratio)
        assertEquals(TorrentStatus.SEEDING, s.entry(id).status)
        // Paused and resumed, it seeds again (not "downloading").
        s.manager.pause(id)
        advanceUntilIdle()
        assertEquals(TorrentStatus.PAUSED, s.entry(id).status)
        s.manager.resume(id)
        advanceUntilIdle()
        assertEquals(TorrentStatus.SEEDING, s.entry(id).status)
        val again = s.engine.added.last().id
        s.engine.completeOnApproval += again
        s.engine.emitMetadata(again, sintelFiles)
        advanceUntilIdle()
        assertEquals(TorrentStatus.SEEDING, s.entry(id).status)
        assertTrue(again in s.engine.running)
        assertTrue(s.published.isEmpty())
    }

    @Test
    fun reachingTheRatioLimitSavesTheFilesAndDeletesThePayload() = runTest {
        val s = setup(this)
        s.settings.value = keepSeeding.copy(seedRatioLimit = 1f)
        val id = seedingTorrent(s)
        val eid = s.lastId()
        val dir = File(s.entry(id).saveDir)
        s.engine.advance(eid, 10, 10, EngineState.SEEDING, ratio = 0.9f)
        advanceUntilIdle()
        assertEquals(TorrentStatus.SEEDING, s.entry(id).status)
        s.engine.advance(eid, 10, 10, EngineState.SEEDING, ratio = 1f)
        advanceUntilIdle()
        assertEquals(TorrentStatus.COMPLETED, s.entry(id).status)
        assertEquals("Download/Orbis/$secretName", s.entry(id).publishedPath)
        assertEquals(setOf("movie.mp4", "en.srt"), s.published.map { it.name }.toSet())
        assertTrue(s.engine.removed.contains(eid to false))
        assertFalse("payload deleted after the copy", dir.exists())
        assertTrue(s.logs.contains("stop seeding RATIO"))
        assertFalse(s.engine.started)
    }

    @Test
    fun aRatioLimitIsIgnoredWhileStopSeedingIsOn() = runTest {
        val s = setup(this)
        // Stop seeding on: the torrent never seeds, the limit has nothing to do.
        s.settings.value = TorrentSettings(seedRatioLimit = 1f)
        val id = finishedTorrent(s)
        assertEquals(TorrentStatus.COMPLETED, s.entry(id).status)
        assertFalse(s.logs.any { it.startsWith("stop seeding") })
    }

    @Test
    fun stopSeedingByTheUserSavesTheFiles() = runTest {
        val s = setup(this)
        s.settings.value = keepSeeding
        val id = seedingTorrent(s)
        val dir = File(s.entry(id).saveDir)
        s.manager.stopSeeding(id)
        advanceUntilIdle()
        assertEquals(TorrentStatus.COMPLETED, s.entry(id).status)
        assertEquals(2, s.published.size)
        assertFalse(dir.exists())
        assertTrue(s.logs.contains("stop seeding USER"))
        // Nothing to stop any more.
        s.manager.stopSeeding(id)
        advanceUntilIdle()
        assertEquals(1, s.logs.count { it == "stop seeding USER" })
    }

    @Test
    fun turningStopSeedingOnFinishesEverySeedingTorrent() = runTest {
        val s = setup(this)
        s.settings.value = keepSeeding
        val first = seedingTorrent(s)
        s.clock += 1
        val second = seedingTorrent(s)
        assertEquals(2, s.manager.entries.value.count { it.status == TorrentStatus.SEEDING })
        s.settings.value = s.settings.value.copy(stopSeeding = true)
        advanceUntilIdle()
        assertEquals(TorrentStatus.COMPLETED, s.entry(first).status)
        assertEquals(TorrentStatus.COMPLETED, s.entry(second).status)
        assertEquals(4, s.published.size)
        assertTrue(s.logs.contains("stop seeding SETTING 2"))
        assertFalse(s.engine.started)
        // Turning it off again changes nothing that was saved.
        s.settings.value = s.settings.value.copy(stopSeeding = false)
        advanceUntilIdle()
        assertEquals(TorrentStatus.COMPLETED, s.entry(first).status)
        assertTrue(s.unpublished.isEmpty())
    }

    @Test
    fun aPrivateSeedingTorrentIsDeletedWhenPrivateBrowsingEnds() = runTest {
        val s = setup(this)
        s.settings.value = keepSeeding
        val id = seedingTorrent(s, isPrivate = true)
        val eid = s.lastId()
        val dir = File(s.entry(id).saveDir)
        assertTrue(dir.path.contains(TorrentDirs.PRIVATE_DIR))
        assertEquals(listOf(id), s.manager.forgetPrivate())
        advanceUntilIdle()
        assertTrue(s.manager.entries.value.isEmpty())
        // OD-3: never saved, so nothing of it stays.
        assertTrue(s.published.isEmpty())
        assertTrue(s.engine.removed.contains(eid to true))
        assertFalse(dir.exists())
        assertTrue(s.keepRoot.listFiles().orEmpty().isEmpty())
        assertTrue(s.logs.contains("seeding dropped, deleted 1"))
        assertTrue(s.store.load().entries.isEmpty())
        assertFalse(s.engine.started)
    }

    @Test
    fun saveAndRemoveOnASeedingTorrentPublishesThenRemovesTheRow() = runTest {
        val s = setup(this)
        s.settings.value = keepSeeding
        val id = seedingTorrent(s)
        val dir = File(s.entry(id).saveDir)
        s.manager.remove(id, deleteFiles = false)
        advanceUntilIdle()
        assertTrue(s.manager.entries.value.isEmpty())
        assertEquals(2, s.published.size)
        assertFalse(dir.exists())
        assertNull(s.store.readResume(id))
        assertTrue(s.store.load().entries.isEmpty())
    }

    @Test
    fun aFailedSaveAndRemoveKeepsTheRowForRetry() = runTest {
        val s = setup(this)
        s.settings.value = keepSeeding
        val id = seedingTorrent(s)
        s.publishOk = false
        s.manager.remove(id, deleteFiles = false)
        advanceUntilIdle()
        assertEquals(TorrentErrorKind.PUBLISH_FAILED, s.entry(id).errorKind)
        assertTrue(File(s.entry(id).saveDir, "$secretName/movie.mp4").isFile)
    }

    @Test
    fun deleteOnASeedingTorrentDeletesItsFiles() = runTest {
        val s = setup(this)
        s.settings.value = keepSeeding
        val id = seedingTorrent(s)
        val eid = s.lastId()
        val dir = File(s.entry(id).saveDir)
        s.manager.remove(id, deleteFiles = true)
        advanceUntilIdle()
        assertTrue(s.manager.entries.value.isEmpty())
        assertTrue(s.published.isEmpty())
        assertTrue(s.engine.removed.contains(eid to true))
        assertFalse(dir.exists())
    }

    @Test
    fun aSettingsChangeWhileRunningIsAppliedOnce() = runTest {
        val s = setup(this)
        s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)
        advanceUntilIdle()
        assertTrue(s.engine.started)
        val limits = s.engine.calls.count { it == "limits" }
        val discovery = s.engine.calls.count { it == "discovery" }
        s.settings.value = s.settings.value.copy(downBps = 512_000, dht = false)
        advanceUntilIdle()
        assertEquals(limits + 1, s.engine.calls.count { it == "limits" })
        assertEquals(discovery + 1, s.engine.calls.count { it == "discovery" })
        assertEquals(1, s.logs.count { it == "settings applied" })
        // The same value again is no change.
        s.settings.value = s.settings.value.copy()
        advanceUntilIdle()
        assertEquals(limits + 1, s.engine.calls.count { it == "limits" })
    }

    @Test
    fun theEngineStartsOnTheInstallsListenPort() = runTest {
        val s = setup(this)
        s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)
        advanceUntilIdle()
        val started = s.engine.starts.single()
        assertEquals(TorrentTestSetup.LISTEN_PORT, started.listenPort)
        // Local peers is off unless the user turns it on.
        assertFalse(started.lsd)
        assertFalse(s.engine.discovery.getValue(s.lastId()).lsd)
    }

    @Test
    fun aPrivateTorrentNeverUsesLocalPeersWhateverTheSettingsSay() = runTest {
        val s = setup(this)
        s.settings.value = TorrentSettings(lsd = true)
        s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)
        s.manager.add(TorrentSource.Magnet(magnet), isPrivate = true)
        advanceUntilIdle()
        val normal = s.engine.added.single { !it.isPrivate }.id
        val priv = s.engine.added.single { it.isPrivate }.id
        assertEquals(TorrentDiscovery(dht = true, pex = true, lsd = true), s.engine.discovery[normal])
        // Trackers only: no DHT, no peer exchange, no local peers.
        assertEquals(TorrentDiscovery.TRACKERS_ONLY, s.engine.discovery[priv])

        // Off and on again, with other changes in between: every change reaches both torrents, none turns local
        // peers on for the private one.
        s.settings.value = s.settings.value.copy(lsd = false)
        advanceUntilIdle()
        s.settings.value = s.settings.value.copy(lsd = true, dht = false)
        advanceUntilIdle()
        s.settings.value = s.settings.value.copy(dht = true, pex = false)
        advanceUntilIdle()
        val privateChanges = s.engine.discoveryLog.filter { it.first == priv }.map { it.second }
        assertEquals(4, privateChanges.size)
        assertTrue(privateChanges.all { it == TorrentDiscovery.TRACKERS_ONLY })
        assertEquals(TorrentDiscovery(dht = true, pex = false, lsd = true), s.engine.discovery[normal])
    }

    @Test
    fun withoutWifiOnlyTorrentsRunOnAnyNetwork() = runTest {
        val s = setup(this)
        s.settings.value = TorrentSettings(wifiOnly = false)
        s.network.kind.value = NetworkKind.OTHER
        s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)
        advanceUntilIdle()
        assertEquals(TorrentStatus.FETCHING_METADATA, s.only().status)
        assertTrue(s.engine.started)
    }

    @Test
    fun aSeedingTorrentWaitsForWifiAndPausesAtTheTimeout() = runTest {
        val s = setup(this)
        s.settings.value = keepSeeding
        val id = seedingTorrent(s)
        s.network.kind.value = NetworkKind.OTHER
        advanceUntilIdle()
        assertEquals(TorrentStatus.WAITING_FOR_WIFI, s.entry(id).status)
        assertFalse(s.engine.started)
        s.network.kind.value = NetworkKind.WIFI_OR_ETHERNET
        advanceUntilIdle()
        assertEquals(TorrentStatus.SEEDING, s.entry(id).status)
        assertTrue(s.engine.started)

        s.manager.onTimeout()
        advanceUntilIdle()
        assertEquals(TorrentStatus.PAUSED, s.entry(id).status)
        assertEquals(PauseReason.TIMEOUT, s.entry(id).pauseReason)
        assertFalse(s.engine.started)
        s.manager.resume(id)
        advanceUntilIdle()
        assertEquals(TorrentStatus.SEEDING, s.entry(id).status)
        assertTrue(s.published.isEmpty())
    }

    /** A private torrent, finished with stop seeding off, then knocked out of SEEDING by [knock]: deleted (OD-3). */
    private fun TestScope.privateFinishedIsDeletedAtTheEnd(knock: (TorrentTestSetup, String) -> Unit, expected: TorrentStatus) {
        val s = setup(this)
        s.settings.value = keepSeeding
        val id = seedingTorrent(s, isPrivate = true)
        val dir = File(s.entry(id).saveDir)
        knock(s, id)
        advanceUntilIdle()
        assertEquals(expected, s.entry(id).status)
        assertTrue(s.entry(id).finishedUnsaved)
        assertTrue(File(dir, "$secretName/movie.mp4").isFile)
        assertEquals(listOf(id), s.manager.forgetPrivate())
        advanceUntilIdle()
        assertTrue(s.manager.entries.value.isEmpty())
        assertTrue(s.published.isEmpty())
        assertFalse(dir.exists())
        assertTrue(s.keepRoot.listFiles().orEmpty().isEmpty())
        assertTrue(s.logs.contains("seeding dropped, deleted 1"))
    }

    @Test
    fun aPausedFinishedPrivateTorrentIsDeletedWhenPrivateBrowsingEnds() = runTest {
        privateFinishedIsDeletedAtTheEnd({ s, id -> s.manager.pause(id) }, TorrentStatus.PAUSED)
    }

    @Test
    fun aFinishedPrivateTorrentWaitingForWifiIsDeletedWhenPrivateBrowsingEnds() = runTest {
        privateFinishedIsDeletedAtTheEnd({ s, _ -> s.network.kind.value = NetworkKind.OTHER }, TorrentStatus.WAITING_FOR_WIFI)
    }

    @Test
    fun aFinishedPrivateTorrentStoppedByAndroidIsDeletedWhenPrivateBrowsingEnds() = runTest {
        privateFinishedIsDeletedAtTheEnd({ s, _ -> s.manager.onTimeout() }, TorrentStatus.PAUSED)
    }

    @Test
    fun aPrivateTorrentThatFinishedWhilePausedIsDeletedAtPrivateEnd() = runTest {
        val s = setup(this)
        s.settings.value = keepSeeding
        val priv = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = true)!!
        s.clock += 1
        // Another torrent keeps the session up, so the paused one stays in it and can finish.
        s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)
        advanceUntilIdle()
        val eid = s.engine.added.first { it.saveDir.name == priv }.id
        s.engine.emitMetadata(eid, sintelFiles)
        advanceUntilIdle()
        s.manager.pause(priv)
        advanceUntilIdle()
        writePayload(s.entry(priv))
        s.engine.finish(eid)
        advanceUntilIdle()
        assertEquals(TorrentStatus.PAUSED, s.entry(priv).status)
        s.manager.forgetPrivate()
        advanceUntilIdle()
        assertTrue(s.manager.entries.value.none { it.id == priv })
        assertTrue(s.published.isEmpty())
        assertTrue(s.engine.removed.contains(eid to true))
        assertFalse(File(s.payloadRoot, "${TorrentDirs.PRIVATE_DIR}/$priv").exists())
    }

    @Test
    fun anUnfinishedPausedPrivateTorrentIsStillDeleted() = runTest {
        val s = setup(this)
        s.settings.value = keepSeeding
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = true)!!
        advanceUntilIdle()
        s.engine.emitMetadata(s.lastId(), sintelFiles)
        advanceUntilIdle()
        s.engine.advance(s.lastId(), done = 5, total = 10)
        advanceUntilIdle()
        writePayload(s.entry(id))
        val dir = File(s.entry(id).saveDir)
        s.manager.pause(id)
        advanceUntilIdle()
        assertFalse(s.entry(id).finishedUnsaved)
        s.manager.forgetPrivate()
        advanceUntilIdle()
        assertTrue(s.published.isEmpty())
        assertFalse(dir.exists())
    }

    @Test
    fun saveAndRemoveOnAPausedFinishedTorrentPublishesThenRemovesTheRow() = runTest {
        val s = setup(this)
        s.settings.value = keepSeeding
        val id = seedingTorrent(s)
        s.manager.pause(id)
        advanceUntilIdle()
        s.manager.remove(id, deleteFiles = false)
        advanceUntilIdle()
        assertTrue(s.manager.entries.value.isEmpty())
        assertEquals(2, s.published.size)
        assertTrue(s.logs.contains("removed PAUSED, publishing"))
    }

    // ---- private torrents kept inside Orbis (security PR 9, F-06; OD-3) ----

    /** A private torrent that finished with stop seeding on: kept inside Orbis. */
    private fun TestScope.keptTorrent(s: TorrentTestSetup): String {
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = true)!!
        advanceUntilIdle()
        val eid = s.lastId()
        s.engine.emitMetadata(eid, sintelFiles)
        advanceUntilIdle()
        writePayload(s.entry(id))
        s.engine.finish(eid)
        advanceUntilIdle()
        return id
    }

    @Test
    fun aFinishedPrivateTorrentIsKeptInsideOrbisNotPublished() = runTest {
        val s = setup(this)
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = true)!!
        advanceUntilIdle()
        val eid = s.lastId()
        s.engine.emitMetadata(eid, sintelFiles)
        advanceUntilIdle()
        val payload = File(s.entry(id).saveDir)
        writePayload(s.entry(id))
        s.engine.finish(eid)
        advanceUntilIdle()
        assertTrue("nothing goes to MediaStore", s.published.isEmpty())
        val e = s.entry(id)
        assertEquals(TorrentStatus.COMPLETED, e.status)
        assertNull(e.publishedPath)
        val kept = File(s.keepRoot, secretName)
        assertEquals(kept.absolutePath, e.keptDir)
        // The publish layout: no top folder of the torrent's own, the declared sizes.
        assertEquals("movie123", File(kept, "movie.mp4").readText())
        assertEquals("su", File(kept, "sub/en.srt").readText())
        assertEquals(setOf("movie.mp4", "sub"), kept.list()!!.toSet())
        assertFalse("payload folder gone", payload.exists())
        assertTrue(s.engine.removed.contains(eid to false))
        assertTrue(s.logs.contains("kept"))
        assertTrue(s.store.load().entries.isEmpty())
        assertFalse(s.engine.started)
    }

    @Test
    fun aKeptFolderNameThatIsTakenGetsACounter() = runTest {
        val s = setup(this)
        File(s.keepRoot, secretName).mkdirs()
        val id = keptTorrent(s)
        assertEquals(File(s.keepRoot, "$secretName (1)").absolutePath, s.entry(id).keptDir)
        assertTrue(File(s.keepRoot, "$secretName (1)/movie.mp4").isFile)
        assertTrue(File(s.keepRoot, secretName).list()!!.isEmpty())
    }

    @Test
    fun aFileOfTheWrongSizeFailsTheKeepAndLeavesNothingKept() = runTest {
        val s = setup(this)
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = true)!!
        advanceUntilIdle()
        s.engine.emitMetadata(s.lastId(), sintelFiles)
        advanceUntilIdle()
        writePayload(s.entry(id))
        File(s.entry(id).saveDir, "$secretName/movie.mp4").writeText("short")
        s.engine.finish(s.lastId())
        advanceUntilIdle()
        assertEquals(TorrentStatus.FAILED, s.entry(id).status)
        assertEquals(TorrentErrorKind.PUBLISH_FAILED, s.entry(id).errorKind)
        assertNull(s.entry(id).keptDir)
        assertTrue(s.keepRoot.listFiles().orEmpty().isEmpty())
        assertTrue("payload untouched for Retry", File(s.entry(id).saveDir, "$secretName/sub/en.srt").isFile)
        assertTrue(s.logs.contains("keep failed"))
        assertTrue(s.published.isEmpty())
    }

    @Test
    fun aSymlinkInThePayloadFailsTheKeep() = runTest {
        val s = setup(this)
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = true)!!
        advanceUntilIdle()
        s.engine.emitMetadata(s.lastId(), sintelFiles)
        advanceUntilIdle()
        writePayload(s.entry(id))
        val outside = File(tmp.root, "outside.bin").apply { writeText("movie123") }
        val link = File(s.entry(id).saveDir, "$secretName/movie.mp4")
        link.delete()
        val made = runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        org.junit.Assume.assumeTrue("symlinks need privileges on this OS", made)
        s.engine.finish(s.lastId())
        advanceUntilIdle()
        assertEquals(TorrentErrorKind.PUBLISH_FAILED, s.entry(id).errorKind)
        assertTrue(s.keepRoot.listFiles().orEmpty().isEmpty())
        assertTrue(outside.isFile)
    }

    @Test
    fun retryAfterAFailedKeepKeepsAgain() = runTest {
        val s = setup(this)
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = true)!!
        advanceUntilIdle()
        s.engine.emitMetadata(s.lastId(), sintelFiles)
        advanceUntilIdle()
        writePayload(s.entry(id))
        File(s.entry(id).saveDir, "$secretName/movie.mp4").writeText("short")
        s.engine.finish(s.lastId())
        advanceUntilIdle()
        File(s.entry(id).saveDir, "$secretName/movie.mp4").writeText("movie123")
        s.manager.retry(id)
        advanceUntilIdle()
        assertEquals(TorrentStatus.COMPLETED, s.entry(id).status)
        assertEquals(File(s.keepRoot, secretName).absolutePath, s.entry(id).keptDir)
        assertTrue(s.published.isEmpty())
    }

    @Test
    fun saveToDownloadsPublishesTheSamePathsAsANormalTorrentAndDeletesTheKeptCopy() = runTest {
        val s = setup(this)
        val id = keptTorrent(s)
        val kept = File(s.entry(id).keptDir!!)
        s.manager.saveKept(id)
        advanceUntilIdle()
        val targets = s.published.map { it.dir to it.name }.toSet()
        assertEquals(setOf("Orbis/$secretName" to "movie.mp4", "Orbis/$secretName/sub" to "en.srt"), targets)
        assertEquals("movie123", s.published.first { it.name == "movie.mp4" }.content)
        assertFalse(kept.exists())
        val e = s.entry(id)
        assertEquals(TorrentStatus.COMPLETED, e.status)
        assertNull(e.keptDir)
        assertEquals("Download/Orbis/$secretName", e.publishedPath)
        assertTrue(s.logs.contains("kept saved"))
        // Saved: the end of private browsing takes the row, not the copy.
        s.manager.forgetPrivate()
        advanceUntilIdle()
        assertTrue(s.manager.entries.value.isEmpty())
        assertTrue(s.unpublished.isEmpty())
    }

    @Test
    fun aFailedSaveToDownloadsLeavesTheKeptFilesAsTheyWere() = runTest {
        val s = setup(this)
        val id = keptTorrent(s)
        val kept = File(s.entry(id).keptDir!!)
        s.failPublishAt = 2
        s.manager.saveKept(id)
        advanceUntilIdle()
        assertEquals(TorrentStatus.COMPLETED, s.entry(id).status)
        assertEquals(kept.absolutePath, s.entry(id).keptDir)
        assertEquals("movie123", File(kept, "movie.mp4").readText())
        assertEquals("su", File(kept, "sub/en.srt").readText())
        // The first copy was rolled back.
        assertEquals(1, s.unpublished.size)
        assertTrue(s.logs.contains("kept save failed"))
    }

    @Test
    fun saveToDownloadsIsOnlyForAKeptRow() = runTest {
        val s = setup(this)
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = true)!!
        advanceUntilIdle()
        s.manager.saveKept(id)
        advanceUntilIdle()
        assertEquals(TorrentStatus.FETCHING_METADATA, s.entry(id).status)
        assertTrue(s.published.isEmpty())
    }

    @Test
    fun theEndOfPrivateBrowsingDeletesKeptFiles() = runTest {
        val s = setup(this)
        val id = keptTorrent(s)
        val kept = File(s.entry(id).keptDir!!)
        assertEquals(listOf(id), s.manager.forgetPrivate())
        advanceUntilIdle()
        assertTrue(s.manager.entries.value.isEmpty())
        assertFalse("OD-3: unsaved private files are deleted", kept.exists())
        assertTrue(s.published.isEmpty())
    }

    @Test
    fun theEndOfPrivateBrowsingDuringAKeepDeletesWhatWasMoved() = runTest {
        val s = setup(this)
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = true)!!
        advanceUntilIdle()
        s.engine.emitMetadata(s.lastId(), sintelFiles)
        advanceUntilIdle()
        writePayload(s.entry(id))
        val payload = File(s.entry(id).saveDir)
        s.engine.finish(s.lastId())
        // The keep job is queued; private browsing ends before it runs.
        s.manager.forgetPrivate()
        advanceUntilIdle()
        assertTrue(s.manager.entries.value.isEmpty())
        assertTrue(s.keepRoot.listFiles().orEmpty().isEmpty())
        assertFalse(payload.exists())
    }

    @Test
    fun removingAKeptRowDeletesItsKeptFiles() = runTest {
        val s = setup(this)
        val id = keptTorrent(s)
        val kept = File(s.entry(id).keptDir!!)
        s.manager.remove(id, deleteFiles = false)
        advanceUntilIdle()
        assertTrue(s.manager.entries.value.isEmpty())
        assertFalse(kept.exists())
        assertTrue(s.published.isEmpty())
    }

    @Test
    fun saveAndRemoveOnASeedingPrivateTorrentStillPublishes() = runTest {
        val s = setup(this)
        s.settings.value = keepSeeding
        val id = seedingTorrent(s, isPrivate = true)
        val dir = File(s.entry(id).saveDir)
        s.manager.remove(id, deleteFiles = false)
        advanceUntilIdle()
        assertTrue(s.manager.entries.value.isEmpty())
        assertEquals(setOf("movie.mp4", "en.srt"), s.published.map { it.name }.toSet())
        assertFalse(dir.exists())
        assertTrue(s.keepRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun stopSeedingOnAPrivateTorrentKeepsIt() = runTest {
        val s = setup(this)
        s.settings.value = keepSeeding
        val id = seedingTorrent(s, isPrivate = true)
        s.manager.stopSeeding(id)
        advanceUntilIdle()
        assertEquals(TorrentStatus.COMPLETED, s.entry(id).status)
        assertNotNull(s.entry(id).keptDir)
        assertTrue(s.published.isEmpty())
    }

    @Test
    fun keptLeftoversOfADeadProcessAreDeletedOnRestoreButPrivateFilesAreNotTouched() = runTest {
        val s = setup(this)
        val leftover = File(s.keepRoot, "$secretName/sub")
        leftover.mkdirs()
        File(leftover, "en.srt").writeText("su")
        // A private file download: the download manager's to clean, not the torrent manager's.
        val file = File(s.keepRoot, "report.pdf").apply { writeText("pdf") }
        s.manager.restore()
        advanceUntilIdle()
        assertFalse(File(s.keepRoot, secretName).exists())
        assertTrue(file.isFile)
        assertTrue(s.logs.contains("kept leftovers deleted 1"))
    }

    @Test
    fun webSocketTrackersAreStrippedFromAMagnetAtIntake() = runTest {
        val s = setup(this)
        val ws = "$magnet&tr=wss%3A%2F%2Ftracker.example.org%2Fannounce&tr=ws://tracker.example.org:8000"
        s.manager.add(TorrentSource.Magnet(ws), isPrivate = false)
        advanceUntilIdle()
        val uri = (s.engine.added.single().source as TorrentSource.Magnet).uri
        assertEquals(magnet, uri)
        assertTrue(s.logs.contains("announce urls dropped 2"))
        for (line in s.logs) assertFalse(line, line.contains("tracker", ignoreCase = true))
    }

    @Test
    fun keptEntriesAreNeverWrittenToTheIndex() = runTest {
        val s = setup(this)
        keptTorrent(s)
        s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)
        advanceUntilIdle()
        val text = File(s.stateDir, "index.json").readText()
        assertFalse(text.contains(s.keepRoot.name))
        assertFalse(text.contains("keptDir"))
        assertEquals(1, s.store.load().entries.size)
    }

    // ---- Clear browsing data > Downloads list (security PR 10, F-07) ----

    /** Added, metadata checked, partial payload written, then a file error: FAILED with its resume data and payload. */
    private fun TestScope.fileErrorTorrent(s: TorrentTestSetup): String {
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)!!
        advanceUntilIdle()
        val eid = s.lastId()
        s.engine.emitMetadata(eid, sintelFiles)
        advanceUntilIdle()
        writePayload(s.entry(id))
        s.engine.fail(eid, TorrentErrorKind.FILE_ERROR)
        advanceUntilIdle()
        assertEquals(TorrentErrorKind.FILE_ERROR, s.entry(id).errorKind)
        return id
    }

    /** Added and metadata checked: DOWNLOADING. */
    private fun TestScope.downloadingTorrent(s: TorrentTestSetup): String {
        val id = s.manager.add(TorrentSource.Magnet(magnet), isPrivate = false)!!
        advanceUntilIdle()
        s.engine.emitMetadata(s.lastId(), sintelFiles)
        advanceUntilIdle()
        assertEquals(TorrentStatus.DOWNLOADING, s.entry(id).status)
        return id
    }

    @Test
    fun clearTakesFinishedAndFailedRowsAndLeavesEverythingElse() = runTest {
        val s = setup(this)
        val completed = finishedTorrent(s)
        s.clock += 1
        s.publishOk = false
        val publishFailed = finishedTorrent(s)
        s.publishOk = true
        assertEquals(TorrentErrorKind.PUBLISH_FAILED, s.entry(publishFailed).errorKind)
        s.clock += 1
        val failed = fileErrorTorrent(s)
        val failedDir = File(s.entry(failed).saveDir)
        assertTrue(failedDir.isDirectory)
        assertNotNull(s.store.readResume(failed))
        assertNotNull(s.store.readTorrent(failed))
        s.clock += 1
        val paused = downloadingTorrent(s)
        s.manager.pause(paused)
        advanceUntilIdle()
        s.clock += 1
        val downloading = downloadingTorrent(s)
        s.clock += 1
        s.settings.value = keepSeeding
        val seeding = seedingTorrent(s)
        val before = s.manager.entries.value.associateBy { it.id }

        val ids = s.manager.clearSettled()
        advanceUntilIdle()

        assertEquals(setOf(completed, failed), ids.toSet())
        assertEquals(setOf(publishFailed, paused, downloading, seeding), s.manager.entries.value.map { it.id }.toSet())
        for (e in s.manager.entries.value) assertEquals(before.getValue(e.id).status, e.status)
        assertEquals(TorrentErrorKind.PUBLISH_FAILED, s.entry(publishFailed).errorKind)
        // The failed torrent's partial data and state files are gone; the unsaved finished files stay for Retry.
        assertFalse(failedDir.exists())
        assertNull(s.store.readResume(failed))
        assertNull(s.store.readTorrent(failed))
        assertTrue(File(s.entry(publishFailed).saveDir, "$secretName/movie.mp4").isFile)
        assertTrue(File(s.entry(paused).saveDir).isDirectory)
        assertNotNull(s.store.readResume(paused))
        // What is published stays published: nothing was taken back.
        assertTrue(s.unpublished.isEmpty())
        // The index on disk has only the rows that stayed.
        assertEquals(setOf(publishFailed, paused, downloading, seeding), s.store.load().entries.map { it.id }.toSet())
        assertFalse(File(s.stateDir, "index.json").readText().contains(completed))
        assertTrue(s.logs.contains("torrent list cleared 2"))
        // The engine runs: session.dat is not touched now.
        assertTrue(s.engine.started)
        assertFalse("forgetSessionState" in s.engine.calls)
        assertFalse(s.logs.contains("session state dropped"))
    }

    @Test
    fun clearWithAnIdleEngineDropsTheSessionStateAndASetAsideIndex() = runTest {
        val s = setup(this)
        finishedTorrent(s)
        assertFalse(s.engine.started)
        assertTrue("the stop wrote it", s.store.sessionFile.isFile)
        val aside = File(s.stateDir, "index.json.corrupt").apply { writeText("{ old") }

        s.manager.clearSettled()
        advanceUntilIdle()

        assertTrue(s.manager.entries.value.isEmpty())
        assertFalse(s.store.sessionFile.exists())
        assertFalse(aside.exists())
        assertTrue(s.logs.contains("session state dropped"))
        assertTrue(s.store.load().entries.isEmpty())
    }

    @Test
    fun clearWhileTheEngineRunsDropsTheSessionStateAfterTheStopWhenTheListIsEmpty() = runTest {
        val s = setup(this)
        val downloading = downloadingTorrent(s)
        s.clock += 1
        finishedTorrent(s)
        assertTrue(s.engine.started)

        s.manager.clearSettled()
        advanceUntilIdle()
        assertEquals(listOf(downloading), s.manager.entries.value.map { it.id })
        assertFalse("forgetSessionState" in s.engine.calls)

        s.manager.remove(downloading, deleteFiles = true)
        advanceUntilIdle()
        assertFalse(s.engine.started)
        assertTrue(s.engine.calls.lastIndexOf("stop") < s.engine.calls.indexOf("forgetSessionState"))
        assertFalse(s.store.sessionFile.exists())
        assertTrue(s.logs.contains("session state dropped"))
    }

    @Test
    fun clearWhileTheEngineRunsKeepsTheSessionStateWhenRowsAreLeftAtTheStop() = runTest {
        val s = setup(this)
        val downloading = downloadingTorrent(s)
        s.clock += 1
        finishedTorrent(s)
        s.manager.clearSettled()
        advanceUntilIdle()

        s.manager.pause(downloading)
        advanceUntilIdle()
        assertFalse(s.engine.started)
        assertTrue(s.store.sessionFile.isFile)
        assertFalse("forgetSessionState" in s.engine.calls)
    }

    @Test
    fun aClearedListIsNotRestored() = runTest {
        val first = setup(this)
        finishedTorrent(first)
        first.clock += 1
        fileErrorTorrent(first)
        first.manager.clearSettled()
        advanceUntilIdle()

        val s = setup(this)
        s.manager.restore()
        advanceUntilIdle()
        assertTrue(s.manager.entries.value.isEmpty())
        assertTrue(s.store.storedIds().isEmpty())
    }

    @Test
    fun aClearBeforeTheIndexIsReadAlsoClearsWhatItReads() = runTest {
        val first = setup(this)
        val completed = finishedTorrent(first)
        first.clock += 1
        val paused = downloadingTorrent(first)
        first.manager.pause(paused)
        advanceUntilIdle()
        first.clock += 1
        val failed = fileErrorTorrent(first)
        val failedDir = File(first.entry(failed).saveDir)

        val s = setup(this)
        assertTrue(s.manager.clearSettled().isEmpty())
        s.manager.restore()
        advanceUntilIdle()
        assertEquals(listOf(paused), s.manager.entries.value.map { it.id })
        assertEquals(listOf(paused), s.store.load().entries.map { it.id })
        assertFalse(failedDir.exists())
        assertNull(s.store.readResume(failed))
        assertNull(s.store.readResume(completed))
        assertNotNull(s.store.readResume(paused))
    }

    @Test
    fun aKeptPrivateRowLeavesWithTheClearAndItsFilesGoWhenPrivateBrowsingEnds() = runTest {
        val s = setup(this)
        val id = keptTorrent(s)
        val kept = File(s.entry(id).keptDir!!)
        assertTrue(File(kept, "movie.mp4").isFile)

        assertEquals(listOf(id), s.manager.clearSettled())
        advanceUntilIdle()
        assertTrue(s.manager.entries.value.isEmpty())
        // The files stay, like a private file download's when the list is cleared.
        assertTrue(File(kept, "movie.mp4").isFile)

        assertTrue(s.manager.forgetPrivate().isEmpty())
        advanceUntilIdle()
        assertFalse(kept.exists())
    }
}
