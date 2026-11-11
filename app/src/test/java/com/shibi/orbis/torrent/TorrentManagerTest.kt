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
        // Reaping at the next start.
        File(s.payloadRoot, "0123456789abcdef0123456789abcdef").mkdirs()
        File(s.payloadRoot, "keep").mkdirs()
        val next = TorrentTestSetup(tmp.root, testScheduler).also { scopes += it.scope }
        next.manager.restore()
        advanceUntilIdle()
        assertTrue(s.logs.any { it.startsWith("retry") })
        assertTrue(s.logs.any { it.startsWith("publish rolled back") })
        assertTrue(next.logs.any { it.startsWith("orphan payloads reaped") })
        s.logs += next.logs

        assertTrue(s.logs.isNotEmpty())
        val hex40 = Regex("[0-9a-fA-F]{40}")
        for (line in s.logs) {
            assertFalse(line, hex40.containsMatchIn(line))
            assertFalse(line, line.contains("magnet", ignoreCase = true))
            assertFalse(line, line.contains("btih", ignoreCase = true))
            assertFalse(line, line.contains(secretName, ignoreCase = true))
            assertFalse(line, line.contains("tracker", ignoreCase = true))
            assertFalse(line, line.contains(id))
            assertFalse(line, line.contains(tmp.root.path))
        }
        // Entries print without their name too.
        assertFalse(s.manager.entries.value.toString().contains(secretName))
    }
}
