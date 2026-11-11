package com.shibi.orbis.torrent

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TorrentSettingsStoreTest {
    /** Key-value prefs in memory, counting the writes. */
    private class MapPrefs(val map: HashMap<String, Any> = HashMap()) : TorrentPrefs {
        var writes = 0

        override fun boolean(key: String): Boolean? = map[key] as? Boolean

        override fun int(key: String): Int? = map[key] as? Int

        override fun float(key: String): Float? = map[key] as? Float

        override fun write(values: Map<String, Any?>) {
            writes++
            for ((k, v) in values) if (v == null) map.remove(k) else map[k] = v
        }
    }

    @Test
    fun absentKeysReadAsTheOwnersDefaults() {
        val s = TorrentSettingsStore(MapPrefs()).settings.value
        assertEquals(TorrentSettings(), s)
        assertEquals(true, s.wifiOnly)
        assertEquals(true, s.stopSeeding)
        assertEquals(true, s.dht && s.pex && s.lsd)
        assertEquals(0, s.downBps)
        assertEquals(0, s.upBps)
        assertNull(s.seedRatioLimit)
    }

    @Test
    fun everyFieldRoundTrips() {
        val prefs = MapPrefs()
        val changed =
            TorrentSettings(
                wifiOnly = false,
                stopSeeding = false,
                dht = false,
                pex = false,
                lsd = false,
                downBps = 512_000,
                upBps = 64_000,
                seedRatioLimit = 1.5f,
            )
        TorrentSettingsStore(prefs).update { changed }
        assertEquals(changed, TorrentSettingsStore(prefs).settings.value)
        assertEquals(
            mapOf<String, Any>(
                "wifi_only" to false,
                "stop_seeding" to false,
                "seed_ratio" to 1.5f,
                "dht" to false,
                "pex" to false,
                "lsd" to false,
                "down_bps" to 512_000,
                "up_bps" to 64_000,
            ),
            prefs.map,
        )
        // No limit removes the key again.
        TorrentSettingsStore(prefs).update { it.copy(seedRatioLimit = null) }
        assertFalse(prefs.map.containsKey("seed_ratio"))
        assertNull(TorrentSettingsStore(prefs).settings.value.seedRatioLimit)
    }

    @Test
    fun valuesAreClampedOnWriteAndOnRead() {
        val prefs = MapPrefs()
        val store = TorrentSettingsStore(prefs)
        store.update { it.copy(downBps = -5, upBps = -1, seedRatioLimit = 0f) }
        assertEquals(0, store.settings.value.downBps)
        assertEquals(0, store.settings.value.upBps)
        assertNull(store.settings.value.seedRatioLimit)
        store.update { it.copy(seedRatioLimit = 250f) }
        assertEquals(100f, store.settings.value.seedRatioLimit)
        store.update { it.copy(seedRatioLimit = 1.26f) }
        assertEquals(1.3f, store.settings.value.seedRatioLimit)
        store.update { it.copy(seedRatioLimit = 0.04f) }
        assertEquals(0.1f, store.settings.value.seedRatioLimit)

        // A file written by hand (or an older app) is clamped when read.
        val raw = MapPrefs(hashMapOf("down_bps" to -100, "up_bps" to -2, "seed_ratio" to 0f))
        val read = TorrentSettingsStore(raw).settings.value
        assertEquals(0, read.downBps)
        assertEquals(0, read.upBps)
        assertNull(read.seedRatioLimit)
        assertEquals(100f, TorrentSettingsStore(MapPrefs(hashMapOf("seed_ratio" to 250f))).settings.value.seedRatioLimit)
        assertNull(TorrentSettingsStore(MapPrefs(hashMapOf("seed_ratio" to Float.NaN))).settings.value.seedRatioLimit)
    }

    @Test
    fun updateWritesAndEmitsOncePerChange() = runTest {
        val prefs = MapPrefs()
        val store = TorrentSettingsStore(prefs)
        val seen = ArrayList<TorrentSettings>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.settings.collect { seen += it } }
        store.update { it.copy(wifiOnly = false) }
        // The same value again, or one that clamps to the current one: no write, no new value.
        store.update { it.copy(wifiOnly = false) }
        store.update { it.copy(downBps = -1) }
        store.update { it.copy(dht = false) }
        assertEquals(2, prefs.writes)
        assertEquals(listOf(TorrentSettings(), TorrentSettings(wifiOnly = false), TorrentSettings(wifiOnly = false, dht = false)), seen)
    }

    @Test
    fun speedConversionsUseKilobytesOfAThousandBytes() {
        assertEquals(512_000, TorrentSettings.kbpsToBps(512))
        assertEquals(512, TorrentSettings.bpsToKbps(512_000))
        assertEquals(0, TorrentSettings.kbpsToBps(0))
        assertEquals(0, TorrentSettings.kbpsToBps(-3))
        assertEquals(1_000_000_000, TorrentSettings.kbpsToBps(TorrentSettings.MAX_KBPS))
        assertEquals(Int.MAX_VALUE, TorrentSettings.kbpsToBps(Int.MAX_VALUE))
        assertEquals(0, TorrentSettings.bpsToKbps(-1))
    }
}
