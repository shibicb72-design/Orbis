package com.shibi.orbis.ui.passwords

import com.shibi.orbis.engine.ProviderPool
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The password CSV import and export never wait on a hung provider without a deadline. */
class PickedFileTest {
    private val pool = ProviderPool()

    /** Stuck provider calls wait on this; released after each test so no thread outlives it. */
    private val stuck = CountDownLatch(1)

    @After
    fun release() {
        stuck.countDown()
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (pool.aliveThreads() > 0 && System.nanoTime() < until) Thread.sleep(10)
        assertEquals(0, pool.aliveThreads())
    }

    /** A provider stream whose read blocks until it is closed (as a pipe from a hung provider does). */
    private inner class HangingInput : InputStream() {
        val closed = CountDownLatch(1)

        override fun read(): Int {
            closed.await()
            throw IOException("closed")
        }

        override fun close() = closed.countDown()
    }

    private inner class HangingOutput : OutputStream() {
        val closed = CountDownLatch(1)
        val writing = CountDownLatch(1)

        override fun write(b: Int) {
            writing.countDown()
            closed.await()
            throw IOException("closed")
        }

        override fun close() = closed.countDown()
    }

    private class TrackedInput(bytes: ByteArray) : java.io.ByteArrayInputStream(bytes) {
        val closed = CountDownLatch(1)

        override fun close() = closed.countDown()
    }

    private class TrackedOutput : ByteArrayOutputStream() {
        val closed = CountDownLatch(1)

        override fun close() = closed.countDown()
    }

    @Test
    fun readsAPickedFile() = runBlocking {
        val input = TrackedInput("url,username,password\n".toByteArray())
        assertEquals("url,username,password\n", readPickedText({ input }, cap = 1024, pool = pool))
        assertTrue("closed", input.closed.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun aFileOverTheCapIsRefused() = runBlocking {
        val input = TrackedInput(ByteArray(2048))
        assertNull(readPickedText({ input }, cap = 1024, pool = pool))
        assertTrue("closed", input.closed.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun anOpenThatHangsGivesUp() = runBlocking {
        val started = System.nanoTime()
        assertNull(readPickedText({ stuck.await(); null }, cap = 1024, openTimeoutMs = 200, pool = pool))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5_000)
    }

    @Test
    fun aReadThatHangsGivesUpAndClosesTheStream() = runBlocking {
        val input = HangingInput()
        assertNull(readPickedText({ input }, cap = 1024, transferTimeoutMs = 200, pool = pool))
        assertTrue("closed, which wakes the blocked read", input.closed.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun aFailingOpenGivesNull() = runBlocking {
        assertNull(readPickedText({ throw SecurityException("no grant") }, cap = 1024, pool = pool))
        assertNull(readPickedText({ null }, cap = 1024, pool = pool))
    }

    @Test
    fun writesAPickedFileAndClosesIt() = runBlocking {
        val out = TrackedOutput()
        assertTrue(writePickedBytes({ out }, "a,b\n".toByteArray(), pool = pool))
        assertArrayEquals("a,b\n".toByteArray(), out.toByteArray())
        assertTrue("closed", out.closed.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun aWriteThatHangsFailsAndClosesTheStream() = runBlocking {
        val out = HangingOutput()
        assertFalse(writePickedBytes({ out }, "a,b\n".toByteArray(), transferTimeoutMs = 200, pool = pool))
        assertTrue("closed", out.closed.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun aCancelledWriteClosesTheStream() = runBlocking {
        val out = HangingOutput()
        val writing = async(start = CoroutineStart.UNDISPATCHED) { writePickedBytes({ out }, "a,b".toByteArray(), pool = pool) }
        // Default (long) deadlines: only the cancel can end this write.
        withTimeout(10_000) { while (out.writing.count > 0) delay(10) }
        writing.cancelAndJoin()
        assertTrue("closed after the cancel", out.closed.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun anExportOpenThatHangsOrFailsGivesFalse() = runBlocking {
        assertFalse(writePickedBytes({ stuck.await(); null }, ByteArray(1), openTimeoutMs = 200, pool = pool))
        assertFalse(writePickedBytes({ throw SecurityException("no grant") }, ByteArray(1), pool = pool))
    }
}
