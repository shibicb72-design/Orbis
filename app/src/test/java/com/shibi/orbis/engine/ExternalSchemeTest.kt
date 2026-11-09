package com.shibi.orbis.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalSchemeTest {
    @Test
    fun appSchemesGoToApps() {
        listOf("tel:+1555", "mailto:a@b.c", "intent://x#Intent;end", "market://details?id=a", "zoommtg://join", "SMS:123")
            .forEach { assertTrue(it, isExternalScheme(it)) }
    }

    @Test
    fun engineSchemesStayInTheEngine() {
        listOf("https://a.example/", "http://a", "about:blank", "data:text/html,x", "blob:https://a/1", "javascript:void(0)", "file:///sdcard/x", "content://x", "moz-extension://id/p")
            .forEach { assertFalse(it, isExternalScheme(it)) }
    }

    @Test
    fun schemeParsing() {
        assertEquals("tel", schemeOf("TEL:+1"))
        assertNull(schemeOf("no scheme here"))
        assertNull(schemeOf(":x"))
        assertNull(schemeOf("1abc:x"))
        assertFalse(isExternalScheme("/relative/path"))
    }
}
