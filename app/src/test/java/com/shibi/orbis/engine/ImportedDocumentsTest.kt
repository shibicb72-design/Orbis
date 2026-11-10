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
        assertNull(extensionForImportedMime("application/xhtml+xml"))
        assertNull(extensionForImportedMime("application/xhtml+xml;charset=utf-8"))
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
        assertEquals("page.html", sanitizeImportFileName("page", "html"))
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
        assertEquals("document.html", sanitizeImportFileName("/", "html"))
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
        assertNull(sharedDocumentRequest(VIEW, "content://x/page.xhtml", "application/xhtml+xml", null))
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
        val other = cache.copyIn(null, "text/html", null) { ByteArrayInputStream("<p>z</p>".toByteArray()) }
        val pdfUrl = (pdf as ImportResult.Ready).url
        val htmlUrl = (html as ImportResult.Ready).url
        val otherUrl = (other as ImportResult.Ready).url

        assertTrue(pdfUrl.endsWith("/secret.pdf"))
        assertTrue(htmlUrl.endsWith("/page.html"))
        assertTrue(otherUrl.endsWith("/document.html"))
        assertEquals("pdf!", File(URI(pdfUrl)).readText())
        assertTrue(importFileAllowed(cache.root, pdfUrl))
        assertTrue(importFileAllowed(cache.root, htmlUrl))
        assertTrue(importFileAllowed(cache.root, otherUrl))
        assertEquals("secret.pdf", importDisplayName(pdfUrl))
        assertTrue(cache.copyIn("p.xhtml", "application/xhtml+xml", null) { ByteArrayInputStream("<p>".toByteArray()) } is ImportResult.Failed)

        val spaced = cache.copyIn("My Report (1).pdf", "application/pdf", 3) {
            ByteArrayInputStream("abc".toByteArray())
        } as ImportResult.Ready
        assertTrue(importFileAllowed(cache.root, spaced.url))
        assertEquals("abc", File(URI(spaced.url)).readText())
        assertEquals("My Report (1).pdf", importDisplayName(spaced.url))

        val parents = listOf(pdfUrl, htmlUrl, otherUrl, spaced.url).map { File(URI(it)).parentFile!!.canonicalFile }
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
        // A userId prefix (content://0@authority/...) stays in the raw authority. Resolving that whole string
        // misses our provider, so any authority that contains '@' is treated as ours.
        val prefixed = "content://0@com.shibi.orbis.downloads/cache/secret.pdf"
        assertTrue(contentProviderIsThisApp(prefixed, "com.shibi.orbis") { error("should not resolve") })
        val encoded = "content://0%40com.shibi.orbis.downloads/cache/secret.pdf"
        assertTrue(contentProviderIsThisApp(encoded, "com.shibi.orbis") { error("should not resolve") })
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
        assertTrue(text.startsWith("<meta charset=\"utf-8\">"))
        val charset = text.indexOf("<meta charset=\"utf-8\">")
        val policyAt = text.indexOf(IMPORT_CONTENT_SECURITY_POLICY)
        assertTrue(charset >= 0 && charset < policyAt && policyAt < text.indexOf("<img"))
        assertPolicyBlocksFile(IMPORT_CONTENT_SECURITY_POLICY)
        assertFalse(importedPageJavaScript(html.url)!!)

        val pdfBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x34)
        val pdf = cache.copyIn("a.pdf", "application/pdf", pdfBytes.size.toLong()) { ByteArrayInputStream(pdfBytes) } as ImportResult.Ready
        assertEquals(pdfBytes.toList(), File(URI(pdf.url)).readBytes().toList())
        assertTrue(importedPageJavaScript(pdf.url)!!)
        assertNull(importedPageJavaScript("https://example.com/"))

        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x3c, 0x00)
        val wide = cache.copyIn("wide.html", "text/html", null) { ByteArrayInputStream(utf16) }
        assertTrue(wide is ImportResult.Failed)
    }

    @Test
    fun theDoctypeStaysFirstAndThePolicyMetaPrecedesEveryOtherTag() = runBlocking {
        val cache = ImportedFileCache(tmp.root)
        val body = "<!DOCTYPE html>\n<html><body><p>é</p></body></html>"
        val ready = cache.copyIn("page.html", "text/html", null) { ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)) } as ImportResult.Ready
        val text = File(URI(ready.url)).readText(Charsets.UTF_8)
        assertTrue(text.startsWith("<!DOCTYPE html>"))
        val charset = text.indexOf("<meta charset=\"utf-8\">")
        val policy = text.indexOf("<meta http-equiv=\"Content-Security-Policy\"")
        val html = text.indexOf("<html>")
        assertTrue("charset=$charset policy=$policy html=$html", charset > 0 && charset < policy && policy < html)
        val before = text.substring(0, charset).lowercase()
        assertFalse(before.contains("<html"))
        assertFalse(before.contains("<meta"))
        assertFalse(before.contains("<script"))
        assertFalse(before.contains("<img"))
    }

    @Test
    fun bomWhitespaceAndCommentsStayAheadOfTheDoctype() = runBlocking {
        val cache = ImportedFileCache(tmp.root)
        val body = "\uFEFF \n<!-- note --><!DOCTYPE html><img src=\"file:///data/secret.png\">"
        val ready = cache.copyIn("page.html", "text/html", null) { ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)) } as ImportResult.Ready
        val text = File(URI(ready.url)).readText(Charsets.UTF_8)
        assertTrue(text.startsWith("\uFEFF \n<!-- note --><!DOCTYPE html>"))
        val doctype = text.indexOf("<!DOCTYPE html>")
        val charset = text.indexOf("<meta charset=\"utf-8\">")
        val img = text.indexOf("<img")
        assertTrue(doctype >= 0 && doctype < charset && charset < img)
    }

    @Test
    fun htmlThatIsNotUtf8OrStartsWithANulIsRefused() = runBlocking {
        val cache = ImportedFileCache(tmp.root)
        val invalid = byteArrayOf('<'.code.toByte(), 0xFF.toByte(), '>'.code.toByte())
        assertTrue(cache.copyIn("bad.html", "text/html", null) { ByteArrayInputStream(invalid) } is ImportResult.Failed)
        val utf16 = "<html></html>".toByteArray(Charsets.UTF_16LE)
        assertTrue(cache.copyIn("wide.html", "text/html", null) { ByteArrayInputStream(utf16) } is ImportResult.Failed)
        val nul = "<html>a</html>".toByteArray(Charsets.UTF_8)
        nul[6] = 0
        assertTrue(cache.copyIn("nul.html", "text/html", null) { ByteArrayInputStream(nul) } is ImportResult.Failed)
        val late = ByteArray(70 * 1024)
        val head = "<html><p>".toByteArray(Charsets.UTF_8)
        head.copyInto(late)
        late.fill('a'.code.toByte(), head.size, late.size - 1)
        late[late.size - 1] = 0xFF.toByte()
        assertTrue(cache.copyIn("tail.html", "text/html", null) { ByteArrayInputStream(late) } is ImportResult.Failed)
        assertTrue(cache.root.listFiles().isNullOrEmpty())
        val ok = "<!-- c --><p>é</p>".toByteArray(Charsets.UTF_8)
        val ready = cache.copyIn("ok.html", "text/html", null) { ByteArrayInputStream(ok) } as ImportResult.Ready
        val text = File(URI(ready.url)).readText(Charsets.UTF_8)
        assertTrue(text.contains("é"))
        assertTrue(text.indexOf("<meta charset=\"utf-8\">") < text.indexOf("<p>"))
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

    @Test
    fun aCopyThatKeepsReadingPastTheIdleWindowSucceeds() = runBlocking {
        val cache = ImportedFileCache(tmp.root, copyTimeoutMillis = 200, overallTimeoutMillis = 5_000, dispatcher = Dispatchers.IO)
        var reads = 0
        val result = cache.copyIn("a.pdf", "application/pdf", null) {
            object : InputStream() {
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (reads >= 4) return -1
                    Thread.sleep(120)
                    reads++
                    b[off] = 'a'.code.toByte()
                    return 1
                }
            }
        }
        assertTrue(result is ImportResult.Ready)
    }

    @Test
    fun theOverallCapStopsACopyThatNeverGoesIdle() = runBlocking {
        val cache = ImportedFileCache(tmp.root, copyTimeoutMillis = 60_000, overallTimeoutMillis = 400, dispatcher = Dispatchers.IO)
        val closed = AtomicBoolean(false)
        val start = System.nanoTime()
        val result = cache.copyIn("a.pdf", "application/pdf", null) {
            object : InputStream() {
                override fun read(): Int {
                    if (closed.get()) throw IOException("closed")
                    Thread.sleep(30)
                    return 'a'.code
                }
                override fun close() {
                    closed.set(true)
                }
            }
        }
        val waitedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(result is ImportResult.Failed)
        assertTrue("overall cap waited ${waitedMs}ms", waitedMs < 3_000)
        assertTrue(closed.get())
    }

    @Test(timeout = 5_000)
    fun aBlockedOpenFailsAtTheIdleTimeout() = runBlocking {
        val cache = ImportedFileCache(tmp.root, copyTimeoutMillis = 200, overallTimeoutMillis = 8_000, dispatcher = Dispatchers.IO)
        val release = CountDownLatch(1)
        val start = System.nanoTime()
        val result = try {
            cache.copyIn("a.pdf", "application/pdf", null) {
                release.await(8, TimeUnit.SECONDS)
                ByteArrayInputStream(byteArrayOf(1))
            }
        } finally {
            release.countDown()
        }
        val waitedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(result is ImportResult.Failed)
        assertTrue("open waited ${waitedMs}ms", waitedMs < 3_000)
    }

    @Test(timeout = 5_000)
    fun aBlockedQueryFailsAtTheIdleTimeout() = runBlocking {
        val cache = ImportedFileCache(tmp.root, copyTimeoutMillis = 200, overallTimeoutMillis = 8_000, dispatcher = Dispatchers.IO)
        val release = CountDownLatch(1)
        var opened = false
        val start = System.nanoTime()
        val result = try {
            cache.readShared(
                "application/pdf",
                query = {
                    release.await(8, TimeUnit.SECONDS)
                    "a.pdf" to 1L
                },
                open = {
                    opened = true
                    ByteArrayInputStream(byteArrayOf(1))
                },
            )
        } finally {
            release.countDown()
        }
        val waitedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(result is ImportResult.Failed)
        assertFalse(opened)
        assertTrue("query waited ${waitedMs}ms", waitedMs < 3_000)
    }

    @Test
    fun historyBackToImportedHtmlTurnsScriptOffAndPdfKeepsIt() {
        val html = "file:///data/user/0/com.shibi.orbis/cache/imports/11111111-1111-1111-1111-111111111111/page.html"
        val pdf = "file:///data/user/0/com.shibi.orbis/cache/imports/11111111-1111-1111-1111-111111111111/a.pdf"
        assertEquals(false, importScriptCorrection(html, javascriptEnabled = true))
        assertNull(importScriptCorrection(html, javascriptEnabled = false))
        assertEquals(true, importScriptCorrection(pdf, javascriptEnabled = false))
        assertNull(importScriptCorrection(pdf, javascriptEnabled = true))
        assertNull(importScriptCorrection("https://example.com/", javascriptEnabled = true))
        assertNull(importScriptCorrection(null, javascriptEnabled = true))
    }

    @Test
    fun forwardFromAnImportRestoresTheWebsiteAndDoesNotReloadAgain() {
        val html = "file:///data/user/0/com.shibi.orbis/cache/imports/11111111-1111-1111-1111-111111111111/page.html"
        val pdf = "file:///data/user/0/com.shibi.orbis/cache/imports/11111111-1111-1111-1111-111111111111/a.pdf"
        val site = "https://example.com/"
        assertEquals(HistoryScript.Force(enabled = false, reload = true), scriptAfterHistoryCommit(html, javascriptEnabled = true, previousForcedImportScript = false))
        assertEquals(HistoryScript.Force(enabled = true, reload = false), scriptAfterHistoryCommit(pdf, javascriptEnabled = true, previousForcedImportScript = true))
        // Forward to the site while the import is still forcing the setting.
        assertEquals(HistoryScript.RestoreSite, scriptAfterHistoryCommit(site, javascriptEnabled = false, previousForcedImportScript = true))
        assertEquals(HistoryScript.RestoreSite, scriptAfterHistoryCommit(site, javascriptEnabled = true, previousForcedImportScript = true))
        // The reload that applies site policy clears the flag first, so the same commit does not reload again.
        assertEquals(HistoryScript.Leave, scriptAfterHistoryCommit(site, javascriptEnabled = true, previousForcedImportScript = false))
        assertEquals(HistoryScript.Leave, scriptAfterHistoryCommit(site, javascriptEnabled = false, previousForcedImportScript = false))
        assertEquals(HistoryScript.Leave, scriptAfterHistoryCommit("about:blank", javascriptEnabled = false, previousForcedImportScript = true))
    }

    @Test
    fun emptyCommentsAndAQuotedDoctypeDoNotHideATagBeforeThePolicy() = runBlocking {
        val cache = ImportedFileCache(tmp.root)
        assertMetaBeforeTag(cache, "<!--><img src=\"file:///data/secret.png\"><!-- -->", "<!-->")
        assertMetaBeforeTag(cache, "<!---><img src=\"file:///data/secret.png\">", "<!--->")
        assertMetaBeforeTag(cache, "<!-- secret --!><img src=\"file:///data/secret.png\">", "<!-- secret --!>")
        assertMetaBeforeTag(cache, "<!DOCTYPE html \"><img src=\"file:///data/secret.png>\">", "<!DOCTYPE html \">")
        val ambiguous = cache.copyIn("gap.html", "text/html", null) {
            ByteArrayInputStream("<!-- keep > this --><img src=\"file:///data/secret.png\">".toByteArray())
        } as ImportResult.Ready
        val gap = File(URI(ambiguous.url)).readText()
        assertTrue(gap.indexOf("<meta charset=\"utf-8\">") < gap.indexOf("<!--"))
        assertTrue(gap.indexOf("<meta charset=\"utf-8\">") < gap.indexOf("<img"))
    }

    @Test
    fun aLongPreambleStillPutsTheCharsetMetaInsideThePrescan() = runBlocking {
        val cache = ImportedFileCache(tmp.root)
        val long = " ".repeat(2_000) + "<!DOCTYPE html><p>z</p>"
        val ready = cache.copyIn("long.html", "text/html", null) { ByteArrayInputStream(long.toByteArray()) } as ImportResult.Ready
        val text = File(URI(ready.url)).readText()
        assertTrue(text.startsWith("<meta charset=\"utf-8\">"))
        assertTrue(text.indexOf("<meta charset=\"utf-8\">") < 1_024)
        assertTrue(text.indexOf("<!DOCTYPE html>") > text.indexOf(IMPORT_CONTENT_SECURITY_POLICY))

        val withBom = "\uFEFF" + " ".repeat(2_000) + "<p>z</p>"
        val bom = cache.copyIn("bom.html", "text/html", null) { ByteArrayInputStream(withBom.toByteArray(Charsets.UTF_8)) } as ImportResult.Ready
        val bomText = File(URI(bom.url)).readText(Charsets.UTF_8)
        assertTrue(bomText.startsWith("\uFEFF<meta charset=\"utf-8\">"))

        val moderate = " ".repeat(400) + "<!DOCTYPE html><p>z</p>"
        val kept = cache.copyIn("kept.html", "text/html", null) { ByteArrayInputStream(moderate.toByteArray()) } as ImportResult.Ready
        val keptText = File(URI(kept.url)).readText()
        assertTrue(keptText.startsWith(" ".repeat(400) + "<!DOCTYPE html>"))
        assertTrue(keptText.indexOf("<!DOCTYPE html>") < keptText.indexOf("<meta charset=\"utf-8\">"))
    }
}

private suspend fun assertMetaBeforeTag(cache: ImportedFileCache, body: String, prefix: String) {
    val ready = cache.copyIn("t.html", "text/html", null) { ByteArrayInputStream(body.toByteArray()) } as ImportResult.Ready
    val text = File(URI(ready.url)).readText()
    assertTrue("body $body -> $text", text.startsWith(prefix))
    assertTrue(text.indexOf(prefix) < text.indexOf("<meta charset=\"utf-8\">"))
    assertTrue(text.indexOf("<meta charset=\"utf-8\">") < text.indexOf("<img"))
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
