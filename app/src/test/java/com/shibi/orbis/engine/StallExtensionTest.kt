package com.shibi.orbis.engine

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `the manifest has one content script, only the messaging permissions and no background`() {
        val m = manifest()
        assertEquals(
            setOf("manifest_version", "name", "version", "description", "browser_specific_settings", "content_scripts", "permissions"),
            m.keys().asSequence().toSet(),
        )
        assertEquals(
            listOf("nativeMessaging", "nativeMessagingFromContent", "geckoViewAddons"),
            m.getJSONArray("permissions").let { a -> (0 until a.length()).map { a.getString(it) } },
        )
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

    @Test(timeout = 10_000)
    fun `the script messages the app on the name the app listens on`() {
        assertEquals("orbisStall", StallExtension.NATIVE_APP)
        assertTrue(File(folder, "content.js").readText().contains("NATIVE_APP: \"${StallExtension.NATIVE_APP}\""))
    }

    private fun msg(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { put(it.first, it.second) } }

    @Test(timeout = 10_000)
    fun `the two types from the stall extension are read`() {
        assertEquals(StreamSignal.DROPPED, parseStallMessage(StallExtension.ID, msg("type" to "dropped")))
        assertEquals(StreamSignal.PLAYING, parseStallMessage(StallExtension.ID, msg("type" to "playing")))
    }

    @Test(timeout = 10_000)
    fun `an unknown type, a malformed message or extra data is ignored`() {
        val id = StallExtension.ID
        for (type in listOf("Dropped", "DROPPED", "back", "stop", "", " dropped", "dropped ")) {
            assertNull(type, parseStallMessage(id, msg("type" to type)))
        }
        assertNull(parseStallMessage(id, msg("type" to 1)))
        assertNull(parseStallMessage(id, msg("type" to JSONArray().put("dropped"))))
        assertNull(parseStallMessage(id, msg("kind" to "dropped")))
        assertNull(parseStallMessage(id, JSONObject()))
        assertNull(parseStallMessage(id, msg("type" to "dropped", "url" to "https://cdn.example/live.m3u8")))
        assertNull(parseStallMessage(id, "dropped"))
        assertNull(parseStallMessage(id, null))
    }

    @Test(timeout = 10_000)
    fun `a message from another extension is ignored`() {
        for (other in listOf(CastExtensionHolder.ID, CompatExtensionHolder.ID, "someone@example.com", "", null)) {
            assertNull(other, parseStallMessage(other, msg("type" to "dropped")))
            assertNull(other, parseStallMessage(other, msg("type" to "playing")))
        }
    }
}
