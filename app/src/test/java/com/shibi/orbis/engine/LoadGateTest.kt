package com.shibi.orbis.engine

import com.shibi.orbis.engine.LoadVerdict.DENY
import com.shibi.orbis.engine.LoadVerdict.EXTERNAL
import com.shibi.orbis.engine.LoadVerdict.IMPORT_FILE
import com.shibi.orbis.engine.LoadVerdict.PASS
import com.shibi.orbis.engine.LoadVerdict.WEB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadGateTest {
    private val web = "https://example.com/page"
    private val imported = "file:///data/user/0/com.shibi.orbis/cache/imports/abc/invoice.html"
    private val extension = "moz-extension://1234-abcd/options.html"

    /** One target per scheme. Shared by every committed page. */
    private val targets =
        mapOf(
            "http" to "http://example.com/",
            "https" to "https://example.com/",
            "file" to "file:///data/user/0/com.shibi.orbis/cache/imports/abc/a.pdf",
            "about" to "about:blank",
            "about-other" to "about:config",
            "data" to "data:text/html,hi",
            "blob" to "blob:https://example.com/1234",
            "javascript" to "javascript:void(0)",
            "content" to "content://com.shibi.orbis.downloads/downloads_external/a.pdf",
            "resource" to "resource://gre/modules/x.js",
            "chrome" to "chrome://browser/content/x.html",
            "view-source" to "view-source:https://example.com/",
            "ws" to "ws://example.com/",
            "wss" to "wss://example.com/",
            "jar" to "jar:file:///data/x.zip!/a.html",
            "moz-extension" to "moz-extension://9999-ffff/page.html",
            "tel" to "tel:+123",
            "mailto" to "mailto:a@example.com",
            "intent" to "intent://x#Intent;scheme=x;end",
            "none" to "no scheme here",
        )

    private val onAWebPage =
        mapOf(
            "http" to WEB, "https" to WEB, "file" to IMPORT_FILE,
            "about" to PASS, "about-other" to PASS, "data" to PASS, "blob" to PASS, "javascript" to PASS,
            "content" to DENY, "resource" to DENY, "chrome" to DENY, "view-source" to DENY, "ws" to DENY, "wss" to DENY,
            "jar" to DENY, "moz-extension" to DENY,
            "tel" to EXTERNAL, "mailto" to EXTERNAL, "intent" to EXTERNAL,
            "none" to DENY,
        )

    private val onAnImport =
        mapOf(
            "http" to WEB, "https" to WEB, "file" to IMPORT_FILE,
            "about" to PASS, "about-other" to DENY, "data" to DENY, "blob" to DENY, "javascript" to DENY,
            "content" to DENY, "resource" to DENY, "chrome" to DENY, "view-source" to DENY, "ws" to DENY, "wss" to DENY,
            "jar" to DENY, "moz-extension" to DENY,
            "tel" to EXTERNAL, "mailto" to EXTERNAL, "intent" to EXTERNAL,
            "none" to DENY,
        )

    private fun assertTable(committed: String?, expected: Map<String, LoadVerdict>) {
        assertEquals(targets.keys, expected.keys)
        for ((name, target) in targets) {
            assertEquals("$name from $committed", expected[name], topLevelLoadVerdict(target, committed, appExtensionLoad = false))
        }
    }

    @Test
    fun aWebPage() = assertTable(web, onAWebPage)

    @Test
    fun aBlankTab() {
        assertTable(null, onAWebPage)
        assertTable("about:blank", onAWebPage)
    }

    @Test
    fun anExtensionPage() {
        // Same table, except that the extension may move between its own pages.
        assertTable(extension, onAWebPage)
        assertEquals(PASS, topLevelLoadVerdict("moz-extension://1234-abcd/other.html", extension, appExtensionLoad = false))
        assertEquals(PASS, topLevelLoadVerdict("MOZ-EXTENSION://1234-ABCD/other.html", extension, appExtensionLoad = false))
    }

    @Test
    fun anImportedDocument() {
        assertTable(imported, onAnImport)
        assertTable("file:///data/user/0/com.shibi.orbis/cache/imports/abc/invoice.pdf", onAnImport)
    }

    @Test
    fun extensionPagesNeedTheAppOrTheSameExtension() {
        val target = "moz-extension://1234-abcd/options.html"
        assertEquals(PASS, topLevelLoadVerdict(target, web, appExtensionLoad = true))
        assertEquals(PASS, topLevelLoadVerdict(target, null, appExtensionLoad = true))
        assertEquals(DENY, topLevelLoadVerdict(target, web, appExtensionLoad = false))
        assertEquals(DENY, topLevelLoadVerdict(target, "moz-extension://5678-ef01/options.html", appExtensionLoad = false))
        // A host that only starts like ours is another extension.
        assertEquals(DENY, topLevelLoadVerdict(target, "moz-extension://1234-abcd.evil/x.html", appExtensionLoad = false))
        assertEquals(DENY, topLevelLoadVerdict("moz-extension:///x.html", "moz-extension:///y.html", appExtensionLoad = false))
        // The app flag does not let an import open an extension page.
        assertEquals(DENY, topLevelLoadVerdict(target, imported, appExtensionLoad = true))
    }

    @Test
    fun schemesAreCaseInsensitive() {
        assertEquals(WEB, topLevelLoadVerdict("HTTPS://example.com/", web, appExtensionLoad = false))
        assertEquals(IMPORT_FILE, topLevelLoadVerdict("File:///x", web, appExtensionLoad = false))
        assertEquals(DENY, topLevelLoadVerdict("CONTENT://x/y", web, appExtensionLoad = false))
        assertEquals(DENY, topLevelLoadVerdict("Resource://gre/x", web, appExtensionLoad = false))
        assertEquals(PASS, topLevelLoadVerdict("JavaScript:void(0)", web, appExtensionLoad = false))
        assertEquals(DENY, topLevelLoadVerdict("JavaScript:void(0)", imported, appExtensionLoad = false))
        assertEquals(EXTERNAL, topLevelLoadVerdict("TEL:+1", imported, appExtensionLoad = false))
        // Only the exact about:blank leaves an import for a blank page.
        assertEquals(DENY, topLevelLoadVerdict("ABOUT:blank", imported, appExtensionLoad = false))
    }

    @Test
    fun jarIsNeverHandedToAnotherApp() {
        assertFalse(isExternalScheme("jar:file:///x.zip!/a"))
    }

    @Test
    fun subframes() {
        for (url in listOf(
            "content://com.shibi.orbis.downloads/a.pdf",
            "resource://pdf.js/web/viewer.html",
            "file:///data/data/com.shibi.orbis/x",
            "chrome://global/content/x.html",
            "view-source:https://example.com/",
            "jar:file:///x.zip!/a.html",
            "CONTENT://x/y",
        )) {
            assertFalse(url, subframeLoadAllowed(url))
        }
        for (url in listOf("data:text/html,hi", "blob:https://example.com/1", "about:srcdoc", "about:blank", "https://example.com/")) {
            assertTrue(url, subframeLoadAllowed(url))
        }
    }

    @Test
    fun logLinesNameOnlyKnownSchemes() {
        assertEquals("content", loggableScheme("content://com.shibi.orbis.downloads/secret.pdf"))
        assertEquals("jar", loggableScheme("JAR:file:///x"))
        assertEquals("other", loggableScheme("myapp-secret://token"))
        assertEquals("other", loggableScheme("no scheme"))
    }
}
