package com.shibi.orbis.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RangeStartTest {
    @Test
    fun readsTheFirstByteOfAContentRange() {
        assertEquals(2588672L, rangeStart("bytes 2588672-3145727/3145728"))
        assertEquals(0L, rangeStart("bytes 0-9/10"))
        assertNull(rangeStart(null))
        assertNull(rangeStart("bytes */10"))
    }
}
