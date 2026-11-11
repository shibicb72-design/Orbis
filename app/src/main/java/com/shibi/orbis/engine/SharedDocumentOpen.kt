package com.shibi.orbis.engine

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * `EXTRA_STREAM` as a [Uri], or the first clip item's URI when the extra is absent.
 *
 * A foreign Parcelable (the wrong class, or one this process cannot unmarshall) used to crash the untyped
 * getter on API 32 and below, and the API 33 typed getter is itself buggy. [IntentCompat.getParcelableExtra]
 * avoids that getter, and both reads sit in [runCatching] so a bad parcel becomes "no document" instead of
 * taking the process down. The clip is read the same way: a hostile extra can throw out of `clipData` too.
 */
internal fun streamUriFrom(intent: Intent): Uri? {
    val extra = runCatching {
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    }.getOrNull()
    if (extra != null) return extra
    val clip = runCatching { intent.clipData }.getOrNull() ?: return null
    return runCatching {
        if (clip.itemCount < 1) null else clip.getItemAt(0).uri
    }.getOrNull()
}

/**
 * Display name and size from [OpenableColumns] for a `content:` URI. A failed query is not fatal: the copy
 * then uses a generic name and the byte cap.
 */
internal fun openableColumns(resolver: ContentResolver, uri: Uri): Pair<String?, Long?> {
    if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) return null to null
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

/** Opens a `content:` document with the temporary read grant. Anything else, including `file:`, is refused. */
internal fun openSharedDocument(resolver: ContentResolver, uri: Uri): InputStream? =
    try {
        if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) {
            null
        } else {
            resolver.openInputStream(uri)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

/**
 * Copies [uri] into [cache] unless its provider is this app. [ownerOfAuthority] is
 * `PackageManager.resolveContentProvider(authority, 0)?.packageName`.
 */
internal suspend fun importSharedDocument(
    cache: ImportedFileCache,
    resolver: ContentResolver,
    uri: Uri,
    mime: String,
    ourPackage: String,
    ownerOfAuthority: (String) -> String?,
): ImportResult {
    if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) return ImportResult.Failed
    // uri.authority is the parser ContentResolver uses; the check refuses when its own reading disagrees.
    val ours = contentProviderIsThisApp(uri.toString(), uri.authority, ourPackage, setOf("$ourPackage.downloads"), ownerOfAuthority)
    if (ours) return ImportResult.Failed
    // The name query and the stream share one idle timeout, so a stuck provider fails the import
    // instead of holding the copy open until the process gives up. Both run on a provider thread that counts against
    // ProviderDeadline.MAX_THREADS, and no dispatcher thread waits on them.
    return cache.readShared(mime, query = { openableColumns(resolver, uri) }, open = { openSharedDocument(resolver, uri) })
}
