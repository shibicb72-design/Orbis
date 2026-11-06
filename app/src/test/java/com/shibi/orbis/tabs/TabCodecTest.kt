package com.shibi.orbis.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabCodecTest {
    private fun tab(id: Long, url: String = "https://example.com/", state: String? = "S$id") =
        PersistedTab(id, url, "Title $id", desktop = id % 2 == 0L, savedState = state)

    @Test
    fun roundTrip() {
        val snapshot = TabsSnapshot(listOf(tab(1), tab(2, state = null), tab(3, url = "")), selectedId = 2)
        val decoded = TabCodec.decode(TabCodec.encode(snapshot))
        assertEquals(snapshot, decoded)
    }

    @Test
    fun encodedJsonCarriesNoPrivateMarker() {
        val json = TabCodec.encode(TabsSnapshot(listOf(tab(1), tab(2)), selectedId = 1))
        assertFalse(json.contains("private", ignoreCase = true))
        assertFalse(org.json.JSONObject(json).getJSONArray("tabs").getJSONObject(0).has("private"))
    }

    @Test
    fun anUnknownPrivateFieldDecodesAsAnOrdinaryTab() {
        val json =
            """{"version":1,"selected":1,"tabs":[{"id":1,"url":"https://example.com/","title":"T","desktop":false,"private":true}]}"""
        val decoded = TabCodec.decode(json)
        assertEquals(TabsSnapshot(listOf(PersistedTab(1, "https://example.com/", "T", false, null)), 1), decoded)
    }

    @Test
    fun roundTripKeepsTrickyText() {
        val odd = PersistedTab(9, "https://example.com/?q=\"a\"&b=\\", "Tab \"quoted\"\nline", true, "{\"x\":[1,2]} ")
        val decoded = TabCodec.decode(TabCodec.encode(TabsSnapshot(listOf(odd), 9)))
        assertEquals(listOf(odd), decoded?.tabs)
    }

    @Test
    fun missingEmptyAndCorruptInputDecodeToNull() {
        assertNull(TabCodec.decode(null))
        assertNull(TabCodec.decode(""))
        assertNull(TabCodec.decode("   "))
        assertNull(TabCodec.decode("not json"))
        assertNull(TabCodec.decode("{\"tabs\":"))
        assertNull(TabCodec.decode("[1,2,3]"))
        assertNull(TabCodec.decode("{\"tabs\":[]}"))
        assertNull(TabCodec.decode("{\"tabs\":\"x\"}"))
    }

    @Test
    fun badEntriesAreSkippedGoodOnesKept() {
        val text =
            """{"selected":2,"tabs":[7,{"id":0,"url":"https://a.example/"},{"url":"https://no-id.example/"},
              {"id":2,"url":"https://ok.example/","title":null,"desktop":null},
              {"id":2,"url":"https://dupe.example/"}]}"""
        val decoded = TabCodec.decode(text)!!
        assertEquals(1, decoded.tabs.size)
        assertEquals(2L, decoded.tabs[0].id)
        assertEquals("https://ok.example/", decoded.tabs[0].url)
        assertEquals("", decoded.tabs[0].title)
        assertEquals(false, decoded.tabs[0].desktop)
    }

    @Test
    fun oversizeSavedStateIsDroppedButUrlSurvives() {
        val big = "x".repeat(TabCodec.MAX_SAVED_STATE_CHARS + 1)
        val encoded = TabCodec.encode(TabsSnapshot(listOf(tab(1, state = big)), 1))
        assertTrue(encoded.length < TabCodec.MAX_SAVED_STATE_CHARS)
        val decoded = TabCodec.decode(encoded)!!
        assertNull(decoded.tabs[0].savedState)
        assertEquals("https://example.com/", decoded.tabs[0].url)

        // A hand-edited file with an oversize state is also refused on the way in.
        val handMade = """{"selected":1,"tabs":[{"id":1,"url":"https://example.com/","state":"$big"}]}"""
        assertNull(TabCodec.decode(handMade)!!.tabs[0].savedState)
    }

    @Test
    fun exactlyAtTheLimitIsKept() {
        val ok = "y".repeat(TabCodec.MAX_SAVED_STATE_CHARS)
        val decoded = TabCodec.decode(TabCodec.encode(TabsSnapshot(listOf(tab(1, state = ok)), 1)))!!
        assertEquals(ok, decoded.tabs[0].savedState)
    }

    @Test
    fun totalSavedStateBudgetFavoursTheSelectedTab() {
        val chunk = "z".repeat(TabCodec.MAX_SAVED_STATE_CHARS)
        val tabs = (1L..20L).map { tab(it, state = chunk) }
        val decoded = TabCodec.decode(TabCodec.encode(TabsSnapshot(tabs, selectedId = 20)))!!
        val kept = decoded.tabs.count { it.savedState != null }
        assertEquals(TabCodec.MAX_TOTAL_SAVED_STATE_CHARS / TabCodec.MAX_SAVED_STATE_CHARS, kept)
        assertNotNull(decoded.tabs.first { it.id == 20L }.savedState)
    }

    @Test
    fun nonWebFallbackUrlIsDropped() {
        for (bad in listOf("javascript:alert(1)", "file:///etc/passwd", "intent://x#Intent;end", "data:text/html,hi", "about:blank", "content://x/y")) {
            val text = """{"selected":1,"tabs":[{"id":1,"url":${org.json.JSONObject.quote(bad)},"title":"t"}]}"""
            val decoded = TabCodec.decode(text)!!
            assertEquals("url $bad", "", decoded.tabs[0].url)
            assertEquals("t", decoded.tabs[0].title)
        }
        val ok = TabCodec.decode("""{"selected":1,"tabs":[{"id":1,"url":"HTTPS://Example.com/"}]}""")!!
        assertEquals("HTTPS://Example.com/", ok.tabs[0].url)
    }

    @Test
    fun unknownSelectedFallsBackToFirstTab() {
        val decoded = TabCodec.decode(TabCodec.encode(TabsSnapshot(listOf(tab(4), tab(5)), selectedId = 99)))!!
        assertEquals(4L, decoded.selectedId)
    }

    @Test
    fun tabCountIsCapped() {
        val many = (1L..(MAX_TABS + 10L)).map { tab(it, state = null) }
        val decoded = TabCodec.decode(TabCodec.encode(TabsSnapshot(many, 1)))!!
        assertEquals(MAX_TABS, decoded.tabs.size)
    }
}
