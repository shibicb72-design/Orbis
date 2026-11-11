package com.shibi.orbis.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MagnetLinkTest {
    private val hex = "08ada5a7a6183aae1e09d831df6748d566095a10"

    @Test
    fun v1HexIsLowerCasedAndTrackersCounted() {
        val m = MagnetLink.parse("magnet:?xt=urn:btih:${hex.uppercase()}&dn=Sintel&tr=udp%3A%2F%2Fa&tr=udp%3A%2F%2Fb")!!
        assertEquals(hex, m.infoHash)
        assertEquals("Sintel", m.displayName)
        assertEquals(2, m.trackerCount)
    }

    @Test
    fun v1Base32BecomesHex() {
        // RFC 4648 base32 of the 20 bytes 08ada5a7...
        val base32 = "BCW2LJ5GDA5K4HQJ3AY56Z2I2VTASWQQ"
        assertEquals(hex, MagnetLink.parse("magnet:?xt=urn:btih:$base32")!!.infoHash)
        assertEquals(hex, MagnetLink.parse("magnet:?xt=urn:btih:${base32.lowercase()}")!!.infoHash)
    }

    @Test
    fun v2MultihashIsAccepted() {
        val v2 = "a".repeat(64)
        val m = MagnetLink.parse("magnet:?xt=urn:btmh:1220$v2&dn=x")!!
        assertEquals(v2, m.infoHash)
        assertNull(MagnetLink.parse("magnet:?xt=urn:btmh:1220${"a".repeat(63)}"))
        // A hybrid link names both; the v1 hash is reported.
        assertEquals(hex, MagnetLink.parse("magnet:?xt=urn:btih:$hex&xt=urn:btmh:1220$v2")!!.infoHash)
    }

    @Test
    fun upperCaseSchemeIsAccepted() {
        assertEquals(hex, MagnetLink.parse("MAGNET:?XT=URN:BTIH:$hex")!!.infoHash)
    }

    @Test
    fun twoOrNoTopicsAreRefused() {
        assertNull(MagnetLink.parse("magnet:?xt=urn:btih:$hex&xt=urn:btih:$hex"))
        assertNull(MagnetLink.parse("magnet:?dn=Sintel&tr=udp%3A%2F%2Fa"))
        assertNull(MagnetLink.parse("magnet:?xt=urn:sha1:$hex"))
        assertNull(MagnetLink.parse("magnet:?xt=urn:btih:${hex.dropLast(1)}"))
    }

    @Test
    fun tooLongAndControlCharactersAreRefused() {
        val base = "magnet:?xt=urn:btih:$hex&dn="
        val atLimit = base + "a".repeat(TorrentLimits.MAX_MAGNET_CHARS - base.length)
        assertEquals(TorrentLimits.MAX_MAGNET_CHARS, atLimit.length)
        assertEquals(hex, MagnetLink.parse(atLimit)!!.infoHash)
        assertNull(MagnetLink.parse(atLimit + "a"))
        assertNull(MagnetLink.parse("magnet:?xt=urn:btih:$hex&dn=a\u0000b"))
        assertNull(MagnetLink.parse("magnet:?xt=urn:btih:$hex&dn=a\nb"))
        assertNull(MagnetLink.parse("magnet:?xt=urn:btih:$hex&dn=a b"))
    }

    @Test
    fun displayNameIsDecodedAndSanitised() {
        assertEquals("Big Buck Bunny", MagnetLink.parse("magnet:?xt=urn:btih:$hex&dn=Big+Buck%20Bunny")!!.displayName)
        val evil = MagnetLink.parse("magnet:?xt=urn:btih:$hex&dn=..%2F..%2Fetc%2Fpasswd%E2%80%AE")!!.displayName!!
        assertEquals("passwd", evil)
        assertNull(MagnetLink.parse("magnet:?xt=urn:btih:$hex&dn=..")!!.displayName)
        assertNull(MagnetLink.parse("magnet:?xt=urn:btih:$hex")!!.displayName)
    }

    @Test
    fun junkIsRefused() {
        assertNull(MagnetLink.parse("javascript:alert(1)//magnet:?xt=urn:btih:$hex"))
        assertNull(MagnetLink.parse("magnet:xt=urn:btih:$hex"))
        assertNull(MagnetLink.parse("https://example.com/?xt=urn:btih:$hex"))
        assertNull(MagnetLink.parse(""))
    }

    @Test
    fun toStringCarriesNoHashOrName() {
        val s = MagnetLink.parse("magnet:?xt=urn:btih:$hex&dn=Secret").toString()
        assertFalse(s.contains(hex))
        assertFalse(s.contains("Secret"))
    }
}
