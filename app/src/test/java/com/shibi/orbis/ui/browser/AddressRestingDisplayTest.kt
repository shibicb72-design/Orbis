package com.shibi.orbis.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class AddressRestingDisplayTest {
    @Test
    fun httpsRestsAsHostOnly() {
        assertEquals(
            RestingAddress("www.example.org", isHost = true, insecurePrefix = null),
            addressRestingDisplay("https://www.example.org/path?q=1#x"),
        )
    }

    @Test
    fun lookalikeKeepsTheRealDomainAtTheEnd() {
        val d = addressRestingDisplay("http://accounts.google.com.secure-login-verify.10.0.2.2.nip.io:8765/spoof")
        assertEquals("accounts.google.com.secure-login-verify.10.0.2.2.nip.io:8765", d.text)
        assertEquals(true, d.isHost)
        assertEquals("http://", d.insecurePrefix)
    }

    @Test
    fun userinfoIsNeverShown() {
        assertEquals("evil.example", addressRestingDisplay("https://www.paypal.com@evil.example/").text)
        assertEquals("evil.example", addressRestingDisplay("https://a@b@evil.example\\x").text)
    }

    @Test
    fun nonWebTextIsUnchanged() {
        listOf("about:blank", "search words", "https://", "file:///x").forEach {
            assertEquals(it, RestingAddress(it, isHost = false, insecurePrefix = null), addressRestingDisplay(it))
        }
    }
}
