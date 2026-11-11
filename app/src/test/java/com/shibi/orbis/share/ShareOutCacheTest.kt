package com.shibi.orbis.share

import com.shibi.orbis.engine.ImportResult
import com.shibi.orbis.engine.ImportedFileCache
import com.shibi.orbis.engine.IncomingDownload
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShareOutCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    private class FakePdf(
        override val contentDisposition: String?,
        private val body: () -> InputStream?,
        override val contentLength: Long = -1L,
        override val url: String = "https://example.com/files/report.pdf",
        override val contentType: String? = "text/html",
    ) : IncomingDownload {
        var opened = false
        var discarded = false
        var closed = false

        override fun openBody(): InputStream? {
            opened = true
            val inner = body() ?: return null
            return object : InputStream() {
                override fun read(): Int = inner.read()

                override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)

                override fun close() {
                    closed = true
                    inner.close()
                }
            }
        }

        override fun discard() {
            discarded = true
        }
    }

    private fun shareRoot() = File(tmp.root, SHARE_OUT_DIR)

    private fun filesUnder(dir: File): List<File> = dir.walkTopDown().filter { it.isFile }.toList()

    /** A real import, made the way the app makes one. */
    private fun import(imports: ImportedFileCache, name: String, mime: String, bytes: ByteArray): String =
        runBlocking { (imports.copyIn(name, mime, bytes.size.toLong()) { ByteArrayInputStream(bytes) } as ImportResult.Ready).url }

    @Test
    fun copyImportedCopiesAPdfByteForByteIntoTheNormalDirectory() = runBlocking {
        val imports = ImportedFileCache(tmp.root)
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val url = import(imports, "a.pdf", "application/pdf", bytes)
        val result = ShareOutCache(tmp.root).copyImported(imports.root, url) as ShareOutResult.Ready
        assertEquals("application/pdf", result.mime)
        assertEquals("a.pdf", result.file.name)
        assertTrue(uuid.matches(result.file.parentFile!!.name))
        assertEquals(File(shareRoot(), "n").canonicalFile, result.file.parentFile!!.parentFile!!.canonicalFile)
        assertArrayEquals(bytes, result.file.readBytes())
        // The import stays where it was; the share is a separate copy.
        assertTrue(File(URI(url)).isFile)
    }

    @Test
    fun copyImportedSharesHtmlAsHtml() = runBlocking {
        val imports = ImportedFileCache(tmp.root)
        val url = import(imports, "page.html", "text/html", "<p>hi</p>".toByteArray())
        val result = ShareOutCache(tmp.root).copyImported(imports.root, url) as ShareOutResult.Ready
        assertEquals("text/html", result.mime)
        assertEquals("page.html", result.file.name)
        assertEquals(File(URI(url)).readText(), result.file.readText())
    }

    @Test
    fun copyImportedRefusesAnythingThatIsNotAnImport() = runBlocking {
        val imports = ImportedFileCache(tmp.root)
        val cache = ShareOutCache(tmp.root)
        val url = import(imports, "a.pdf", "application/pdf", byteArrayOf(1, 2, 3))
        val outside = File(tmp.root, "imports-not/x/secret.pdf").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(9))
        }
        assertEquals(ShareOutResult.Failed, cache.copyImported(imports.root, outside.toURI().toASCIIString()))
        assertEquals(ShareOutResult.Failed, cache.copyImported(imports.root, url.replace("/imports/", "/imports/../imports/")))
        assertEquals(ShareOutResult.Failed, cache.copyImported(imports.root, url.substringBeforeLast('/') + "/missing.pdf"))
        val txt = File(File(URI(url)).parentFile, "notes.txt").apply { writeText("x") }
        assertEquals(ShareOutResult.Failed, cache.copyImported(imports.root, txt.toURI().toASCIIString()))
        assertEquals(ShareOutResult.Failed, cache.copyImported(imports.root, "file:///etc/passwd"))
        assertEquals(ShareOutResult.Failed, cache.copyImported(imports.root, "https://example.com/a.pdf"))
        assertTrue(filesUnder(shareRoot()).isEmpty())
    }

    @Test
    fun copyImportedRefusesASymlinkThatLeavesTheImportRoot() = runBlocking {
        val imports = ImportedFileCache(tmp.root)
        val url = import(imports, "a.pdf", "application/pdf", byteArrayOf(1))
        val outside = File(tmp.root, "secret.pdf").apply { writeBytes(byteArrayOf(7)) }
        val link = File(File(URI(url)).parentFile, "escape.pdf")
        val made = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        assumeTrue("symlinks need extra rights on this OS", made)
        assertEquals(ShareOutResult.Failed, ShareOutCache(tmp.root).copyImported(imports.root, link.toURI().toASCIIString()))
        assertTrue(filesUnder(shareRoot()).isEmpty())
    }

    @Test
    fun copyPdfMakesEveryHostileNameASafePdfInAFreshDirectory() = runBlocking {
        val cache = ShareOutCache(tmp.root)
        val hostile =
            listOf(
                "attachment; filename=\"../../x\"",
                "attachment; filename=\"a\\b\"",
                "attachment; filename=\"‮gnp.pdf\"",
                "attachment; filename=\"" + "n".repeat(5_000) + ".pdf\"",
                "attachment; filename=\"evil.html\"",
                "attachment; filename=\"\"",
                "",
                null,
            )
        val dirs = HashSet<String>()
        for (header in hostile) {
            val pdf = FakePdf(header, { ByteArrayInputStream(byteArrayOf(37, 80, 68, 70)) })
            val ready = cache.copyPdf(pdf, isPrivate = false) as ShareOutResult.Ready
            val file = ready.file
            assertEquals("application/pdf", ready.mime)
            assertTrue(file.name, file.name.endsWith(".pdf"))
            assertTrue(file.name, file.name.toByteArray(Charsets.UTF_8).size <= 120)
            assertFalse(file.name, file.name.contains('/') || file.name.contains('\\') || file.name.contains('‮'))
            assertTrue(uuid.matches(file.parentFile!!.name))
            assertEquals(File(shareRoot(), "n").canonicalFile, file.parentFile!!.parentFile!!.canonicalFile)
            assertEquals(listOf(file.name), file.parentFile!!.list()!!.toList())
            assertTrue(dirs.add(file.parentFile!!.name))
            assertTrue(pdf.closed)
        }
    }

    @Test
    fun copyPdfRefusesADeclaredLengthOverTheCapWithoutOpeningTheBody() = runBlocking {
        val pdf = FakePdf("attachment; filename=a.pdf", { error("must not open") }, contentLength = 101)
        assertEquals(ShareOutResult.TooLarge, ShareOutCache(tmp.root, maxBytes = 100).copyPdf(pdf, isPrivate = false))
        assertFalse(pdf.opened)
        assertTrue(pdf.discarded)
        assertTrue(filesUnder(shareRoot()).isEmpty())
    }

    @Test
    fun copyPdfStopsAStreamThatRunsPastTheCap() = runBlocking {
        val pdf = FakePdf("attachment; filename=a.pdf", { ByteArrayInputStream(ByteArray(300_000)) })
        assertEquals(ShareOutResult.TooLarge, ShareOutCache(tmp.root, maxBytes = 200_000).copyPdf(pdf, isPrivate = false))
        assertTrue(pdf.closed)
        assertTrue(filesUnder(shareRoot()).isEmpty())
        assertTrue(File(shareRoot(), "n").list().orEmpty().isEmpty())
    }

    @Test
    fun aBodyThatFailsMidReadLeavesNothing() = runBlocking {
        val failing =
            object : InputStream() {
                var sent = 0

                override fun read(): Int = throw IOException()

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (sent > 0) throw IOException("disk full")
                    sent += len
                    return len
                }
            }
        val pdf = FakePdf("attachment; filename=a.pdf", { failing })
        assertEquals(ShareOutResult.Failed, ShareOutCache(tmp.root).copyPdf(pdf, isPrivate = false))
        assertTrue(pdf.closed)
        assertTrue(File(shareRoot(), "n").list().orEmpty().isEmpty())
    }

    @Test
    fun aMissingBodyFails() = runBlocking {
        val pdf = FakePdf("attachment; filename=a.pdf", { null })
        assertEquals(ShareOutResult.Failed, ShareOutCache(tmp.root).copyPdf(pdf, isPrivate = false))
        assertTrue(File(shareRoot(), "n").list().orEmpty().isEmpty())
    }

    @Test
    fun privateSharesLiveUnderPAndClearPrivateLeavesNormalOnes() = runBlocking {
        val cache = ShareOutCache(tmp.root)
        val priv = cache.copyPdf(FakePdf("attachment; filename=p.pdf", { ByteArrayInputStream(byteArrayOf(1)) }), isPrivate = true) as ShareOutResult.Ready
        val norm = cache.copyPdf(FakePdf("attachment; filename=n.pdf", { ByteArrayInputStream(byteArrayOf(2)) }), isPrivate = false) as ShareOutResult.Ready
        assertEquals(File(shareRoot(), "p").canonicalFile, priv.file.parentFile!!.parentFile!!.canonicalFile)
        cache.clearPrivate()
        assertFalse(File(shareRoot(), "p").exists())
        assertTrue(norm.file.isFile)
    }

    @Test
    fun oldNormalSharesAreTrimmedAndClearAllRemovesEverything() = runBlocking {
        val now = System.currentTimeMillis()
        val cache = ShareOutCache(tmp.root, now = { now })
        val old = cache.copyPdf(FakePdf("attachment; filename=old.pdf", { ByteArrayInputStream(byteArrayOf(1)) }), isPrivate = false) as ShareOutResult.Ready
        val young = cache.copyPdf(FakePdf("attachment; filename=new.pdf", { ByteArrayInputStream(byteArrayOf(2)) }), isPrivate = false) as ShareOutResult.Ready
        val priv = cache.copyPdf(FakePdf("attachment; filename=p.pdf", { ByteArrayInputStream(byteArrayOf(3)) }), isPrivate = true) as ShareOutResult.Ready
        assertTrue(old.file.setLastModified(now - 25L * 60 * 60 * 1000))
        assertEquals(1, cache.deleteOlderThan())
        assertFalse(old.file.parentFile!!.exists())
        assertTrue(young.file.isFile)
        cache.clearAll()
        assertFalse(young.file.exists())
        assertFalse(priv.file.exists())
        assertFalse(shareRoot().exists())
    }
}
