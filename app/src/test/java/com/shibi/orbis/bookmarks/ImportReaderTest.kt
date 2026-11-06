package com.shibi.orbis.bookmarks

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ImportReaderTest {
    private class CountingStream(private val total: Int) : InputStream() {
        var served = 0L

        override fun read(): Int = if (served < total) { served++; 1 } else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served >= total) return -1
            val n = minOf(len.toLong(), total - served).toInt()
            served += n
            return n
        }
    }

    @Test
    fun readsEverythingUnderTheCap() {
        val data = ByteArray(100_000) { (it % 251).toByte() }
        assertArrayEquals(data, readCapped(ByteArrayInputStream(data), 200_000))
        assertNotNull(readCapped(ByteArrayInputStream(ByteArray(0)), 10))
    }

    @Test
    fun exactlyTheCapIsAccepted() {
        assertEquals(1000, readCapped(ByteArrayInputStream(ByteArray(1000)), 1000)!!.size)
    }

    @Test
    fun oneByteOverIsRefused() {
        assertNull(readCapped(ByteArrayInputStream(ByteArray(1001)), 1000))
    }

    @Test
    fun neverReadsMoreThanCapPlusOne() {
        val stream = CountingStream(Int.MAX_VALUE)
        assertNull(readCapped(stream, 50_000))
        assertEquals(50_001L, stream.served)
    }
}
