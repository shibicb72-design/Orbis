package com.shibi.orbis.debug

import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DebugLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun log_appendsLineAndClearEmpties() {
        val clock = Clock.fixed(Instant.parse("2026-11-05T22:00:00Z"), ZoneOffset.UTC)
        val log = DebugLog(tmp.root, clock)
        log.log("Test", "hello")
        assertEquals(1, log.lines().size)
        assertTrue(log.lines()[0].contains("Test hello"))
        log.clear()
        assertTrue(log.lines().isEmpty())
        assertTrue(File(tmp.root, DebugLog.FILE_NAME).exists())
    }
}