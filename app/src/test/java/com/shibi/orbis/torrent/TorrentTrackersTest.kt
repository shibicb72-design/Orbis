package com.shibi.orbis.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentTrackersTest {
    private val base = "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Name"

    @Test
    fun onlyHttpHttpsAndUdpWithSlashesAreAllowed() {
        assertTrue(TorrentTrackers.isAllowed("udp://t.example:1337"))
        assertTrue(TorrentTrackers.isAllowed("https://t.example/announce"))
        assertTrue(TorrentTrackers.isAllowed("HTTP://t.example/announce"))
        // libtorrent takes the text before the first ':' as the scheme: these all reach its WebSocket branch.
        for (ws in listOf("ws:x", "wss:/x", "WS:x", "ws://t.example/announce", "WSS://t.example", "wss:")) {
            assertFalse(ws, TorrentTrackers.isAllowed(ws))
        }
        for (other in listOf("file:///x", "javascript:x", "http:x", "https:/x", "udp:", "wsx://t.example", "  udp://t.example:1", "", ":x", "i2p://x")) {
            assertFalse(other, TorrentTrackers.isAllowed(other))
        }
        for (bad in listOf("udp://t.example:1/ a", "http://t.example/\u0000", "http://t.example/\n", "http://t.example/\u202e")) {
            assertFalse(bad, TorrentTrackers.isAllowed(bad))
        }
    }

    @Test
    fun stripMagnetDropsEveryRefusedTrackerUnderEveryTrackerKey() {
        val udp = "tr=udp%3A%2F%2Ft.example%3A1337"
        val http = "tr.2=http%3A%2F%2Ft.example%2Fannounce"
        val uri = "$base&tr=wss%3A%2F%2Ft.example&$udp&TR=ws:x&tr.1=wss:/x&$http&tr=WS%3ax&tr=file%3A%2F%2F%2Fx&tr=javascript:x"
        val r = TorrentTrackers.stripMagnet(uri)
        assertEquals(6, r.dropped)
        assertEquals("$base&$udp&$http", r.uri)
        assertFalse(r.toString().contains("example"))
        // Not a tracker key: left alone.
        assertEquals(0, TorrentTrackers.stripMagnet("$base&tr.x=ws:x&xtr=ws:x").dropped)
    }

    @Test
    fun stripMagnetLeavesALinkWithoutWebSocketTrackersAlone() {
        val uri = "$base&tr=udp%3A%2F%2Ft.example%3A1337&ws=https%3A%2F%2Fseed.example%2Ff"
        val r = TorrentTrackers.stripMagnet(uri)
        assertEquals(0, r.dropped)
        assertSame(uri, r.uri)
    }

    private fun bstr(s: String) = "${s.toByteArray().size}:$s"

    private fun torrent(announce: String?, tiers: List<List<String>>?): ByteArray {
        val sb = StringBuilder("d")
        if (announce != null) sb.append(bstr("announce")).append(bstr(announce))
        if (tiers != null) {
            sb.append(bstr("announce-list")).append("l")
            tiers.forEach { tier -> sb.append("l"); tier.forEach { sb.append(bstr(it)) }; sb.append("e") }
            sb.append("e")
        }
        sb.append(bstr("info")).append("d").append(bstr("length")).append("i1e").append(bstr("name")).append(bstr("a")).append("ee")
        return sb.toString().toByteArray()
    }

    @Test
    fun announceListGivesTiersInOrderWithoutWebSocketOnes() {
        val bytes = torrent("http://a.example/announce", listOf(listOf("udp://b.example:1", "wss://c.example"), listOf("https://d.example/announce", "ws://e.example")))
        val a = TorrentTrackers.fromTorrentFile(bytes)
        assertEquals(listOf("udp://b.example:1", "https://d.example/announce"), a.urls)
        assertEquals(listOf(0, 1), a.tiers)
        assertEquals(2, a.dropped)
    }

    @Test
    fun onlyPrintableAsciiIsAllowed() {
        // URLDecoder turns a non-UTF-8 byte into U+FFFD; libtorrent would keep the raw byte.
        assertFalse(TorrentTrackers.isAllowed("http://a/\uFFFD"))
        assertFalse(TorrentTrackers.isAllowed("http://\u00e4.example/announce"))
        assertTrue(TorrentTrackers.isAllowed("http://xn--4ca.example/announce?a=1&b=~"))
    }

    @Test
    fun stripMagnetDropsNonUtf8AndTheBareTrDotKey() {
        val udp = "tr=udp%3A%2F%2Ft.example%3A1337"
        val r = TorrentTrackers.stripMagnet("$base&tr=http%3A%2F%2Fa%2F%FF&tr.=ws:x&$udp&tr.=http%3A%2F%2Fa%2F%FF")
        assertEquals(3, r.dropped)
        assertEquals("$base&$udp", r.uri)
    }

    @Test
    fun aTorrentWhoseAnnounceHoldsANonUtf8ByteGivesNoTracker() {
        val url = "http://a.example/".toByteArray() + byteArrayOf(0xFF.toByte())
        val head = "d8:announce${url.size}:".toByteArray()
        val tail = "4:infod6:lengthi1e4:name1:aee".toByteArray()
        val a = TorrentTrackers.fromTorrentFile(head + url + tail)
        assertTrue(a.urls.isEmpty())
        assertEquals(1, a.dropped)
    }

    @Test
    fun tiersAreNumberedDenselyAndBadUrlsAreDropped() {
        val tiers = listOf(listOf("ws:x"), listOf("udp://a.example:1"), listOf("wss:/x", "file:///x"), listOf("http://b.example/ a", "https://c.example/announce"))
        val a = TorrentTrackers.fromTorrentFile(torrent(null, tiers))
        assertEquals(listOf("udp://a.example:1", "https://c.example/announce"), a.urls)
        assertEquals(listOf(0, 1), a.tiers)
        assertEquals(4, a.dropped)
        // Hundreds of tiers still give small tier numbers (libtorrent keeps a tier in one byte).
        val many = (0 until 300).map { listOf("ws:$it") } + listOf(listOf("udp://d.example:1"))
        assertEquals(listOf(0), TorrentTrackers.fromTorrentFile(torrent(null, many)).tiers)
    }

    @Test
    fun announceIsUsedOnlyWithoutAnAnnounceList() {
        val a = TorrentTrackers.fromTorrentFile(torrent("udp://a.example:1", null))
        assertEquals(listOf("udp://a.example:1"), a.urls)
        assertEquals(listOf(0), a.tiers)
        val ws = TorrentTrackers.fromTorrentFile(torrent("wss://a.example", null))
        assertTrue(ws.urls.isEmpty())
        assertEquals(1, ws.dropped)
        // A list of WebSocket trackers only does not fall back to announce (libtorrent would not either).
        val onlyWs = TorrentTrackers.fromTorrentFile(torrent("http://a.example/announce", listOf(listOf("wss://b.example"))))
        assertTrue(onlyWs.urls.isEmpty())
        assertEquals(1, onlyWs.dropped)
    }

    @Test
    fun aBrokenFileGivesNoTrackers() {
        assertSame(TorrentTrackers.NONE, TorrentTrackers.fromTorrentFile("d8:announce".toByteArray()))
        assertSame(TorrentTrackers.NONE, TorrentTrackers.fromTorrentFile(ByteArray(0)))
        assertSame(TorrentTrackers.NONE, TorrentTrackers.fromTorrentFile("le".toByteArray()))
    }

    @Test
    fun trackersAreCappedAndDeduplicated() {
        val many = (0 until TorrentTrackers.MAX_TRACKERS + 10).map { "udp://t$it.example:1" }
        val a = TorrentTrackers.fromTorrentFile(torrent(null, listOf(many + many.take(3))))
        assertEquals(TorrentTrackers.MAX_TRACKERS, a.urls.size)
        assertEquals(a.urls.toSet().size, a.urls.size)
    }

    // ---- discovery ----

    @Test
    fun aPrivateTabTorrentUsesTrackersOnlyWhateverTheSettings() {
        assertEquals(TorrentDiscovery.TRACKERS_ONLY, TorrentDiscovery.of(dht = true, pex = true, lsd = true, trackersOnly = true))
        assertEquals(TorrentDiscovery(dht = true, pex = false, lsd = true), TorrentDiscovery.of(dht = true, pex = false, lsd = true, trackersOnly = false))
    }

    @Test
    fun sessionLocalPeersAreOffWhileAnyPrivateTorrentIsInTheSession() {
        assertTrue(SessionDiscovery.of(dht = true, lsd = true, privateTorrents = 0, torrents = 3).lsd)
        assertFalse(SessionDiscovery.of(dht = true, lsd = true, privateTorrents = 1, torrents = 3).lsd)
        assertFalse(SessionDiscovery.of(dht = true, lsd = false, privateTorrents = 0, torrents = 1).lsd)
    }

    @Test
    fun theSessionDhtNodeRunsOnlyWhenSomeTorrentCanUseIt() {
        assertTrue(SessionDiscovery.of(dht = true, lsd = false, privateTorrents = 1, torrents = 2).dht)
        assertFalse(SessionDiscovery.of(dht = true, lsd = false, privateTorrents = 2, torrents = 2).dht)
        assertFalse(SessionDiscovery.of(dht = false, lsd = false, privateTorrents = 0, torrents = 2).dht)
    }

    @Test
    fun anEmptySessionKeepsWhatItHad() {
        // The last private-tab torrent left: the DHT node and local peers stay off until the session stops.
        val privateOnly = SessionDiscovery(dht = false, lsd = false)
        assertEquals(privateOnly, SessionDiscovery.of(dht = true, lsd = true, privateTorrents = 0, torrents = 0, previous = privateOnly))
        // A normal session keeps its DHT node at zero torrents (session.dat keeps its DHT table).
        val normal = SessionDiscovery(dht = true, lsd = true)
        assertEquals(normal, SessionDiscovery.of(dht = true, lsd = true, privateTorrents = 0, torrents = 0, previous = normal))
        // Never more than the settings allow.
        assertEquals(SessionDiscovery(dht = false, lsd = true), SessionDiscovery.of(dht = false, lsd = true, privateTorrents = 0, torrents = 0, previous = normal))
        // A torrent coming in is judged as usual.
        assertEquals(normal, SessionDiscovery.of(dht = true, lsd = true, privateTorrents = 0, torrents = 1, previous = privateOnly))
        // No session before: the settings.
        assertEquals(normal, SessionDiscovery.of(dht = true, lsd = true, privateTorrents = 0, torrents = 0, previous = null))
    }
}
