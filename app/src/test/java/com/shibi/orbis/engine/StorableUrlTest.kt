package com.shibi.orbis.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorableUrlTest {
    @Test
    fun acceptsOrdinaryWebUrls() {
        listOf(
            "https://example.com",
            "http://example.com:8080/a/b?c=d#e",
            "HTTPS://EXAMPLE.COM/",
            "https://[::1]:8080/",
            "https://xn--e1afmkfd.xn--p1ai/",
            "https://exämple.com/",
            "https://example.com/path@x",
            "https://example.com/?u=a@b",
            "https://example.com#frag@x",
        ).forEach { assertTrue(it, isStorableWebUrl(it)) }
    }

    @Test
    fun refusesOtherSchemesAndMissingHosts() {
        listOf(
            "",
            "ftp://example.com/",
            "javascript:alert(1)",
            "data:text/html,x",
            "file:///sdcard/x.html",
            "place:type=6",
            "about:blank",
            "https://",
            "https:///path",
            "https:/example.com",
            "https://:8080/",
        ).forEach { assertFalse(it, isStorableWebUrl(it)) }
    }

    @Test
    fun refusesUserinfo() {
        listOf(
            "https://good.example@evil.example/",
            "https://user:pw@example.com/",
            "https://@example.com/",
            "http://a@b",
        ).forEach { assertFalse(it, isStorableWebUrl(it)) }
    }

    @Test
    fun refusesWhitespaceControlAndBidiCharacters() {
        listOf(
            "https://example.com/a b",
            "https://example.com/\n",
            "https://example.com/\t",
            "https://exam ple.com/",
            "https://example.com/\u0000",
            "https://example.com/\u007f",
            "https://example.com/ ",
            "https://example.com/\u200E",
            "https://example.com/\u200F",
            "https://example.com/\u202A",
            "https://example.com/\u202E",
            "https://example.com/\u2066",
            "https://example.com/\u2069",
        ).forEach { assertFalse(it.replace("\u0000", "NUL"), isStorableWebUrl(it)) }
    }

    @Test
    fun lengthBoundary() {
        val prefix = "https://example.com/"
        val atLimit = prefix + "x".repeat(MAX_STORED_URL_LENGTH - prefix.length)
        assertEquals(MAX_STORED_URL_LENGTH, atLimit.length)
        assertTrue(isStorableWebUrl(atLimit))
        assertFalse(isStorableWebUrl(atLimit + "x"))
    }

    @Test
    fun titleIsCleanedAndCollapsed() {
        assertEquals("", cleanStoredTitle(null))
        assertEquals("", cleanStoredTitle(""))
        assertEquals("", cleanStoredTitle(" \n\t "))
        assertEquals("a b c", cleanStoredTitle("  a\n\tb \r\n c  "))
        assertEquals("ab", cleanStoredTitle("a\u0000b"))
        assertEquals("ab", cleanStoredTitle("a\u202Eb\u200F"))
        assertEquals("Hello", cleanStoredTitle("\u2066Hello\u2069"))
    }

    @Test
    fun titleCapKeepsTheLimit() {
        assertEquals(MAX_STORED_TITLE_LENGTH, cleanStoredTitle("a".repeat(600)).length)
        assertEquals("a".repeat(MAX_STORED_TITLE_LENGTH), cleanStoredTitle("a".repeat(MAX_STORED_TITLE_LENGTH)))
    }

    @Test
    fun titleCapNeverSplitsASurrogatePair() {
        val emoji = "😀"
        val title = cleanStoredTitle("a".repeat(MAX_STORED_TITLE_LENGTH - 1) + emoji + "tail")
        assertEquals("a".repeat(MAX_STORED_TITLE_LENGTH - 1), title)
        assertFalse(Character.isHighSurrogate(title.last()))
        // A pair that fits whole is kept.
        val fits = cleanStoredTitle("a".repeat(MAX_STORED_TITLE_LENGTH - 2) + emoji + "tail")
        assertEquals(MAX_STORED_TITLE_LENGTH, fits.length)
        assertTrue(fits.endsWith(emoji))
    }

    @Test
    fun editedUrlGetsHttpsWhenTheSchemeIsMissing() {
        assertEquals("https://example.com", parseEditedBookmarkUrl("example.com"))
        assertEquals("https://example.com", parseEditedBookmarkUrl("  example.com  "))
        assertEquals("http://a.example/x", parseEditedBookmarkUrl("  http://a.example/x\n"))
        assertEquals("https://localhost:8080/x", parseEditedBookmarkUrl("localhost:8080/x"))
        assertEquals("https://example.com/p:q:r", parseEditedBookmarkUrl("example.com/p:q:r"))
        assertEquals("https://[::1]:8080", parseEditedBookmarkUrl("[::1]:8080"))
    }

    @Test
    fun editedUrlRefusesWhatIsNotAWebAddress() {
        listOf(
            "",
            "   ",
            "javascript:alert(1)",
            "JavaScript:alert(1)",
            "data:text/html,x",
            "file:///x",
            "ftp://a.example/",
            "mailto:a@b.example",
            "a b.example",
            "https://good@evil.example/",
            "https://example.com/a b",
        ).forEach { assertNull(it, parseEditedBookmarkUrl(it)) }
    }
}
