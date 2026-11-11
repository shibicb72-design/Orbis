package com.shibi.orbis.torrent

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(true, s.dht && s.pex)
        // OD-2: local peers off by default.
        assertFalse(s.lsd)
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
                "schema" to 2,
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
    fun anOlderFilesLocalPeersOnIsTheOldDefaultAndTurnsOff() {
        // Before schema 2 every change wrote every key, so "lsd" = true there was the old default, not a choice.
        val old = MapPrefs(hashMapOf("wifi_only" to false, "dht" to true, "pex" to true, "lsd" to true))
        val s = TorrentSettingsStore(old).settings.value
        assertFalse(s.lsd)
        assertFalse(s.wifiOnly)
        assertFalse(old.map.containsKey("lsd"))
        assertEquals(2, old.map["schema"])
        // Migrated once: turned on again by the user, it stays on.
        TorrentSettingsStore(old).update { it.copy(lsd = true) }
        assertTrue(TorrentSettingsStore(old).settings.value.lsd)
        assertTrue(TorrentSettingsStore(old).settings.value.lsd)
    }

    @Test
    fun anOlderFilesLocalPeersOffWasAChoiceAndStays() {
        val old = MapPrefs(hashMapOf("lsd" to false, "dht" to false))
        assertEquals(TorrentSettings(dht = false, lsd = false), TorrentSettingsStore(old).settings.value)
        assertEquals(0, old.writes)
        // A fresh install writes nothing either until something changes.
        val fresh = MapPrefs()
        TorrentSettingsStore(fresh)
        assertEquals(0, fresh.writes)
        // Turned on in this version, it survives the next start (the schema is written with it).
        TorrentSettingsStore(fresh).update { it.copy(lsd = true) }
        assertTrue(TorrentSettingsStore(fresh).settings.value.lsd)
    }

    @Test
    fun theListenPortIsPickedOnceInTheDynamicRangeAndKept() {
        val prefs = MapPrefs()
        val store = TorrentSettingsStore(prefs)
        assertFalse("nothing before first use", prefs.map.containsKey("listen_port"))
        val port = store.listenPort()
        assertTrue(port in 49152..65535)
        assertEquals(port, prefs.map["listen_port"])
        assertEquals(port, store.listenPort())
        assertEquals(port, TorrentSettingsStore(prefs).listenPort())
        assertEquals(1, prefs.writes)
        // Settings changes leave it alone.
        store.update { it.copy(wifiOnly = false) }
        assertEquals(port, TorrentSettingsStore(prefs).listenPort())
        // The ends of the range are reachable and nothing outside it is picked.
        assertEquals(49152, TorrentSettingsStore(MapPrefs(), FixedRandom(0)).listenPort())
        assertEquals(65535, TorrentSettingsStore(MapPrefs(), FixedRandom(65535 - 49152)).listenPort())
    }

    @Test
    fun aStoredListenPortOutsideTheRangeIsReplaced() {
        for (bad in listOf(0, -1, 6881, 49151, 65536, Int.MAX_VALUE)) {
            val prefs = MapPrefs(hashMapOf("listen_port" to bad))
            val port = TorrentSettingsStore(prefs, FixedRandom(7)).listenPort()
            assertEquals(49152 + 7, port)
            assertEquals(port, prefs.map["listen_port"])
            assertEquals(port, TorrentSettingsStore(prefs).listenPort())
        }
        // A value of the wrong type reads as absent and is replaced too.
        val wrongType = MapPrefs(hashMapOf("listen_port" to "6881"))
        assertEquals(49152 + 7, TorrentSettingsStore(wrongType, FixedRandom(7)).listenPort())
        // A good one is kept.
        assertEquals(50000, TorrentSettingsStore(MapPrefs(hashMapOf("listen_port" to 50000)), FixedRandom(7)).listenPort())
    }

    @Test
    fun theEngineSettingsCarryThePortAndTheChoices() {
        val e = TorrentSettings(dht = false, lsd = true, downBps = 1000).engine(50123)
        assertEquals(50123, e.listenPort)
        assertFalse(e.dht)
        assertTrue(e.pex)
        assertTrue(e.lsd)
        assertEquals(1000, e.downBps)
        assertFalse(TorrentSettings().engine(50123).lsd)
    }

    @Test
    fun aPrivateTorrentUsesTrackersOnly() {
        assertEquals(TorrentDiscovery(dht = false, pex = false, lsd = false), TorrentDiscovery.of(dht = true, pex = true, lsd = true, trackersOnly = true))
        assertEquals(TorrentDiscovery(dht = true, pex = true, lsd = true), TorrentDiscovery.of(dht = true, pex = true, lsd = true, trackersOnly = false))
        assertEquals(TorrentDiscovery(dht = false, pex = false, lsd = false), TorrentDiscovery.of(dht = false, pex = false, lsd = false, trackersOnly = false))
    }

    /** nextInt(bound) always gives [value]. */
    private class FixedRandom(private val value: Int) : java.util.Random() {
        override fun nextInt(bound: Int): Int = value.also { check(it in 0 until bound) }
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
