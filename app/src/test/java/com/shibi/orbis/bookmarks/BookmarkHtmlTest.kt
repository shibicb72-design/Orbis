package com.shibi.orbis.bookmarks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkHtmlTest {
    private fun parse(html: String) = BookmarkHtml.parse(html)

    // ---- export ------------------------------------------------------------------------------------------------

    @Test
    fun exportHasTheNetscapeStructure() {
        val html = BookmarkHtml.export(listOf(Bookmark(1, "https://a.example/", "A", 1_600_000_000_999)))
        assertTrue(html.startsWith("<!DOCTYPE NETSCAPE-Bookmark-file-1>"))
        assertTrue(html.contains("charset=UTF-8"))
        assertTrue(html.contains("<TITLE>"))
        assertTrue(html.contains("<H1>"))
        assertTrue(html.contains("<DL><p>"))
        assertTrue(html.contains("<DT><A HREF=\"https://a.example/\" ADD_DATE=\"1600000000\">A</A>"))
        assertTrue(html.trimEnd().endsWith("</DL><p>"))
    }

    @Test
    fun exportEscapesUrlAndTitle() {
        val html = BookmarkHtml.export(
            listOf(Bookmark(1, "https://a.example/?x=1&y=\"2\"<b>", "Tom & \"Jerry\" <i>", 0)),
        )
        assertTrue(html, html.contains("HREF=\"https://a.example/?x=1&amp;y=&quot;2&quot;&lt;b&gt;\""))
        assertTrue(html, html.contains(">Tom &amp; &quot;Jerry&quot; &lt;i&gt;</A>"))
        assertFalse(html.contains("<i>"))
    }

    @Test
    fun exportThenParseRoundTrips() {
        val list = listOf(
            Bookmark(1, "https://a.example/?x=1&y=2", "Tom & \"Jerry\" <b>", 1_600_000_000_000),
            Bookmark(2, "https://b.example/café", "世界 😀", 1_700_000_000_000),
            Bookmark(3, "http://c.example/", "http://c.example/", 0),
        )
        val parsed = parse(BookmarkHtml.export(list))
        assertEquals(0, parsed.skipped)
        assertEquals(list.map { it.url }, parsed.items.map { it.url })
        assertEquals(list.map { it.title }, parsed.items.map { it.title })
        assertEquals(listOf<Long?>(1_600_000_000_000, 1_700_000_000_000, 0L), parsed.items.map { it.addedAtMillis })
    }

    // ---- real-world exports ------------------------------------------------------------------------------------

    private val firefox = """<!DOCTYPE NETSCAPE-Bookmark-file-1>
<!-- This is an automatically generated file.
     It will be read and overwritten.
     DO NOT EDIT! -->
<META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'self'; script-src 'none'; img-src data: *; object-src 'none'"></meta>
<TITLE>Bookmarks</TITLE>
<H1>Bookmarks Menu</H1>

<DL><p>
    <DT><H3 ADD_DATE="1600000000" LAST_MODIFIED="1600000001">Mozilla Firefox</H3>
    <DL><p>
        <DT><A HREF="https://support.mozilla.org/products/firefox" ADD_DATE="1600000002" LAST_MODIFIED="1600000003" ICON_URI="fake-favicon-uri:https://support.mozilla.org/favicon.ico" ICON="data:image/png;base64,iVBORw0KGgo=">Help and Tutorials</A>
        <DT><A HREF="https://www.mozilla.org/contribute/" ADD_DATE="1600000004" LAST_MODIFIED="1600000005">Get Involved</A>
    </DL><p>
    <DT><H3 ADD_DATE="1600000006" LAST_MODIFIED="1600000007" PERSONAL_TOOLBAR_FOLDER="true">Bookmarks Toolbar</H3>
    <DD>Add bookmarks to this folder to see them displayed on the Bookmarks Toolbar
    <DL><p>
        <DT><A HREF="place:type=6&amp;sort=14&amp;mobile=1" ADD_DATE="1600000008">Recent Tags</A>
        <DT><A HREF="https://example.org/a?b=1&amp;c=2" ADD_DATE="1600000009" TAGS="x,y">Example &amp; Co</A>
    </DL><p>
</DL>
"""

    private val chrome = """<!DOCTYPE NETSCAPE-Bookmark-file-1>
<!-- This is an automatically generated file.
     It will be read and overwritten.
     DO NOT EDIT! -->
<META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
<TITLE>Bookmarks</TITLE>
<H1>Bookmarks</H1>
<DL><p>
    <DT><H3 ADD_DATE="1609459200" LAST_MODIFIED="1609459300" PERSONAL_TOOLBAR_FOLDER="true">Bookmarks bar</H3>
    <DL><p>
        <DT><A HREF="https://www.wikipedia.org/" ADD_DATE="1609459201" ICON="data:image/png;base64,AAAA">Wikipedia</A>
        <DT><H3 ADD_DATE="1609459202" LAST_MODIFIED="1609459203">News</H3>
        <DL><p>
            <DT><A HREF="https://news.example/world" ADD_DATE="1609459204">World &#8211; News</A>
            <DT><A HREF="javascript:(function(){alert(1)})()" ADD_DATE="1609459205">Bookmarklet</A>
        </DL><p>
    </DL><p>
    <DT><A HREF="https://other.example/" ADD_DATE="1609459206">Other bookmarks item</A>
</DL><p>
"""

    @Test
    fun parsesAFirefoxExportFlatteningFoldersAndRefusingPlaceUrls() {
        val parsed = parse(firefox)
        assertEquals(
            listOf(
                "https://support.mozilla.org/products/firefox",
                "https://www.mozilla.org/contribute/",
                "https://example.org/a?b=1&c=2",
            ),
            parsed.items.map { it.url },
        )
        assertEquals(listOf("Help and Tutorials", "Get Involved", "Example & Co"), parsed.items.map { it.title })
        assertEquals(1_600_000_002_000L, parsed.items[0].addedAtMillis)
        assertEquals(1, parsed.skipped)
    }

    @Test
    fun parsesAChromeExportWithNestedFoldersAndABookmarklet() {
        val parsed = parse(chrome)
        assertEquals(
            listOf("https://www.wikipedia.org/", "https://news.example/world", "https://other.example/"),
            parsed.items.map { it.url },
        )
        assertEquals("World – News", parsed.items[1].title)
        assertEquals(1, parsed.skipped)
    }

    // ---- tolerance ---------------------------------------------------------------------------------------------

    @Test
    fun acceptsMixedCaseTagsAndAllQuotingStyles() {
        val parsed = parse(
            """<dl><dt><a href='https://single.example/' add_date='1600000000'>Single</a>
<DT><A HREF=https://unquoted.example/path ADD_DATE=1600000001>Unquoted</A>
<dt><A
   HREF="https://multi.example/"
   Add_Date = "1600000002" >Multi line</a >
<DT><a class=x href = "https://spaced.example/">Spaced</A></dl>""",
        )
        assertEquals(
            listOf("https://single.example/", "https://unquoted.example/path", "https://multi.example/", "https://spaced.example/"),
            parsed.items.map { it.url },
        )
        assertEquals(listOf("Single", "Unquoted", "Multi line", "Spaced"), parsed.items.map { it.title })
        assertEquals(1_600_000_001_000L, parsed.items[1].addedAtMillis)
        assertEquals(1_600_000_002_000L, parsed.items[2].addedAtMillis)
        assertNull(parsed.items[3].addedAtMillis)
        assertEquals(0, parsed.skipped)
    }

    @Test
    fun anchorTagNameMustBeFollowedByWhitespace() {
        val parsed = parse("<abbr title='x'>t</abbr><a>no attrs</a><area href='https://x.example/'><a href='https://ok.example/'>ok</a>")
        assertEquals(listOf("https://ok.example/"), parsed.items.map { it.url })
        assertEquals(0, parsed.skipped)
    }

    @Test
    fun titleStripsNestedTagsAndDecodesEntities() {
        val parsed = parse(
            """<A HREF="https://e.example/">  <B>Bold</B>
  &amp; <i>it&#97;lic</i>
&lt;tag&gt; &quot;q&quot; &apos;a&apos; &#39;b&#39; &#x41;&#X42; &#128512; </A>""",
        )
        assertEquals("Bold & italic <tag> \"q\" 'a' 'b' AB 😀", parsed.items[0].title)
    }

    @Test
    fun invalidNumericEntitiesAreLeftOutAndUnknownOnesStay() {
        val parsed = parse(
            """<A HREF="https://e.example/">a&#0;b&#xD800;c&#x110000;d&#99999999999;e&nbsp;f&bogus g&amp</A>""",
        )
        assertEquals("abcde&nbsp;f&bogus g&amp", parsed.items[0].title)
    }

    @Test
    fun entityEncodedSchemesAreStillRefused() {
        val parsed = parse(
            """<A HREF="java&#115;cript:alert(1)">x</A><A HREF="&#106;avascript:alert(1)">y</A><A HREF="https://ok.example/">z</A>""",
        )
        assertEquals(listOf("https://ok.example/"), parsed.items.map { it.url })
        assertEquals(2, parsed.skipped)
    }

    @Test
    fun refusesNonWebSchemesUserinfoAndOverlongUrls() {
        val long = "https://long.example/" + "x".repeat(5000)
        val parsed = parse(
            """<A HREF="javascript:alert(1)">1</A>
<A HREF="data:text/html,x">2</A>
<A HREF="file:///sdcard/x.html">3</A>
<A HREF="place:type=6">4</A>
<A HREF="ftp://f.example/">5</A>
<A HREF="https://user@evil.example/">6</A>
<A HREF="">7</A>
<A HREF="https://has space.example/">8</A>
<A>9</A>
<A name="x" >10</A>
<A HREF="$long">11</A>
<A HREF="https://good.example/">good</A>""",
        )
        assertEquals(listOf("https://good.example/"), parsed.items.map { it.url })
        // "<A>" (no whitespace after the tag name) is not an anchor at all; the other 10 bad ones are counted.
        assertEquals(10, parsed.skipped)
    }

    @Test
    fun emptyTitleFallsBackToTheUrl() {
        val parsed = parse("""<A HREF="https://e.example/"></A><A HREF="https://f.example/">   <b></b> </A>""")
        assertEquals(listOf("https://e.example/", "https://f.example/"), parsed.items.map { it.title })
    }

    @Test
    fun badDatesAreIgnored() {
        val parsed = parse(
            """<A HREF="https://a.example/" ADD_DATE="abc">1</A>
<A HREF="https://b.example/" ADD_DATE="-5">2</A>
<A HREF="https://c.example/" ADD_DATE="99999999999999999999">3</A>
<A HREF="https://d.example/" ADD_DATE="9223372036854775807">4</A>
<A HREF="https://e.example/" ADD_DATE="1600000000000000">5</A>
<A HREF="https://f.example/" ADD_DATE="">6</A>
<A HREF="https://g.example/" ADD_DATE="0">7</A>""",
        )
        assertEquals(7, parsed.items.size)
        assertEquals(listOf<Long?>(null, null, null, null, null, null, 0L), parsed.items.map { it.addedAtMillis })
        assertEquals(0, parsed.skipped)
    }

    @Test
    fun aLeadingByteOrderMarkIsIgnored() {
        val parsed = parse("\uFEFF<!DOCTYPE NETSCAPE-Bookmark-file-1><DL><DT><A HREF=\"https://a.example/\">A</A></DL>")
        assertEquals(listOf("https://a.example/"), parsed.items.map { it.url })
    }

    @Test
    fun anUnterminatedTagEndsTheParseButKeepsWhatCameBefore() {
        val parsed = parse("""<A HREF="https://a.example/">A</A><A HREF="https://b.example/">B</A><A HREF="https://c.example/""")
        assertEquals(listOf("https://a.example/", "https://b.example/"), parsed.items.map { it.url })
        val noClose = parse("""<A HREF="https://a.example/">A</A><A HREF="https://b.example/"""")
        assertEquals(listOf("https://a.example/"), noClose.items.map { it.url })
        val unclosedAnchor = parse("""<A HREF="https://a.example/">A</A><A HREF="https://b.example/">B and no end""")
        assertEquals(listOf("https://a.example/"), unclosedAnchor.items.map { it.url })
        assertEquals(1, unclosedAnchor.skipped)
    }

    @Test
    fun emptyAndTagFreeInputGivesNothing() {
        assertEquals(0, parse("").items.size)
        assertEquals(0, parse("just some text, no markup").items.size)
        assertEquals(0, parse("<").items.size)
        assertEquals(0, parse("<a").items.size)
        assertEquals(0, parse("<a ").skipped)
    }

    @Test
    fun stopsAtTheBookmarkCap() {
        val sb = StringBuilder()
        for (i in 1..(MAX_BOOKMARKS + 100)) sb.append("<DT><A HREF=\"https://h$i.example/\">t$i</A>\n")
        val parsed = parse(sb.toString())
        assertEquals(MAX_BOOKMARKS, parsed.items.size)
        assertEquals("https://h1.example/", parsed.items.first().url)
    }

    // ---- hostile input: must stay linear ------------------------------------------------------------------------

    private val tenMegabytes = 10 * 1024 * 1024

    @Test(timeout = 30_000)
    fun manyAnchorsWithoutAnyClosingTagFinishQuickly() {
        val sb = StringBuilder(tenMegabytes + 16)
        val unit = "<a href=x>"
        while (sb.length < tenMegabytes) sb.append(unit)
        val parsed = parse(sb.toString())
        assertEquals(0, parsed.items.size)
    }

    @Test(timeout = 30_000)
    fun manyValidLookingAnchorsWithoutClosingTagsFinishQuickly() {
        val sb = StringBuilder(tenMegabytes + 64)
        val unit = "<a href=https://x.example/>t"
        while (sb.length < tenMegabytes) sb.append(unit)
        assertEquals(0, parse(sb.toString()).items.size)
    }

    @Test(timeout = 30_000)
    fun oneClosingTagAtTheVeryEndFinishesQuickly() {
        val sb = StringBuilder(tenMegabytes + 64)
        val unit = "<a href=https://x.example/>t"
        while (sb.length < tenMegabytes) sb.append(unit)
        sb.append("</a>")
        val parsed = parse(sb.toString())
        assertEquals(1, parsed.items.size)
        assertTrue(parsed.items[0].title.length <= 512)
    }

    @Test(timeout = 30_000)
    fun unterminatedTagsAndQuotesFinishQuickly() {
        val tags = StringBuilder(tenMegabytes + 16)
        while (tags.length < tenMegabytes) tags.append("<a ")
        assertEquals(0, parse(tags.toString()).items.size)
        val quotes = StringBuilder(tenMegabytes + 16)
        while (quotes.length < tenMegabytes) quotes.append("<a href=\"")
        assertEquals(0, parse(quotes.toString()).items.size)
    }

    @Test(timeout = 30_000)
    fun manyCloseLookalikesAndAngleBracketsFinishQuickly() {
        val sb = StringBuilder(tenMegabytes + 16)
        sb.append("<a href=https://x.example/>")
        while (sb.length < tenMegabytes) sb.append("</abbr><<<<</")
        sb.append("</a>")
        assertEquals(1, parse(sb.toString()).items.size)
    }

    @Test(timeout = 30_000)
    fun manyEmptyAnchorsAreCappedQuickly() {
        val sb = StringBuilder(tenMegabytes + 32)
        val unit = "<a href=https://x.example/></a>"
        while (sb.length < tenMegabytes) sb.append(unit)
        assertEquals(MAX_BOOKMARKS, parse(sb.toString()).items.size)
    }

    @Test(timeout = 30_000)
    fun aHugeRunOfAmpersandsInOneHrefFinishesQuickly() {
        val html = "<a href=\"" + "&".repeat(tenMegabytes) + "\">t</a>"
        val parsed = parse(html)
        assertEquals(0, parsed.items.size)
        assertEquals(1, parsed.skipped)
    }

    @Test
    fun entitiesAtTheLengthLimitStillDecode() {
        // The ';' is 14 chars after the '&' in the first and 15 in the second.
        assertEquals("A", BookmarkHtml.decodeEntities("&#000000000065;"))
        assertEquals("&#0000000000065;", BookmarkHtml.decodeEntities("&#0000000000065;"))
    }
}
