package com.shibi.orbis.engine

import com.shibi.orbis.ui.browser.readerHtmlWithoutMedia
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ReaderTest {
    @Test
    fun parsesTheContentScriptsMessages() {
        assertEquals(ReaderMessage.Readerable("https://a.test/x", true), parseReaderMessage(JSONObject("""{"type":"readerable","url":"https://a.test/x","value":true}""")))
        val a = parseReaderMessage(JSONObject("""{"type":"article","url":"https://a.test/x","article":{"title":"T","byline":"B","siteName":"S","content":"<p>hi</p>"}}""")) as ReaderMessage.Article
        assertEquals(ReaderArticle("https://a.test/x", "T", "B", "S", "<p>hi</p>"), a.article)
        assertNull("no article", (parseReaderMessage(JSONObject("""{"type":"article","url":"u","article":null}""")) as ReaderMessage.Article).article)
        assertNull("empty content is no article", (parseReaderMessage(JSONObject("""{"type":"article","url":"u","article":{"content":" "}}""")) as ReaderMessage.Article).article)
        assertNull(parseReaderMessage(JSONObject("""{"type":"other"}""")))
    }

    @Test
    fun readerHtmlDropsImagesAndEmbeddedMedia() {
        val html = "<p>a<img src=\"x.png\">b</p><figure><img src=y><figcaption>c</figcaption></figure><video src=v></video><iframe src=f></iframe><p>d</p>"
        val out = readerHtmlWithoutMedia(html)
        assertFalse(out.contains("<img"))
        assertFalse(out.contains("<video"))
        assertFalse(out.contains("<iframe"))
        assertTrue(out.contains("<p>ab</p>"))
        assertTrue(out.contains("<p>d</p>"))
    }
}
