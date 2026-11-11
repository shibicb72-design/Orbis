package com.shibi.orbis.engine

import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderDeadlineTest {
    /** Stuck provider blocks wait on this; released after each test so no thread outlives it. */
    private val stuck = CountDownLatch(1)

    private val logged = Collections.synchronizedList(mutableListOf<String>())

    private fun hang(): Nothing {
        stuck.await()
        throw IllegalStateException("released")
    }

    @After
    fun releaseStuckThreads() {
        stuck.countDown()
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (ProviderDeadline.aliveThreads() > 0 && System.nanoTime() < until) Thread.sleep(10)
        assertEquals(0, ProviderDeadline.aliveThreads())
    }

    private class TrackedStream : ByteArrayInputStream(ByteArray(4)) {
        val closed = CountDownLatch(1)

        override fun close() {
            closed.countDown()
        }
    }

    @Test
    fun aNormalValueIsReturned() = runBlocking {
        assertEquals("application/x-bittorrent", providerCall { "application/x-bittorrent" })
        assertEquals(0, logged.size)
    }

    @Test
    fun theBlockRunsOnANamedDaemonThread() = runBlocking {
        val thread = providerCall { Thread.currentThread() }!!
        assertEquals(ProviderDeadline.THREAD_NAME, thread.name)
        assertTrue(thread.isDaemon)
    }

    @Test
    fun anExceptionGivesNull() = runBlocking {
        assertNull(providerCall<String>(log = { logged += it }) { throw SecurityException("no grant") })
        assertNull(providerCall<String> { throw OutOfMemoryError() })
        assertEquals(0, logged.size)
    }

    @Test
    fun aBlockThatNeverReturnsGivesNullAfterTheTimeoutAndLogsOnce() = runBlocking {
        val started = System.nanoTime()
        assertNull(providerCall<String>(timeoutMs = 200, log = { logged += it }) { hang() })
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $ms ms", ms >= 150)
        assertEquals(listOf(ProviderDeadline.TIMEOUT_LOG), logged.toList())
    }

    @Test
    fun stuckCallsDoNotHoldADispatcherThread() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val single = executor.asCoroutineDispatcher()
            runBlocking {
                val started = System.nanoTime()
                val results =
                    withContext(single) {
                        (1..100).map { async { providerCall<String>(timeoutMs = 1_000) { hang() } } }.awaitAll()
                    }
                val ms = (System.nanoTime() - started) / 1_000_000
                assertEquals(100, results.size)
                assertTrue(results.all { it == null })
                // Blocking the one thread would take at least 8 x 1 s for the eight calls that got a thread.
                assertTrue("took $ms ms", ms < 4_000)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun theNinthConcurrentStuckCallReturnsNullAtOnce() = runBlocking {
        val calls = (1..ProviderDeadline.MAX_THREADS).map { async { providerCall<String>(timeoutMs = 10_000) { hang() } } }
        withTimeout(5_000) { while (ProviderDeadline.aliveThreads() < ProviderDeadline.MAX_THREADS) delay(5) }
        val ran = AtomicBoolean(false)
        // "At once" is shown by the block never running, not by a wall clock, which a busy CI machine can stretch.
        val ninth = providerCall(timeoutMs = 10_000) { ran.set(true); "value" }
        assertNull(ninth)
        assertFalse(ran.get())
        assertEquals(ProviderDeadline.MAX_THREADS, ProviderDeadline.aliveThreads())
        stuck.countDown()
        assertTrue(calls.awaitAll().all { it == null })
    }

    @Test
    fun poolsAreSeparate() = runBlocking {
        val calls = (1..ProviderDeadline.MAX_THREADS).map { async { providerCall<String>(timeoutMs = 10_000) { hang() } } }
        withTimeout(5_000) { while (ProviderDeadline.intents.aliveThreads() < ProviderDeadline.MAX_THREADS) delay(5) }
        assertNull(providerCall { "intent" })
        assertEquals("picked", providerCall(pool = ProviderDeadline.picked) { "picked" })
        val own = ProviderPool(max = 1)
        assertEquals("own", providerCall(pool = own) { "own" })
        assertEquals(0, own.aliveThreads())
        stuck.countDown()
        assertTrue(calls.awaitAll().all { it == null })
    }

    @Test
    fun aLateStreamIsClosed() = runBlocking {
        val stream = TrackedStream()
        val late = CountDownLatch(1)
        val result =
            providerCall(timeoutMs = 100) {
                late.await()
                stream
            }
        assertNull(result)
        late.countDown()
        assertTrue(stream.closed.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun aThreadThatCannotBeBuiltGivesNullAndReleasesItsSlot() = runBlocking {
        val ran = AtomicBoolean(false)
        assertNull(providerCallOn(1_000, {}, { _, _ -> throw OutOfMemoryError() }) { ran.set(true); "value" })
        assertEquals(0, ProviderDeadline.aliveThreads())
        val unstartable = { body: Runnable, name: String ->
            object : Thread(body, name) {
                override fun start(): Unit = throw OutOfMemoryError("pthread_create")
            }
        }
        assertNull(providerCallOn(1_000, {}, unstartable) { ran.set(true); "value" })
        assertEquals(0, ProviderDeadline.aliveThreads())
        assertFalse(ran.get())
        // Every slot is still there: MAX_THREADS calls can be in flight again.
        val calls = (1..ProviderDeadline.MAX_THREADS).map { async { providerCall<String>(timeoutMs = 10_000) { hang() } } }
        withTimeout(5_000) { while (ProviderDeadline.aliveThreads() < ProviderDeadline.MAX_THREADS) delay(5) }
        stuck.countDown()
        assertTrue(calls.awaitAll().all { it == null })
    }

    @Test
    fun aStreamDeliveredInTimeIsNotClosed() = runBlocking {
        val stream = TrackedStream()
        assertTrue(providerCall { stream } === stream)
        assertFalse(stream.closed.await(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun cancellingTheCallerClosesALateStream() = runBlocking {
        val stream = TrackedStream()
        val late = CountDownLatch(1)
        val job =
            async {
                providerCall(timeoutMs = 10_000) {
                    late.await()
                    stream
                }
            }
        withTimeout(5_000) { while (ProviderDeadline.aliveThreads() < 1) delay(5) }
        job.cancel()
        runCatching { job.await() }
        late.countDown()
        assertTrue(stream.closed.await(10, TimeUnit.SECONDS))
    }
}
