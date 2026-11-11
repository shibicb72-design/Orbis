package com.shibi.orbis.cast

import java.security.KeyPairGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S-01: each Chromecast's deviceauth identity key (SPKI) is pinned on first use and must match afterwards. */
class CastPinsTest {
    private class MemoryStore : CastPinStore {
        val map = HashMap<String, String>()

        override fun get(key: String): String? = map[key]

        override fun put(key: String, value: String) {
            map[key] = value
        }

        override fun remove(key: String) {
            map.remove(key)
        }
    }

    private val store = MemoryStore()
    private val pins = CastPins(store)

    @Test
    fun theFirstKeyIsPinnedAndMatchesAfterwards() {
        assertEquals(CastPins.Verdict.PINNED, pins.check("tv-1", KEY_A))
        assertEquals(1, store.map.size)
        assertEquals(CastPins.Verdict.MATCHED, pins.check("tv-1", KEY_A))
        assertEquals(CastPins.Verdict.MATCHED, pins.check("tv-1", KEY_A))
    }

    @Test
    fun aDifferentKeyForTheSameDeviceIsReportedAndThePinKept() {
        pins.check("tv-1", KEY_A)
        val pinned = store.map.toMap()
        assertEquals(CastPins.Verdict.CHANGED, pins.check("tv-1", KEY_B))
        assertEquals(CastPins.Verdict.CHANGED, pins.check("tv-1", KEY_B))
        assertEquals(pinned, store.map)
        assertEquals(CastPins.Verdict.MATCHED, pins.check("tv-1", KEY_A))
    }

    @Test
    fun devicesArePinnedIndependently() {
        assertEquals(CastPins.Verdict.PINNED, pins.check("tv-1", KEY_A))
        assertEquals(CastPins.Verdict.PINNED, pins.check("tv-2", KEY_B))
        assertEquals(CastPins.Verdict.MATCHED, pins.check("tv-1", KEY_A))
        assertEquals(CastPins.Verdict.MATCHED, pins.check("tv-2", KEY_B))
        assertEquals(CastPins.Verdict.CHANGED, pins.check("tv-2", KEY_A))
    }

    @Test
    fun forgetDropsThePinSoTheNextKeyIsPinnedAnew() {
        pins.check("tv-1", KEY_A)
        pins.check("tv-2", KEY_A)
        pins.forget("tv-1")
        assertEquals(1, store.map.size)
        assertEquals(CastPins.Verdict.PINNED, pins.check("tv-1", KEY_B))
        assertEquals(CastPins.Verdict.MATCHED, pins.check("tv-1", KEY_B))
        assertEquals(CastPins.Verdict.MATCHED, pins.check("tv-2", KEY_A))
    }

    @Test
    fun theStoreHoldsNeitherTheDeviceIdNorTheRawKey() {
        pins.check("tv-1", KEY_A)
        val (key, value) = store.map.entries.single()
        assertFalse(key.contains("tv-1"))
        assertEquals(64, key.length)
        assertEquals(64, value.length)
        assertTrue(value.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun aMismatchMapsToTheUntrustedNotice() {
        val flow = CastFlow()
        flow.showPicker(youtube = false, searching = false)
        val generation = flow.beginConnect(0)
        assertTrue(flow.failConnect(generation, youtube = false, failure = CastNotice.UNTRUSTED))
        flow.updateSearching(searching = false, devicesEmpty = true)
        assertEquals(CastNotice.UNTRUSTED, flow.notice)
        flow.showNotice(null)
        assertNull(flow.notice)
    }

    private companion object {
        /** Two throwaway RSA public keys (SPKI DER) made for this test run. */
        val KEY_A: ByteArray = rsaPublicKey()
        val KEY_B: ByteArray = rsaPublicKey()

        fun rsaPublicKey(): ByteArray =
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public.encoded
    }
}
