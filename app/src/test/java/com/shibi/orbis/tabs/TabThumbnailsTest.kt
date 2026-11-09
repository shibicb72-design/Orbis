package com.shibi.orbis.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabThumbnailsTest {
    @Test
    fun cropKeepsTheTopOfThePageAtFourByFive() {
        assertEquals(1080 to 1350, thumbnailCrop(1080, 2043))
        // A short (landscape) page keeps its whole height.
        assertEquals(2000 to 900, thumbnailCrop(2000, 900))
        assertNull(thumbnailCrop(0, 100))
    }
}
