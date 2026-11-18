package com.shibi.orbis.downloads

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The kind of a downloaded file as shown in the Downloads list, in English (for example "PDF document"). */
@JvmInline
value class FileType(val label: String)

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico", "tif", "tiff")
private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "wmv", "flv", "mpg", "mpeg")
private val AUDIO_EXT = setOf("mp3", "wav", "ogg", "oga", "flac", "m4a", "aac", "opus", "wma", "mid", "midi")
private val ARCHIVE_EXT = setOf("zip", "7z", "rar", "gz", "tar")
private val TEXT_EXT = setOf("txt", "md", "log")
private val WEB_EXT = setOf("html", "htm", "xhtml")
private val WORD_EXT = setOf("doc", "docx", "odt", "rtf")
private val SHEET_EXT = setOf("xls", "xlsx", "ods")
private val SLIDES_EXT = setOf("ppt", "pptx", "odp")
private val EBOOK_EXT = setOf("epub", "mobi")
private const val MAX_EXT_LENGTH = 6

private val ARCHIVE_MIME =
    setOf(
        "application/zip",
        "application/x-zip-compressed",
        "application/x-7z-compressed",
        "application/vnd.rar",
        "application/x-rar-compressed",
        "application/gzip",
        "application/x-gzip",
        "application/x-tar",
    )

/** By MIME type first, then by the file name's extension. */
fun fileTypeLabel(mimeType: String, fileName: String): FileType {
    val mime = mimeType.substringBefore(';').trim().lowercase()
    val ext = fileName.substringAfterLast('.', "").lowercase().takeIf { fileName.lastIndexOf('.') > 0 }.orEmpty()
    val byMime =
        when {
            mime == "application/pdf" -> "PDF document"
            mime.startsWith("image/") -> "Image"
            mime.startsWith("video/") -> "Video"
            mime.startsWith("audio/") -> "Audio"
            mime in ARCHIVE_MIME -> "ZIP archive"
            mime == "application/vnd.android.package-archive" -> "Android app (APK)"
            mime == "text/html" || mime == "application/xhtml+xml" -> "Web page"
            mime.startsWith("text/") -> "Text file"
            mime == "application/msword" || mime.startsWith("application/vnd.openxmlformats-officedocument.wordprocessingml") ||
                mime == "application/vnd.oasis.opendocument.text" -> "Word document"
            mime == "application/vnd.ms-excel" || mime.startsWith("application/vnd.openxmlformats-officedocument.spreadsheetml") ||
                mime == "application/vnd.oasis.opendocument.spreadsheet" -> "Spreadsheet"
            mime == "application/vnd.ms-powerpoint" || mime.startsWith("application/vnd.openxmlformats-officedocument.presentationml") ||
                mime == "application/vnd.oasis.opendocument.presentation" -> "Presentation"
            mime == "application/epub+zip" || mime == "application/x-mobipocket-ebook" -> "E-book"
            mime == "application/x-bittorrent" -> "Torrent file"
            else -> null
        }
    if (byMime != null) return FileType(byMime)
    val byExt =
        when (ext) {
            "pdf" -> "PDF document"
            in IMAGE_EXT -> "Image"
            in VIDEO_EXT -> "Video"
            in AUDIO_EXT -> "Audio"
            in ARCHIVE_EXT -> "ZIP archive"
            "apk" -> "Android app (APK)"
            in TEXT_EXT -> "Text file"
            in WEB_EXT -> "Web page"
            in WORD_EXT -> "Word document"
            in SHEET_EXT -> "Spreadsheet"
            in SLIDES_EXT -> "Presentation"
            in EBOOK_EXT -> "E-book"
            "torrent" -> "Torrent file"
            else -> null
        }
    if (byExt != null) return FileType(byExt)
    val plain = ext.isNotEmpty() && ext.length <= MAX_EXT_LENGTH && ext.all { it in 'a'..'z' || it in '0'..'9' }
    return FileType(if (plain) ext.uppercase(Locale.ENGLISH) + " file" else "File")
}

/** When the file was downloaded: the finish time, else the start time (older downloads), else unknown. */
fun downloadedAt(entry: DownloadEntry): Long? = entry.finishedAt ?: entry.startedAt.takeIf { it > 0L }

/** English date and time, for example "17 Nov 2026, 6:12 PM" or, with [is24Hour], "17 Nov 2026, 18:12". */
fun formatDownloadTime(epochMs: Long, is24Hour: Boolean, zone: TimeZone = TimeZone.getDefault()): String {
    val format = SimpleDateFormat(if (is24Hour) "d MMM yyyy, HH:mm" else "d MMM yyyy, h:mm a", Locale.ENGLISH)
    format.timeZone = zone
    return format.format(Date(epochMs))
}