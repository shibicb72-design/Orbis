package com.shibi.orbis.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextMenuTest {
    @Test
    fun httpLinkOffersTabsCopyShareAndDownload() {
        val menu =
            pageContextMenu(
                ContextElementInfo(
                    type = PageContextType.OTHER,
                    linkUri = "https://example.com/file.zip",
                    srcUri = null,
                    title = null,
                    altText = null,
                    linkText = "Download",
                ),
            )!!
        assertEquals("https://example.com/file.zip", menu.header)
        assertEquals("https://example.com/file.zip", menu.linkUrl)
        assertEquals("Download", menu.linkText)
        assertNull(menu.imageUrl)
        assertEquals(
            listOf(
                ContextMenuItem.OPEN_NEW_TAB,
                ContextMenuItem.OPEN_NEW_PRIVATE_TAB,
                ContextMenuItem.COPY_LINK_ADDRESS,
                ContextMenuItem.COPY_LINK_TEXT,
                ContextMenuItem.SHARE_LINK,
                ContextMenuItem.DOWNLOAD_LINK,
            ),
            menu.items,
        )
        assertFalse(menu.items.contains(ContextMenuItem.OPEN_EXTERNAL))
    }

    @Test
    fun imageOffersOpenSaveCopyAndShare() {
        val menu =
            pageContextMenu(
                ContextElementInfo(
                    type = PageContextType.IMAGE,
                    linkUri = null,
                    srcUri = "https://img.example/a.png",
                    title = null,
                    altText = "poster",
                    linkText = null,
                ),
            )!!
        assertEquals("https://img.example/a.png", menu.header)
        assertNull(menu.linkUrl)
        assertEquals("https://img.example/a.png", menu.imageUrl)
        assertEquals(
            listOf(
                ContextMenuItem.OPEN_IMAGE_NEW_TAB,
                ContextMenuItem.SAVE_IMAGE,
                ContextMenuItem.COPY_IMAGE_ADDRESS,
                ContextMenuItem.SHARE_IMAGE,
            ),
            menu.items,
        )
    }

    @Test
    fun imageLinkShowsBothGroups() {
        val menu =
            pageContextMenu(
                ContextElementInfo(
                    type = PageContextType.IMAGE,
                    linkUri = "https://example.com/article",
                    srcUri = "https://img.example/a.png",
                    title = "Article",
                    altText = null,
                    linkText = null,
                ),
            )!!
        assertEquals("https://example.com/article", menu.header)
        assertEquals("https://example.com/article", menu.linkUrl)
        assertEquals("Article", menu.linkText)
        assertEquals("https://img.example/a.png", menu.imageUrl)
        assertEquals(
            listOf(
                ContextMenuItem.OPEN_NEW_TAB,
                ContextMenuItem.OPEN_NEW_PRIVATE_TAB,
                ContextMenuItem.COPY_LINK_ADDRESS,
                ContextMenuItem.COPY_LINK_TEXT,
                ContextMenuItem.SHARE_LINK,
                ContextMenuItem.DOWNLOAD_LINK,
                ContextMenuItem.OPEN_IMAGE_NEW_TAB,
                ContextMenuItem.SAVE_IMAGE,
                ContextMenuItem.COPY_IMAGE_ADDRESS,
                ContextMenuItem.SHARE_IMAGE,
            ),
            menu.items,
        )
        assertTrue(menu.items.any { !it.isImageAction })
        assertTrue(menu.items.any { it.isImageAction })
    }

    @Test
    fun magnetLinkCanBeCopiedAndOpenedExternallyButNotLoaded() {
        val magnet = "magnet:?xt=urn:btih:" + "a".repeat(80)
        val menu =
            pageContextMenu(
                ContextElementInfo(
                    type = PageContextType.OTHER,
                    linkUri = magnet,
                    srcUri = null,
                    title = null,
                    altText = null,
                    linkText = "Download with uTorrent",
                ),
            )!!
        assertEquals(magnet, menu.linkUrl)
        assertTrue(menu.header.endsWith("…"))
        assertTrue(menu.header.length <= CONTEXT_MENU_HEADER_MAX)
        assertEquals("Download with uTorrent", menu.linkText)
        assertEquals(
            listOf(
                ContextMenuItem.COPY_LINK_ADDRESS,
                ContextMenuItem.COPY_LINK_TEXT,
                ContextMenuItem.SHARE_LINK,
                ContextMenuItem.OPEN_EXTERNAL,
            ),
            menu.items,
        )
        assertFalse(menu.items.contains(ContextMenuItem.OPEN_NEW_TAB))
        assertFalse(menu.items.contains(ContextMenuItem.DOWNLOAD_LINK))
    }

    @Test
    fun copyLinkTextIsHiddenWhenThereIsNoTextOrItRepeatsTheAddress() {
        val bare =
            pageContextMenu(
                ContextElementInfo(PageContextType.OTHER, "mailto:a@b.c", null, "  ", null, null),
            )!!
        assertFalse(bare.items.contains(ContextMenuItem.COPY_LINK_TEXT))
        assertTrue(bare.items.contains(ContextMenuItem.OPEN_EXTERNAL))
        val same =
            pageContextMenu(
                ContextElementInfo(PageContextType.OTHER, "mailto:a@b.c", null, null, null, "mailto:a@b.c"),
            )!!
        assertNull(same.linkText)
        assertFalse(same.items.contains(ContextMenuItem.COPY_LINK_TEXT))
    }

    @Test
    fun nothingUsefulProducesNoMenu() {
        assertNull(pageContextMenu(ContextElementInfo(PageContextType.OTHER, null, null, null, null, null)))
        assertNull(pageContextMenu(ContextElementInfo(PageContextType.OTHER, "/relative", null, null, null, null)))
        assertNull(
            pageContextMenu(
                ContextElementInfo(PageContextType.IMAGE, null, "magnet:?xt=urn:btih:abc", null, null, null),
            ),
        )
    }
}
