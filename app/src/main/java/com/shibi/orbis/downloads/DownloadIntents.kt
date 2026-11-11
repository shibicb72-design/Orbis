package com.shibi.orbis.downloads

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.shibi.orbis.torrent.TorrentEntry
import com.shibi.orbis.torrent.TorrentStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android-only half of downloads: hands a finished file to other apps through [FileProvider] (never a file:// URI).
 * The provider only exposes the two download directories (res/xml/download_paths.xml).
 */
object DownloadIntents {
    enum class Result { OK, MISSING, NO_APP, FAILED }

    fun authority(context: Context): String = "${context.packageName}.downloads"

    fun open(context: Context, entry: DownloadEntry, chooserTitle: String): Result =
        launch(context, entry) { uri, mime ->
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.let { Intent.createChooser(it, chooserTitle) }
        }

    fun share(context: Context, entry: DownloadEntry, chooserTitle: String): Result =
        launch(context, entry) { uri, mime ->
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.let { Intent.createChooser(it, chooserTitle) }
        }

    /**
     * Opens a finished torrent's copy in Download/Orbis/<name>: the copies this app made there are looked up in
     * MediaStore (on IO). One file opens with a chooser through its content URI (never a file:// URI); several open the
     * system Downloads app and [onSeveral] runs (the screen says where they are); none is MISSING.
     * Logs "publish open <Result>" only.
     */
    suspend fun openPublished(
        context: Context,
        entry: TorrentEntry,
        chooserTitle: String,
        log: (String) -> Unit = {},
        onSeveral: () -> Unit = {},
    ): Result {
        val result = openPublishedInner(context, entry, chooserTitle, onSeveral)
        log("publish open ${result.name}")
        return result
    }

    private suspend fun openPublishedInner(context: Context, entry: TorrentEntry, chooserTitle: String, onSeveral: () -> Unit): Result {
        val dir = entry.publishedPath
        if (entry.status != TorrentStatus.COMPLETED || dir == null) return Result.MISSING
        val collection = downloadsCollection()
        val rows =
            withContext(Dispatchers.IO) {
                runCatching {
                    val sel = publishedSelection(dir, context.packageName)
                    context.contentResolver.query(
                        collection,
                        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE),
                        sel.selection,
                        sel.args,
                        null,
                    )?.use { c ->
                        // Two are enough to know it is a folder.
                        val out = ArrayList<Pair<Uri, String?>>(2)
                        while (out.size < 2 && c.moveToNext()) out += ContentUris.withAppendedId(collection, c.getLong(0)) to c.getString(1)
                        out
                    }
                }.getOrNull()
            } ?: return Result.FAILED
        val intent =
            when (rows.size) {
                0 -> return Result.MISSING
                1 -> {
                    val (uri, mime) = rows[0]
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, DownloadFileNames.cleanMime(mime ?: ""))
                        clipData = ClipData.newRawUri("", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }.let { Intent.createChooser(it, chooserTitle) }
                }
                else -> Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
            }
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            if (rows.size > 1) onSeveral()
            Result.OK
        } catch (e: ActivityNotFoundException) {
            Result.NO_APP
        } catch (e: SecurityException) {
            Result.FAILED
        }
    }

    private fun launch(
        context: Context,
        entry: DownloadEntry,
        build: (android.net.Uri, String) -> Intent,
    ): Result {
        val path = entry.filePath
        if (entry.status != DownloadStatus.COMPLETED || path == null) return Result.MISSING
        val file = File(path)
        // The file may have been removed outside the app since the download finished.
        if (!file.isFile) return Result.MISSING
        return try {
            val uri = FileProvider.getUriForFile(context, authority(context), file)
            val intent = build(uri, DownloadFileNames.cleanMime(entry.mimeType))
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Result.OK
        } catch (e: ActivityNotFoundException) {
            Result.NO_APP
        } catch (e: IllegalArgumentException) {
            // File is outside the provider's configured roots.
            Result.FAILED
        } catch (e: SecurityException) {
            Result.FAILED
        }
    }
}
