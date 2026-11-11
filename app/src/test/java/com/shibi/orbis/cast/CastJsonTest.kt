package com.shibi.orbis.cast

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-08: JSON from a TV, the lounge API or a page is refused when nested deeper than [CAST_JSON_MAX_DEPTH], before the
 * recursive platform tokener sees it. The scan mirrors Android's lenient tokener (single quotes, comments, unquoted
 * literals); these JVM tests parse with json.org, and no assertion depends on which parser runs after the scan.
 */
class CastJsonTest {
    @Test
    fun twoHundredThousandOpenBracketsAreRefusedWithoutThrowing() {
        val deep = "[".repeat(200_000)
        assertNull(parseJsonArray(deep))
        assertNull(parseJsonObject("{\"a\":$deep"))
        assertFalse(jsonDepthWithin(deep))
    }

    @Test
    fun aQuoteInsideASingleQuotedStringDoesNotHideTheNesting() {
        assertNull(parseJsonArray("['\"'," + "[".repeat(100_000)))
        assertNull(parseJsonArray("'\"'" + "[".repeat(100_000)))
    }

    @Test
    fun aQuoteInsideACommentDoesNotHideTheNesting() {
        assertNull(parseJsonArray("/*\"*/" + "[".repeat(100_000)))
        assertNull(parseJsonArray("[#\"\n" + "[".repeat(100_000)))
        assertNull(parseJsonArray("[//\"\n" + "[".repeat(100_000)))
    }

    @Test
    fun aQuoteInsideAnUnquotedLiteralDoesNotOpenAString() {
        // The tokener reads a' as a literal, so the brackets after it are real nesting.
        val text = "[a', " + "[".repeat(100_000) + "]".repeat(100_000) + " ']"
        assertFalse(jsonDepthWithin(text))
        assertNull(parseJsonArray(text))
    }

    @Test
    fun theArrowKeySeparatorsDoNotHideTheNesting() {
        // The tokener skips a '>' after ':' or '=', so the quote after it opens a real string.
        val arrow = "{a=>\"[\",b:" + "[".repeat(100_000) + "\""
        assertFalse(jsonDepthWithin(arrow))
        assertNull(parseJsonObject(arrow))
        val colonArrow = "{a:>\"[\",b:" + "[".repeat(100_000) + "\""
        assertFalse(jsonDepthWithin(colonArrow))
        assertNull(parseJsonObject(colonArrow))
        assertTrue(jsonDepthWithin("{a=>\"[\",b:>[1,2]}"))
    }

    @Test
    fun bracketsInsideStringsAreNotCountedButRealOnesAre() {
        val nested = "[ \"]\" ".repeat(100) + "]".repeat(100)
        assertNull(parseJsonArray(nested))
        assertTrue(jsonDepthWithin("[\"[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[\"]"))
        assertNotNull(parseJsonArray("[\"[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[\", '{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{\\'{{{']"))
    }

    @Test
    fun unterminatedStringsAndCommentsAreRefused() {
        assertFalse(jsonDepthWithin("[\"abc"))
        assertFalse(jsonDepthWithin("['abc\\'"))
        assertFalse(jsonDepthWithin("[1 /* never closed"))
        assertFalse(jsonDepthWithin("[1 // to the end"))
    }

    @Test
    fun depthThirtyTwoParsesAndThirtyThreeDoesNot() {
        assertNotNull(parseJsonArray("[".repeat(32) + "]".repeat(32)))
        assertNull(parseJsonArray("[".repeat(33) + "]".repeat(33)))
        val objects32 = "{\"a\":".repeat(31) + "{}" + "}".repeat(31)
        assertNotNull(parseJsonObject(objects32))
        assertNull(parseJsonObject("{\"a\":$objects32}"))
    }

    @Test
    fun realReceiverAndMediaStatusStillParse() {
        val receiver =
            """{"type":"RECEIVER_STATUS","requestId":1,"status":{"applications":[{"appId":"CC1AD845","displayName":"Default Media Receiver","sessionId":"s-1","transportId":"t-1"}],"volume":{"level":0.4,"muted":false}}}"""
        val snapshot = parseReceiverStatus(receiver)!!
        assertEquals("t-1", snapshot.applications.single().transportId)
        assertEquals(0.4f, snapshot.volume, 0.0001f)
        val media =
            """{"type":"MEDIA_STATUS","requestId":4,"status":[{"mediaSessionId":2,"playerState":"PLAYING","currentTime":12.5,"media":{"contentId":"https://cdn.example/v.mp4","contentType":"video/mp4","streamType":"BUFFERED","duration":90.0}}]}"""
        val item = parseMediaStatus(media).single()
        assertEquals("PLAYING", item.playerState)
        assertEquals(90.0, item.durationSec, 0.0)
        assertEquals(item, parseMediaStatus(media).single())
        assertNotNull(parseJsonObject(media))
    }

    @Test
    fun aHostileNestedTvPayloadIsIgnoredByTheSessionParsers() {
        val hostile = "{\"type\":\"MEDIA_STATUS\",\"status\":" + "[".repeat(100_000)
        assertTrue(parseMediaStatus(hostile).isEmpty())
        assertNull(parseReceiverStatus(hostile))
        assertNull(parseCastError(hostile))
        assertNull(parseMdxScreenId(hostile))
        assertEquals("", payloadType(hostile))
    }

    @Test
    fun aStackOverflowInTheSessionLoopEndsTheSessionAndDoesNotPropagate() {
        var crashes = 0
        val cause = runGuardedCastSession(onCrash = { crashes += 1 }) { throw StackOverflowError() }
        assertEquals("error err=StackOverflowError", cause)
        assertEquals(1, crashes)
        val failed = runGuardedCastSession(onCrash = { crashes += 1 }) { throw IllegalStateException("x") }
        assertEquals("error err=IllegalStateException", failed)
        assertEquals(2, crashes)
        assertEquals("closed", runGuardedCastSession(onCrash = { crashes += 1 }) { "closed" })
        assertEquals(2, crashes)
    }

    @Test(expected = OutOfMemoryError::class)
    fun outOfMemoryIsNotSwallowed() {
        runGuardedCastSession(onCrash = {}) { throw OutOfMemoryError() }
    }

    /** No other file in cast/ or youtube/ parses foreign JSON text directly. */
    @Test
    fun onlyCastJsonParsesJsonText() {
        val root = File("src/main/java/com/shibi/orbis")
        assertTrue("run from the app module", root.isDirectory)
        val call = Regex("""\bJSON(Object|Array)\(\s*[^)\s]""")
        // YoutubeAdSavings.decode reads the file Orbis itself wrote (ad-savings totals), not text from the network.
        val ownData = setOf("CastJson.kt", "YoutubeAdSavings.kt")
        val offenders =
            listOf("cast", "youtube").flatMap { dir ->
                File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" && it.name !in ownData }.flatMap { file ->
                    file.readLines().mapIndexedNotNull { index, line ->
                        if (call.containsMatchIn(line)) "${file.name}:${index + 1}: ${line.trim()}" else null
                    }
                }.toList()
            }
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }
}
