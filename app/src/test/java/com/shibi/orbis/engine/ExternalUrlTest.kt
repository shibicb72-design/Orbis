package com.shibi.orbis.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val VIEW = "android.intent.action.VIEW"

class ExternalUrlTest {
    @Test
    fun acceptsHttpAndHttpsViewIntents() {
        assertEquals("https://example.com/a?b=c#d", externalUrlFrom(VIEW, "https://example.com/a?b=c#d"))
        assertEquals("http://example.com", externalUrlFrom(VIEW, "http://example.com"))
        assertEquals("HTTPS://EXAMPLE.COM/", externalUrlFrom(VIEW, "HTTPS://EXAMPLE.COM/"))
        assertEquals("http://localhost:8080/", externalUrlFrom(VIEW, "http://localhost:8080/"))
    }

    @Test
    fun refusesOtherActions() {
        assertNull(externalUrlFrom(null, "https://example.com/"))
        assertNull(externalUrlFrom("android.intent.action.MAIN", "https://example.com/"))
        assertNull(externalUrlFrom("android.intent.action.SEND", "https://example.com/"))
        assertNull(externalUrlFrom("android.intent.action.view", "https://example.com/"))
    }

    @Test
    fun refusesNonWebSchemesAndMissingData() {
        for (bad in listOf(
            null, "", "example.com", "ftp://example.com/", "file:///sdcard/a.html", "content://a/b",
            "javascript:alert(1)", "data:text/html,hi", "intent://x#Intent;scheme=http;end", "about:blank",
            "view-source:https://example.com/", "blob:https://example.com/x", "httpx://example.com",
        )) {
            assertNull("data $bad", externalUrlFrom(VIEW, bad))
        }
    }

    @Test
    fun refusesWhitespaceControlCharsAndMissingHost() {
        assertNull(externalUrlFrom(VIEW, "https://example.com/a b"))
        assertNull(externalUrlFrom(VIEW, " https://example.com/"))
        assertNull(externalUrlFrom(VIEW, "https://example.com/\n"))
        assertNull(externalUrlFrom(VIEW, "https://example.com/\u0000"))
        assertNull(externalUrlFrom(VIEW, "https://"))
        assertNull(externalUrlFrom(VIEW, "https:///path"))
        assertNull(externalUrlFrom(VIEW, "https://:80/"))
    }

    @Test
    fun refusesAbsurdlyLongData() {
        val tooLong = "https://example.com/" + "a".repeat(20_000)
        assertNull(externalUrlFrom(VIEW, tooLong))
    }

    @Test
    fun hostOfUrlStripsUserinfoPortAndCase() {
        assertEquals("example.com", hostOfUrl("https://Example.COM/path"))
        assertEquals("evil.example", hostOfUrl("https://good.example@evil.example/"))
        assertEquals("evil.example", hostOfUrl("https://good.example:pw@evil.example:8443/x"))
        assertEquals("example.com", hostOfUrl("https://example.com:443"))
        assertEquals("example.com", hostOfUrl("https://example.com?x=1"))
        assertEquals("example.com", hostOfUrl("https://example.com#frag"))
        assertEquals("evil.example", hostOfUrl("https://evil.example\\@good.example/"))
        assertEquals("[::1]", hostOfUrl("http://[::1]:8080/"))
        assertNull(hostOfUrl("http://[::1/"))
        assertNull(hostOfUrl("example.com"))
        assertNull(hostOfUrl("https://"))
        assertNull(hostOfUrl(""))
    }

    @Test
    fun isWebUrlIsCaseInsensitiveAndStrict() {
        assertTrue(isWebUrl("http://a"))
        assertTrue(isWebUrl("HtTpS://a"))
        assertFalse(isWebUrl("ftp://a"))
        assertFalse(isWebUrl("javascript:alert(1)"))
        assertFalse(isWebUrl(" https://a"))
    }
}
