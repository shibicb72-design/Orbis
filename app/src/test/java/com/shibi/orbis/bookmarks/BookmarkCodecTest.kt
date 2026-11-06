package com.shibi.orbis.bookmarks

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class BookmarkCodecTest {
    private val a = Bookmark(1, "https://a.example/", "A", 1_000)
    private val b = Bookmark(2, "https://b.example/?q=1&r=2", "B é😀", 2_000)

    @Test
    fun roundTrips() {
        assertEquals(listOf(a, b), BookmarkCodec.decode(BookmarkCodec.encode(listOf(a, b))))
        assertEquals(emptyList<Bookmark>(), BookmarkCodec.decode(BookmarkCodec.encode(emptyList())))
    }

    @Test
    fun wholeDocumentFailureThrows() {
        listOf("", "not json", "[1,2]", "{}", "{\"bookmarks\":5}", "{\"bookmarks\":[").forEach {
            try {
                BookmarkCodec.decode(it)
                fail("expected failure for: $it")
            } catch (e: Exception) {
                // expected
            }
        }
    }

    @Test
    fun badEntriesAreSkippedAndTheRestKept() {
        val text =
            """{"version":1,"bookmarks":[
                1,
                "text",
                {"id":1,"url":"https://ok.example/","title":"OK","created":5},
                {"id":2,"url":"javascript:alert(1)","title":"bad scheme"},
                {"id":3,"url":"file:///sdcard/x","title":"file"},
                {"id":4,"url":"https://user@evil.example/","title":"userinfo"},
                {"id":5,"url":"https://white space.example/","title":"space"},
                {"id":0,"url":"https://zero.example/","title":"zero id"},
                {"url":"https://noid.example/","title":"no id"},
                {"id":6,"title":"no url"},
                {"id":7,"url":null,"title":"null url"},
                {"id":8,"url":"https://fine.example/","title":"Fine"}
            ]}"""
        val out = BookmarkCodec.decode(text)
        assertEquals(listOf("https://ok.example/", "https://fine.example/"), out.map { it.url })
    }

    @Test
    fun duplicateIdsAndUrlsKeepTheFirst() {
        val text =
            """{"bookmarks":[
                {"id":1,"url":"https://a.example/","title":"first"},
                {"id":1,"url":"https://b.example/","title":"same id"},
                {"id":2,"url":"https://a.example/","title":"same url"},
                {"id":3,"url":"https://c.example/","title":"third"}
            ]}"""
        val out = BookmarkCodec.decode(text)
        assertEquals(listOf("first", "third"), out.map { it.title })
        assertEquals(listOf(1L, 3L), out.map { it.id })
    }

    @Test
    fun titlesAreCleanedAgainAndFallBackToTheUrl() {
        val longTitle = "x".repeat(2000)
        val text =
            """{"bookmarks":[
                {"id":1,"url":"https://a.example/","title":"  a\n\tb\u202E  "},
                {"id":2,"url":"https://b.example/","title":""},
                {"id":3,"url":"https://c.example/","title":null},
                {"id":4,"url":"https://d.example/","title":"$longTitle"}
            ]}"""
        val out = BookmarkCodec.decode(text)
        assertEquals("a b", out[0].title)
        assertEquals("https://b.example/", out[1].title)
        assertEquals("https://c.example/", out[2].title)
        assertEquals(512, out[3].title.length)
    }

    @Test
    fun negativeOrMissingCreatedBecomesZero() {
        val out = BookmarkCodec.decode(
            """{"bookmarks":[{"id":1,"url":"https://a.example/","created":-5},{"id":2,"url":"https://b.example/"}]}""",
        )
        assertEquals(listOf(0L, 0L), out.map { it.createdAt })
    }

    @Test
    fun decodeStopsAtTheCap() {
        val sb = StringBuilder("""{"bookmarks":[""")
        for (i in 1..(MAX_BOOKMARKS + 5)) {
            if (i > 1) sb.append(',')
            sb.append("""{"id":$i,"url":"https://h$i.example/","title":"t"}""")
        }
        sb.append("]}")
        assertEquals(MAX_BOOKMARKS, BookmarkCodec.decode(sb.toString()).size)
    }
}
