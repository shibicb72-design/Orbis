package com.shibi.orbis.downloads

import android.content.ContentUris
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.shibi.orbis.torrent.TorrentPublisher
import java.io.File

/**
 * Puts a copy of a finished download in the phone's public Download folder (Files app, other apps), through
 * MediaStore: no storage permission is needed. Blocking IO: call off the main thread. Returns the copy's content URI,
 * or null on any failure (its own half-written row is deleted again); the file in Orbis's own folder is untouched
 * either way. [subPath] (already sanitised, '/'-separated, e.g. "Orbis/Sintel") puts it in a folder below Download.
 */
internal fun copyToPublicDownloads(context: Context, file: File, displayName: String, mimeType: String, subPath: String? = null): Uri? {
    if (!file.isFile) return null
    val resolver = context.contentResolver
    val values =
        ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
            put(MediaStore.Downloads.RELATIVE_PATH, publicRelativePath(subPath))
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
    val uri = runCatching { resolver.insert(downloadsCollection(), values) }.getOrNull() ?: return null
    return try {
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw IllegalStateException("no stream")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        uri
    } catch (e: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        null
    }
}

/**
 * One file of a finished torrent into Download/<subPath>. A finished copy this app already made of the same file
 * (same folder, name and size: a publish cut short by a killed process) is reused instead of copied again, so a
 * restart never leaves "name (1)" duplicates; such a copy is marked reused, so a rollback leaves it alone. A
 * half-written (IS_PENDING) copy of ours with that folder and name is deleted first. Blocking IO.
 */
internal fun publishTorrentFile(context: Context, file: File, displayName: String, mimeType: String, subPath: String): TorrentPublisher.Published? {
    val resolver = context.contentResolver
    val collection = downloadsCollection()
    val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
    val args = arrayOf(publicRelativePath(subPath) + "/", displayName, context.packageName)
    val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING)
    val rows =
        runCatching {
            val cursor =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val query =
                        Bundle().apply {
                            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                        }
                    resolver.query(collection, projection, query, null)
                } else {
                    @Suppress("DEPRECATION")
                    resolver.query(MediaStore.setIncludePending(collection), projection, selection, args, null)
                }
            cursor?.use { c ->
                val out = ArrayList<PublishRow>()
                while (c.moveToNext()) out += PublishRow(c.getLong(0), c.getLong(1), pending = c.getInt(2) != 0)
                out
            }
        }.getOrNull().orEmpty()
    val decision = publishReuse(rows, file.length())
    for (id in decision.deletePending) runCatching { resolver.delete(ContentUris.withAppendedId(collection, id), null, null) }
    decision.reuse?.let { return TorrentPublisher.Published(ContentUris.withAppendedId(collection, it).toString(), reused = true) }
    return copyToPublicDownloads(context, file, displayName, mimeType, subPath)?.let { TorrentPublisher.Published(it.toString(), reused = false) }
}

/** One of this app's MediaStore rows with the folder and name a torrent file is about to be published under. */
internal class PublishRow(val id: Long, val size: Long, val pending: Boolean)

/** [reuse]: a finished row to take as the copy (null: copy again). [deletePending]: half-written rows to delete first. */
internal class PublishReuse(val reuse: Long?, val deletePending: List<Long>)

/** Pure. A finished row of exactly [fileLength] bytes is reused; every pending row is a leftover and goes. */
internal fun publishReuse(rows: List<PublishRow>, fileLength: Long): PublishReuse =
    PublishReuse(
        reuse = rows.firstOrNull { !it.pending && it.size == fileLength }?.id,
        deletePending = rows.filter { it.pending }.map { it.id },
    )

/** Deletes a copy this app made (app-owned rows need no permission). Blocking IO; false when it could not. */
internal fun deletePublished(context: Context, uri: Uri): Boolean = runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)

/** MediaStore selection for every copy this app made below [relativeDir] (e.g. "Download/Orbis/Sintel"). */
internal class PublishedSelection(val selection: String, val args: Array<String>) {
    override fun toString(): String = "PublishedSelection(args=${args.size})"
}

/**
 * Pure. The folder is a bound argument (never concatenated into the SQL), with LIKE's wildcards escaped so a name
 * holding '%' or '_' matches only itself.
 */
internal fun publishedSelection(relativeDir: String, packageName: String): PublishedSelection {
    val dir = relativeDir.trim('/')
    val escaped =
        buildString {
            for (ch in dir) {
                if (ch == '\\' || ch == '%' || ch == '_') append('\\')
                append(ch)
            }
        }
    return PublishedSelection(
        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\' AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
        arrayOf("$escaped/%", packageName),
    )
}

internal fun downloadsCollection(): Uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

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
