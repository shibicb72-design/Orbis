package com.shibi.orbis.diagnostics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagMessagesTest {
    @Test
    fun eachExpectedShapeParses() {
        assertEquals(DiagMessage.Media(3, true, SourceKind.BLOB, true, false), parseDiagMessage(media(3)))
        assertEquals(DiagMessage.Hls("maybe", ""), parseDiagMessage(JSONObject().put("type", "hls").put("mpegurl", "maybe").put("xmpegurl", "")))
        val e = parseDiagMessage(event(1, "error")) as DiagMessage.Event
        assertEquals(MediaEvent.ERROR, e.event)
        assertEquals(2, e.errorCode)
        val r = parseDiagMessage(event(1, "resize")) as DiagMessage.Event
        assertEquals(1280 to 720, r.width to r.height)
        assertEquals(DiagMessage.Sample(1, 8.0, 1280, 720, 1520, 3), parseDiagMessage(sample(1)))
        assertEquals(DiagMessage.Page(DiagMessage.PageWhat.OFFLINE), parseDiagMessage(JSONObject().put("type", "page").put("what", "offline")))
        for (name in MediaEvent.entries) assertEquals(name, (parseDiagMessage(event(1, name.wire)) as DiagMessage.Event).event)
    }

    @Test
    fun anExtraFieldDropsTheMessage() {
        assertNull(parseDiagMessage(media(1).put("url", "https://example.com/a.mp4")))
        assertNull(parseDiagMessage(event(1, "playing").put("title", "A title")))
        assertNull(parseDiagMessage(sample(1).put("x", 1)))
        // A field that belongs to another event kind is extra too.
        assertNull(parseDiagMessage(event(1, "playing").put("code", 2)))
        assertNull(parseDiagMessage(JSONObject().put("type", "page").put("what", "hidden").put("text", "x")))
    }

    @Test
    fun aMissingOrWrongFieldDropsTheMessage() {
        assertNull(parseDiagMessage(media(1).apply { remove("same") }))
        assertNull(parseDiagMessage(media(1).put("kind", "image")))
        assertNull(parseDiagMessage(media(1).put("source", "https://example.com/")))
        assertNull(parseDiagMessage(media(1).put("same", "yes")))
        assertNull(parseDiagMessage(media(0)))
        assertNull(parseDiagMessage(media(10_001)))
        assertNull(parseDiagMessage(event(1, "playing").put("ready", 5)))
        assertNull(parseDiagMessage(event(1, "playing").put("pos", -1)))
        assertNull(parseDiagMessage(event(1, "playing").put("pos", "1.0")))
        assertNull(parseDiagMessage(event(1, "playing").put("ahead", Double.NaN.toString())))
        assertNull(parseDiagMessage(event(1, "timeupdate")))
        assertNull(parseDiagMessage(event(1, "resize").put("w", 1.5)))
        assertNull(parseDiagMessage(JSONObject().put("type", "hls").put("mpegurl", "yes").put("xmpegurl", "")))
        assertNull(parseDiagMessage(JSONObject().put("type", "page").put("what", "Secret title")))
        assertNull(parseDiagMessage(JSONObject().put("type", "hello")))
        assertNull(parseDiagMessage(JSONObject()))
    }
}
