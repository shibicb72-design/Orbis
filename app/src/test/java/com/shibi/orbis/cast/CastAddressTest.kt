package com.shibi.orbis.cast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastAddressTest {
    @Test
    fun localNetworkHostsAreRecognised() {
        assertTrue("127.0.0.1", isLocalNetworkHost("127.0.0.1"))
        assertTrue("127.1", isLocalNetworkHost("127.1"))
        assertTrue("10.0.0.5", isLocalNetworkHost("10.0.0.5"))
        assertTrue("172.16.0.1", isLocalNetworkHost("172.16.0.1"))
        assertTrue("172.31.255.255", isLocalNetworkHost("172.31.255.255"))
        assertTrue("192.168.1.1", isLocalNetworkHost("192.168.1.1"))
        assertTrue("169.254.169.254", isLocalNetworkHost("169.254.169.254"))
        assertTrue("100.64.0.1", isLocalNetworkHost("100.64.0.1"))
        assertTrue("0.0.0.0", isLocalNetworkHost("0.0.0.0"))
        assertTrue("255.255.255.255", isLocalNetworkHost("255.255.255.255"))
        assertTrue("3232235777", isLocalNetworkHost("3232235777"))
        assertTrue("2130706433", isLocalNetworkHost("2130706433"))
        assertTrue("0xC0A80101", isLocalNetworkHost("0xC0A80101"))
        assertTrue("0300.0250.01.01", isLocalNetworkHost("0300.0250.01.01"))
        assertTrue("192.168.257", isLocalNetworkHost("192.168.257"))
        assertTrue("192.168.1.1.", isLocalNetworkHost("192.168.1.1."))
        assertTrue("[::1]", isLocalNetworkHost("[::1]"))
        assertTrue("[::]", isLocalNetworkHost("[::]"))
        assertTrue("[fe80::1]", isLocalNetworkHost("[fe80::1]"))
        assertTrue("[fe80::1%25eth0]", isLocalNetworkHost("[fe80::1%25eth0]"))
        assertTrue("[fd00::1]", isLocalNetworkHost("[fd00::1]"))
        assertTrue("[ff02::1]", isLocalNetworkHost("[ff02::1]"))
        assertTrue("[::ffff:192.168.1.1]", isLocalNetworkHost("[::ffff:192.168.1.1]"))
        assertTrue("[::ffff:c0a8:101]", isLocalNetworkHost("[::ffff:c0a8:101]"))
        assertTrue("[64:ff9b::c0a8:101]", isLocalNetworkHost("[64:ff9b::c0a8:101]"))
        assertTrue("[2002:c0a8:101::1]", isLocalNetworkHost("[2002:c0a8:101::1]"))
        assertTrue("localhost", isLocalNetworkHost("localhost"))
        assertTrue("LOCALHOST", isLocalNetworkHost("LOCALHOST"))
        assertTrue("a.localhost", isLocalNetworkHost("a.localhost"))
        assertTrue("nas.local", isLocalNetworkHost("nas.local"))
        assertTrue("router", isLocalNetworkHost("router"))
        assertTrue("printer.home.arpa", isLocalNetworkHost("printer.home.arpa"))
        assertTrue("999.1.1.1", isLocalNetworkHost("999.1.1.1"))
        assertTrue("1.2.3.4.5", isLocalNetworkHost("1.2.3.4.5"))
    }

    @Test
    fun publicHostsAreNotLocal() {
        assertFalse("8.8.8.8", isLocalNetworkHost("8.8.8.8"))
        assertFalse("1.1.1.1", isLocalNetworkHost("1.1.1.1"))
        assertFalse("172.32.0.1", isLocalNetworkHost("172.32.0.1"))
        assertFalse("172.15.255.255", isLocalNetworkHost("172.15.255.255"))
        assertFalse("192.167.1.1", isLocalNetworkHost("192.167.1.1"))
        assertFalse("100.63.255.255", isLocalNetworkHost("100.63.255.255"))
        assertFalse("100.128.0.1", isLocalNetworkHost("100.128.0.1"))
        assertFalse("[2606:4700:4700::1111]", isLocalNetworkHost("[2606:4700:4700::1111]"))
        assertFalse("[::ffff:8.8.8.8]", isLocalNetworkHost("[::ffff:8.8.8.8]"))
        assertFalse("[64:ff9b::808:808]", isLocalNetworkHost("[64:ff9b::808:808]"))
        assertFalse("example.com", isLocalNetworkHost("example.com"))
        assertFalse("cdn.example.co.uk", isLocalNetworkHost("cdn.example.co.uk"))
        assertFalse("192.168.1.1.example.com", isLocalNetworkHost("192.168.1.1.example.com"))
        assertFalse("10.example.com", isLocalNetworkHost("10.example.com"))
        assertFalse("local.example.com", isLocalNetworkHost("local.example.com"))
    }

    @Test
    fun publicPageMayCastPublicMedia() {
        assertTrue(castAddressAllowed("https://cdn.example.com/a.mp4", "https://example.com/watch"))
    }

    @Test
    fun publicPageMayNotCastLocalMedia() {
        val page = "https://example.com/watch"
        assertFalse(castAddressAllowed("http://192.168.1.1/a.mp4", page))
        assertFalse(castAddressAllowed("http://[::1]/a.mp4", page))
        assertFalse(castAddressAllowed("http://3232235777/a.mp4", page))
        assertFalse(castAddressAllowed("http://user@10.0.0.2/a.mp4", page))
        assertFalse(castAddressAllowed("http://nas.local/a.mp4", page))
    }

    @Test
    fun localPageMayCastLocalMedia() {
        assertTrue(castAddressAllowed("http://192.168.1.10:8096/v.mp4", "http://192.168.1.10:8096/"))
        assertTrue(castAddressAllowed("https://cdn.example.com/a.mp4", "http://192.168.1.10:8096/"))
    }

    @Test
    fun unparsableMediaUrlIsRefused() {
        assertFalse(castAddressAllowed("http://exa mple.com/a.mp4", "https://example.com/"))
        assertFalse(castAddressAllowed("", "https://example.com/"))
    }
}
