package com.shibi.orbis.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                "  \"network.captive-portal-service.enabled\": false\n",
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
    fun contentHoldsNoAddressAndNoOtherValue() {
        val c = EngineStartupPrefs.CONTENT
        for (word in listOf("http", "://", ".com", ".org", "portal-detection", "url", "domain", "true")) {
            assertFalse("content must not hold '$word'", c.contains(word, ignoreCase = true))
        }
        assertEquals(EngineStartupPrefs.OFF.size + 1, c.lines().filter { it.isNotEmpty() }.size)
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

    /**
     * The engine's own reader (GeckoRuntime.init calls DebugConfig.fromFile, then mergeIntoPrefs, which is not called
     * here because it logs through android.util.Log) turns the file into exactly the two prefs as Boolean false.
     */
    @Test(timeout = 10_000)
    fun engineConfigReaderParsesTheFileToTheTwoPrefsOff() {
        val f = File(tmp.root, EngineStartupPrefs.FILE_NAME)
        EngineStartupPrefs.ensureFile(f)
        // SnakeYAML reads DebugConfig's fields directly only on the Android runtime, which it detects by this property
        // (as GeckoView's own DebugConfigTest does). On a plain JVM it fails before reading a value.
        val runtimeName = System.setProperty("java.runtime.name", "Android Runtime")
        val config =
            try {
                org.mozilla.gecko.util.DebugConfig.fromFile(f)
            } finally {
                if (runtimeName == null) System.clearProperty("java.runtime.name") else System.setProperty("java.runtime.name", runtimeName)
            }
        val field = org.mozilla.gecko.util.DebugConfig::class.java.getDeclaredField("prefs").apply { isAccessible = true }
        assertEquals(
            mapOf(
                "network.connectivity-service.enabled" to java.lang.Boolean.FALSE,
                "network.captive-portal-service.enabled" to java.lang.Boolean.FALSE,
            ),
            field.get(config),
        )
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
    fun logLineHasTheAgreedTextAndNoAddress() {
        assertEquals("engine connectivity probe off: true", EngineStartupPrefs.logLine(true))
        assertEquals("engine connectivity probe off: false", EngineStartupPrefs.logLine(false))
    }
}
