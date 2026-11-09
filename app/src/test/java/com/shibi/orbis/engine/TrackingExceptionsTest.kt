package com.shibi.orbis.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingExceptionsTest {
    @Test
    fun originsIgnorePathCaseUserinfoAndDefaultPorts() {
        assertEquals("https://example.com", trackingOrigin("https://Example.COM/a/b?c#d"))
        assertEquals("https://example.com", trackingOrigin("https://example.com:443/"))
        assertEquals("http://example.com", trackingOrigin("http://user:pw@example.com:80/x"))
        assertEquals("http://127.0.0.1:18096", trackingOrigin("http://127.0.0.1:18096/etp/t"))
        assertNull(trackingOrigin("about:blank"))
        assertNull(trackingOrigin("file:///x"))
        assertNull(trackingOrigin(null))
    }

    @Test
    fun anExceptionCoversTheWholeSiteOnlyAndNormalAndPrivateAreApart() {
        val t = TrackingExceptions()
        t.set("https://news.test/article", isPrivate = false, excepted = true)
        assertTrue(t.isExcepted("https://news.test/other?x", isPrivate = false))
        assertFalse("another site", t.isExcepted("https://shop.test/", isPrivate = false))
        assertFalse("http is another origin", t.isExcepted("http://news.test/", isPrivate = false))
        assertFalse("private tabs do not inherit normal exceptions", t.isExcepted("https://news.test/", isPrivate = true))
        t.set("https://p.test/", isPrivate = true, excepted = true)
        assertTrue(t.isExcepted("https://p.test/a", isPrivate = true))
        t.clearPrivate()
        assertFalse("private exceptions end with the private session", t.isExcepted("https://p.test/a", isPrivate = true))
        assertTrue(t.isExcepted("https://news.test/", isPrivate = false))
        t.set("https://news.test/", isPrivate = false, excepted = false)
        assertFalse(t.isExcepted("https://news.test/", isPrivate = false))
    }

    @Test
    fun storedExceptionsLoadAsOrigins() {
        val t = TrackingExceptions()
        t.loadNormal(listOf("http://localtest.me:18096/", "https://a.test/page", "about:blank"))
        assertTrue(t.isExcepted("http://localtest.me:18096/etp/x", isPrivate = false))
        assertTrue(t.isExcepted("https://a.test/", isPrivate = false))
    }
}
