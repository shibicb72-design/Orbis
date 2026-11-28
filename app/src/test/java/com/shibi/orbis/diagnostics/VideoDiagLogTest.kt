package com.shibi.orbis.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoDiagLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun overTheCapTheOldestEntriesGoAndTheHeaderStays() {
        val file = MemoryDiagLogFile()
        val log = VideoDiagLog(file, io = { it.run() }, capBytes = 1_000)
        log.setHeader(listOf("Orbis video diagnostics log", "Tracking protection: strict"))
        repeat(200) { log.append("entry %03d ..........".format(it)) }
        assertTrue(log.sizeBytes <= 1_000)
        val lines = log.lines()
        assertEquals("# Orbis video diagnostics log", lines[0])
        assertEquals("# Tracking protection: strict", lines[1])
        assertTrue(lines.last().startsWith("entry 199"))
        assertFalse(lines.any { it.startsWith("entry 000") })
        // Oldest first: what is left is one unbroken run up to the newest.
        val numbers = lines.drop(2).map { it.substring(6, 9).toInt() }
        assertEquals((numbers.first()..199).toList(), numbers)
        // The file holds exactly what the screen shows.
        assertEquals(log.text() + "\n", file.text)
    }

    @Test
    fun anEntryCannotPassForAHeaderLine() {
        val log = VideoDiagLog(MemoryDiagLogFile(), io = { it.run() })
        log.append("# not a header\nsecond")
        assertEquals(listOf(" # not a header second"), log.lines())
    }

    @Test
    fun theFileIsReadBackAndDeletedWithClear() {
        val f = File(tmp.root, FileDiagLogFile.FILE_NAME)
        val log = VideoDiagLog(FileDiagLogFile(f), io = { it.run() })
        log.setHeader(listOf("head"))
        log.append("one")
        log.append("two")
        val again = VideoDiagLog(FileDiagLogFile(f), io = { it.run() })
        again.load()
        assertEquals(listOf("# head", "one", "two"), again.lines())
        assertEquals(log.sizeBytes, again.sizeBytes)
        again.clear()
        assertFalse(f.exists())
        assertTrue(again.isEmpty)
        assertNull(FileDiagLogFile(f).read())
    }
}
