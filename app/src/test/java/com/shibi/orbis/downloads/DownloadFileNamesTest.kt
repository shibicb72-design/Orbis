package com.shibi.orbis.downloads

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadFileNamesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun resolve(
        disposition: String? = null,
        url: String = "https://example.com/",
        type: String? = null,
        ext: (String) -> String? = { null },
    ) = DownloadFileNames.resolve(disposition, url, type, ext)

    private fun cd(filename: String) = "attachment; filename=\"$filename\""

    // ---- path traversal and separators ----

    @Test
    fun unixTraversalKeepsOnlyLastSegment() {
        assertEquals("evil.sh", resolve(cd("../../evil.sh")))
    }

    @Test
    fun windowsTraversalKeepsOnlyLastSegment() {
        assertEquals("evil.bat", resolve(cd("..\\..\\evil.bat")))
        assertEquals("evil.bat", resolve("attachment; filename=..\\..\\evil.bat"))
        assertEquals("file.txt", resolve(cd("C:\\Users\\me\\file.txt")))
    }

    @Test
    fun absolutePathKeepsOnlyLastSegment() {
        assertEquals("passwd", resolve(cd("/etc/passwd")))
    }

    @Test
    fun percentEncodedTraversalInExtendedValueIsNeutralised() {
        assertEquals("evil", resolve("attachment; filename*=UTF-8''%2e%2e%2fevil"))
        assertEquals("evil", resolve("attachment; filename*=UTF-8''..%5C..%5Cevil"))
    }

    @Test
    fun percentEncodedSlashInUrlSegmentIsNeutralised() {
        assertEquals("evil.sh", resolve(url = "https://example.com/dl/..%2F..%2Fevil.sh"))
    }

    // ---- header precedence and parsing ----

    @Test
    fun extendedFilenameBeatsPlainFilenameInEitherOrder() {
        assertEquals(
            "fancy name.txt",
            resolve("attachment; filename=\"plain.txt\"; filename*=UTF-8''fancy%20name.txt"),
        )
        assertEquals(
            "fancy name.txt",
            resolve("attachment; filename*=UTF-8''fancy%20name.txt; filename=\"plain.txt\""),
        )
    }

    @Test
    fun unsupportedExtendedCharsetFallsBackToPlainFilename() {
        assertEquals("plain.txt", resolve("attachment; filename*=EBCDIC''x.txt; filename=\"plain.txt\""))
    }

    @Test
    fun latin1ExtendedValueDecodes() {
        assertEquals("caf\u00e9.txt", resolve("attachment; filename*=iso-8859-1''caf%E9.txt"))
    }

    @Test
    fun quotedFilenameWithEscapedQuotes() {
        // Header text: attachment; filename="a\"b.txt"  ('"' is not allowed in names, so it becomes '_')
        assertEquals("a_b.txt", resolve("attachment; filename=\"a\\\"b.txt\""))
    }

    @Test
    fun semicolonInsideQuotesDoesNotSplit() {
        assertEquals("a;b.txt", resolve(cd("a;b.txt")))
    }

    @Test
    fun parameterKeysAreCaseInsensitive() {
        assertEquals("x.bin", resolve("attachment; FILENAME=\"x.bin\""))
    }

    @Test
    fun garbageDispositionDoesNotThrowAndFallsBack() {
        assertEquals("download", resolve("attachment; filename"))
        assertEquals("download", resolve(";;;===;\"\"\"; filename*=''"))
        assertEquals("download", resolve("   "))
        assertEquals("download", resolve(null))
    }

    // ---- dangerous characters ----

    @Test
    fun rightToLeftOverrideIsStripped() {
        val result = resolve(cd("exe\u202Egpj."))
        assertEquals("exegpj", result)
        assertFalse(result.contains('\u202E'))
    }

    @Test
    fun allBidiControlsAreStripped() {
        val bidi = "\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069\u200E\u200F"
        assertEquals("ab.txt", resolve(cd("a${bidi}b.txt")))
    }

    @Test
    fun nulByteAndControlCharactersAreStripped() {
        assertEquals("ab.txt", resolve(cd("a\u0000b.txt")))
        assertEquals("ab.txt", resolve(cd("a\r\n\tb.txt")))
        assertEquals("ab.txt", resolve(cd("a\u007Fb\u0085.txt")))
    }

    @Test
    fun nulInPercentEncodedValueIsStripped() {
        assertEquals("ab.txt", resolve("attachment; filename*=UTF-8''a%00b.txt"))
    }

    @Test
    fun windowsIllegalCharactersAreReplaced() {
        assertEquals("a_b_c_d_e_f.txt", resolve(cd("a:b*c?d|e<f.txt")))
    }

    @Test
    fun onlyDotsFallsBackToDownload() {
        assertEquals("download", resolve(cd(".....")))
        assertEquals("download", resolve(cd("..")))
        assertEquals("download", resolve(cd(". . .")))
    }

    @Test
    fun emptyAndWhitespaceOnlyNamesFallBackToUrlThenDownload() {
        assertEquals("report.pdf", resolve(cd(""), url = "https://example.com/files/report.pdf"))
        assertEquals("report.pdf", resolve(cd("   "), url = "https://example.com/files/report.pdf"))
        assertEquals("download", resolve(cd("\u0000\u202E")))
    }

    @Test
    fun leadingDotsAndDotfilesLoseTheirDots() {
        assertEquals("bashrc", resolve(cd(".bashrc")))
        assertEquals("evil.sh", resolve(cd("...evil.sh")))
    }

    @Test
    fun trailingDotsAndSpacesAreTrimmed() {
        assertEquals("name.txt", resolve(cd("name.txt. . ")))
    }

    @Test
    fun windowsReservedDeviceNamesAreDefused() {
        assertEquals("_CON.txt", resolve(cd("CON.txt")))
        assertEquals("_nul", resolve(cd("nul")))
        assertEquals("_com1.bin", resolve(cd("com1.bin")))
        assertEquals("console.txt", resolve(cd("console.txt")))
    }

    // ---- length ----

    @Test
    fun longAsciiNameKeepsExtensionAndStaysWithin200Bytes() {
        val result = resolve(cd("a".repeat(500) + ".txt"))
        assertTrue(result.endsWith(".txt"))
        assertEquals(200, result.toByteArray(Charsets.UTF_8).size)
        assertTrue(result.startsWith("aaaa"))
    }

    @Test
    fun longMultiByteNameIsNotSplitMidCharacter() {
        val twoByte = resolve(cd("\u00e9".repeat(300) + ".pdf"))
        assertValidUtf8WithinLimit(twoByte)
        assertTrue(twoByte.endsWith(".pdf"))

        val threeByte = resolve(cd("\u65e5".repeat(300) + ".pdf"))
        assertValidUtf8WithinLimit(threeByte)
        assertTrue(threeByte.endsWith(".pdf"))

        val fourByte = resolve(cd("\uD83D\uDE00".repeat(100) + ".zip"))
        assertValidUtf8WithinLimit(fourByte)
        assertTrue(fourByte.endsWith(".zip"))
        // 196 bytes of base = exactly 49 whole emoji.
        assertEquals(49, fourByte.removeSuffix(".zip").codePointCount(0, fourByte.length - 4))
    }

    @Test
    fun hugeExtensionIsTruncatedWithTheName() {
        val result = resolve(cd("name." + "x".repeat(400)))
        assertValidUtf8WithinLimit(result)
    }

    private fun assertValidUtf8WithinLimit(name: String) {
        val bytes = name.toByteArray(Charsets.UTF_8)
        assertTrue("${bytes.size} bytes", bytes.size <= 200)
        assertEquals(name, String(bytes, Charsets.UTF_8))
        assertFalse(name.contains('\uFFFD'))
        assertTrue(name.isNotEmpty())
    }

    // ---- percent-decoding ----

    @Test
    fun malformedPercentEncodingDoesNotThrow() {
        assertEquals("100%zz.txt", resolve("attachment; filename*=UTF-8''100%zz.txt"))
        assertEquals("a%4", resolve("attachment; filename*=UTF-8''a%4"))
        assertEquals("a%.bin", resolve(url = "https://example.com/a%.bin"))
        assertEquals("a%zzb.bin", resolve(url = "https://example.com/a%zzb.bin"))
    }

    @Test
    fun invalidUtf8InPercentEncodingDoesNotThrow() {
        val result = resolve("attachment; filename*=UTF-8''%ff%fe.txt")
        assertTrue(result.endsWith(".txt"))
    }

    // ---- URL fallback ----

    @Test
    fun urlQueryAndFragmentAreIgnored() {
        assertEquals("file.zip", resolve(url = "https://example.com/dl/file.zip?token=abc&x=/y#frag"))
    }

    @Test
    fun urlPercentEncodedNameIsDecoded() {
        assertEquals("my file.zip", resolve(url = "https://example.com/dl/my%20file.zip"))
        assertEquals("caf\u00e9.txt", resolve(url = "https://example.com/caf%C3%A9.txt"))
    }

    @Test
    fun urlWithTrailingSlashOrNoPathGivesDownload() {
        assertEquals("download", resolve(url = "https://example.com/dir/"))
        assertEquals("download", resolve(url = "https://example.com"))
        assertEquals("download", resolve(url = "https://example.com/"))
    }

    @Test
    fun unparseableUrlStillResolves() {
        assertEquals("file.zip", resolve(url = "https://exa mple.com/some dir/file.zip"))
        assertEquals("download", resolve(url = "::::not a url"))
        assertEquals("download", resolve(url = ""))
        assertEquals("download", resolve(url = "blob:https://example.com/6f1c"))
    }

    // ---- extension from mime ----

    private val mimeMap: (String) -> String? = { if (it == "application/pdf") "pdf" else if (it == "text/html") ".html" else null }

    @Test
    fun mimeExtensionAddedOnlyWhenNameHasNone() {
        assertEquals("get.pdf", resolve(url = "https://example.com/get", type = "application/pdf; charset=binary", ext = mimeMap))
        assertEquals("download.html", resolve(url = "https://example.com/", type = "TEXT/HTML", ext = mimeMap))
        assertEquals("data.bin", resolve(cd("data.bin"), type = "application/pdf", ext = mimeMap))
        assertEquals("bashrc.pdf", resolve(cd(".bashrc"), type = "application/pdf", ext = mimeMap))
    }

    @Test
    fun mimeExtensionIsValidatedAndFailuresIgnored() {
        assertEquals("get", resolve(url = "https://example.com/get", type = "a/b", ext = { "../x" }))
        assertEquals("get", resolve(url = "https://example.com/get", type = "a/b", ext = { "" }))
        assertEquals("get", resolve(url = "https://example.com/get", type = "a/b", ext = { error("boom") }))
        assertEquals("get", resolve(url = "https://example.com/get", type = "not a mime", ext = { "x" }))
        assertEquals("get", resolve(url = "https://example.com/get", type = "application/octet-stream", ext = { "bin" }))
    }

    // ---- every output is safe ----

    @Test
    fun hostileInputsAlwaysYieldASafeName() {
        val hostile =
            listOf(
                "../../evil.sh", "..\\..\\evil.bat", "/etc/passwd", "C:\\Windows\\system32\\cmd.exe", "..", ".", "...",
                "a/../../b", "\u0000", "\u202E", "con", "NUL.txt", "  ", "x".repeat(1000), "\uD83D", "a\uDE00b",
                "%2e%2e%2f", "foo/", "foo\\", ".hidden/.also", "name\u2028.txt", "\uFEFFbom.txt",
            )
        for (h in hostile) {
            for (header in listOf(cd(h), "attachment; filename*=UTF-8''" + h)) {
                val r = resolve(header, url = "https://example.com/$h")
                assertTrue("empty for '$h'", r.isNotEmpty())
                assertFalse("separator in '$r'", r.contains('/') || r.contains('\\'))
                assertFalse("leading dot in '$r'", r.startsWith("."))
                assertFalse("trailing dot/space in '$r'", r.endsWith(".") || r.endsWith(" "))
                assertTrue("control char in '$r'", r.none { it.isISOControl() || it == '\u202E' || it == '\u2028' })
                assertValidUtf8WithinLimit(r)
            }
        }
    }

    // ---- sanitize / mime / host / counter ----

    @Test
    fun sanitizeReturnsEmptyWhenNothingUsable() {
        assertEquals("", DownloadFileNames.sanitize(""))
        assertEquals("", DownloadFileNames.sanitize("../"))
        assertEquals("", DownloadFileNames.sanitize(" . "))
    }

    @Test
    fun cleanMimeStripsParametersAndRejectsGarbage() {
        assertEquals("text/html", DownloadFileNames.cleanMime(" Text/HTML ; charset=utf-8"))
        assertEquals("application/octet-stream", DownloadFileNames.cleanMime(null))
        assertEquals("application/octet-stream", DownloadFileNames.cleanMime("nonsense"))
        assertEquals("application/octet-stream", DownloadFileNames.cleanMime("text/html\nX-Evil: 1"))
        assertEquals("application/vnd.api+json", DownloadFileNames.cleanMime("application/vnd.api+json"))
    }

    @Test
    fun sourceHostParsesAndNeverThrows() {
        assertEquals("example.com", DownloadFileNames.sourceHost("https://Example.COM:8080/a/b?q=1"))
        assertEquals("203.0.113.5", DownloadFileNames.sourceHost("http://203.0.113.5/x"))
        assertNull(DownloadFileNames.sourceHost("not a url"))
        assertNull(DownloadFileNames.sourceHost(""))
        assertNull(DownloadFileNames.sourceHost("blob:https://example.com/6f1c"))
        assertNull(DownloadFileNames.sourceHost("data:text/plain,hello"))
        assertNull(DownloadFileNames.sourceHost("file:///sdcard/x"))
        assertNull(DownloadFileNames.sourceHost("https://exa mple.com/"))
    }

    @Test
    fun withCounterInsertsBeforeExtension() {
        assertEquals("a (1).txt", DownloadFileNames.withCounter("a.txt", 1))
        assertEquals("a.tar (2).gz", DownloadFileNames.withCounter("a.tar.gz", 2))
        assertEquals("a (3)", DownloadFileNames.withCounter("a", 3))
    }

    // ---- canonical path check ----

    @Test
    fun isInsideAcceptsChildrenOnly() {
        val dir = tmp.newFolder("downloads")
        assertTrue(DownloadFileNames.isInside(dir, File(dir, "a.txt")))
        assertTrue(DownloadFileNames.isInside(dir, File(dir, "sub/../a.txt")))
        assertFalse("dir itself", DownloadFileNames.isInside(dir, dir))
        assertFalse("dir with dot", DownloadFileNames.isInside(dir, File(dir, ".")))
    }

    @Test
    fun isInsideRejectsTraversalAndSiblingPrefix() {
        val dir = tmp.newFolder("downloads")
        val sibling = tmp.newFolder("downloads-evil")
        assertFalse(DownloadFileNames.isInside(dir, File(dir, "../x.txt")))
        assertFalse(DownloadFileNames.isInside(dir, File(dir, "../../etc/passwd")))
        assertFalse("sibling sharing a name prefix", DownloadFileNames.isInside(dir, File(sibling, "x.txt")))
        assertFalse(DownloadFileNames.isInside(dir, File(dir.parentFile, "x.txt")))
    }
}
