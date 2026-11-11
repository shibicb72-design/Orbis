package com.shibi.orbis.engine

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UploadCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8).size

    // ---- sanitizeUploadName ----

    @Test
    fun plainNameIsKept() {
        assertEquals("photo.jpg", sanitizeUploadName("photo.jpg", null))
        assertEquals("my report (1).pdf", sanitizeUploadName("my report (1).pdf", "pdf"))
    }

    @Test
    fun traversalKeepsOnlyLastSegment() {
        assertEquals("x", sanitizeUploadName("../../x", null))
        assertEquals("x", sanitizeUploadName("..\\x", null))
        assertEquals("x", sanitizeUploadName("a/b\\c/x", null))
        assertEquals("upload", sanitizeUploadName("a/b/", null))
        assertEquals("upload", sanitizeUploadName("a/..", null))
        assertEquals("upload", sanitizeUploadName("..\\..", null))
    }

    @Test
    fun controlAndFormatCharactersAreStripped() {
        assertEquals("ab.txt", sanitizeUploadName("a\u0000b.txt", null))
        assertEquals("ab.txt", sanitizeUploadName("a\tb\n.txt", null))
        assertEquals("abtxt", sanitizeUploadName("ab‮txt", null))
        // NUL cannot be used to hide a separator or a dot-dot.
        assertEquals("x", sanitizeUploadName("..\u0000/x", null))
        assertEquals("upload", sanitizeUploadName(".\u0000.", null))
        // A lone surrogate is dropped; a valid pair survives.
        assertEquals("ab", sanitizeUploadName("a\uD800b", null))
        assertEquals("a😀b", sanitizeUploadName("a😀b", null))
    }

    @Test
    fun emptyAndDotNamesBecomeUpload() {
        for (raw in listOf(null, "", " ", ".", "..", "...", " . . ", "\u0000", "/", "\\")) {
            assertEquals("raw=$raw", "upload", sanitizeUploadName(raw, null))
        }
    }

    @Test
    fun leadingAndTrailingDotsAndSpacesAreTrimmed() {
        assertEquals("hidden", sanitizeUploadName(".hidden", null))
        assertEquals("a.txt", sanitizeUploadName("  a.txt  ", null))
        assertEquals("a", sanitizeUploadName("a...", null))
        assertEquals("a.b", sanitizeUploadName("..a.b. .", null))
    }

    @Test
    fun extensionIsAddedOnlyWhenMissing() {
        assertEquals("photo.jpg", sanitizeUploadName("photo", "jpg"))
        assertEquals("photo.jpg", sanitizeUploadName("photo", ".jpg"))
        assertEquals("photo.png", sanitizeUploadName("photo.png", "jpg"))
        assertEquals("photo", sanitizeUploadName("photo", null))
        assertEquals("photo", sanitizeUploadName("photo", ""))
        assertEquals("upload.pdf", sanitizeUploadName(null, "pdf"))
        // A hostile extension cannot add separators.
        assertEquals("photo.etcpasswd", sanitizeUploadName("photo", "../etc/passwd"))
    }

    @Test
    fun longNameIsCappedKeepingExtension() {
        val name = "a".repeat(300) + ".pdf"
        val out = sanitizeUploadName(name, null)
        assertTrue(out.endsWith(".pdf"))
        assertEquals(120, utf8(out))
        assertEquals("a".repeat(116) + ".pdf", out)
    }

    @Test
    fun longNameWithoutExtensionIsCapped() {
        val out = sanitizeUploadName("b".repeat(300), null)
        assertEquals(120, utf8(out))
    }

    @Test
    fun multibyteCutNeverSplitsACharacter() {
        // 3-byte characters: 120 / 3 = 40 exactly; with ".txt" (4 bytes) only 38 fit (114 + 4 = 118).
        val out = sanitizeUploadName("€".repeat(100) + ".txt", null)
        assertTrue(out.endsWith(".txt"))
        assertTrue(utf8(out) <= 120)
        assertEquals("€".repeat(38) + ".txt", out)
        // 4-byte characters (surrogate pairs) are cut whole.
        val emoji = "😀".repeat(60)
        val out2 = sanitizeUploadName(emoji, null)
        assertEquals(120, utf8(out2))
        assertEquals(30, out2.codePointCount(0, out2.length))
        // 4-byte characters that do not fit evenly: 119 bytes of room.
        val out3 = sanitizeUploadName("x" + "😀".repeat(60), null)
        assertTrue(utf8(out3) <= 120)
        assertFalse(Character.isLowSurrogate(out3.last()) && !Character.isHighSurrogate(out3[out3.length - 2]))
    }

    @Test
    fun giantExtensionIsTreatedAsPartOfTheName() {
        val out = sanitizeUploadName("a." + "e".repeat(300), null)
        assertTrue(utf8(out) <= 120)
        assertTrue(out.isNotEmpty())
    }

    // ---- copy ----

    private class FakeSource(
        override val name: String?,
        private val data: ByteArray? = ByteArray(0),
        override val size: Long? = data?.size?.toLong(),
        override val extension: String? = null,
        private val failOpen: Boolean = false,
    ) : UploadCache.Source {
        override fun open(): InputStream? {
            if (failOpen) throw SecurityException("denied")
            return data?.let { ByteArrayInputStream(it) }
        }
    }

    /** Coroutines' stack-trace recovery may wrap the exception in a copy, so the original sits somewhere in the chain. */
    private fun causeChain(e: Throwable): Sequence<Throwable> = generateSequence(e) { it.cause }

    private fun newCache() = UploadCache(tmp.newFolder("cache"), Dispatchers.IO)

    private fun rootChildren(cache: UploadCache): List<File> = cache.root.listFiles()?.toList().orEmpty()

    @Test
    fun copySucceedsAndKeepsNameAndBytes() = runBlocking {
        val cache = newCache()
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val out = cache.copy(listOf(FakeSource("../../report.pdf", bytes), FakeSource("note", "hi".toByteArray(), extension = "txt")))
        assertEquals(listOf("report.pdf", "note.txt"), out.map { it.name })
        assertArrayEquals(bytes, out[0].readBytes())
        assertEquals("hi", out[1].readText())
        for (f in out) assertTrue(f.canonicalPath.startsWith(cache.root.canonicalPath + File.separator))
    }

    @Test
    fun everyFileGetsItsOwnDirectoryEvenWithTheSameName() = runBlocking {
        val cache = newCache()
        val out = cache.copy(listOf(FakeSource("a.txt", byteArrayOf(1)), FakeSource("a.txt", byteArrayOf(2))))
        assertEquals("a.txt", out[0].name)
        assertEquals("a.txt", out[1].name)
        assertNotEquals(out[0].parentFile, out[1].parentFile)
        assertEquals(cache.root, out[0].parentFile!!.parentFile)
        assertEquals(1, out[0].readBytes().size)
        assertEquals(2.toByte(), out[1].readBytes()[0])
    }

    @Test
    fun emptyListCopiesNothing() = runBlocking {
        assertTrue(newCache().copy(emptyList()).isEmpty())
    }

    @Test
    fun nullStreamFailsAllOrNothing() = runBlocking {
        val cache = newCache()
        try {
            cache.copy(listOf(FakeSource("a.txt", byteArrayOf(1)), FakeSource("b.txt", data = null, size = 1)))
            fail("expected UploadCopyException")
        } catch (e: UploadCopyException) {
            assertFalse(e.message.orEmpty().contains("b.txt"))
        }
        assertTrue(rootChildren(cache).isEmpty())
    }

    @Test
    fun securityExceptionBecomesCopyExceptionAndCleansUp() = runBlocking {
        val cache = newCache()
        try {
            cache.copy(listOf(FakeSource("a.txt", byteArrayOf(1)), FakeSource("b.txt", failOpen = true)))
            fail("expected UploadCopyException")
        } catch (e: UploadCopyException) {
            assertTrue(causeChain(e).any { it is SecurityException })
        }
        assertTrue(rootChildren(cache).isEmpty())
    }

    @Test
    fun readIoErrorBecomesCopyExceptionAndCleansUp() = runBlocking {
        val cache = newCache()
        val broken =
            object : UploadCache.Source {
                override val name = "c.bin"
                override val size: Long? = null

                override fun open(): InputStream =
                    object : InputStream() {
                        override fun read(): Int = throw IOException("boom")

                        override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("boom")
                    }
            }
        try {
            cache.copy(listOf(FakeSource("a.txt", byteArrayOf(1)), broken))
            fail("expected UploadCopyException")
        } catch (e: UploadCopyException) {
            assertTrue(causeChain(e).any { it is IOException && it !is UploadCopyException })
        }
        assertTrue(rootChildren(cache).isEmpty())
    }

    @Test
    fun knownSizeBeyondFreeSpaceFailsBeforeCopying() = runBlocking {
        val cache = newCache()
        try {
            cache.copy(listOf(FakeSource("a.txt", byteArrayOf(1)), FakeSource("huge.bin", byteArrayOf(1), size = Long.MAX_VALUE)))
            fail("expected UploadCopyException")
        } catch (e: UploadCopyException) {
            // expected
        }
        assertTrue(rootChildren(cache).isEmpty())
    }

    @Test
    fun cancellationCleansUpAndRethrows() = runBlocking {
        val cache = newCache()
        val ready = FakeSource("a.txt", byteArrayOf(1))
        val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            cache.copy(
                listOf(
                    ready,
                    object : UploadCache.Source {
                        override val name = "big.bin"
                        override val size: Long? = null

                        override fun open(): InputStream =
                            object : InputStream() {
                                override fun read(): Int = 0

                                override fun read(b: ByteArray, off: Int, len: Int): Int {
                                    // Cancel from inside the copy, then keep producing data: only ensureActive can stop it.
                                    this@launch.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
                                    return minOf(len, 1024)
                                }
                            }
                    },
                ),
            )
        }
        job.start()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(rootChildren(cache).isEmpty())
    }

    // ---- hostile providers ----

    /** Stuck provider calls wait on this; released after each test so no provider thread outlives it. */
    private val stuck = CountDownLatch(1)

    @After
    fun releaseStuckProviders() {
        stuck.countDown()
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (ProviderDeadline.aliveThreads() > 0 && System.nanoTime() < until) Thread.sleep(10)
        assertEquals(0, ProviderDeadline.aliveThreads())
    }

    /** Blocks in read() until closed, like a provider pipe that stops sending. */
    private class StalledStream : InputStream() {
        val closed = CountDownLatch(1)

        override fun read(): Int {
            closed.await()
            throw IOException("closed")
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = read()

        override fun close() {
            closed.countDown()
        }
    }

    private fun hostileCache(idleTimeoutMs: Long = 60_000) =
        UploadCache(tmp.newFolder("cache"), Dispatchers.IO, metadataTimeoutMs = 200, openTimeoutMs = 300, idleTimeoutMs = idleTimeoutMs)

    @Test
    fun aHangingNameQueryAndTypeLookupFallBackToAGenericName() = runBlocking {
        val cache = hostileCache()
        val source =
            object : UploadCache.Source {
                override val name: String? get() = stuck.await().let { "secret.pdf" }
                override val size: Long? get() = stuck.await().let { 1L }
                override val extension: String? get() = stuck.await().let { "pdf" }

                override fun open(): InputStream = ByteArrayInputStream("hi".toByteArray())
            }
        val out = withTimeout(10_000) { cache.copy(listOf(source)) }
        assertEquals("upload", out.single().name)
        assertEquals("hi", out.single().readText())
    }

    @Test
    fun aHangingOpenFailsAtItsDeadlineCleansUpAndALateStreamIsClosed() = runBlocking {
        val cache = hostileCache()
        val late = StalledStream()
        val hanging =
            object : UploadCache.Source {
                override val name = "b.txt"
                override val size: Long? = null

                override fun open(): InputStream {
                    stuck.await()
                    return late
                }
            }
        try {
            withTimeout(10_000) { cache.copy(listOf(FakeSource("a.txt", byteArrayOf(1)), hanging)) }
            fail("expected UploadCopyException")
        } catch (e: UploadCopyException) {
            // expected
        }
        assertTrue(rootChildren(cache).isEmpty())
        stuck.countDown()
        assertTrue(late.closed.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun aStalledReadFailsAtTheIdleTimeoutAndClosesTheStream() = runBlocking {
        val cache = hostileCache(idleTimeoutMs = 300)
        val stream = StalledStream()
        val stalled =
            object : UploadCache.Source {
                override val name = "big.bin"
                override val size: Long? = null

                override fun open(): InputStream = stream
            }
        try {
            withTimeout(10_000) { cache.copy(listOf(stalled)) }
            fail("expected UploadCopyException")
        } catch (e: UploadCopyException) {
            assertFalse(e.message.orEmpty().contains("big.bin"))
        }
        assertTrue(stream.closed.await(10, TimeUnit.SECONDS))
        assertTrue(rootChildren(cache).isEmpty())
    }

    @Test
    fun aSlowCopyThatKeepsSendingIsNotCutOff() = runBlocking {
        val cache = hostileCache(idleTimeoutMs = 1_000)
        var sent = 0
        val trickle =
            object : UploadCache.Source {
                override val name = "slow.bin"
                override val size: Long? = null

                override fun open(): InputStream =
                    object : InputStream() {
                        override fun read(): Int = throw UnsupportedOperationException()

                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (sent >= 8) return -1
                            Thread.sleep(150)
                            sent++
                            b[off] = 7
                            return 1
                        }
                    }
            }
        // 8 x 150 ms is past the idle window as a whole; no single gap comes near it.
        val out = cache.copy(listOf(trickle))
        assertEquals(8, out.single().readBytes().size)
    }

    @Test
    fun cancellingDuringABlockedReadClosesTheStreamAndCleansUp() = runBlocking {
        val cache = hostileCache()
        val stream = StalledStream()
        val job =
            launch(Dispatchers.Default) {
                cache.copy(
                    listOf(
                        object : UploadCache.Source {
                            override val name = "big.bin"
                            override val size: Long? = null

                            override fun open(): InputStream = stream
                        },
                    ),
                )
            }
        withTimeout(10_000) { while (rootChildren(cache).isEmpty()) delay(5) }
        job.cancel()
        withTimeout(10_000) { job.join() }
        assertTrue(job.isCancelled)
        assertTrue(stream.closed.await(10, TimeUnit.SECONDS))
        assertTrue(rootChildren(cache).isEmpty())
    }

    @Test
    fun aCancelBeforeTheWatchdogStartsStillClosesTheStream() = runBlocking {
        // The watchdog's dispatcher is busy until after the cancel, so its body never runs: only the completion
        // handler can close the stream that the copy is blocked on.
        val busy = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)
        busy.execute { gate.await() }
        try {
            val cache =
                UploadCache(
                    tmp.newFolder("cache"),
                    Dispatchers.IO,
                    idleTimeoutMs = 60_000,
                    watchdogDispatcher = busy.asCoroutineDispatcher(),
                )
            val stream = StalledStream()
            val job =
                launch(Dispatchers.Default) {
                    cache.copy(
                        listOf(
                            object : UploadCache.Source {
                                override val name = "big.bin"
                                override val size: Long? = null

                                override fun open(): InputStream = stream
                            },
                        ),
                    )
                }
            withTimeout(10_000) { while (rootChildren(cache).isEmpty()) delay(5) }
            job.cancel()
            gate.countDown()
            withTimeout(10_000) { job.join() }
            assertTrue(job.isCancelled)
            assertTrue(stream.closed.await(10, TimeUnit.SECONDS))
            assertTrue(rootChildren(cache).isEmpty())
        } finally {
            gate.countDown()
            busy.shutdownNow()
        }
    }

    @Test
    fun anUploadStillWorksWhenIntentCallsFillTheirPool() = runBlocking {
        val held =
            (1..ProviderDeadline.MAX_THREADS).map {
                async(Dispatchers.IO) { providerCall<Unit>(timeoutMs = 10_000) { stuck.await() } }
            }
        withTimeout(5_000) { while (ProviderDeadline.intents.aliveThreads() < ProviderDeadline.MAX_THREADS) delay(5) }
        // The intents pool is full: an intent-driven call gets nothing.
        assertEquals(null, providerCall { "type" })
        val out = newCache().copy(listOf(FakeSource("report", "hi".toByteArray(), extension = "pdf")))
        assertEquals("report.pdf", out.single().name)
        assertEquals("hi", out.single().readText())
        stuck.countDown()
        held.awaitAll()
        Unit
    }

    // ---- clearAll / discard ----

    @Test
    fun clearAllDeletesTheRootAndIsRepeatable() = runBlocking {
        val cache = newCache()
        val out = cache.copy(listOf(FakeSource("a.txt", byteArrayOf(1))))
        assertTrue(out[0].exists())
        cache.clearAll()
        assertFalse(cache.root.exists())
        cache.clearAll()
        // The cache works again afterwards.
        assertEquals(1, cache.copy(listOf(FakeSource("b.txt", byteArrayOf(1)))).size)
    }

    @Test
    fun discardRemovesOnlyDirectoriesUnderTheRoot() = runBlocking {
        val cache = newCache()
        val out = cache.copy(listOf(FakeSource("a.txt", byteArrayOf(1))))
        val outside = tmp.newFile("keep.txt")
        cache.discard(listOf(out[0], outside))
        assertFalse(out[0].parentFile!!.exists())
        assertTrue(outside.exists())
    }

    @Test
    fun rootIsUploadsUnderTheCacheDir() {
        val dir = tmp.newFolder("c2")
        assertEquals(File(dir, "uploads"), UploadCache(dir).root)
    }
}
