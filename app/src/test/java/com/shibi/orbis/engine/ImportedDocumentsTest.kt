package com.shibi.orbis.engine

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val VIEW = "android.intent.action.VIEW"
private const val SEND = "android.intent.action.SEND"

class ImportedDocumentsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // ---- MIME and extension ----

    @Test
    fun extensionFollowsTheMimeTypeNotTheDisplayName() {
        assertEquals("pdf", extensionForImportedMime("application/pdf"))
        assertEquals("pdf", extensionForImportedMime("Application/PDF; charset=binary"))
        assertEquals("html", extensionForImportedMime("text/html; charset=utf-8"))
        assertEquals("html", extensionForImportedMime("  Text/HTML  "))
        assertEquals("xhtml", extensionForImportedMime("application/xhtml+xml"))
        assertEquals("xhtml", extensionForImportedMime("application/xhtml+xml;charset=utf-8"))
    }

    @Test
    fun plainTextAndUnknownTypesAreRefused() {
        for (raw in listOf(null, "", "text/plain", "text/plain; charset=utf-8", "application/octet-stream", "text/html-not", "image/png")) {
            assertNull("mime $raw", extensionForImportedMime(raw))
            assertNull("mime $raw", importedMime(raw))
        }
    }

    // ---- filename ----

    @Test
    fun sanitizeKeepsTheLastSegmentAndForcesTheExtension() {
        assertEquals("notes.pdf", sanitizeImportFileName("notes.pdf", "pdf"))
        assertEquals("notes.pdf", sanitizeImportFileName("notes.PDF", "pdf"))
        assertEquals("notes.html.pdf", sanitizeImportFileName("notes.html", "pdf"))
        assertEquals("notes.html", sanitizeImportFileName("notes.html", "html"))
        assertEquals("my report (1).pdf", sanitizeImportFileName("my report (1)", "pdf"))
        assertEquals("page.xhtml", sanitizeImportFileName("page", "xhtml"))
    }

    @Test
    fun sanitizeDropsTraversalControlCharactersAndEmptyNames() {
        assertEquals("passwd.pdf", sanitizeImportFileName("../../etc/passwd", "pdf"))
        assertEquals("x.pdf", sanitizeImportFileName("a/b\\c/x", "pdf"))
        assertEquals("x.pdf", sanitizeImportFileName("..\u0000/x", "pdf"))
        assertEquals("ab.pdf", sanitizeImportFileName("a\u0000b.pdf", "pdf"))
        assertEquals("ab.pdf", sanitizeImportFileName("ab\u202E.pdf", "pdf"))
        assertEquals("a😀b.html", sanitizeImportFileName("a😀b", "html"))
        assertEquals("document.pdf", sanitizeImportFileName(null, "pdf"))
        assertEquals("document.html", sanitizeImportFileName("  ..  ", "html"))
        assertEquals("document.xhtml", sanitizeImportFileName("/", "xhtml"))
        assertEquals("document.pdf", sanitizeImportFileName("a/..", "pdf"))
    }

    @Test
    fun sanitizeCapsUtf8LengthAndKeepsTheExtension() {
        val long = "é".repeat(200)
        val name = sanitizeImportFileName(long, "pdf")
        assertTrue(name.endsWith(".pdf"))
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 120)
        assertTrue(name.length > ".pdf".length)
    }

    // ---- intent shape ----

    @Test
    fun viewAndSendAcceptContentAndFileWithTheThreeMimeTypes() {
        assertEquals(
            SharedDocumentRequest("content://com.whatsapp.provider.media/item/1", "application/pdf"),
            sharedDocumentRequest(VIEW, "content://com.whatsapp.provider.media/item/1", "application/pdf", null),
        )
        assertEquals(
            SharedDocumentRequest("file:///sdcard/Download/a.html", "text/html"),
            sharedDocumentRequest(VIEW, "file:///sdcard/Download/a.html", "text/html; charset=utf-8", null),
        )
        assertEquals(
            SharedDocumentRequest("content://x/y", "text/html"),
            sharedDocumentRequest(SEND, null, "text/html", "content://x/y"),
        )
        assertEquals(
            SharedDocumentRequest("file:///sdcard/a.xhtml", "application/xhtml+xml"),
            sharedDocumentRequest(SEND, "file:///sdcard/a.xhtml", "application/xhtml+xml", null),
        )
        assertEquals(
            SharedDocumentRequest("content://stream", "application/pdf"),
            sharedDocumentRequest(SEND, "file:///ignored.pdf", "application/pdf", "content://stream"),
        )
    }

    @Test
    fun webLinksPlainTextAndBadUrisAreNotDocuments() {
        assertNull(sharedDocumentRequest(VIEW, "https://example.com/a.pdf", "application/pdf", null))
        assertNull(sharedDocumentRequest(VIEW, "http://example.com/", "text/html", null))
        assertNull(sharedDocumentRequest(VIEW, "content://x", "text/plain", null))
        assertNull(sharedDocumentRequest(VIEW, "content://x y", "text/html", null))
        assertNull(sharedDocumentRequest(VIEW, "file://evil.example/a.pdf", "application/pdf", null))
        assertNull(sharedDocumentRequest(SEND, "https://example.com/", "text/html", null))
        assertNull(sharedDocumentRequest(SEND, null, "application/pdf", null))
        assertNull(sharedDocumentRequest("android.intent.action.MAIN", "content://x", "application/pdf", null))
        assertNull(sharedDocumentRequest(null, "content://x", "application/pdf", null))
    }

    // ---- cache ----

    @Test
    fun copyUsesTheMimeExtensionAndIsolatesEachFile() = runBlocking {
        val cache = ImportedFileCache(tmp.root, now = { 10_000L })
        val pdf = cache.copyIn("../../secret.pdf", "application/pdf", 4) { ByteArrayInputStream("pdf!".toByteArray()) }
        val html = cache.copyIn("page.html", "text/html; charset=utf-8", null) { ByteArrayInputStream("<p>".toByteArray()) }
        val xhtml = cache.copyIn(null, "application/xhtml+xml", 1) { ByteArrayInputStream("x".toByteArray()) }
        val pdfUrl = (pdf as ImportResult.Ready).url
        val htmlUrl = (html as ImportResult.Ready).url
        val xhtmlUrl = (xhtml as ImportResult.Ready).url

        assertTrue(pdfUrl.endsWith("/secret.pdf"))
        assertTrue(htmlUrl.endsWith("/page.html"))
        assertTrue(xhtmlUrl.endsWith("/document.xhtml"))
        assertEquals("pdf!", File(URI(pdfUrl)).readText())
        assertTrue(importFileAllowed(cache.root, pdfUrl))
        assertTrue(importFileAllowed(cache.root, htmlUrl))
        assertTrue(importFileAllowed(cache.root, xhtmlUrl))
        assertEquals("secret.pdf", importDisplayName(pdfUrl))

        val spaced = cache.copyIn("My Report (1).pdf", "application/pdf", 3) {
            ByteArrayInputStream("abc".toByteArray())
        } as ImportResult.Ready
        assertTrue(importFileAllowed(cache.root, spaced.url))
        assertEquals("abc", File(URI(spaced.url)).readText())
        assertEquals("My Report (1).pdf", importDisplayName(spaced.url))

        val parents = listOf(pdfUrl, htmlUrl, xhtmlUrl, spaced.url).map { File(URI(it)).parentFile!!.canonicalFile }
        assertEquals(4, parents.toSet().size)
        parents.forEach { assertEquals(cache.root.canonicalFile, it.parentFile) }
    }

    @Test
    fun copyRefusesAnOversizeDocumentAndLeavesNothingBehind() = runBlocking {
        val cache = ImportedFileCache(tmp.root, maxBytes = 8)
        var opened = false
        val declared = cache.copyIn("a.pdf", "application/pdf", 9) {
            opened = true
            ByteArrayInputStream(byteArrayOf(1))
        }
        assertTrue(declared is ImportResult.TooLarge)
        assertFalse(opened)

        val streamed = cache.copyIn("b.pdf", "application/pdf", null) { ByteArrayInputStream(ByteArray(9)) }
        assertTrue(streamed is ImportResult.TooLarge)
        assertTrue(cache.root.listFiles().isNullOrEmpty())
    }

    @Test
    fun aFailedCopyIsDeleted() = runBlocking {
        val cache = ImportedFileCache(tmp.root)
        val result = cache.copyIn("a.pdf", "application/pdf", null) {
            object : InputStream() {
                override fun read(): Int = throw IOException("boom")
            }
        }
        assertTrue(result is ImportResult.Failed)
        assertTrue(cache.root.listFiles().isNullOrEmpty())
        assertTrue(cache.copyIn("a.pdf", "text/plain", null) { ByteArrayInputStream(byteArrayOf(1)) } is ImportResult.Failed)
    }

    @Test
    fun deleteOlderThanRemovesOnlyStaleImports() = runBlocking {
        val cache = ImportedFileCache(tmp.root, now = { 10_000L }, maxAgeMillis = 1_000)
        val young = cache.copyIn("young.pdf", "application/pdf", 1) { ByteArrayInputStream(byteArrayOf(1)) } as ImportResult.Ready
        val old = cache.copyIn("old.html", "text/html", 1) { ByteArrayInputStream(byteArrayOf(2)) } as ImportResult.Ready
        val oldFile = File(URI(old.url))
        assertTrue(oldFile.setLastModified(10_000L - 1_000L - 1))
        val outside = File(tmp.root, "keep.pdf").apply { writeText("keep"); setLastModified(0) }

        assertEquals(1, cache.deleteOlderThan())
        assertFalse(oldFile.exists())
        assertTrue(File(URI(young.url)).isFile)
        assertTrue(outside.isFile)

        // Exactly 24h (here, the max age) is not older than the cutoff.
        assertTrue(File(URI(young.url)).setLastModified(10_000L - 1_000L))
        assertEquals(0, cache.deleteOlderThan())
        assertTrue(File(URI(young.url)).isFile)
    }

    @Test
    fun clearAllDeletesYoungFilesToo() = runBlocking {
        val cache = ImportedFileCache(tmp.root, now = { 10_000L })
        val ready = cache.copyIn("a.pdf", "application/pdf", 1) { ByteArrayInputStream(byteArrayOf(1)) } as ImportResult.Ready
        cache.clearAll()
        assertFalse(File(URI(ready.url)).exists())
        assertFalse(cache.root.exists())
    }

    @Test
    fun importFileAllowedRejectsPathsThatEscapeTheCache() = runBlocking {
        val cache = ImportedFileCache(tmp.root)
        val ready = cache.copyIn("a.pdf", "application/pdf", 1) { ByteArrayInputStream(byteArrayOf(1)) } as ImportResult.Ready
        val file = File(URI(ready.url))
        val direct = File(cache.root, "loose.pdf").apply { writeBytes(byteArrayOf(1)) }
        assertFalse(importFileAllowed(cache.root, direct.toURI().toASCIIString()))

        val outside = File(tmp.root, "secret.pdf").apply { writeBytes(byteArrayOf(1)) }
        val link = File(file.parentFile, "escape.pdf")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertFalse(importFileAllowed(cache.root, link.toURI().toASCIIString()))

        val encodedDotDot = ready.url.replace("/imports/", "/imports/../imports/")
        assertFalse("url $encodedDotDot", isImportLoadUrl(encodedDotDot))
        assertFalse(importFileAllowed(cache.root, "file:///etc/passwd"))
        assertFalse(importFileAllowed(cache.root, "https://example.com/a.pdf"))
        assertNull(importDisplayName("file:///sdcard/x.pdf"))
    }

    @Test
    fun readableSharedFileRefusesPrivateDirectoriesAndFollowsSymlinksIntoThem() {
        val app = File(tmp.root, "appdata").apply { mkdirs() }
        val secret = File(app, "secret.pdf").apply { writeBytes(byteArrayOf(1)) }
        val pub = File(tmp.root, "pub").apply { mkdirs() }
        val doc = File(pub, "notes.pdf").apply { writeBytes(byteArrayOf(2)) }
        val link = File(pub, "link.pdf")
        Files.createSymbolicLink(link.toPath(), secret.toPath())

        assertEquals(doc.canonicalFile, readableSharedFile(doc.toURI().toASCIIString(), listOf(app)))
        assertNull(readableSharedFile(secret.toURI().toASCIIString(), listOf(app)))
        assertNull(readableSharedFile(link.toURI().toASCIIString(), listOf(app)))
        assertNull(readableSharedFile("file://evil.example/tmp/a.pdf", listOf(app)))
        assertNull(readableSharedFile("content://x/y", listOf(app)))
        assertNull(readableSharedFile(pub.toURI().toASCIIString(), listOf(app)))
    }
}
