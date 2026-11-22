package com.shibi.orbis.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EngineStartupPrefsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test(timeout = 10_000)
    fun contentIsExactlyTheTwoServicePrefsOff() {
        assertEquals(
            "prefs:\n" +
                "  \"network.connectivity-service.enabled\": false\n" +
                "  \"network.captive-portal-service.enabled\": false\n" +
                "  \"media.cache_readahead_limit\": 300\n" +
                "  \"media.cache_readahead_limit.cellular\": 300\n",
            EngineStartupPrefs.CONTENT,
        )
    }

    @Test(timeout = 10_000)
    fun offListIsExactlyTheTwoServicePrefs() {
        assertEquals(
            listOf("network.connectivity-service.enabled", "network.captive-portal-service.enabled"),
            EngineStartupPrefs.OFF,
        )
    }

    @Test(timeout = 10_000)
    fun numbersAreExactlyTheTwoReadAheadLimitsOf300Seconds() {
        assertEquals(
            listOf(
                EngineStartupPrefs.NumberPref("media.cache_readahead_limit", 300),
                EngineStartupPrefs.NumberPref("media.cache_readahead_limit.cellular", 300),
            ),
            EngineStartupPrefs.NUMBERS,
        )
    }

    @Test(timeout = 10_000)
    fun numbersAreWrittenAsBareNumbersAndNeverInTheOffList() {
        val lines = EngineStartupPrefs.CONTENT.lines()
        for (pref in EngineStartupPrefs.NUMBERS) {
            assertEquals(1, lines.count { it == "  \"${pref.name}\": ${pref.value}" })
            assertFalse(pref.name in EngineStartupPrefs.OFF)
        }
        // A quoted number would be read as a string by the engine's reader.
        assertFalse(EngineStartupPrefs.CONTENT.contains(": \""))
        assertFalse(EngineStartupPrefs.CONTENT.contains(": '"))
    }

    @Test(timeout = 10_000)
    fun contentHoldsNoAddressAndNoOtherValue() {
        val c = EngineStartupPrefs.CONTENT
        for (word in listOf("http", "://", ".com", ".org", "portal-detection", "url", "domain", "true")) {
            assertFalse("content must not hold '$word'", c.contains(word, ignoreCase = true))
        }
        assertEquals(EngineStartupPrefs.OFF.size + EngineStartupPrefs.NUMBERS.size + 1, c.lines().filter { it.isNotEmpty() }.size)
    }

    @Test(timeout = 10_000)
    fun firstCallWritesTheFile() {
        val f = File(tmp.root, EngineStartupPrefs.FILE_NAME)
        assertEquals(EngineStartupPrefs.FileState.WRITTEN, EngineStartupPrefs.ensureFile(f))
        assertEquals(EngineStartupPrefs.CONTENT, f.readText())
        assertFalse(File(tmp.root, "${f.name}.tmp").exists())
    }

    @Test(timeout = 10_000)
    fun sameContentIsNotWrittenAgain() {
        val f = File(tmp.root, EngineStartupPrefs.FILE_NAME)
        EngineStartupPrefs.ensureFile(f)
        // An old time stamp: any rewrite (or replacing rename) would give the file a new one.
        val old = 1_000_000_000_000L
        assertTrue(f.setLastModified(old))
        assertEquals(EngineStartupPrefs.FileState.UNCHANGED, EngineStartupPrefs.ensureFile(f))
        assertEquals(old, f.lastModified())
        assertEquals(EngineStartupPrefs.CONTENT, f.readText())
    }

    @Test(timeout = 10_000)
    fun differentContentIsReplaced() {
        val f = File(tmp.root, EngineStartupPrefs.FILE_NAME)
        f.writeText("prefs:\n  \"network.connectivity-service.enabled\": true\n")
        assertEquals(EngineStartupPrefs.FileState.WRITTEN, EngineStartupPrefs.ensureFile(f))
        assertEquals(EngineStartupPrefs.CONTENT, f.readText())
    }

    @Test(timeout = 10_000)
    fun unwritablePlaceFailsWithoutThrowing() {
        val missingDir = File(tmp.root, "no-such-dir")
        val f = File(missingDir, EngineStartupPrefs.FILE_NAME)
        assertEquals(EngineStartupPrefs.FileState.FAILED, EngineStartupPrefs.ensureFile(f))
        assertFalse(f.exists())
    }

    @Test(timeout = 10_000)
    fun probeOffOnlyWhenBothReadFalse() {
        val c = EngineStartupPrefs.CONNECTIVITY_SERVICE
        val p = EngineStartupPrefs.CAPTIVE_PORTAL_SERVICE
        assertTrue(EngineStartupPrefs.probeOff(mapOf(c to false, p to false)))
        assertFalse(EngineStartupPrefs.probeOff(mapOf(c to true, p to false)))
        assertFalse(EngineStartupPrefs.probeOff(mapOf(c to false, p to true)))
        assertFalse(EngineStartupPrefs.probeOff(mapOf(c to false)))
        assertFalse(EngineStartupPrefs.probeOff(mapOf(p to false)))
        assertFalse(EngineStartupPrefs.probeOff(mapOf(c to false, p to null)))
        assertFalse(EngineStartupPrefs.probeOff(mapOf(c to "false", p to false)))
        assertFalse(EngineStartupPrefs.probeOff(emptyMap()))
    }

    @Test(timeout = 10_000)
    fun logLinesHaveTheAgreedTextAndNoAddress() {
        assertEquals("engine connectivity probe off: true", EngineStartupPrefs.logLine(EngineStartupPrefs.ProbeState.OFF_AT_START))
        assertEquals("engine connectivity probe off: late", EngineStartupPrefs.logLine(EngineStartupPrefs.ProbeState.OFF_LATE))
        assertEquals("engine connectivity probe off: false", EngineStartupPrefs.logLine(EngineStartupPrefs.ProbeState.NOT_OFF))
        assertEquals("engine start-up file skipped after a failed start", EngineStartupPrefs.SKIPPED_LINE)
    }

    // ---- safety net one: the start marker ----

    @Test(timeout = 10_000)
    fun noMarkerUsesTheFile() {
        assertTrue(EngineStartupPrefs.useStartupFile(null, 29))
    }

    @Test(timeout = 10_000)
    fun markerOfThisVersionSkipsTheFile() {
        assertFalse(EngineStartupPrefs.useStartupFile("29", 29))
        assertFalse(EngineStartupPrefs.useStartupFile(EngineStartupPrefs.markerContent(29), 29))
        assertFalse(EngineStartupPrefs.useStartupFile("29\n", 29))
    }

    @Test(timeout = 10_000)
    fun markerOfAnotherVersionUsesTheFileAgain() {
        assertTrue(EngineStartupPrefs.useStartupFile("28", 29))
        assertTrue(EngineStartupPrefs.useStartupFile("30", 29))
    }

    @Test(timeout = 10_000)
    fun unreadableMarkerCountsAsThisVersion() {
        assertFalse(EngineStartupPrefs.useStartupFile("", 29))
        assertFalse(EngineStartupPrefs.useStartupFile("garbage", 29))
    }

    @Test(timeout = 10_000)
    fun markerRoundTripsThroughTheFile() {
        val m = File(tmp.root, EngineStartupPrefs.MARKER_NAME)
        assertNull(EngineStartupPrefs.readMarker(m))
        assertTrue(EngineStartupPrefs.writeMarker(m, 29))
        assertFalse(EngineStartupPrefs.useStartupFile(EngineStartupPrefs.readMarker(m), 29))
        assertTrue(EngineStartupPrefs.useStartupFile(EngineStartupPrefs.readMarker(m), 30))
        assertFalse(EngineStartupPrefs.writeMarker(File(File(tmp.root, "no-such-dir"), EngineStartupPrefs.MARKER_NAME), 29))
    }

    // ---- read-back and safety net two: the late set ----

    private val bothOff =
        mapOf<String, Any?>(EngineStartupPrefs.CONNECTIVITY_SERVICE to false, EngineStartupPrefs.CAPTIVE_PORTAL_SERVICE to false)

    @Test(timeout = 10_000)
    fun bothOffAtReadBackLogsTrueAndSetsNothing() {
        val lines = mutableListOf<String>()
        var sets = 0
        EngineStartupPrefs.afterReadBack(bothOff, { sets++; it(true) }, { lines += it })
        assertEquals(0, sets)
        assertEquals(listOf("engine connectivity probe off: true"), lines)
    }

    @Test(timeout = 10_000)
    fun eitherPrefNotOffIsSetLateAndLogsLate() {
        for (values in listOf(
            bothOff + (EngineStartupPrefs.CONNECTIVITY_SERVICE to true),
            bothOff + (EngineStartupPrefs.CAPTIVE_PORTAL_SERVICE to true),
            mapOf(EngineStartupPrefs.CONNECTIVITY_SERVICE to false),
            null,
        )) {
            val lines = mutableListOf<String>()
            var sets = 0
            EngineStartupPrefs.afterReadBack(values, { sets++; it(true) }, { lines += it })
            assertEquals("read-back $values", 1, sets)
            assertEquals(listOf("engine connectivity probe off: late"), lines)
        }
    }

    @Test(timeout = 10_000)
    fun failedLateSetLogsFalse() {
        val lines = mutableListOf<String>()
        EngineStartupPrefs.afterReadBack(null, { it(false) }, { lines += it })
        assertEquals(listOf("engine connectivity probe off: false"), lines)
    }
}