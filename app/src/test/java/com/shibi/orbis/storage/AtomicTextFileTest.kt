package com.shibi.orbis.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicTextFileTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun readOfAMissingFileIsNull() {
        assertNull(AtomicTextFile(File(tmp.root, "none.json")).read())
    }

    @Test
    fun writeThenReadRoundTripsUnicodeAndCreatesTheDirectory() {
        val file = File(tmp.root, "sub/dir/data.json")
        val atomic = AtomicTextFile(file)
        atomic.write("héllo 世界 😀")
        assertEquals("héllo 世界 😀", atomic.read())
        atomic.write("second")
        assertEquals("second", atomic.read())
        // The temp file is gone after the rename.
        assertFalse(File(tmp.root, "sub/dir/data.json.tmp").exists())
    }

    @Test
    fun setAsideMovesTheFileAndKeepsItsContent() {
        val file = File(tmp.root, "data.json")
        val atomic = AtomicTextFile(file)
        atomic.write("broken")
        assertTrue(atomic.setAside())
        assertNull(atomic.read())
        val corrupt = File(tmp.root, "data.json.corrupt")
        assertEquals("broken", corrupt.readText())
        // Writing afterwards does not touch the set-aside copy.
        atomic.write("fresh")
        assertEquals("fresh", atomic.read())
        assertEquals("broken", corrupt.readText())
    }

    @Test
    fun setAsideReplacesAnOlderCorruptCopy() {
        val file = File(tmp.root, "data.json")
        val atomic = AtomicTextFile(file)
        atomic.write("one")
        atomic.setAside()
        atomic.write("two")
        atomic.setAside()
        assertEquals("two", File(tmp.root, "data.json.corrupt").readText())
    }

    @Test
    fun setAsideNeverThrowsWhenThereIsNothingToMove() {
        val atomic = AtomicTextFile(File(tmp.root, "absent.json"))
        atomic.setAside()
        assertNull(atomic.read())
    }
}
