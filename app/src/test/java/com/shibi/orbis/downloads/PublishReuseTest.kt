package com.shibi.orbis.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PublishReuseTest {
    @Test
    fun aFinishedCopyOfTheSameSizeIsReusedAndPendingOnesGo() {
        val d = publishReuse(listOf(PublishRow(1, 10, pending = true), PublishRow(2, 9, pending = false), PublishRow(3, 10, pending = false)), 10)
        assertEquals(3L, d.reuse)
        assertEquals(listOf(1L), d.deletePending)
    }

    @Test
    fun aPendingRowOfTheRightSizeIsNeverReused() {
        val d = publishReuse(listOf(PublishRow(1, 10, pending = true)), 10)
        assertNull(d.reuse)
        assertEquals(listOf(1L), d.deletePending)
        val none = publishReuse(emptyList(), 10)
        assertNull(none.reuse)
        assertEquals(emptyList<Long>(), none.deletePending)
    }
}
