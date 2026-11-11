package com.shibi.orbis.cast

import java.security.KeyPairGenerator
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-01: a Chromecast is trusted per (identity key, friendly name), and only after the user confirmed it. The mDNS id is
 * kept only to spot "same id, different key".
 */
class CastPinsTest {
    private class MemoryStore : CastPinStore {
        val map = LinkedHashMap<String, String>()

        override fun get(key: String): String? = map[key]

        override fun put(key: String, value: String) {
            map[key] = value
        }

        override fun remove(key: String) {
            map.remove(key)
        }

        override fun all(): Map<String, String> = map.toMap()
    }

    private val store = MemoryStore()
    private val pins = CastPins(store)

    private fun needs(verdict: CastPins.Verdict): CastPins.Verdict.NeedsConfirm {
        assertTrue("expected NeedsConfirm, got $verdict", verdict is CastPins.Verdict.NeedsConfirm)
        return verdict as CastPins.Verdict.NeedsConfirm
    }

    /** Confirms and reconnects, as the coordinator does after "Trust and cast". */
    private fun trust(id: String, name: String, key: ByteArray, now: Long = 1_000L): CastPins.Verdict {
        val ask = needs(pins.check(id, name, key, now))
        pins.approve(ask.ticket, now)
        return pins.check(id, name, key, now + 1)
    }

    @Test
    fun aFirstUseAsksAndWritesNothingThenApprovalPinsAndTheNextCheckMatches() {
        val ask = needs(pins.check("tv-1", "Living Room", KEY_A, 0))
        assertEquals(CastPins.Reason.FIRST_USE, ask.reason)
        assertTrue(store.map.isEmpty())
        pins.approve(ask.ticket, 10)
        assertEquals(CastPins.Verdict.PINNED, pins.check("tv-1", "Living Room", KEY_A, 20))
        assertEquals(CastPins.Verdict.MATCHED, pins.check("tv-1", "Living Room", KEY_A, 30))
        assertEquals(1, store.map.size)
    }

    @Test
    fun theSameNameWithANewIdAndANewKeyIsAKeyChange() {
        trust("tv-1", "Living Room", KEY_A)
        val ask = needs(pins.check("tv-evil", "Living Room", KEY_B, 5_000))
        assertEquals(CastPins.Reason.KEY_CHANGED, ask.reason)
    }

    @Test
    fun theSameIdWithANewKeyAndANewNameIsAKeyChange() {
        trust("tv-1", "Living Room", KEY_A)
        val before = store.map.toMap()
        val ask = needs(pins.check("tv-1", "Kitchen", KEY_B, 5_000))
        assertEquals(CastPins.Reason.KEY_CHANGED, ask.reason)
        assertEquals("nothing is written while asking", before, store.map)
    }

    @Test
    fun theSameKeyUnderANewNameIsARenameAndAfterApprovalTheOldNameNoLongerMatches() {
        trust("tv-1", "Living Room", KEY_A)
        val ask = needs(pins.check("tv-1", "Den", KEY_A, 5_000))
        assertEquals(CastPins.Reason.RENAMED, ask.reason)
        pins.approve(ask.ticket, 5_000)
        assertEquals(CastPins.Verdict.PINNED, pins.check("tv-1", "Den", KEY_A, 5_001))
        assertEquals(1, store.map.size)
        assertEquals(CastPins.Reason.RENAMED, needs(pins.check("tv-1", "Living Room", KEY_A, 6_000)).reason)
    }

    @Test
    fun theSameKeyAndNameWithANewIdMatchesAndTheIdIsUpdated() {
        trust("tv-1", "Living Room", KEY_A)
        assertEquals(CastPins.Verdict.MATCHED, pins.check("tv-1-after-reset", "Living Room", KEY_A, 7_000))
        assertEquals(sha256Hex("tv-1-after-reset") + ":7000", store.map.values.single())
        // The stored id is now the new one: the old id with another key and name is first use, not a key change.
        assertEquals(CastPins.Reason.FIRST_USE, needs(pins.check("tv-1", "Kitchen", KEY_B, 8_000)).reason)
    }

    @Test
    fun anApprovalForOneKeyDoesNotPinAnother() {
        val ask = needs(pins.check("tv-1", "Living Room", KEY_A, 0))
        pins.approve(ask.ticket, 0)
        val other = needs(pins.check("tv-1", "Living Room", KEY_B, 1))
        assertEquals(CastPins.Reason.FIRST_USE, other.reason)
        assertTrue(store.map.isEmpty())
        // The approval for KEY_A still stands for KEY_A.
        assertEquals(CastPins.Verdict.PINNED, pins.check("tv-1", "Living Room", KEY_A, 2))
    }

