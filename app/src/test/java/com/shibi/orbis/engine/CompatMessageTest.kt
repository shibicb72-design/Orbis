package com.shibi.orbis.engine

import com.shibi.orbis.compat.PageSample
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompatMessageTest {
    @Test
    fun aSampleIsParsedAndCapped() {
        val heads = JSONArray()
        repeat(7) { heads.put("h$it" + "x".repeat(300)) }
        val msg =
            parseCompatMessage(
                JSONObject().put("type", "sample").put("title", "Unsupported browser").put("headlines", heads)
                    .put("text", "y".repeat(5000)).put("blockPath", true),
            )
        val sample = (msg as CompatMessage.Sample).sample
        assertEquals("Unsupported browser", sample.title)
        assertEquals(5, sample.headlines.size)
        assertEquals(200, sample.headlines[0].length)
        assertEquals(801, sample.visibleText.length)
        assertEquals(true, sample.blockPath)
    }

    @Test
    fun cleanCarriesOnlyEmptyOrNotEmpty_anythingElseIsDropped() {
        fun clean(page: Any?) = parseCompatMessage(JSONObject().put("type", "clean").also { if (page != null) it.put("page", page) })
        assertEquals(CompatMessage.Clean(empty = true), clean("empty"))
        assertEquals(CompatMessage.Clean(empty = false), clean("not-empty"))
        assertNull(clean(null))
        assertNull(clean(""))
        assertNull(clean("EMPTY"))
        assertNull(clean("empty "))
        assertNull(clean("maybe"))
        assertNull(clean(true))
        assertNull(clean(1))
        assertNull(clean(JSONObject().put("page", "empty")))
    }

    @Test
    fun cleanAndUnknownMessages() {
        assertNull(parseCompatMessage(JSONObject().put("type", "probe")))
        assertEquals(
            CompatMessage.Sample(PageSample(title = "", headlines = emptyList(), visibleText = "", blockPath = false)),
            parseCompatMessage(JSONObject().put("type", "sample")),
        )
    }
}
