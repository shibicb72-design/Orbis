package com.shibi.orbis.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DohPrefsTest {
    private class FakePrefs : DohPrefs {
        override var enabled = false
        override var provider = DohProvider.QUAD9
        override var customUrl: String? = null
    }

    @Test
    fun freshInstallIsOffAndMapsToNoResolver() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val sp = ctx.getSharedPreferences(SharedPreferencesDohPrefs.FILE_NAME, android.content.Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        val settings = DohSettings(SharedPreferencesDohPrefs(sp))
        assertFalse(settings.config.value.enabled)
        assertNull(settings.config.value.resolverUrl)
    }

    @Test
    fun eachProviderMapsToItsExactAddress() {
        val settings = DohSettings(FakePrefs())
        settings.setEnabled(true)
        val expected =
            mapOf(
                DohProvider.QUAD9 to "https://dns.quad9.net/dns-query",
                DohProvider.CLOUDFLARE to "https://cloudflare-dns.com/dns-query",
                DohProvider.MULLVAD to "https://dns.mullvad.net/dns-query",
            )
        for ((provider, url) in expected) {
            settings.setProvider(provider)
            assertEquals(url, settings.config.value.resolverUrl)
        }
    }

    @Test
    fun badCustomAddressesAreRefusedAndNotSaved() {
        val prefs = FakePrefs()
        val settings = DohSettings(prefs)
        val bad = listOf("http://x/dns-query", "dns.quad9.net", "", "https://" + "a".repeat(293), "https://user:pass@dns.example/dns-query", "https://user@dns.example/dns-query")
        assertEquals(301, bad[3].length)
        for (text in bad) {
            assertFalse(text, settings.setCustomUrl(text))
        }
        assertNull(prefs.customUrl)
        assertTrue(settings.setCustomUrl("https://dns.example/dns-query"))
        assertEquals("https://dns.example/dns-query", prefs.customUrl)
    }

    @Test
    fun customWithoutAnAddressIsOffNotADefaultProvider() {
        val settings = DohSettings(FakePrefs())
        settings.setEnabled(true)
        settings.setProvider(DohProvider.CUSTOM)
        assertNull(settings.config.value.resolverUrl)
        settings.setCustomUrl("https://dns.example/dns-query")
        assertEquals("https://dns.example/dns-query", settings.config.value.resolverUrl)
    }

    @Test
    fun sharedPreferencesKeepTheChoiceAndStartOff() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val sp = ctx.getSharedPreferences(SharedPreferencesDohPrefs.FILE_NAME, android.content.Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        assertFalse(SharedPreferencesDohPrefs(sp).enabled)
        val settings = DohSettings(SharedPreferencesDohPrefs(sp))
        settings.setEnabled(true)
        settings.setProvider(DohProvider.MULLVAD)
        val again = DohSettings(SharedPreferencesDohPrefs(sp)).config.value
        assertEquals(DohConfig(true, DohProvider.MULLVAD, null), again)
    }
}
