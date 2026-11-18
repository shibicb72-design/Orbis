package com.shibi.orbis.downloads

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadDetailsTest {
    private val octet = DownloadFileNames.DEFAULT_MIME

    private fun label(mime: String, name: String) = fileTypeLabel(mime, name).label

    private fun entry(started: Long, finished: Long?) =
        DownloadEntry(1, "a.pdf", octet, null, 1, 1, DownloadStatus.COMPLETED, "/a.pdf", started, finishedAt = finished)

    @Test
    fun pdfByMimeAndByExtension() {
        assertEquals("PDF document", label("application/pdf", "report"))
        assertEquals("PDF document", label(octet, "x.PDF"))
    }

    @Test
    fun mimeTypeWinsOverExtension() {
        assertEquals("Image", label("image/webp", "photo.bin"))
        assertEquals("Video", label("video/mp4", "clip"))
        assertEquals("Audio", label("audio/mpeg; charset=x", "song"))
        assertEquals("Web page", label("text/html", "index.txt"))
        assertEquals("Text file", label("text/plain", "notes"))
    }

    @Test
    fun archivesAndApps() {
        assertEquals("ZIP archive", label(octet, "a.tar.gz"))
        assertEquals("ZIP archive", label(octet, "a.7z"))
        assertEquals("Android app (APK)", label(octet, "app.apk"))
        assertEquals("Android app (APK)", label("application/vnd.android.package-archive", "app"))
    }

    @Test
    fun officeBooksAndTorrents() {
        assertEquals("Word document", label(octet, "a.docx"))
        assertEquals("Spreadsheet", label(octet, "a.xlsx"))
        assertEquals("Presentation", label(octet, "a.pptx"))
        assertEquals("E-book", label(octet, "a.epub"))
        assertEquals("Torrent file", label(octet, "a.torrent"))
    }

    @Test
    fun otherExtensionsAreShownInCapitals() {
        assertEquals("ISO file", label(octet, "a.iso"))
        assertEquals("File", label(octet, "README"))
        assertEquals("File", label(octet, "name.verylongext"))
        assertEquals("File", label(octet, ".hidden"))
    }

    @Test
    fun downloadedAtPrefersFinishThenStartThenNothing() {
        assertEquals(30L, downloadedAt(entry(started = 10, finished = 30)))
        assertEquals(10L, downloadedAt(entry(started = 10, finished = null)))
        assertNull(downloadedAt(entry(started = 0, finished = null)))
    }

    @Test
    fun timeFollowsThePhones12Or24HourSetting() {
        val utc = TimeZone.getTimeZone("UTC")
        val t = 1_794_939_120_000L // 2026-11-17 18:12 UTC
        assertEquals("17 Nov 2026, 6:12 PM", formatDownloadTime(t, is24Hour = false, zone = utc))
        assertEquals("17 Nov 2026, 18:12", formatDownloadTime(t, is24Hour = true, zone = utc))
    }
}