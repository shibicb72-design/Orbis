package com.shibi.orbis.torrent

import java.math.BigInteger
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedRangesTest {
    private val always =
        listOf(
            "0.0.0.0" to "0.255.255.255",
            "127.0.0.0" to "127.255.255.255",
            "::" to "::",
            "::1" to "::1",
            "::ffff:0.0.0.0" to "::ffff:0.255.255.255",
            "::ffff:127.0.0.0" to "::ffff:127.255.255.255",
        )

    @Test
    fun localPeersOnBlocksOnlyThisDevice() {
        assertEquals(always, blockedRanges(lsd = true))
    }

    @Test
    fun localPeersOffAlsoBlocksTheHomeNetwork() {
        assertEquals(
            listOf(
                "0.0.0.0" to "0.255.255.255",
                "127.0.0.0" to "127.255.255.255",
                "10.0.0.0" to "10.255.255.255",
                "172.16.0.0" to "172.31.255.255",
                "192.168.0.0" to "192.168.255.255",
                "169.254.0.0" to "169.254.255.255",
                "100.64.0.0" to "100.127.255.255",
                "::" to "::",
                "::1" to "::1",
                "fe80::" to "febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
                "fec0::" to "feff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
                "fc00::" to "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
                "::ffff:0.0.0.0" to "::ffff:0.255.255.255",
                "::ffff:127.0.0.0" to "::ffff:127.255.255.255",
                "::ffff:10.0.0.0" to "::ffff:10.255.255.255",
                "::ffff:172.16.0.0" to "::ffff:172.31.255.255",
                "::ffff:192.168.0.0" to "::ffff:192.168.255.255",
                "::ffff:169.254.0.0" to "::ffff:169.254.255.255",
                "::ffff:100.64.0.0" to "::ffff:100.127.255.255",
            ),
            blockedRanges(lsd = false),
        )
    }

    @Test
    fun everyRangeIsOneFamilyAndInOrder() {
        for (lsd in listOf(false, true)) {
            for ((first, last) in blockedRanges(lsd)) {
                val a = bytes(first)
                val b = bytes(last)
                assertEquals("$first..$last", a.size, b.size)
                assertTrue("$first..$last", BigInteger(1, a) <= BigInteger(1, b))
            }
        }
    }

    @Test
    fun noPublicAddressIsBlocked() {
        val public = listOf("8.8.8.8", "1.1.1.1", "2001:4860:4860::8888", "::ffff:8.8.8.8", "::ffff:1.1.1.1", "172.32.0.1", "100.128.0.1")
        for (lsd in listOf(false, true)) {
            for (ip in public) assertFalse("$ip lsd=$lsd", blocked(ip, lsd))
        }
    }

    @Test
    fun loopbackIsAlwaysBlockedAndTheHomeNetworkOnlyWithoutLocalPeers() {
        for (ip in listOf("127.0.0.1", "::1", "::ffff:127.0.0.1", "0.0.0.0", "::")) {
            assertTrue(ip, blocked(ip, lsd = true))
            assertTrue(ip, blocked(ip, lsd = false))
        }
        for (ip in listOf("192.168.1.10", "10.1.2.3", "172.20.0.1", "169.254.1.1", "100.100.0.1", "fe80::1", "fd12::1", "fec0::1", "::ffff:192.168.1.10")) {
            assertFalse(ip, blocked(ip, lsd = true))
            assertTrue(ip, blocked(ip, lsd = false))
        }
    }

    /** As libtorrent's ip_filter::access: an IPv4 address only against IPv4 ranges, an IPv6 one (mapped too) only against IPv6. */
    private fun blocked(ip: String, lsd: Boolean): Boolean {
        val x = bytes(ip)
        return blockedRanges(lsd).any { (first, last) ->
            val a = bytes(first)
            a.size == x.size && BigInteger(1, a) <= BigInteger(1, x) && BigInteger(1, x) <= BigInteger(1, bytes(last))
        }
    }

    /** A literal address as libtorrent stores it: 4 bytes for IPv4, 16 for any IPv6 text (Java unmaps ::ffff: itself). */
    private fun bytes(ip: String): ByteArray {
        val raw = InetAddress.getByName(ip).address
        if (!ip.contains(':') || raw.size == 16) return raw
        return ByteArray(10) + byteArrayOf(-1, -1) + raw
    }
}
