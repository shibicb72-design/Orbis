package com.shibi.orbis.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteOriginTest {
    private fun origin(url: String?) = siteOriginOf(url)

    @Test
    fun plainOriginsKeepSchemeAndHost() {
        assertEquals("https://example.com", origin("https://example.com"))
        assertEquals("http://example.com", origin("http://example.com/"))
        assertEquals("https://sub.example.co.uk", origin("https://sub.example.co.uk/a/b.html"))
    }

    @Test
    fun pathQueryFragmentAreDropped() {
        assertEquals("https://example.com", origin("https://example.com/a/b?q=1#frag"))
        assertEquals("https://example.com", origin("https://example.com?q=1"))
        assertEquals("https://example.com", origin("https://example.com#frag"))
    }

    @Test
    fun uppercaseSchemeAndHostAreLowered() {
        assertEquals("https://example.com", origin("HTTPS://EXAMPLE.COM/Path"))
        assertEquals("http://example.com", origin("HtTp://ExAmPlE.CoM"))
    }

    @Test
    fun onlyHttpAndHttpsAreOrigins() {
        assertNull(origin("file:///etc/passwd"))
        assertNull(origin("about:blank"))
        assertNull(origin("data:text/html,<b>hi</b>"))
        assertNull(origin("blob:https://example.com/3f1a-uuid"))
        assertNull(origin("moz-extension://abcd-1234/page.html"))
        assertNull(origin("javascript:alert(1)"))
        assertNull(origin("javascript://example.com/%0aalert(1)"))
        assertNull(origin("ftp://example.com/"))
        assertNull(origin("ws://example.com/"))
        assertNull(origin("xhttps://example.com/"))
    }

    @Test
    fun userinfoIsStrippedAndTheRealHostWins() {
        assertEquals("https://example.com", origin("https://user:pw@example.com/"))
        assertEquals("https://example.com", origin("https://@example.com/"))
        assertEquals("https://evil.example", origin("https://good.example@evil.example/"))
        assertEquals("https://evil.example", origin("https://good.example:443@evil.example/"))
        // A '@' after the authority belongs to the path, query or fragment.
        assertEquals("https://example.com", origin("https://example.com/path@evil.example"))
        assertEquals("https://example.com", origin("https://example.com#@evil.example"))
        assertEquals("https://example.com", origin("https://example.com?x=@evil.example"))
        // A backslash ends the authority like a slash does.
        assertEquals("https://example.com", origin("https://example.com\\@evil.example/"))
    }

    @Test
    fun defaultPortsAreDroppedOthersKept() {
        assertEquals("https://example.com", origin("https://example.com:443/"))
        assertEquals("http://example.com", origin("http://example.com:80/"))
        assertEquals("https://example.com:80", origin("https://example.com:80/"))
        assertEquals("http://example.com:443", origin("http://example.com:443/"))
        assertEquals("https://example.com:8443", origin("https://example.com:8443/x"))
        assertEquals("http://localhost:3000", origin("http://localhost:3000"))
        // An empty port is the default port.
        assertEquals("https://example.com", origin("https://example.com:/"))
        // Leading zeros are the same port.
        assertEquals("https://example.com", origin("https://example.com:0443/"))
    }

    @Test
    fun badPortsAreRejected() {
        assertNull(origin("https://example.com:0/"))
        assertNull(origin("https://example.com:65536/"))
        assertNull(origin("https://example.com:99999/"))
        assertNull(origin("https://example.com:abc/"))
        assertNull(origin("https://example.com:80:80/"))
        assertNull(origin("https://example.com:-1/"))
        assertNotNull(origin("https://example.com:65535/"))
    }

    @Test
    fun internationalisedHostsBecomePunycode() {
        assertEquals("https://xn--bcher-kva.example", origin("https://bücher.example/"))
        assertEquals("https://xn--bcher-kva.example", origin("https://BÜCHER.example/"))
        assertEquals("https://xn--bcher-kva.example:8080", origin("https://bücher.example:8080/"))
    }

    @Test
    fun lookAlikeHostIsNeverShownAsUnicode() {
        // Cyrillic small a followed by "pple.com": looks like apple.com.
        val result = origin("https://аpple.com/")
        assertNotNull(result)
        assertTrue(result!!.startsWith("https://xn--"))
        assertTrue(result.all { it.code < 128 })
        assertTrue(result != "https://apple.com")
    }

    @Test
    fun ipv6HostsStayBracketed() {
        assertEquals("http://[::1]", origin("http://[::1]/"))
        assertEquals("http://[::1]:8080", origin("http://[::1]:8080/x"))
        assertEquals("https://[2001:db8::1]", origin("https://[2001:DB8::1]/"))
        assertEquals("https://[2001:db8::1]:444", origin("https://[2001:db8::1]:444"))
        assertEquals("https://[2001:db8::1]", origin("https://[2001:db8::1]:443"))
        assertNull(origin("https://[::1"))
        assertNull(origin("https://[]/"))
        assertNull(origin("https://[::1]x/"))
        assertNull(origin("https://[zz::1]/"))
        assertNull(origin("https://[1234]/"))
    }

    @Test
    fun ipv4HostsAreHosts() {
        assertEquals("http://192.168.0.1:8080", origin("http://192.168.0.1:8080/admin"))
    }

    @Test
    fun trailingDotIsTheSameHost() {
        assertEquals("https://example.com", origin("https://example.com./"))
    }

    @Test
    fun malformedInputGivesNullAndNeverThrows() {
        val bad =
            listOf(
                null,
                "",
                "   ",
                "example.com",
                "//example.com",
                "://example.com",
                "https://",
                "https:///path",
                "https://./",
                "https://.com",
                "https://a..b",
                "https://exa mple.com/",
                " https://example.com",
                "https://example.com\n",
                "https://exa\u0000mple.com",
                "https://exa%6dple.com",
                "https://exa<mple.com",
                "https://" + "a".repeat(64) + ".example",
                "https://" + "a.".repeat(200) + "com",
                "https://example.com/" + "x".repeat(10_000),
                "https:/example.com",
                "https:",
                "\u0000",
                "https://[",
            )
        for (input in bad) {
            assertNull("input: ${input?.take(40)}", origin(input))
        }
    }
}
