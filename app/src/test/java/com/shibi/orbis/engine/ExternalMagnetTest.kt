package com.shibi.orbis.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalMagnetTest {
    private val view = "android.intent.action.VIEW"
    private val magnet = "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel"

    @Test
    fun onlyAViewOfAMagnetWithinTheLinkRulesPasses() {
        assertEquals(magnet, externalMagnetFrom(view, magnet))
        assertEquals("MAGNET:?xt=x", externalMagnetFrom(view, "MAGNET:?xt=x"))
        assertNull(externalMagnetFrom("android.intent.action.SEND", magnet))
        assertNull(externalMagnetFrom(null, magnet))
        assertNull(externalMagnetFrom(view, null))
        assertNull(externalMagnetFrom(view, "https://example.com/"))
        assertNull(externalMagnetFrom(view, "magnet:xt=x"))
        assertNull(externalMagnetFrom(view, "$magnet extra"))
        assertNull(externalMagnetFrom(view, "$magnet\u0000"))
        val long = "magnet:?xt=" + "a".repeat(16_384)
        assertNull(externalMagnetFrom(view, long))
        assertEquals(long.take(16_384), externalMagnetFrom(view, long.take(16_384)))
        // The web-link router still refuses magnets.
        assertNull(externalUrlFrom(view, magnet))
    }
}
