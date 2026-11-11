package com.shibi.orbis.downloads

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.File

/**
 * Puts a copy of a finished download in the phone's public Download folder (Files app, other apps), through
 * MediaStore: no storage permission is needed. Blocking IO: call off the main thread. Returns false on any failure;
 * the file in Orbis's own folder is untouched either way. [subPath] (already sanitised, '/'-separated, e.g.
 * "Orbis/Sintel") puts it in a folder below Download.
 */
internal fun copyToPublicDownloads(context: Context, file: File, displayName: String, mimeType: String, subPath: String? = null): Boolean {
    if (!file.isFile) return false
    val resolver = context.contentResolver
    val values =
        ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
            put(MediaStore.Downloads.RELATIVE_PATH, publicRelativePath(subPath))
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return false
    return try {
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw IllegalStateException("no stream")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        true
    } catch (e: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        false
    }
}

/** "Download" or "Download/<subPath>": MediaStore's RELATIVE_PATH for [copyToPublicDownloads]. */
internal fun publicRelativePath(subPath: String?): String =
    if (subPath.isNullOrEmpty()) DOWNLOAD_DIR else DOWNLOAD_DIR + "/" + subPath.trim('/')

/** Environment.DIRECTORY_DOWNLOADS, spelled out: the android.jar stub leaves it null in JVM tests. */
private const val DOWNLOAD_DIR = "Download"

/**
 * Which entries just finished and should be copied: newly COMPLETED (not already [handled]), not private. Pure, for
 * the app's watcher over the downloads list.
 */
internal fun newlyCompleted(entries: List<DownloadEntry>, handled: Set<Long>): List<DownloadEntry> =
    entries.filter { it.status == DownloadStatus.COMPLETED && !it.isPrivate && it.id !in handled && it.filePath != null }
