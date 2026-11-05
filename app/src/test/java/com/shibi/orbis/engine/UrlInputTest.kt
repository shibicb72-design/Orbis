package com.shibi.orbis.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlInputTest {
    private fun search(encoded: String) = "https://duckduckgo.com/?q=$encoded"

    @Test
    fun blankIsNull() {
        assertNull(normalizeAddressInput(""))
        assertNull(normalizeAddressInput("   \t "))
    }

    @Test
    fun httpAndHttpsPassThrough() {
        assertEquals("http://example.com/a", normalizeAddressInput("http://example.com/a"))
        assertEquals("https://example.com", normalizeAddressInput("https://example.com"))
        assertEquals("HTTPS://Example.com/X", normalizeAddressInput("HTTPS://Example.com/X"))
        assertEquals("Http://example.com", normalizeAddressInput("Http://example.com"))
        assertEquals("https://example.com", normalizeAddressInput("  https://example.com  "))
    }

    @Test
    fun bareHostGetsHttps() {
        assertEquals("https://example.com", normalizeAddressInput("example.com"))
        assertEquals("https://www.example.co.uk", normalizeAddressInput(" www.example.co.uk "))
        assertEquals("https://192.168.1.1", normalizeAddressInput("192.168.1.1"))
    }

    @Test
    fun hostWithPortAndPath() {
        assertEquals("https://example.com:8080/a/b?x=1#f", normalizeAddressInput("example.com:8080/a/b?x=1#f"))
        assertEquals("https://example.com/path", normalizeAddressInput("example.com/path"))
        assertEquals("https://example.com/?q=a", normalizeAddressInput("example.com/?q=a"))
    }

    @Test
    fun localhost() {
        assertEquals("https://localhost", normalizeAddressInput("localhost"))
        assertEquals("https://localhost:3000/x", normalizeAddressInput("localhost:3000/x"))
    }

    @Test
    fun wordsBecomeSearch() {
        assertEquals(search("hello+world"), normalizeAddressInput("hello world"))
        assertEquals(search("cats"), normalizeAddressInput("cats"))
        assertEquals(search("cats+%26+dogs"), normalizeAddressInput("cats & dogs"))
        assertEquals(search("example.com+reviews"), normalizeAddressInput("example.com reviews"))
    }

    @Test
    fun webInputWithWhitespaceBecomesSearch() {
        assertEquals(
            search("http%3A%2F%2Fexample.com+reviews"),
            normalizeAddressInput("http://example.com reviews"),
        )
        assertEquals(
            search("https%3A%2F%2Fexample.com%2Fa+b"),
            normalizeAddressInput("https://example.com/a b"),
        )
    }

    @Test
    fun unicodeHostGetsHttps() {
        assertEquals("https://münchen.de", normalizeAddressInput("münchen.de"))
        assertEquals("https://münchen.de/straße?q=1", normalizeAddressInput("münchen.de/straße?q=1"))
        assertEquals("https://пример.рф", normalizeAddressInput("пример.рф"))
    }

    @Test
    fun nonWebSchemesBecomeSearch() {
        assertEquals(search("javascript%3Aalert%281%29"), normalizeAddressInput("javascript:alert(1)"))
        assertEquals(search("javascript%3Aa.b"), normalizeAddressInput("javascript:a.b"))
        assertEquals(search("javascript%3A1.2"), normalizeAddressInput("javascript:1.2"))
        assertEquals(search("file%3A%2F%2F%2Fa.html"), normalizeAddressInput("file:///a.html"))
        assertEquals(search("data%3Atext%2Fhtml%2Cx"), normalizeAddressInput("data:text/html,x"))
        assertEquals(search("intent%3A%2F%2Fx"), normalizeAddressInput("intent://x"))
        assertEquals(search("content%3A%2F%2Fa.b"), normalizeAddressInput("content://a.b"))
        assertEquals(search("about%3Aconfig"), normalizeAddressInput("about:config"))
    }
}
