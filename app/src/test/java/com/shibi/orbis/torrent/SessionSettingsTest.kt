package com.shibi.orbis.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSettingsTest {
    private val discovery = SessionDiscovery(dht = true, lsd = false)

    @Test
    fun theSettingsOrbisMakesAskAllTrackersAndAllTiersAtOnce() {
        val settings = sessionSettings(EngineSettings(listenPort = 51413), discovery).toMap()
        assertEquals(true, settings[SessionSetting.ANNOUNCE_TO_ALL_TRACKERS])
        assertEquals(true, settings[SessionSetting.ANNOUNCE_TO_ALL_TIERS])
    }

    @Test
    fun everyOtherSettingIsAsBefore() {
        val s = EngineSettings(listenPort = 51413, downBps = -5, upBps = 2_000, stopSeeding = true, maxActive = 3)
        assertEquals(
            listOf(
                SessionSetting.LISTEN_INTERFACES to "0.0.0.0:51413,[::]:51413",
                SessionSetting.ENABLE_UPNP to false,
                SessionSetting.ENABLE_NATPMP to false,
                SessionSetting.SSRF_MITIGATION to true,
                SessionSetting.ENABLE_DHT to true,
                SessionSetting.ENABLE_LSD to false,
                SessionSetting.MAX_METADATA_SIZE to TorrentLimits.MAX_METADATA_BYTES,
                SessionSetting.DOWNLOAD_RATE_LIMIT to 0,
                SessionSetting.UPLOAD_RATE_LIMIT to 2_000,
                SessionSetting.ACTIVE_DOWNLOADS to 3,
                SessionSetting.ACTIVE_SEEDS to 0,
                SessionSetting.STOP_TRACKER_TIMEOUT to 2,
                SessionSetting.ANNOUNCE_TO_ALL_TRACKERS to true,
                SessionSetting.ANNOUNCE_TO_ALL_TIERS to true,
            ),
            sessionSettings(s, discovery),
        )
        // Seeding allowed: active_seeds stays libtorrent's.
        val seeding = sessionSettings(s.copy(stopSeeding = false), SessionDiscovery(dht = false, lsd = true)).toMap()
        assertEquals(null, seeding[SessionSetting.ACTIVE_SEEDS])
        assertEquals(false, seeding[SessionSetting.ENABLE_DHT])
        assertEquals(true, seeding[SessionSetting.ENABLE_LSD])
    }

    @Test
    fun eachValueHasItsSettingsType() {
        for ((key, value) in sessionSettings(EngineSettings(listenPort = 1), discovery)) {
            assertTrue("$key carries ${value::class}", key.type.isInstance(value))
        }
    }
}
