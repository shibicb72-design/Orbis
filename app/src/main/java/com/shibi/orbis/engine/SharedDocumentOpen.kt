package com.shibi.orbis.engine

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * Display name and size from [OpenableColumns] for a `content:` URI. A `file:` URI uses its last path segment
 * and an unknown size. A failed query is not fatal: the copy then uses a generic name and the byte cap.
 */
internal fun openableColumns(resolver: ContentResolver, uri: Uri): Pair<String?, Long?> {
    if (!uri.scheme.equals("content", ignoreCase = true)) {
        return uri.lastPathSegment to null
    }
    return try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null to null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val size =
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex).takeIf { it >= 0 } else null
            name to size
        } ?: (null to null)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null to null
    }
}

/**
 * Opens a document another app handed us. `content:` uses the temporary read grant. `file:` is copied only when
 * [readableSharedFile] accepts it, so the path is never loaded in place and our private directories are refused.
 */
internal fun openSharedDocument(resolver: ContentResolver, uri: Uri, forbiddenRoots: List<File>): InputStream? =
    try {
        when (uri.scheme?.lowercase(Locale.ROOT)) {
            ContentResolver.SCHEME_CONTENT -> resolver.openInputStream(uri)
            ContentResolver.SCHEME_FILE -> {
                val file = readableSharedFile(uri.toString(), forbiddenRoots) ?: return null
                FileInputStream(file)
            }
            else -> null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

/** Reads the display name, then copies the bytes into [cache]. */
internal suspend fun importSharedDocument(
    cache: ImportedFileCache,
    resolver: ContentResolver,
    uri: Uri,
    mime: String,
    forbiddenRoots: List<File>,
): ImportResult {
    val (name, size) = openableColumns(resolver, uri)
    return cache.copyIn(name, mime, size) { openSharedDocument(resolver, uri, forbiddenRoots) }
}
