package com.shibi.orbis.share

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Parcelable
import androidx.core.content.FileProvider
import com.shibi.orbis.MainActivity
import com.shibi.orbis.downloads.DownloadIntents
import java.io.File

/**
 * Hands one file from [ShareOutCache] to another app: a FileProvider content URI with a read-only grant, never a
 * file:// URI. The provider only reaches `shared_out/n/` and `shared_out/p/` among the cache directories.
 */
object ShareIntents {
    /** Chooser for one file from the share-out cache. Null when the file is outside the provider roots. */
    fun fileChooser(context: Context, file: File, mime: String, title: String): Intent? {
        val uri =
            try {
                FileProvider.getUriForFile(context, DownloadIntents.authority(context), file)
            } catch (e: IllegalArgumentException) {
                return null
            }
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        // Orbis accepts SEND of PDF and HTML but refuses its own provider, so it is not offered as a target.
        val self: Array<Parcelable> = arrayOf(ComponentName(context, MainActivity::class.java))
        return Intent.createChooser(send, title).putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, self)
    }

    fun start(context: Context, chooser: Intent): Boolean {
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(chooser)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
