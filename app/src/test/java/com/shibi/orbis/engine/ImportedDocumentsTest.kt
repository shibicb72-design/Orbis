package com.shibi.orbis.engine

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
        assertEquals("Report..v2.pdf", sanitizeImportFileName("Report..v2.pdf", "pdf"))
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
    fun viewAndSendAcceptContentWithTheThreeMimeTypes() {
        assertEquals(
            SharedDocumentRequest("content://com.whatsapp.provider.media/item/1", "application/pdf"),
            sharedDocumentRequest(VIEW, "content://com.whatsapp.provider.media/item/1", "application/pdf", null),
        )
        assertEquals(
            SharedDocumentRequest("content://x/y", "text/html"),
            sharedDocumentRequest(SEND, null, "text/html", "content://x/y"),
        )
        assertEquals(
            SharedDocumentRequest("content://stream", "application/pdf"),
            sharedDocumentRequest(SEND, "content://ignored", "application/pdf", "content://stream"),
        )
        assertEquals("application/pdf", mimeForSharedDocument(null, "application/pdf"))
        assertEquals("text/html", mimeForSharedDocument("  ", "text/html; charset=utf-8"))
        assertEquals("application/pdf", mimeForSharedDocument("application/pdf; charset=binary", "text/html"))
        assertNull(mimeForSharedDocument(null, null))
        assertNull(mimeForSharedDocument(null, "text/plain"))
        // An explicit type wins, even when the provider would have said PDF.
        assertNull(mimeForSharedDocument("text/plain", "application/pdf"))
    }

    @Test
    fun webLinksPlainTextAndBadUrisAreNotDocuments() {
        assertNull(sharedDocumentRequest(VIEW, "https://example.com/a.pdf", "application/pdf", null))
        assertNull(sharedDocumentRequest(VIEW, "http://example.com/", "text/html", null))
        assertNull(sharedDocumentRequest(VIEW, "content://x", "text/plain", null))
        assertNull(sharedDocumentRequest(VIEW, "content://x y", "text/html", null))
        assertNull(sharedDocumentRequest(VIEW, "file://evil.example/a.pdf", "application/pdf", null))
        assertNull(sharedDocumentRequest(VIEW, "file:///sdcard/Download/a.pdf", "application/pdf", null))
        assertNull(sharedDocumentRequest(SEND, "file:///sdcard/a.xhtml", "application/xhtml+xml", null))
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
        val xhtml = cache.copyIn(null, "application/xhtml+xml", null) {
            ByteArrayInputStream(
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>t</title></head><body>x</body></html>".toByteArray(),
            )
        }
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
        val dotted = ready.url.substringBeforeLast('/') + "/Report..v2.pdf"
        assertTrue(isImportLoadUrl(dotted))
        assertFalse(isImportLoadUrl(ready.url.substringBeforeLast('/') + "/../Report..v2.pdf"))
        assertFalse(isImportLoadUrl(ready.url.substringBeforeLast('/') + "/./Report..v2.pdf"))
    }

    @Test
    fun ownContentProviderIsRefusedAndAnotherAppsIsNot() {
        val ours = "content://com.shibi.orbis.fileprovider/cache/imports/secret.pdf"
        val theirs = "content://com.whatsapp.provider.media/item/1"
        assertTrue(contentProviderIsThisApp(ours, "com.shibi.orbis") { "com.shibi.orbis" })
        assertFalse(contentProviderIsThisApp(theirs, "com.shibi.orbis") { "com.whatsapp" })
        assertFalse(contentProviderIsThisApp(theirs, "com.shibi.orbis") { null })
        assertFalse(contentProviderIsThisApp("file:///data/secret.pdf", "com.shibi.orbis") { "com.shibi.orbis" })
        assertTrue(contentProviderIsThisApp(theirs, "com.shibi.orbis") { error("lookup failed") })
    }

    @Test
    fun doubleDotsInsideAFileNameAreNotAPathSegment() {
        val url = "file:///data/user/0/com.shibi.orbis/cache/imports/11111111-1111-1111-1111-111111111111/Report..v2.pdf"
        assertTrue(isImportLoadUrl(url))
        assertEquals("Report..v2.pdf", importDisplayName(url))
        val escaped = "file:///data/user/0/com.shibi.orbis/cache/imports/%2e%2e/11111111-1111-1111-1111-111111111111/a.pdf"
        assertFalse(isImportLoadUrl(escaped))
        assertFalse(isImportLoadUrl(url.replace("/imports/", "/imports/../imports/")))
    }

    @Test
    fun fileSubframesAreDeniedAndImportedHtmlDoesNotRunScript() {
        assertFalse(fileSubframeAllowed("file:///data/user/0/com.shibi.orbis/cache/imports/id/a.html"))
        assertFalse(fileSubframeAllowed("file:///data/data/com.shibi.orbis/shared_prefs/x.xml"))
        assertFalse(fileSubframeAllowed("FILE:///etc/passwd"))
        assertTrue(fileSubframeAllowed("https://example.com/a.png"))
        assertTrue(fileSubframeAllowed("about:blank"))
        assertTrue(fileSubframeAllowed("data:text/html,hi"))
    }

    @Test
    fun importedHtmlCarriesAPolicyThatBlocksFileSubresources() = runBlocking {
        val cache = ImportedFileCache(tmp.root)
        val body = "<html><head><title>T</title></head><body><img src=\"file:///data/secret.png\"><script src=\"file:///data/x.js\"></script></body></html>"
        val html = cache.copyIn("page.html", "text/html", null) { ByteArrayInputStream(body.toByteArray()) } as ImportResult.Ready
        val text = File(URI(html.url)).readText()
        assertTrue(text.startsWith("<meta http-equiv=\"Content-Security-Policy\""))
        assertTrue(text.indexOf(IMPORT_CONTENT_SECURITY_POLICY) < text.indexOf("<img"))
        assertPolicyBlocksFile(IMPORT_CONTENT_SECURITY_POLICY)
        assertFalse(importedPageJavaScript(html.url)!!)

        val pdfBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x34)
        val pdf = cache.copyIn("a.pdf", "application/pdf", pdfBytes.size.toLong()) { ByteArrayInputStream(pdfBytes) } as ImportResult.Ready
        assertEquals(pdfBytes.toList(), File(URI(pdf.url)).readBytes().toList())
        assertTrue(importedPageJavaScript(pdf.url)!!)
        assertNull(importedPageJavaScript("https://example.com/"))

        val xhtml = """<?xml version="1.0"?><!-- <head> --><html xmlns="http://www.w3.org/1999/xhtml"><head><title>T</title></head><body/></html>"""
        val x = cache.copyIn("p.xhtml", "application/xhtml+xml", null) { ByteArrayInputStream(xhtml.toByteArray()) } as ImportResult.Ready
        val xText = File(URI(x.url)).readText()
        val commentEnd = xText.indexOf("-->")
        val policy = xText.indexOf(IMPORT_CONTENT_SECURITY_POLICY)
        val title = xText.indexOf("<title>")
        // The decoy <head> inside the comment is not where the policy was inserted.
        assertTrue(commentEnd >= 0 && policy > commentEnd && policy < title)
        assertFalse(importedPageJavaScript(x.url)!!)

        val bare = cache.copyIn("bare.xhtml", "application/xhtml+xml", null) {
            ByteArrayInputStream("<p>no html</p>".toByteArray())
        }
        assertTrue(bare is ImportResult.Failed)

        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x3c, 0x00)
        val wide = cache.copyIn("wide.html", "text/html", null) { ByteArrayInputStream(utf16) }
        assertTrue(wide is ImportResult.Failed)
    }

    @Test
    fun aSecondShareIsQueuedBehindTheFirst() {
        val queue = ImportOpenQueue()
        queue.add("file:///a.pdf")
        queue.add("file:///b.html")
        assertEquals("file:///a.pdf", queue.head.value)
        assertTrue(queue.consume("file:///a.pdf"))
        assertEquals("file:///b.html", queue.head.value)
        assertFalse(queue.consume("file:///a.pdf"))
        assertTrue(queue.consume("file:///b.html"))
        assertNull(queue.head.value)
        queue.add("file:///c.pdf")
        queue.clear()
        assertNull(queue.head.value)
    }

    @Test
    fun clearAllDoesNotWaitForTheProviderReadAndDropsTheCopy() = runBlocking {
        val cache = ImportedFileCache(tmp.root, dispatcher = Dispatchers.IO)
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        val deferred = async(Dispatchers.IO) {
            cache.copyIn("a.pdf", "application/pdf", 1) {
                object : InputStream() {
                    override fun read(): Int {
                        opened.countDown()
                        if (!release.await(10, TimeUnit.SECONDS)) throw IOException("timed out")
                        return -1
                    }
                }
            }
        }
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        val start = System.nanoTime()
        cache.clearAll()
        val waitedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("clear waited ${waitedMs}ms on the provider", waitedMs < 1_000)
        release.countDown()
        assertTrue(deferred.await() is ImportResult.Failed)
        assertTrue(cache.root.listFiles().isNullOrEmpty())
    }

    @Test
    fun aStuckProviderIsClosedWhenTheCopyTimesOut() = runBlocking {
        val cache = ImportedFileCache(tmp.root, copyTimeoutMillis = 200, dispatcher = Dispatchers.IO)
        val closed = AtomicBoolean(false)
        val result = cache.copyIn("a.pdf", "application/pdf", null) {
            object : InputStream() {
                private val gate = CountDownLatch(1)
                override fun read(): Int {
                    gate.await(5, TimeUnit.SECONDS)
                    if (closed.get()) throw IOException("closed")
                    return -1
                }
                override fun close() {
                    closed.set(true)
                    gate.countDown()
                }
            }
        }
        assertTrue(result is ImportResult.Failed)
        assertTrue(closed.get())
        assertTrue(cache.root.listFiles().isNullOrEmpty())
        assertTrue(File(tmp.root, "import-staging").listFiles().isNullOrEmpty())
    }

    @Test
    fun deleteImportRemovesOnlyThatDocumentsDirectory() = runBlocking {
        val cache = ImportedFileCache(tmp.root)
        val a = cache.copyIn("a.pdf", "application/pdf", 1) { ByteArrayInputStream(byteArrayOf(1)) } as ImportResult.Ready
        val b = cache.copyIn("b.pdf", "application/pdf", 1) { ByteArrayInputStream(byteArrayOf(2)) } as ImportResult.Ready
        assertTrue(cache.deleteImport(a.url))
        assertFalse(File(URI(a.url)).exists())
        assertTrue(File(URI(b.url)).isFile)
        assertFalse(cache.deleteImport(a.url))
        assertFalse(cache.deleteImport("file:///etc/passwd"))
    }
}

private fun assertPolicyBlocksFile(policy: String) {
    for (directive in listOf("default-src", "script-src", "img-src", "style-src", "font-src", "connect-src", "frame-src", "object-src")) {
        assertFalse("$directive file", cspDirectiveAllows(policy, directive, "file"))
        assertFalse("$directive https", cspDirectiveAllows(policy, directive, "https"))
        assertFalse("$directive http", cspDirectiveAllows(policy, directive, "http"))
    }
    assertTrue(cspDirectiveAllows(policy, "img-src", "data"))
    assertTrue(cspDirectiveAllows(policy, "img-src", "blob"))
    assertTrue(cspDirectiveAllows(policy, "media-src", "data"))
    assertTrue(cspDirectiveAllows(policy, "font-src", "data"))
    assertTrue(cspDirectiveAllows(policy, "style-src", "data"))
    assertFalse(cspDirectiveAllows(policy, "prefetch-src", "https"))
    assertFalse(policy.contains("*"))
    assertFalse(policy.contains("file:"))
}
