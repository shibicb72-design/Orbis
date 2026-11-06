package com.shibi.orbis.downloads

import com.shibi.orbis.engine.IncomingDownload
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class FakeDownload(
    override val url: String = "https://example.com/files/a.bin",
    override val contentDisposition: String? = null,
    override val contentType: String? = null,
    override val contentLength: Long = -1,
    private val body: () -> InputStream? = { null },
) : IncomingDownload {
    var openCalls = 0
    var discardCalls = 0

    override fun openBody(): InputStream? {
        openCalls++
        return body()
    }

    override fun discard() {
        discardCalls++
    }
}

/** In-memory stream that hands out [chunk] bytes per read; never blocks. */
private class ChunkStream(
    private val data: ByteArray,
    private val chunk: Int = 4,
    private val failOnRead: Int = -1,
    private val onRead: (Int) -> Unit = {},
) : InputStream() {
    private var pos = 0
    private var reads = 0

    @Volatile var closed = false

    override fun read(): Int = throw UnsupportedOperationException()

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        reads++
        onRead(reads)
        if (reads == failOnRead) throw IOException("simulated disk full or connection drop")
        if (pos >= data.size) return -1
        val n = minOf(chunk, len, data.size - pos)
        System.arraycopy(data, pos, b, off, n)
        pos += n
        return n
    }

    override fun close() {
        closed = true
    }
}

/** Blocks inside read() until released or closed, like a stalled socket. close() makes the read throw. */
private class BlockingStream(private val payload: ByteArray = ByteArray(0)) : InputStream() {
    val readStarted = CountDownLatch(1)
    val closedLatch = CountDownLatch(1)
    private val gate = CountDownLatch(1)
    private var served = false

    @Volatile private var closed = false

    fun release() = gate.countDown()

    override fun read(): Int = throw UnsupportedOperationException()

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        readStarted.countDown()
        gate.await()
        if (closed) throw IOException("Stream closed")
        if (served) return -1
        served = true
        System.arraycopy(payload, 0, b, off, payload.size)
        return payload.size
    }

    override fun close() {
        closed = true
        closedLatch.countDown()
        gate.countDown()
    }
}

class DownloadManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val logs = CopyOnWriteArrayList<String>()
    private val scopeJobs = mutableListOf<Job>()
    private var tick = 1_000L
    private var tickStep = 1L

    private val dir by lazy { File(tmp.root, "dl") }
    private val indexFile by lazy { File(tmp.root, "downloads_index.json") }
    private val store by lazy { DownloadStore(indexFile) }

    @After
    fun cancelScopes() {
        scopeJobs.forEach { it.cancel() }
    }

    private fun TestScope.manager(
        dirProvider: () -> File = { dir },
        ext: (String) -> String? = { null },
    ): DownloadManager {
        val io = StandardTestDispatcher(testScheduler)
        val job = SupervisorJob().also { scopeJobs += it }
        return DownloadManager(dirProvider, store, CoroutineScope(io + job), io, { logs += it }, { tick += tickStep; tick }, ext)
    }

    private fun TestScope.managerWithScopeJob(): Pair<DownloadManager, Job> {
        val io = StandardTestDispatcher(testScheduler)
        val job = SupervisorJob().also { scopeJobs += it }
        return DownloadManager({ dir }, store, CoroutineScope(io + job), io, { logs += it }, { tick += tickStep; tick }) to job
    }

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun DownloadManager.only(): DownloadEntry = entries.value.single()

    private fun DownloadManager.byId(id: Long): DownloadEntry = entries.value.first { it.id == id }

    private fun cd(name: String) = "attachment; filename=\"$name\""

    // ---- success ----

    @Test
    fun successCopiesBytesAndRenamesPartFile() = runTest {
        val m = manager()
        val stream = ChunkStream(bytes("hello world"))
        val dl = FakeDownload(contentDisposition = cd("a.txt"), contentType = "text/plain", contentLength = 11, body = { stream })
        val id = m.start(dl)
        assertEquals(DownloadStatus.IN_PROGRESS, m.byId(id).status)
        advanceUntilIdle()

        val e = m.byId(id)
        assertEquals(DownloadStatus.COMPLETED, e.status)
        assertEquals("a.txt", e.fileName)
        assertEquals(11L, e.receivedBytes)
        assertEquals(11L, e.totalBytes)
        assertEquals("text/plain", e.mimeType)
        assertEquals("example.com", e.sourceHost)
        assertEquals(File(dir, "a.txt").absolutePath, e.filePath)
        assertEquals("hello world", File(dir, "a.txt").readText())
        assertFalse(File(dir, "a.txt.part").exists())
        assertEquals(1, dl.openCalls)
        assertEquals(0, dl.discardCalls)
        assertTrue(stream.closed)
        assertEquals(listOf(e), store.load())
    }

    @Test
    fun unknownLengthCompletesAndLearnsTotal() = runTest {
        val m = manager()
        val id = m.start(FakeDownload(contentDisposition = cd("a.bin"), contentLength = -1, body = { ChunkStream(bytes("12345")) }))
        advanceUntilIdle()
        val e = m.byId(id)
        assertEquals(DownloadStatus.COMPLETED, e.status)
        assertEquals(5L, e.totalBytes)
        assertEquals(5L, e.receivedBytes)
    }

    @Test
    fun emptyBodyWithKnownZeroLengthCompletes() = runTest {
        val m = manager()
        val id = m.start(FakeDownload(contentDisposition = cd("empty.txt"), contentLength = 0, body = { ChunkStream(ByteArray(0)) }))
        advanceUntilIdle()
        assertEquals(DownloadStatus.COMPLETED, m.byId(id).status)
        assertEquals(0L, File(dir, "empty.txt").length())
    }

    @Test
    fun entriesAreNewestFirst() = runTest {
        val m = manager()
        val a = m.start(FakeDownload(contentDisposition = cd("1.txt"), body = { ChunkStream(bytes("1")) }))
        val b = m.start(FakeDownload(contentDisposition = cd("2.txt"), body = { ChunkStream(bytes("2")) }))
        val c = m.start(FakeDownload(contentDisposition = cd("3.txt"), body = { ChunkStream(bytes("3")) }))
        assertEquals(listOf(c, b, a), m.entries.value.map { it.id })
        advanceUntilIdle()
        assertEquals(listOf(c, b, a), m.entries.value.map { it.id })
    }

    @Test
    fun progressIsPublishedWhileCopying() = runTest {
        tickStep = 300 // every read crosses the 250 ms throttle
        lateinit var m: DownloadManager
        val seen = mutableListOf<Long>()
        val stream =
            ChunkStream(bytes("0123456789abcdef"), chunk = 4) { n -> if (n >= 2) seen += m.entries.value.first().receivedBytes }
        m = manager()
        m.start(FakeDownload(contentDisposition = cd("p.bin"), contentLength = 16, body = { stream }))
        advanceUntilIdle()
        assertTrue("progress seen: $seen", seen.any { it in 1..15 })
        assertEquals(DownloadStatus.COMPLETED, m.only().status)
    }

    @Test
    fun extensionForMimeIsInjected() = runTest {
        val m = manager(ext = { if (it == "application/pdf") "pdf" else null })
        val id = m.start(
            FakeDownload(url = "https://example.com/get", contentType = "application/pdf", body = { ChunkStream(bytes("x")) }),
        )
        advanceUntilIdle()
        assertEquals("get.pdf", m.byId(id).fileName)
        assertTrue(File(dir, "get.pdf").isFile)
    }

    // ---- failure ----

    @Test
    fun shortBodyWithKnownLengthIsFailedNotCompleted() = runTest {
        val m = manager()
        val stream = ChunkStream(bytes("12345"))
        val dl = FakeDownload(contentDisposition = cd("t.bin"), contentLength = 10, body = { stream })
        val id = m.start(dl)
        advanceUntilIdle()
        val e = m.byId(id)
        assertEquals(DownloadStatus.FAILED, e.status)
        assertNull(e.filePath)
        assertFalse(File(dir, "t.bin").exists())
        assertFalse(File(dir, "t.bin.part").exists())
        assertTrue(stream.closed)
        assertEquals(0, dl.discardCalls)
    }

    @Test
    fun longerBodyThanAnnouncedIsAlsoFailed() = runTest {
        val m = manager()
        val id = m.start(FakeDownload(contentDisposition = cd("t.bin"), contentLength = 3, body = { ChunkStream(bytes("12345")) }))
        advanceUntilIdle()
        assertEquals(DownloadStatus.FAILED, m.byId(id).status)
        assertFalse(File(dir, "t.bin").exists())
        assertFalse(File(dir, "t.bin.part").exists())
    }

    @Test
    fun nullBodyIsFailed() = runTest {
        val m = manager()
        val dl = FakeDownload(contentDisposition = cd("n.bin"), body = { null })
        val id = m.start(dl)
        advanceUntilIdle()
        assertEquals(DownloadStatus.FAILED, m.byId(id).status)
        assertEquals(1, dl.openCalls)
        assertEquals(0, dl.discardCalls)
        assertFalse(File(dir, "n.bin.part").exists())
    }

    @Test
    fun ioExceptionMidCopyIsFailedAndPartDeleted() = runTest {
        val m = manager()
        val stream = ChunkStream(bytes("0123456789"), chunk = 4, failOnRead = 2)
        val id = m.start(FakeDownload(contentDisposition = cd("d.bin"), contentLength = 10, body = { stream }))
        advanceUntilIdle()
        val e = m.byId(id)
        assertEquals(DownloadStatus.FAILED, e.status)
        assertNull(e.filePath)
        assertFalse(File(dir, "d.bin.part").exists())
        assertFalse(File(dir, "d.bin").exists())
        assertTrue(stream.closed)
    }

    @Test
    fun openBodyThrowingIsFailed() = runTest {
        val m = manager()
        val id = m.start(FakeDownload(contentDisposition = cd("d.bin"), body = { throw IllegalStateException("engine gone") }))
        advanceUntilIdle()
        assertEquals(DownloadStatus.FAILED, m.byId(id).status)
        assertFalse(File(dir, "d.bin.part").exists())
    }

    @Test
    fun unusableDirectoryFailsAndDiscardsTheBody() = runTest {
        val notADir = tmp.newFile("blocker")
        val m = manager(dirProvider = { File(notADir, "sub") })
        val dl = FakeDownload(contentDisposition = cd("x.bin"), body = { ChunkStream(bytes("x")) })
        val id = m.start(dl)
        advanceUntilIdle()
        assertEquals(DownloadStatus.FAILED, m.byId(id).status)
        assertEquals(0, dl.openCalls)
        assertEquals(1, dl.discardCalls)
    }

    @Test
    fun dirProviderThrowingFailsAndDiscardsTheBody() = runTest {
        val m = manager(dirProvider = { throw IOException("no storage") })
        val dl = FakeDownload(contentDisposition = cd("x.bin"), body = { ChunkStream(bytes("x")) })
        val id = m.start(dl)
        advanceUntilIdle()
        assertEquals(DownloadStatus.FAILED, m.byId(id).status)
        assertEquals(1, dl.discardCalls)
        assertEquals(0, dl.openCalls)
    }

    // ---- cancel ----

    @Test
    fun cancelMidCopyStopsReadingClosesStreamAndDeletesPart() = runTest {
        lateinit var m: DownloadManager
        var id = 0L
        var reads = 0
        val stream =
            ChunkStream(bytes("0123456789abcdef"), chunk = 4) { n ->
                reads = n
                if (n == 2) m.cancel(id)
            }
        m = manager()
        val dl = FakeDownload(contentDisposition = cd("c.bin"), contentLength = 16, body = { stream })
        id = m.start(dl)
        advanceUntilIdle()

        assertEquals(DownloadStatus.CANCELLED, m.byId(id).status)
        assertEquals("copy must stop at the next check, not run to the end", 2, reads)
        assertTrue(stream.closed)
        assertFalse(File(dir, "c.bin.part").exists())
        assertFalse(File(dir, "c.bin").exists())
        assertEquals(1, dl.openCalls)
        assertEquals(0, dl.discardCalls)
        assertTrue("cancel is not a failure", logs.none { it.contains("failed") })
    }

    @Test
    fun cancelBeforeTheCopyStartsDiscardsWithoutOpening() = runTest {
        val m = manager()
        val dl = FakeDownload(contentDisposition = cd("c.bin"), body = { ChunkStream(bytes("x")) })
        val id = m.start(dl)
        m.cancel(id)
        advanceUntilIdle()
        assertEquals(DownloadStatus.CANCELLED, m.byId(id).status)
        assertEquals(0, dl.openCalls)
        assertEquals(1, dl.discardCalls)
        assertFalse(File(dir, "c.bin.part").exists())
    }

    @Test
    fun scopeDeathBeforeTheCopyStartsStillDiscardsTheBody() = runTest {
        val (m, scopeJob) = managerWithScopeJob()
        val dl = FakeDownload(contentDisposition = cd("c.bin"), body = { ChunkStream(bytes("x")) })
        val id = m.start(dl)
        scopeJob.cancel()
        advanceUntilIdle()
        assertEquals(0, dl.openCalls)
        assertEquals(1, dl.discardCalls)
        assertEquals(DownloadStatus.CANCELLED, m.byId(id).status)
    }

    @Test
    fun cancelOnFinishedOrUnknownIdIsHarmless() = runTest {
        val m = manager()
        val id = m.start(FakeDownload(contentDisposition = cd("a.txt"), body = { ChunkStream(bytes("x")) }))
        advanceUntilIdle()
        m.cancel(id)
        m.cancel(424242)
        assertEquals(DownloadStatus.COMPLETED, m.byId(id).status)
        assertTrue(File(dir, "a.txt").isFile)
    }

    @Test
    fun cancelUnblocksARealBlockedRead() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopeJobs += it.coroutineContext[Job]!! }
        val m = DownloadManager({ dir }, store, scope, Dispatchers.IO, { logs += it }, { System.currentTimeMillis() })
        val stream = BlockingStream()
        val dl = FakeDownload(contentDisposition = cd("b.bin"), contentLength = 100, body = { stream })
        val id = m.start(dl)
        assertTrue("read never started", stream.readStarted.await(5, TimeUnit.SECONDS))

        m.cancel(id)

        waitUntil("cancelled") { m.entries.value.single().status == DownloadStatus.CANCELLED }
        assertTrue("stream was not closed", stream.closedLatch.await(5, TimeUnit.SECONDS))
        assertFalse(File(dir, "b.bin.part").exists())
        assertFalse(File(dir, "b.bin").exists())
        assertEquals(1, dl.openCalls)
        assertEquals(0, dl.discardCalls)
        assertTrue(logs.none { it.contains("failed") })
        scope.cancel()
    }

    // ---- naming and reservation ----

    @Test
    fun sameNameTwiceGetsCounter() = runTest {
        val m = manager()
        val a = m.start(FakeDownload(contentDisposition = cd("a.txt"), body = { ChunkStream(bytes("first")) }))
        val b = m.start(FakeDownload(contentDisposition = cd("a.txt"), body = { ChunkStream(bytes("second")) }))
        advanceUntilIdle()
        assertEquals("a.txt", m.byId(a).fileName)
        assertEquals("a (1).txt", m.byId(b).fileName)
        assertEquals("first", File(dir, "a.txt").readText())
        assertEquals("second", File(dir, "a (1).txt").readText())
    }

    @Test
    fun concurrentStartsWithTheSameNameNeverShareAFile() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopeJobs += it.coroutineContext[Job]!! }
        val m = DownloadManager({ dir }, store, scope, Dispatchers.IO, { logs += it }, { System.currentTimeMillis() })
        val s1 = BlockingStream(bytes("one"))
        val s2 = BlockingStream(bytes("two"))
        val id1 = m.start(FakeDownload(contentDisposition = cd("same.txt"), contentLength = 3, body = { s1 }))
        val id2 = m.start(FakeDownload(contentDisposition = cd("same.txt"), contentLength = 3, body = { s2 }))
        assertTrue(s1.readStarted.await(5, TimeUnit.SECONDS))
        assertTrue(s2.readStarted.await(5, TimeUnit.SECONDS))

        val n1 = m.entries.value.first { it.id == id1 }.fileName
        val n2 = m.entries.value.first { it.id == id2 }.fileName
        assertNotEquals(n1, n2)
        assertTrue(File(dir, "$n1.part").exists())
        assertTrue(File(dir, "$n2.part").exists())

        s1.release()
        s2.release()
        waitUntil("both completed") { m.entries.value.all { it.status == DownloadStatus.COMPLETED } }
        assertEquals("one", File(dir, n1).readText())
        assertEquals("two", File(dir, n2).readText())
        scope.cancel()
    }

    @Test
    fun existingFileIsNeverOverwritten() = runTest {
        dir.mkdirs()
        File(dir, "a.txt").writeText("old")
        val m = manager()
        val id = m.start(FakeDownload(contentDisposition = cd("a.txt"), body = { ChunkStream(bytes("new")) }))
        advanceUntilIdle()
        assertEquals("a (1).txt", m.byId(id).fileName)
        assertEquals("old", File(dir, "a.txt").readText())
        assertEquals("new", File(dir, "a (1).txt").readText())
    }

    @Test
    fun existingPartFileForTheNameIsAvoidedToo() = runTest {
        dir.mkdirs()
        File(dir, "a.txt.part").writeText("someone else's partial")
        val m = manager()
        val id = m.start(FakeDownload(contentDisposition = cd("a.txt"), body = { ChunkStream(bytes("new")) }))
        advanceUntilIdle()
        assertEquals("a (1).txt", m.byId(id).fileName)
        assertEquals("someone else's partial", File(dir, "a.txt.part").readText())
    }

    @Test
    fun aDownloadNamedLikeAPartFileDoesNotCollideWithTheRealPart() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopeJobs += it.coroutineContext[Job]!! }
        val m = DownloadManager({ dir }, store, scope, Dispatchers.IO, { logs += it }, { System.currentTimeMillis() })
        val s1 = BlockingStream(bytes("one"))
        val s2 = BlockingStream(bytes("two"))
        val id1 = m.start(FakeDownload(contentDisposition = cd("x"), contentLength = 3, body = { s1 }))
        assertTrue(s1.readStarted.await(5, TimeUnit.SECONDS)) // "x" holds x and x.part
        val id2 = m.start(FakeDownload(contentDisposition = cd("x.part"), contentLength = 3, body = { s2 }))
        assertTrue(s2.readStarted.await(5, TimeUnit.SECONDS))
        val n1 = m.entries.value.first { it.id == id1 }.fileName
        val n2 = m.entries.value.first { it.id == id2 }.fileName
        assertEquals("x", n1)
        assertNotEquals("x.part", n2)
        s1.release()
        s2.release()
        waitUntil("both completed") { m.entries.value.all { it.status == DownloadStatus.COMPLETED } }
        assertEquals("one", File(dir, n1).readText())
        assertEquals("two", File(dir, n2).readText())
        scope.cancel()
    }

    @Test
    fun hostileNamesStayInsideTheDirectory() = runTest {
        val m = manager()
        val names = listOf("../../evil.sh", "..\\..\\evil.bat", "/etc/passwd", "....//x")
        val ids = names.map { n -> m.start(FakeDownload(contentDisposition = cd(n), body = { ChunkStream(bytes("x")) })) }
        advanceUntilIdle()
        for (id in ids) {
            val e = m.byId(id)
            assertEquals(DownloadStatus.COMPLETED, e.status)
            assertTrue(DownloadFileNames.isInside(dir, File(e.filePath!!)))
            assertEquals(dir.canonicalFile, File(e.filePath!!).canonicalFile.parentFile)
        }
        assertEquals(listOf("downloads_index.json", "dl").sorted(), tmp.root.list()!!.toList().sorted())
        assertEquals(4, dir.list()!!.size)
    }

    // ---- entry metadata and logging ----

    @Test
    fun entryNeverHoldsTheUrlAndBadUrlGivesNullHost() = runTest {
        val m = manager()
        val a = m.start(FakeDownload(url = "https://files.example.org/p/f.bin?token=SECRET123", body = { ChunkStream(bytes("x")) }))
        val b = m.start(FakeDownload(url = "not a url at all", contentDisposition = cd("b.bin"), body = { ChunkStream(bytes("x")) }))
        advanceUntilIdle()
        assertEquals("files.example.org", m.byId(a).sourceHost)
        assertNull(m.byId(b).sourceHost)
        val index = indexFile.readText()
        assertFalse(index.contains("SECRET123"))
        assertFalse(index.contains("https://"))
        assertFalse(index.contains("token"))
    }

    @Test
    fun logsCarryStatusOnly() = runTest {
        val m = manager()
        val dl = FakeDownload(
            url = "https://secret-host.example.net/path/topsecret.pdf?token=abc",
            contentDisposition = cd("topsecret-name.pdf"),
            contentLength = 10,
            body = { ChunkStream(bytes("short")) },
        )
        m.start(dl)
        m.start(FakeDownload(url = "https://secret-host.example.net/z", contentDisposition = cd("topsecret-ok.pdf"), body = { ChunkStream(bytes("ok")) }))
        advanceUntilIdle()
        assertTrue(logs.isNotEmpty())
        for (line in logs) {
            assertFalse(line, line.contains("secret", ignoreCase = true))
            assertFalse(line, line.contains("example"))
            assertFalse(line, line.contains(".pdf"))
            assertFalse(line, line.contains("token"))
        }
    }

    // ---- restore ----

    @Test
    fun restoreFailsInterruptedDownloadsAndDeletesTheirParts() = runTest {
        dir.mkdirs()
        val finished = File(dir, "done.txt").also { it.writeText("done") }
        val part = File(dir, "half.bin.part").also { it.writeText("partial") }
        store.save(
            listOf(
                DownloadEntry(4, "done.txt", "text/plain", "example.com", 4, 4, DownloadStatus.COMPLETED, finished.absolutePath, 100),
                DownloadEntry(5, "half.bin", "application/octet-stream", null, 100, 7, DownloadStatus.IN_PROGRESS, File(dir, "half.bin").absolutePath, 200),
            ),
        )
        val m = manager()
        m.restore()
        advanceUntilIdle()

        assertEquals(listOf(5L, 4L), m.entries.value.map { it.id })
        val interrupted = m.byId(5)
        assertEquals(DownloadStatus.FAILED, interrupted.status)
        assertNull(interrupted.filePath)
        assertFalse(part.exists())
        assertEquals(DownloadStatus.COMPLETED, m.byId(4).status)
        assertTrue(finished.exists())
        assertEquals(DownloadStatus.FAILED, store.load().first { it.id == 5L }.status)
    }

    @Test
    fun restoreDoesNotDeleteFilesOutsideTheDirectory() = runTest {
        val outside = tmp.newFile("outside.bin.part")
        store.save(
            listOf(DownloadEntry(5, "x", "a/b", null, -1, 0, DownloadStatus.IN_PROGRESS, File(tmp.root, "outside.bin").absolutePath, 200)),
        )
        val m = manager()
        m.restore()
        advanceUntilIdle()
        assertEquals(DownloadStatus.FAILED, m.byId(5).status)
        assertTrue(outside.exists())
    }

    @Test
    fun restoreWithCorruptIndexIsEmptyAndDoesNotCrash() = runTest {
        indexFile.writeText("}}} not json")
        val m = manager()
        m.restore()
        advanceUntilIdle()
        assertTrue(m.entries.value.isEmpty())
        // The manager still works afterwards.
        val id = m.start(FakeDownload(contentDisposition = cd("a.txt"), body = { ChunkStream(bytes("x")) }))
        advanceUntilIdle()
        assertEquals(DownloadStatus.COMPLETED, m.byId(id).status)
    }

    @Test
    fun restoreKeepsADownloadStartedWhileTheIndexLoaded() = runTest {
        store.save(listOf(DownloadEntry(3, "old.txt", "text/plain", null, 1, 1, DownloadStatus.FAILED, null, 5)))
        val m = manager()
        m.restore()
        val id = m.start(FakeDownload(contentDisposition = cd("new.txt"), body = { ChunkStream(bytes("x")) }))
        advanceUntilIdle()
        assertEquals(setOf(3L, id), m.entries.value.map { it.id }.toSet())
        assertEquals(DownloadStatus.COMPLETED, m.byId(id).status)
        assertEquals(id, m.entries.value.first().id)
    }

    @Test
    fun idsAfterRestoreNeverCollideWithStoredOnes() = runTest {
        store.save(listOf(DownloadEntry(999_999, "old", "a/b", null, 1, 1, DownloadStatus.FAILED, null, 5)))
        val m = manager()
        m.restore()
        advanceUntilIdle()
        val id = m.start(FakeDownload(contentDisposition = cd("n.txt"), body = { ChunkStream(bytes("x")) }))
        assertTrue(id > 999_999)
    }

    // ---- delete ----

    @Test
    fun deleteRemovesEntryFileAndPersists() = runTest {
        val m = manager()
        val id = m.start(FakeDownload(contentDisposition = cd("gone.txt"), body = { ChunkStream(bytes("x")) }))
        advanceUntilIdle()
        assertTrue(File(dir, "gone.txt").exists())
        m.delete(id)
        advanceUntilIdle()
        assertTrue(m.entries.value.isEmpty())
        assertFalse(File(dir, "gone.txt").exists())
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun deleteWhenFileAlreadyVanishedStillRemovesEntry() = runTest {
        val m = manager()
        val id = m.start(FakeDownload(contentDisposition = cd("v.txt"), body = { ChunkStream(bytes("x")) }))
        advanceUntilIdle()
        assertTrue(File(dir, "v.txt").delete())
        m.delete(id)
        advanceUntilIdle()
        assertTrue(m.entries.value.isEmpty())
    }

    @Test
    fun deleteOfRunningDownloadCancelsItAndLeavesNoFiles() = runTest {
        lateinit var m: DownloadManager
        var id = 0L
        val stream = ChunkStream(bytes("0123456789abcdef"), chunk = 4) { n -> if (n == 2) m.delete(id) }
        m = manager()
        id = m.start(FakeDownload(contentDisposition = cd("r.bin"), contentLength = 16, body = { stream }))
        advanceUntilIdle()
        assertTrue(m.entries.value.isEmpty())
        assertTrue(stream.closed)
        assertFalse(File(dir, "r.bin.part").exists())
        assertFalse(File(dir, "r.bin").exists())
    }

    @Test
    fun deleteNeverTouchesAFileOutsideTheDirectory() = runTest {
        val outside = tmp.newFile("precious.txt")
        store.save(
            listOf(DownloadEntry(8, "precious.txt", "text/plain", null, 1, 1, DownloadStatus.COMPLETED, outside.absolutePath, 5)),
        )
        val m = manager()
        m.restore()
        advanceUntilIdle()
        m.delete(8)
        advanceUntilIdle()
        assertTrue(m.entries.value.isEmpty())
        assertTrue(outside.exists())
    }

    @Test
    fun deleteUnknownIdIsHarmless() = runTest {
        val m = manager()
        m.delete(12345)
        advanceUntilIdle()
        assertTrue(m.entries.value.isEmpty())
    }

    @Test
    fun failedAndCancelledEntriesCanBeDeleted() = runTest {
        val m = manager()
        val failed = m.start(FakeDownload(contentDisposition = cd("f.bin"), body = { null }))
        val cancelled = m.start(FakeDownload(contentDisposition = cd("c.bin"), body = { ChunkStream(bytes("x")) }))
        m.cancel(cancelled)
        advanceUntilIdle()
        m.delete(failed)
        m.delete(cancelled)
        advanceUntilIdle()
        assertTrue(m.entries.value.isEmpty())
        assertTrue(store.load().isEmpty())
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for: $what")
    }
}
