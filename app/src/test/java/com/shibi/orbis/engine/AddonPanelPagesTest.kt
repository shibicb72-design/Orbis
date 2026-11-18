package com.shibi.orbis.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonPanelPagesTest {
    private val ubo = "uBlock0@raymondhill.net"
    private val uboBase = "moz-extension://c1ca611c-f9c3-4005-a7ca-42a912b1ab66/"
    private val otherBase = "moz-extension://3c81e1b9-5604-400e-ad2d-8efe66c1a121/"
    private val dashboard = "${uboBase}dashboard.html"

    /** Stands for the selected tab (compared by identity) and another one. */
    private val tab = Any()
    private val otherTab = Any()

    private var clock = 50_000L

    private fun panel() = PanelPages(ubo, uboBase, tab) { clock }

    @Test(timeout = 10_000)
    fun `the add-on's own page may open and nothing else`() {
        assertTrue(mayOpenFromPanel(dashboard, uboBase))
        assertTrue(mayOpenFromPanel("${uboBase}support.html#x", uboBase))
        val refused =
            listOf(
                "https://example.com/",
                "http://example.com/",
                "${otherBase}x.html",
                "about:blank",
                "data:text/html,hi",
                "javascript:alert(1)",
                "file:///sdcard/x",
                "",
                // The base address later in the string.
                "https://example.com/?u=$dashboard",
                "javascript:'$uboBase'",
                // The same id without the slash that ends the base address.
                uboBase.dropLast(1) + ".evil.example/x.html",
            )
        for (url in refused) assertFalse(url, mayOpenFromPanel(url, uboBase))
    }

    @Test(timeout = 10_000)
    fun `no base address allows nothing`() {
        assertFalse(mayOpenFromPanel(dashboard, ""))
        assertFalse(mayOpenFromPanel(dashboard, "moz-extension://"))
        assertFalse(mayOpenFromPanel("https://example.com/", "https://"))
    }

    @Test(timeout = 10_000)
    fun `an open panel opens its own page once`() {
        val pages = panel()
        assertNull(openFromPanel(pages, ubo, dashboard, tab))
        assertEquals(PanelPageRefusal.ALREADY_OPENED, openFromPanel(pages, ubo, dashboard, tab))
        assertEquals(PanelPageRefusal.ALREADY_OPENED, openFromPanel(pages, ubo, "${uboBase}support.html", tab))
    }

    @Test(timeout = 10_000)
    fun `a panel its own page closed may open its page for 1000 ms`() {
        val inGrace = panel()
        inGrace.closed(bySelf = true)
        clock += 900
        assertNull(openFromPanel(inGrace, ubo, dashboard, tab))
        assertEquals(PanelPageRefusal.ALREADY_OPENED, openFromPanel(inGrace, ubo, dashboard, tab))

        val late = panel()
        late.closed(bySelf = true)
        clock += 1_100
        assertEquals(PanelPageRefusal.GRACE_OVER, openFromPanel(late, ubo, dashboard, tab))
    }

    @Test(timeout = 10_000)
    fun `a panel the user or Orbis closed opens nothing`() {
        // Close, Back, a tap outside, another tab, the lock, add-on off and a crash all close it this way.
        val pages = panel()
        pages.closed(bySelf = false)
        clock += 100
        assertEquals(PanelPageRefusal.CLOSED, openFromPanel(pages, ubo, dashboard, tab))
    }

    @Test(timeout = 10_000)
    fun `the first close counts`() {
        val pages = panel()
        pages.closed(bySelf = false)
        pages.closed(bySelf = true)
        assertEquals(PanelPageRefusal.CLOSED, openFromPanel(pages, ubo, dashboard, tab))
    }

    @Test(timeout = 10_000)
    fun `another tab selected or the lock refuses even in the grace`() {
        val pages = panel()
        pages.closed(bySelf = true)
        clock += 100
        assertEquals(PanelPageRefusal.OTHER_TAB, openFromPanel(pages, ubo, dashboard, otherTab))
        // The private-tab lock hides the page: no tab is selected.
        assertEquals(PanelPageRefusal.OTHER_TAB, openFromPanel(pages, ubo, dashboard, null))
    }

    @Test(timeout = 10_000)
    fun `a web address or another add-on's page is refused in the grace`() {
        val pages = panel()
        pages.closed(bySelf = true)
        clock += 100
        assertEquals(PanelPageRefusal.NOT_OWN_PAGE, openFromPanel(pages, ubo, "https://example.com/", tab))
        assertEquals(PanelPageRefusal.NOT_OWN_PAGE, openFromPanel(pages, ubo, "${otherBase}x.html", tab))
        assertEquals(PanelPageRefusal.NOT_OWN_PAGE, openFromPanel(pages, ubo, null, tab))
        assertEquals(PanelPageRefusal.OTHER_ADDON, openFromPanel(pages, "addon@darkreader.org", "${otherBase}x.html", tab))
    }

    @Test(timeout = 10_000)
    fun `a refused request does not use up the panel's page`() {
        val pages = panel()
        assertEquals(PanelPageRefusal.NOT_OWN_PAGE, openFromPanel(pages, ubo, "https://example.com/", tab))
        assertEquals(PanelPageRefusal.OTHER_TAB, openFromPanel(pages, ubo, dashboard, otherTab))
        assertNull(openFromPanel(pages, ubo, dashboard, tab))
    }

    @Test(timeout = 10_000)
    fun `with no panel ever opened nothing opens`() {
        assertEquals(PanelPageRefusal.NO_PANEL, openFromPanel(null, ubo, dashboard, tab))
    }

    @Test(timeout = 10_000)
    fun `another add-on cannot open a page through an open panel`() {
        val pages = panel()
        assertEquals(PanelPageRefusal.OTHER_ADDON, openFromPanel(pages, "addon@darkreader.org", "${otherBase}ui/x.html", tab))
        assertEquals(PanelPageRefusal.OTHER_ADDON, openFromPanel(pages, "addon@darkreader.org", dashboard, tab))
        assertNull(openFromPanel(pages, ubo, dashboard, tab))
    }
}
