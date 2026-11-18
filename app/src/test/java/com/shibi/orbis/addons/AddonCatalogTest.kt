package com.shibi.orbis.addons

import com.shibi.orbis.ubo.UBO_ID
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonCatalogTest {
    @Test
    fun `catalogue holds exactly the five approved add-ons`() {
        assertEquals(
            setOf(
                "sponsorBlocker@ajay.app",
                "gdpr@cavi.au.dk",
                "{74145f27-f039-47ce-a470-a662b129930a}",
                "addon@darkreader.org",
                "{aecec67f-0d10-4fa7-b7c7-609a2db280cf}",
            ),
            AddonCatalog.entries.map { it.id }.toSet(),
        )
        assertEquals(5, AddonCatalog.entries.size)
    }

    @Test
    fun `every address is an https addons-mozilla-org latest xpi for its own id`() {
        for (addon in AddonCatalog.entries) {
            val uri = URI(addon.xpiUrl)
            assertEquals(addon.id, "https", uri.scheme)
            assertEquals(addon.id, "addons.mozilla.org", uri.host)
            assertEquals(addon.id, "/firefox/downloads/latest/${addon.id}/latest.xpi", uri.path)
        }
    }

    @Test
    fun `uBlock Origin is not in the catalogue`() {
        assertFalse(AddonCatalog.entries.any { it.id == UBO_ID })
        assertNull(AddonCatalog.find(UBO_ID))
    }

    @Test
    fun `only Violentmonkey is advanced and it is listed last`() {
        assertEquals(listOf("Violentmonkey"), AddonCatalog.entries.filter { it.advanced }.map { it.name })
        assertTrue(AddonCatalog.entries.last().advanced)
        assertTrue(AddonCatalog.entries.all { it.accessSummary.isNotBlank() && it.name.isNotBlank() })
    }
}
