package com.shibi.orbis.bookmarks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkFoldersTest {
    private val a = Bookmark(1, "https://a.example/", "A", 1000, folder = "Work")
    private val b = Bookmark(2, "https://b.example/", "B", 2000)
    private val c = Bookmark(3, "https://c.example/", "C", 3000, folder = "art")

    @Test
    fun folderSurvivesTheStoredFile() {
        val back = BookmarkCodec.decode(BookmarkCodec.encode(listOf(a, b, c)))
        assertEquals(listOf("Work", "", "art"), back.map { it.folder })
    }

    @Test
    fun foldersAreListedOnceSortedAndNamesAreCleaned() {
        assertEquals(listOf("art", "Work"), folderNames(listOf(a, b, c, a.copy(id = 9, url = "https://d.example/"))))
        assertEquals("Two words", cleanFolderName("  Two\nwords  "))
        assertEquals(MAX_FOLDER_CHARS, cleanFolderName("x".repeat(200)).length)
    }

    @Test
    fun exportWritesFoldersAsSubLists() {
        val html = BookmarkHtml.export(listOf(a, b, c))
        assertTrue(html.contains("<DT><H3>Work</H3>"))
        assertTrue(html.indexOf("https://b.example/") < html.indexOf("<H3>"))
        // Still readable by our own importer (flat).
        assertEquals(3, BookmarkHtml.parse(html).items.size)
    }
}
