package com.shibi.orbis.torrent

import com.shibi.orbis.engine.IncomingDownload
import com.shibi.orbis.engine.ProviderDeadline
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentIntakeReaderTest {
    private val cap = TorrentLimits.MAX_TORRENT_FILE_BYTES

    private class FakeDownload(private val body: () -> InputStream?, override val contentLength: Long = -1L) : IncomingDownload {
        var opens = 0
        var discards = 0
        override val url = "https://example.com/a.torrent"
        override val contentDisposition: String? = null
        override val contentType: String? = "application/x-bittorrent"

        override fun openBody(): InputStream? {
            opens++
            return body()
        }

        override fun discard() {
            discards++
        }
    }

    /** Blocks in read() until closed. */
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

    private val reader = TorrentIntakeReader(Dispatchers.IO, idleTimeoutMs = 300, overallTimeoutMs = 5_000)

    /** Stuck opens wait on this; released after each test so no provider thread outlives it. */
    private val stuck = CountDownLatch(1)

    @After
    fun releaseStuckProviders() {
        stuck.countDown()
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (ProviderDeadline.aliveThreads() > 0 && System.nanoTime() < until) Thread.sleep(10)
        assertEquals(0, ProviderDeadline.aliveThreads())
    }

    @Test
    fun exactlyTheCapIsRead() = runBlocking {
        val d = FakeDownload({ ByteArrayInputStream(ByteArray(cap) { 7 }) })
        val r = reader.readResponse(d) as TorrentIntakeReader.Result.Ok
        assertEquals(cap, r.bytes.size)
        assertEquals(1, d.opens)
        assertEquals(0, d.discards)
    }

    @Test
    fun oneByteOverTheCapIsThrownAwayAndDiscardedOnce() = runBlocking {
        var closed = false
        val d =
            FakeDownload({
                object : ByteArrayInputStream(ByteArray(cap + 1)) {
                    override fun close() {
                        closed = true
                    }
                }
            })
        val r = reader.readResponse(d) as TorrentIntakeReader.Result.Failed
        assertEquals(IntakeRefusal.TOO_LARGE, r.reason)
        assertEquals(1, d.discards)
        assertTrue(closed)
    }

    @Test
    fun aStalledStreamTimesOutAndIsDiscardedOnce() = runBlocking {
        val stream = StalledStream()
        val d = FakeDownload({ stream })
        val r = reader.readResponse(d) as TorrentIntakeReader.Result.Failed
        assertEquals(IntakeRefusal.TIMEOUT, r.reason)
        assertEquals(1, d.discards)
        assertTrue(stream.closed.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun theOverallTimeoutHoldsForAStreamThatKeepsTrickling() = runBlocking {
        val slow = TorrentIntakeReader(Dispatchers.IO, idleTimeoutMs = 5_000, overallTimeoutMs = 400)
        val trickle =
            object : InputStream() {
                @Volatile var open = true

                override fun read(): Int {
                    Thread.sleep(20)
                    if (!open) throw IOException("closed")
                    return 1
                }

                override fun close() {
                    open = false
                }
            }
        val d = FakeDownload({ trickle })
        val r = slow.readResponse(d) as TorrentIntakeReader.Result.Failed
        assertEquals(IntakeRefusal.TIMEOUT, r.reason)
        assertEquals(1, d.discards)
    }

    @Test
    fun aDeclaredLengthOverTheCapIsRefusedBeforeOpening() = runBlocking {
        val d = FakeDownload({ error("must not open") }, contentLength = cap + 1L)
        val r = reader.readResponse(d) as TorrentIntakeReader.Result.Failed
        assertEquals(IntakeRefusal.TOO_LARGE, r.reason)
        assertEquals(0, d.opens)
        assertEquals(1, d.discards)
    }

    @Test
    fun noBodyOrAnEmptyOneFailsAndIsDiscardedOnce() = runBlocking {
        val none = FakeDownload({ null })
        assertEquals(IntakeRefusal.READ_FAILED, (reader.readResponse(none) as TorrentIntakeReader.Result.Failed).reason)
        assertEquals(1, none.discards)
        val empty = FakeDownload({ ByteArrayInputStream(ByteArray(0)) })
        assertEquals(IntakeRefusal.READ_FAILED, (reader.readResponse(empty) as TorrentIntakeReader.Result.Failed).reason)
        assertEquals(1, empty.discards)
    }

    @Test
    fun aSizeQueryThatHangsStillLetsASmallTorrentBeRead() = runBlocking {
        val hung = CountDownLatch(1)
        val logged = mutableListOf<String>()
        val quick =
            TorrentIntakeReader(
                Dispatchers.IO,
                idleTimeoutMs = 300,
                overallTimeoutMs = 5_000,
                providerTimeoutMs = 200,
                log = { synchronized(logged) { logged += it } },
            )
        try {
            val started = System.nanoTime()
            val r =
                quick.readWithDeclaredSize(
                    querySize = {
                        hung.await()
                        cap + 1L
                    },
                    open = { ByteArrayInputStream(ByteArray(100) { 3 }) },
                ) as TorrentIntakeReader.Result.Ok
            val ms = (System.nanoTime() - started) / 1_000_000
            assertEquals(100, r.bytes.size)
            assertTrue("took $ms ms", ms < 3_000)
            assertEquals(listOf(ProviderDeadline.TIMEOUT_LOG), synchronized(logged) { logged.toList() })
        } finally {
            hung.countDown()
        }
    }

    @Test
    fun aDeclaredUriSizeOverTheCapIsRefusedBeforeOpening() = runBlocking {
        val r =
            reader.readWithDeclaredSize(querySize = { cap + 1L }, open = { error("must not open") })
                as TorrentIntakeReader.Result.Failed
        assertEquals(IntakeRefusal.TOO_LARGE, r.reason)
    }

    @Test
    fun aFailedSizeQueryMeansUnknownAndTheCapStillHolds() = runBlocking {
        val r =
            reader.readWithDeclaredSize(
                querySize = { throw SecurityException("no grant") },
                open = { ByteArrayInputStream(ByteArray(cap + 1)) },
            ) as TorrentIntakeReader.Result.Failed
        assertEquals(IntakeRefusal.TOO_LARGE, r.reason)
    }

    @Test
    fun anOpenThatHangsFailsAtTheProviderDeadlineAndALateStreamIsClosed() = runBlocking {
        val logged = mutableListOf<String>()
        val quick =
            TorrentIntakeReader(
                Dispatchers.IO,
                idleTimeoutMs = 60_000,
                overallTimeoutMs = 60_000,
                providerTimeoutMs = 200,
                log = { synchronized(logged) { logged += it } },
            )
        val late = StalledStream()
        val r =
            withTimeout(10_000) {
                quick.readWithDeclaredSize(
                    querySize = { 100L },
                    open = {
                        stuck.await()
                        late
                    },
                )
            } as TorrentIntakeReader.Result.Failed
        assertEquals(IntakeRefusal.READ_FAILED, r.reason)
        assertEquals(listOf(ProviderDeadline.TIMEOUT_LOG), synchronized(logged) { logged.toList() })
        stuck.countDown()
        assertTrue(late.closed.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun opensThatHangDoNotPinTheIoThreads() {
        // One IO thread: an open blocking on it would also starve the watchdog, and these reads would never end.
        val executor = Executors.newSingleThreadExecutor()
        try {
            val single = executor.asCoroutineDispatcher()
            val quick = TorrentIntakeReader(single, idleTimeoutMs = 60_000, overallTimeoutMs = 60_000, providerTimeoutMs = 200)
            runBlocking {
                val results =
                    withTimeout(10_000) {
                        (1..20).map {
                            async {
                                quick.readWithDeclaredSize(querySize = { 100L }, open = { stuck.await(); null })
                            }
                        }.awaitAll()
                    }
                assertTrue(results.all { (it as TorrentIntakeReader.Result.Failed).reason == IntakeRefusal.READ_FAILED })
            }
        } finally {
            stuck.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun cancellingTheReadClosesTheStreamAndDiscardsOnce() = runBlocking {
        val stream = StalledStream()
        val d = FakeDownload({ stream })
        val patient = TorrentIntakeReader(Dispatchers.IO, idleTimeoutMs = 60_000, overallTimeoutMs = 60_000)
        val job = async { patient.readResponse(d) }
        delay(200)
        job.cancel()
        runCatching { job.await() }
        assertTrue(stream.closed.await(10, TimeUnit.SECONDS))
        assertEquals(1, d.discards)
    }
}
