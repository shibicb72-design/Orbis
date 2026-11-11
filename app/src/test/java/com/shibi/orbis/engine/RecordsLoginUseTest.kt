package com.shibi.orbis.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A login used while any private session exists leaves no "last used" record. */
class RecordsLoginUseTest {
    @Test
    fun recordsOnlyWithNoPrivateSession() {
        assertTrue(recordsLoginUse(0))
        assertFalse(recordsLoginUse(1))
        assertFalse(recordsLoginUse(7))
    }
}
