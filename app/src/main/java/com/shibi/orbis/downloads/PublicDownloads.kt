package com.shibi.orbis.downloads

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Puts a copy of a finished download in the phone's public Download folder (Files app, other apps), through
 * MediaStore: no storage permission is needed. Blocking IO: call off the main thread. Returns false on any failure;
 * the file in Orbis's own folder is untouched either way.
 */
internal fun copyToPublicDownloads(context: Context, file: File, displayName: String, mimeType: String): Boolean {
    if (!file.isFile) return false
    val resolver = context.contentResolver
    val values =
        ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
    val collection =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else return false
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

/**
 * Which entries just finished and should be copied: newly COMPLETED (not already [handled]), not private. Pure, for
 * the app's watcher over the downloads list.
 */
internal fun newlyCompleted(entries: List<DownloadEntry>, handled: Set<Long>): List<DownloadEntry> =
    entries.filter { it.status == DownloadStatus.COMPLETED && !it.isPrivate && it.id !in handled && it.filePath != null }
