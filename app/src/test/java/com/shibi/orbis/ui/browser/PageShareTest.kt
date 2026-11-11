package com.shibi.orbis.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class PageShareTest {
    private val import = "file:///data/user/0/com.shibi.orbis/cache/imports/11111111-1111-1111-1111-111111111111/Report.pdf"

    @Test
    fun webPagesShareALinkOrThePdf() {
        assertEquals(ShareTarget.LINK_OR_PDF, shareTargetFor("https://example.com/a.pdf", hasSession = true))
        assertEquals(ShareTarget.LINK_OR_PDF, shareTargetFor("http://example.com/", hasSession = true))
    }

    @Test
    fun anImportedDocumentSharesTheFile() {
        assertEquals(ShareTarget.IMPORTED_FILE, shareTargetFor(import, hasSession = true))
        assertEquals(ShareTarget.IMPORTED_FILE, shareTargetFor(import.replace("Report.pdf", "page.html"), hasSession = true))
    }

    @Test
    fun startPageBlankOtherFilesAndNoSessionShareNothing() {
        assertEquals(ShareTarget.NONE, shareTargetFor("", hasSession = true))
        assertEquals(ShareTarget.NONE, shareTargetFor("about:blank", hasSession = true))
        assertEquals(ShareTarget.NONE, shareTargetFor("file:///sdcard/Download/x.pdf", hasSession = true))
        assertEquals(ShareTarget.NONE, shareTargetFor("file:///data/user/0/com.shibi.orbis/shared_prefs/x.pdf", hasSession = true))
        assertEquals(ShareTarget.NONE, shareTargetFor("https://example.com/", hasSession = false))
        assertEquals(ShareTarget.NONE, shareTargetFor(import, hasSession = false))
    }
}
