package com.shibi.orbis.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mozilla.gecko.util.DebugConfig

/**
 * The one test that uses a GeckoView class (accepted deviation, brief priv11-3 ruling 4): the engine's own reader
 * (GeckoRuntime.init calls DebugConfig.fromFile) turns our file into exactly the two prefs as Boolean false.
 * mergeIntoPrefs is not called because it logs through android.util.Log; the field is read directly.
 */
class EngineConfigReaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test(timeout = 10_000)
    fun engineConfigReaderParsesTheFileToTheTwoPrefsOffAndTheTwoReadAheadNumbers() {
        val f = File(tmp.root, EngineStartupPrefs.FILE_NAME)
        assertEquals(EngineStartupPrefs.FileState.WRITTEN, EngineStartupPrefs.ensureFile(f))
        // SnakeYAML reads DebugConfig's fields directly only on the Android runtime, which it detects by this property
        // (as GeckoView's own DebugConfigTest does). On a plain JVM it fails before reading a value.
        val runtimeName = System.setProperty("java.runtime.name", "Android Runtime")
        val config =
            try {
                DebugConfig.fromFile(f)
            } finally {
                if (runtimeName == null) System.clearProperty("java.runtime.name") else System.setProperty("java.runtime.name", runtimeName)
            }
        val prefs = DebugConfig::class.java.getDeclaredField("prefs").apply { isAccessible = true }.get(config)
        assertEquals(
            mapOf(
                "network.connectivity-service.enabled" to java.lang.Boolean.FALSE,
                "network.captive-portal-service.enabled" to java.lang.Boolean.FALSE,
                "media.cache_readahead_limit" to Integer.valueOf(300),
                "media.cache_readahead_limit.cellular" to Integer.valueOf(300),
            ),
            prefs,
        )
    }
}
