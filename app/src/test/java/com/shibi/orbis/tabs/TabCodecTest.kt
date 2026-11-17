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

    // ---- tab groups (version 2) ----

    private fun grouped(id: Long, group: Long?) = tab(id).copy(groupId = group)

    @Test
    fun aVersionOneFileDecodesToTheSameTabsWithNoGroups() {
        val v1 =
            """{"version":1,"selected":2,"tabs":[
              {"id":1,"url":"https://a.example/","title":"A","desktop":false,"state":"S1"},
              {"id":2,"url":"https://b.example/","title":"B","desktop":true}]}"""
        val decoded = TabCodec.decode(v1)
        assertEquals(
            TabsSnapshot(
                listOf(
                    PersistedTab(1, "https://a.example/", "A", false, "S1"),
                    PersistedTab(2, "https://b.example/", "B", true, null),
                ),
                selectedId = 2,
            ),
            decoded,
        )
        assertTrue(decoded!!.groups.isEmpty())
        // Even a stray group in a version 1 file is not read: that version has none.
        val strayGroups =
            """{"version":1,"selected":1,"groups":[{"id":4,"name":"W","color":"RED"}],
              "tabs":[{"id":1,"url":"https://a.example/","group":4}]}"""
        val stray = TabCodec.decode(strayGroups)!!
        assertTrue(stray.groups.isEmpty())
        assertNull(stray.tabs.single().groupId)
    }

    @Test
    fun roundTripKeepsGroupIdsNamesColoursCollapsedFlagsAndMembership() {
        val work = TabGroup(7, "Work", TabGroupColor.PURPLE, collapsed = true)
        val trip = TabGroup(3, "Trip \"2027\" été", TabGroupColor.CYAN, collapsed = false)
        val snapshot =
            TabsSnapshot(
                listOf(grouped(1, 7), grouped(2, 7), grouped(3, null), grouped(4, 3)),
                selectedId = 4,
                groups = listOf(work, trip),
            )
        val json = TabCodec.encode(snapshot)
        assertEquals(2, org.json.JSONObject(json).getInt("version"))
        assertEquals(snapshot, TabCodec.decode(json))
    }

    @Test
    fun aTabNamingAMissingGroupDecodesUngroupedAndEmptyGroupsAreDropped() {
        val text =
            """{"version":2,"selected":1,"groups":[{"id":5,"name":"Kept","color":"GREEN","collapsed":false},
              {"id":6,"name":"Empty","color":"RED"}],
              "tabs":[{"id":1,"url":"https://a.example/","group":5},{"id":2,"url":"https://b.example/","group":99}]}"""
        val decoded = TabCodec.decode(text)!!
        assertEquals(listOf(5L, null), decoded.tabs.map { it.groupId })
        assertEquals(listOf(TabGroup(5, "Kept", TabGroupColor.GREEN, false)), decoded.groups)
    }

    @Test
    fun badGroupEntriesAreSkippedAndAnUnknownColourIsGrey() {
        val text =
            """{"version":2,"selected":1,"groups":[7,{"id":0,"name":"Zero"},{"name":"No id"},{"id":1,"name":"   "},
              {"id":2,"name":"${"n".repeat(MAX_TAB_GROUP_NAME_CHARS + 1)}"},
              {"id":3,"name":" Fine ","color":"ORANGE","collapsed":null},{"id":3,"name":"Dupe","color":"RED"},
              {"id":4,"name":"NoColour"}],
              "tabs":[{"id":1,"url":"https://a.example/","group":1},{"id":2,"url":"https://a.example/","group":2},
              {"id":3,"url":"https://a.example/","group":3},{"id":4,"url":"https://a.example/","group":4},
              {"id":5,"url":"https://a.example/","group":"x"}]}"""
        val decoded = TabCodec.decode(text)!!
        assertEquals(
            listOf(TabGroup(3, "Fine", TabGroupColor.GREY, false), TabGroup(4, "NoColour", TabGroupColor.GREY, false)),
            decoded.groups,
        )
        assertEquals(listOf(null, null, 3L, 4L, null), decoded.tabs.map { it.groupId })
    }

    @Test
    fun aGroupsTabsAreMadeContiguousOnDecode() {
        val text =
            """{"version":2,"selected":4,"groups":[{"id":1,"name":"A"},{"id":2,"name":"B"}],
              "tabs":[{"id":1,"url":"https://a.example/","group":1},{"id":2,"url":"https://a.example/"},
              {"id":3,"url":"https://a.example/","group":2},{"id":4,"url":"https://a.example/","group":1},
              {"id":5,"url":"https://a.example/","group":2}]}"""
        val decoded = TabCodec.decode(text)!!
        assertEquals(listOf(1L, 4L, 2L, 3L, 5L), decoded.tabs.map { it.id })
        assertEquals(4L, decoded.selectedId)
        assertTrue(groupsAreContiguous(decoded.tabs) { it.groupId })
    }

    @Test
    fun anUngroupedSnapshotWritesNoGroupFieldOnItsTabs() {
        val json = org.json.JSONObject(TabCodec.encode(TabsSnapshot(listOf(tab(1)), 1)))
        assertFalse(json.getJSONArray("tabs").getJSONObject(0).has("group"))
        assertEquals(0, json.getJSONArray("groups").length())
    }
}
