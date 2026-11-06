package com.shibi.orbis.downloads

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

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