    @Test
    fun anApprovalOlderThanTwoMinutesIsIgnoredAndOneCanBeCancelled() {
        val ask = needs(pins.check("tv-1", "Living Room", KEY_A, 0))
        pins.approve(ask.ticket, 0)
        needs(pins.check("tv-1", "Living Room", KEY_A, CAST_TRUST_APPROVAL_MS))
        assertTrue(store.map.isEmpty())
        pins.approve(ask.ticket, 1_000_000)
        pins.cancelApproval()
        needs(pins.check("tv-1", "Living Room", KEY_A, 1_000_001))
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun anOldFormatEntryWithTheSameKeyMigratesSilently() {
        store.put(sha256Hex("tv-1"), sha256Hex(KEY_A))
        assertEquals(CastPins.Verdict.MATCHED, pins.check("tv-1", "Living Room", KEY_A, 100))
        assertNull(store.get(sha256Hex("tv-1")))
        assertEquals(1, store.map.size)
        assertTrue(store.map.keys.single().startsWith("p2:"))
        assertEquals(CastPins.Verdict.MATCHED, pins.check("tv-1", "Living Room", KEY_A, 200))
    }

    @Test
    fun anOldFormatEntryWithAnotherKeyIsAKeyChange() {
        store.put(sha256Hex("tv-1"), sha256Hex(KEY_A))
        assertEquals(CastPins.Reason.KEY_CHANGED, needs(pins.check("tv-1", "Living Room", KEY_B, 100)).reason)
        assertEquals(sha256Hex(KEY_A), store.get(sha256Hex("tv-1")))
    }

    @Test
    fun nameNormalisationFoldsCaseCompatibilityFormsInvisibleMarksAndSpaces() {
        val base = castNameKey("living room")
        listOf(
            "Living Room",
            "LIVING ROOM",
            "  living   room ",
            "living room",
            "ｌｉｖｉｎｇ ｒｏｏｍ",
            "liv‍ing room",
            "living​ room",
            "‏living room‮",
            "living room",
        ).forEach { assertEquals(it, base, castNameKey(it)) }
        assertNotEquals(base, castNameKey("living rooms"))
        assertEquals(castNameKey("chromecast"), castNameKey(""))
        assertEquals(castNameKey("chromecast"), castNameKey("​ ‎"))
        // Approved under one spelling, matched under another.
        trust("tv-1", "Living Room", KEY_A)
        assertEquals(CastPins.Verdict.MATCHED, pins.check("tv-1", "living​  ROOM", KEY_A, 9_000))
    }

    @Test
    fun theSixtyFifthRecordEvictsTheLeastRecentlyUsed() {
        val keys = (0..CAST_PIN_MAX_RECORDS).map { "key-$it".toByteArray() }
        for (i in 0 until CAST_PIN_MAX_RECORDS) trust("tv-$i", "TV $i", keys[i], now = 1_000L + i * 10)
        assertEquals(CAST_PIN_MAX_RECORDS, store.map.size)
        // tv-0 is the oldest; touch it so tv-1 becomes the oldest.
        assertEquals(CastPins.Verdict.MATCHED, pins.check("tv-0", "TV 0", keys[0], 50_000))
        trust("tv-new", "TV new", keys[CAST_PIN_MAX_RECORDS], now = 60_000)
        assertEquals(CAST_PIN_MAX_RECORDS, store.map.size)
        assertEquals(CastPins.Verdict.MATCHED, pins.check("tv-0", "TV 0", keys[0], 70_000))
        assertEquals(CastPins.Verdict.MATCHED, pins.check("tv-new", "TV new", keys[CAST_PIN_MAX_RECORDS], 70_000))
        assertEquals(CastPins.Reason.FIRST_USE, needs(pins.check("tv-1", "TV 1", keys[1], 70_000)).reason)
    }

    @Test
    fun theStoreNeverHoldsAPlainNameOrId() {
        trust("device-id-1234", "Living Room", KEY_A)
        store.put(sha256Hex("old-id"), sha256Hex(KEY_B))
        pins.check("old-id", "Bedroom TV", KEY_B, 2_000)
        assertEquals(2, store.all().size)
        store.all().forEach { (key, value) ->
            listOf("device-id-1234", "old-id", "Living", "living", "Bedroom", "bedroom").forEach { plain ->
                assertFalse(key.contains(plain))
                assertFalse(value.contains(plain))
            }
            assertTrue(key, key.matches(Regex("p2:[0-9a-f]{64}:[0-9a-f]{64}")))
            assertTrue(value, value.matches(Regex("[0-9a-f]{64}:[0-9]+")))
        }
    }

    @Test
    fun aTicketPrintsNoField() {
        val ask = needs(pins.check("device-id-1234", "Living Room", KEY_A, 0))
        assertEquals("Ticket", ask.ticket.toString())
        assertFalse(ask.toString().contains(sha256Hex(KEY_A)))
        assertFalse(ask.toString().contains(castNameKey("Living Room")))
    }

    @Test
    fun aTrustQuestionIsAStickyNoticeAndAnyOtherNoticeDropsIt() {
        val flow = CastFlow()
        flow.showPicker(youtube = false, searching = false)
        val generation = flow.beginConnect(0)
        val ticket = needs(pins.check("tv-1", "Living Room", KEY_A, 0)).ticket
        assertTrue(flow.needsTrust(generation, youtube = false, PendingTrust("tv-1", "Living Room", CastPins.Reason.FIRST_USE, ticket)))
        flow.updateSearching(searching = false, devicesEmpty = true)
        assertEquals(CastNotice.CONFIRM_DEVICE, flow.notice)
        assertEquals("tv-1", flow.trust?.deviceId)
        flow.showNotice(CastNotice.FAILED)
        assertNull(flow.trust)
    }

    private companion object {
        /** Two throwaway RSA public keys (SPKI DER) made for this test run. */
        val KEY_A: ByteArray = rsaPublicKey()
        val KEY_B: ByteArray = rsaPublicKey()

        fun rsaPublicKey(): ByteArray =
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public.encoded

        fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
