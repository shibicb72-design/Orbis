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
    fun cleanAndUnknownMessages() {
        assertEquals(CompatMessage.Clean, parseCompatMessage(JSONObject().put("type", "clean")))
        assertNull(parseCompatMessage(JSONObject().put("type", "probe")))
        assertEquals(
            CompatMessage.Sample(PageSample(title = "", headlines = emptyList(), visibleText = "", blockPath = false)),
            parseCompatMessage(JSONObject().put("type", "sample")),
        )
    }
}
