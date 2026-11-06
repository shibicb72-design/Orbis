package com.shibi.orbis.engine

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SitePermissionCodecTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun d(
        kind: SitePermissionKind = SitePermissionKind.LOCATION,
        origin: String = "https://a.example",
        embedder: String? = null,
        allow: Boolean = true,
    ) = SitePermissionDecision(SitePermissionKey(kind, origin, embedder), allow)

    private fun entryJson(vararg pairs: Pair<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in pairs) o.put(k, v ?: JSONObject.NULL)
        return o
    }

    private fun docOf(vararg entries: JSONObject): String =
        JSONObject().put("version", 1).put("entries", JSONArray(entries.toList())).toString()

    @Test
    fun roundTripKeepsEveryField() {
        val list =
            listOf(
                d(),
                d(SitePermissionKind.CAMERA, "https://xn--bcher-kva.example", allow = false),
                d(SitePermissionKind.MICROPHONE, "http://[::1]:8080", embedder = "https://top.example:8443", allow = true),
                d(SitePermissionKind.LOCATION, "https://embed.example", embedder = "https://top.example", allow = false),
            )
        assertEquals(list, SitePermissionCodec.decode(SitePermissionCodec.encode(list)))
    }

    @Test
    fun emptyListRoundTrips() {
        assertEquals(emptyList<SitePermissionDecision>(), SitePermissionCodec.decode(SitePermissionCodec.encode(emptyList())))
    }

    @Test
    fun encodedTextHoldsOnlyOriginKindAndAllow() {
        val text = SitePermissionCodec.encode(listOf(d(embedder = "https://top.example")))
        val o = JSONObject(text).getJSONArray("entries").getJSONObject(0)
        assertEquals(setOf("kind", "origin", "embedder", "allow"), o.keys().asSequence().toSet())
    }

    @Test
    fun garbageDecodesToEmptyAndNeverThrows() {
        for (input in listOf(null, "", "   ", "not json", "[1,2,3]", "{\"entries\": 5}", "{\"version\":1", "{}", "null", "\u0000")) {
            assertEquals("input: $input", emptyList<SitePermissionDecision>(), SitePermissionCodec.decode(input))
        }
    }

    @Test
    fun truncatedFileDecodesToEmpty() {
        val full = SitePermissionCodec.encode(listOf(d(), d(origin = "https://b.example")))
        assertEquals(emptyList<SitePermissionDecision>(), SitePermissionCodec.decode(full.substring(0, full.length / 2)))
    }

    @Test
    fun badEntriesAreSkippedAndGoodOnesKept() {
        val good = entryJson("kind" to "LOCATION", "origin" to "https://good.example", "allow" to true)
        val text =
            docOf(
                entryJson("kind" to "TELEPORT", "origin" to "https://a.example", "allow" to true),
                entryJson("kind" to "CAMERA", "origin" to "https://a.example"),
                entryJson("kind" to "CAMERA", "origin" to "https://a.example", "allow" to "maybe"),
                entryJson("kind" to "CAMERA", "origin" to "https://a.example", "allow" to 5),
                entryJson("kind" to "CAMERA", "allow" to true),
                entryJson("origin" to "https://a.example", "allow" to true),
                good,
            )
        assertEquals(listOf(SitePermissionDecision(SitePermissionKey(SitePermissionKind.LOCATION, "https://good.example"), true)), SitePermissionCodec.decode(text))
    }

    @Test
    fun nonObjectEntriesAreSkipped() {
        val array = JSONArray().put(5).put("x").put(JSONObject.NULL)
        array.put(entryJson("kind" to "LOCATION", "origin" to "https://a.example", "allow" to false))
        val text = JSONObject().put("version", 1).put("entries", array).toString()
        assertEquals(listOf(d(allow = false)), SitePermissionCodec.decode(text))
    }

    @Test
    fun originsThatAreNotInTheirNormalFormAreRejected() {
        val text =
            docOf(
                // A path, a different case, a default port, userinfo, a Unicode host, a foreign scheme.
                entryJson("kind" to "CAMERA", "origin" to "https://a.example/path", "allow" to true),
                entryJson("kind" to "CAMERA", "origin" to "HTTPS://A.EXAMPLE", "allow" to true),
                entryJson("kind" to "CAMERA", "origin" to "https://a.example:443", "allow" to true),
                entryJson("kind" to "CAMERA", "origin" to "https://u@a.example", "allow" to true),
                entryJson("kind" to "CAMERA", "origin" to "https://bücher.example", "allow" to true),
                entryJson("kind" to "CAMERA", "origin" to "file:///etc/passwd", "allow" to true),
                entryJson("kind" to "CAMERA", "origin" to "https://ok.example", "embedder" to "javascript:alert(1)", "allow" to true),
            )
        assertEquals(emptyList<SitePermissionDecision>(), SitePermissionCodec.decode(text))
    }

    @Test
    fun nullEmbedderMeansNone() {
        val text = docOf(entryJson("kind" to "CAMERA", "origin" to "https://a.example", "embedder" to null, "allow" to true))
        assertEquals(listOf(d(SitePermissionKind.CAMERA)), SitePermissionCodec.decode(text))
    }

    @Test
    fun laterDuplicateReplacesEarlierOne() {
        val a = entryJson("kind" to "CAMERA", "origin" to "https://a.example", "allow" to true)
        val b = entryJson("kind" to "CAMERA", "origin" to "https://a.example", "allow" to false)
        assertEquals(listOf(d(SitePermissionKind.CAMERA, allow = false)), SitePermissionCodec.decode(docOf(a, b)))
    }

    @Test
    fun decodedListIsCapped() {
        val many = (0 until SitePermissionCodec.MAX_ENTRIES + 10).map { d(origin = "https://site$it.example") }
        assertEquals(SitePermissionCodec.MAX_ENTRIES, SitePermissionCodec.decode(SitePermissionCodec.encode(many)).size)
    }

    @Test
    fun fileStoreRoundTripsAndLeavesNoTempFile() {
        val file = File(tmp.root, "sub/site_permissions.json")
        val store = FileSitePermissionStore(file)
        val list = listOf(d(), d(SitePermissionKind.CAMERA, allow = false))
        store.save(list)
        assertEquals(list, store.load())
        store.save(emptyList())
        assertEquals(emptyList<SitePermissionDecision>(), store.load())
        assertFalse(File(tmp.root, "sub/site_permissions.json.tmp").exists())
    }

    @Test
    fun fileStoreMissingOrCorruptFileIsEmpty() {
        val file = File(tmp.root, "site_permissions.json")
        val store = FileSitePermissionStore(file)
        assertEquals(emptyList<SitePermissionDecision>(), store.load())
        file.writeBytes(byteArrayOf(0x7B, 0x00, 0x7F, 0x22))
        assertEquals(emptyList<SitePermissionDecision>(), store.load())
        file.writeText("{\"version\":1,\"entries\":", Charsets.UTF_8)
        assertEquals(emptyList<SitePermissionDecision>(), store.load())
        assertTrue(file.isFile)
    }

    @Test
    fun fileStoreFailedSaveKeepsThePreviousFile() {
        val file = File(tmp.root, "site_permissions.json")
        val store = FileSitePermissionStore(file)
        store.save(listOf(d()))
        // A directory where the temp file must go makes the next save fail before the move.
        File(tmp.root, "site_permissions.json.tmp").mkdirs()
        try {
            store.save(listOf(d(origin = "https://other.example")))
        } catch (e: java.io.IOException) {
            // expected
        }
        assertEquals(listOf(d()), store.load())
    }
}
