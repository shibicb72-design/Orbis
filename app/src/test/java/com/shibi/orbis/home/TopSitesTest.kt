package com.shibi.orbis.home

import com.shibi.orbis.history.HistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopSitesTest {
    private fun e(url: String, visits: Int, at: Long = 0, title: String = "") = HistoryEntry(url, title, at, visits)

    @Test
    fun rankedByVisitsThenRecency() {
        val sites = topSites(listOf(e("https://a.test/", 2, 50), e("https://b.test/", 9, 1), e("https://c.test/", 2, 90)))
        assertEquals(listOf("b.test", "c.test", "a.test"), sites.map { it.host })
    }

    @Test
    fun oneTilePerSiteUsingItsMostVisitedPage() {
        val sites = topSites(listOf(e("https://news.test/a", 3), e("https://news.test/b", 7), e("https://x.test/", 1)))
        assertEquals(listOf("https://news.test/b", "https://x.test/"), sites.map { it.url })
    }

    @Test
    fun searchResultPagesAreLeftOut() {
        val sites = topSites(listOf(e("https://duckduckgo.com/?q=cats", 50), e("https://www.google.com/search?q=x", 40), e("https://duckduckgo.com/about", 1)))
        assertEquals(listOf("https://duckduckgo.com/about"), sites.map { it.url })
    }

    @Test
    fun limitedAndTitledByHostWhenBlank() {
        val many = (1..20).map { e("https://s$it.test/", it) }
        val sites = topSites(many)
        assertEquals(TOP_SITES_LIMIT, sites.size)
        assertEquals("s20.test", sites.first().title)
        assertEquals("Named", topSites(listOf(e("https://n.test/", 1, title = "Named"))).single().title)
        assertEquals("bing.com", topSites(listOf(e("https://www.bing.com/?form=x", 1))).single().title)
    }

    @Test
    fun pinnedComeFirstAndHiddenSitesAreLeftOut() {
        val history = listOf(e("https://a.test/", 9), e("https://b.test/", 5), e("https://c.test/", 1))
        val sites = topSites(history, pinned = listOf(PinnedSite("https://c.test/", "C"), PinnedSite("https://z.test/", "")), hiddenHosts = setOf("a.test"))
        assertEquals(listOf("c.test", "z.test", "b.test"), sites.map { it.host })
        assertEquals(listOf(true, true, false), sites.map { it.pinned })
        assertEquals("z.test", sites[1].title)
    }

    @Test
    fun emptyHistoryMeansNoTiles() {
        assertTrue(topSites(emptyList()).isEmpty())
    }

    @Test
    fun tileLetters() {
        assertEquals("E", tileLetter("www.example.com"))
        assertEquals("1", tileLetter("127.0.0.1"))
        assertEquals("?", tileLetter("---"))
    }
}
