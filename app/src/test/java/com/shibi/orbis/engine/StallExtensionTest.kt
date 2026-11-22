package com.shibi.orbis.engine

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallExtensionTest {
    // Unit tests run in the module directory (app/).
    private val folder = File("src/main/assets/extensions/stall")

    private fun manifest() = JSONObject(File(folder, "manifest.json").readText())

    @Test(timeout = 10_000)
    fun `the asset folder and the id are the ones the app installs`() {
        assertEquals("resource://android/assets/extensions/stall/", StallExtension.ASSET_URL)
        assertEquals(StallExtension.ID, manifest().getJSONObject("browser_specific_settings").getJSONObject("gecko").getString("id"))
        assertTrue(isBuiltInExtension(StallExtension.ID))
    }

    @Test(timeout = 10_000)
    fun `the manifest has one content script and no permissions or background`() {
        val m = manifest()
        assertEquals(
            setOf("manifest_version", "name", "version", "description", "browser_specific_settings", "content_scripts"),
            m.keys().asSequence().toSet(),
        )
        assertFalse(m.has("permissions"))
        assertFalse(m.has("optional_permissions"))
        assertFalse(m.has("background"))
        assertFalse(m.toString().contains("webRequest"))
        val scripts = m.getJSONArray("content_scripts")
        assertEquals(1, scripts.length())
        val cs = scripts.getJSONObject(0)
        assertEquals(setOf("matches", "js", "run_at", "all_frames"), cs.keys().asSequence().toSet())
        assertEquals("[\"http://*/*\",\"https://*/*\"]", cs.getJSONArray("matches").toString())
        assertEquals("[\"content.js\"]", cs.getJSONArray("js").toString())
        assertEquals("document_idle", cs.getString("run_at"))
        assertTrue(cs.getBoolean("all_frames"))
        assertEquals(setOf("manifest.json", "content.js"), folder.list()!!.toSet())
    }
}
