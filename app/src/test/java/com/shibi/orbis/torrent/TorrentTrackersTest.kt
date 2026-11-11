package com.shibi.orbis.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentTrackersTest {
    private val base = "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Name"

    @Test
    fun webSocketSchemesInAnyCaseAreWebSocket() {
        assertTrue(TorrentTrackers.isWebSocket("ws://t.example/announce"))
        assertTrue(TorrentTrackers.isWebSocket("WSS://t.example/announce"))
        assertTrue(TorrentTrackers.isWebSocket("  wss://t.example"))
        assertFalse(TorrentTrackers.isWebSocket("udp://t.example:1337"))
        assertFalse(TorrentTrackers.isWebSocket("https://t.example/announce"))
        assertFalse(TorrentTrackers.isWebSocket("wsx://t.example"))
    }

    @Test
    fun stripMagnetDropsEncodedAndPlainWebSocketTrackersOnly() {
        val udp = "tr=udp%3A%2F%2Ft.example%3A1337"
        val uri = "$base&tr=wss%3A%2F%2Ft.example&$udp&TR=ws://t.example:8000&tr=WSS%3a%2f%2fx"
        val r = TorrentTrackers.stripMagnet(uri)
        assertEquals(3, r.dropped)
        assertEquals("$base&$udp", r.uri)
        assertFalse(r.toString().contains("example"))
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
        assertTrue(SessionDiscovery.of(dht = true, lsd = false, privateTorrents = 0, torrents = 0).dht)
        assertTrue(SessionDiscovery.of(dht = true, lsd = false, privateTorrents = 1, torrents = 2).dht)
        assertFalse(SessionDiscovery.of(dht = true, lsd = false, privateTorrents = 2, torrents = 2).dht)
        assertFalse(SessionDiscovery.of(dht = false, lsd = false, privateTorrents = 0, torrents = 2).dht)
    }
}
