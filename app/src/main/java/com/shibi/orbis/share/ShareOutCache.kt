package com.shibi.orbis.share

import com.shibi.orbis.downloads.DownloadFileNames
import com.shibi.orbis.engine.IncomingDownload
import com.shibi.orbis.engine.importDisplayName
import com.shibi.orbis.engine.importFileAllowed
import com.shibi.orbis.engine.sanitizeImportFileName
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Directory under the app cache that holds copies handed to other apps through the FileProvider. */
const val SHARE_OUT_DIR = "shared_out"

/** Largest file shared (same as the import cap). */
const val SHARE_MAX_BYTES = 64L * 1024 * 1024

/** Normal-tab shares older than this are deleted at startup (same as imports). */
const val SHARE_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000

private const val NORMAL_DIR = "n"
private const val PRIVATE_DIR = "p"
private const val CHUNK = 64 * 1024

sealed class ShareOutResult {
    data class Ready(val file: File, val mime: String) : ShareOutResult()

    data object TooLarge : ShareOutResult()

    data object Failed : ShareOutResult()
}

/**
 * Copies of files the user shares out (an imported document, the PDF a tab shows). One file per random directory:
 * `shared_out/n/<uuid>/<name>` for normal tabs, `shared_out/p/<uuid>/<name>` for private ones. The import directory is
 * never shared directly: an import is deleted when its tab closes, and a receiving app may read the file later.
 *
 * Only `n/` and `p/` are FileProvider roots (res/xml/download_paths.xml). [clearPrivate] runs when private browsing
 * ends and at startup; [deleteOlderThan] trims `n/` at startup; [clearAll] runs with Clear browsing data.
 */
class ShareOutCache(
    cacheDir: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = SHARE_MAX_BYTES,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val root: File = File(cacheDir, SHARE_OUT_DIR)
    private val normalDir = File(root, NORMAL_DIR)
    private val privateDir = File(root, PRIVATE_DIR)
    private val lock = Any()

    /** Bumped by [clearAll] and [clearPrivate]. A copy that started before a clear does not publish. */
    private var epoch = 0

    /** Copies an imported document (`imports/<id>/<name>`, PDF or HTML) into `n/`. Imports are never private. */
    suspend fun copyImported(importRoot: File, url: String): ShareOutResult = withContext(dispatcher) {
        if (!importFileAllowed(importRoot, url)) return@withContext ShareOutResult.Failed
        val source = try {
            File(URI(url).path ?: return@withContext ShareOutResult.Failed).canonicalFile
        } catch (e: Exception) {
            return@withContext ShareOutResult.Failed
        }
        val ext = source.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mime = when (ext) {
            "pdf" -> "application/pdf"
            "html" -> "text/html"
            else -> return@withContext ShareOutResult.Failed
        }
        if (source.length() > maxBytes) return@withContext ShareOutResult.TooLarge
        val name = sanitizeImportFileName(importDisplayName(url), ext)
        write(normalDir, name, mime) { FileInputStream(source) }
    }

    /**
     * Copies the PDF [download] into `p/` when [isPrivate], else `n/`. The body is always ended (read or discarded).
     * The name comes from untrusted headers and is made safe; the MIME is always `application/pdf`.
     */
    suspend fun copyPdf(download: IncomingDownload, isPrivate: Boolean): ShareOutResult {
        var opened = false
        try {
            if (download.contentLength > maxBytes) return ShareOutResult.TooLarge
            val resolved = DownloadFileNames.resolve(download.contentDisposition, download.url, "application/pdf") { null }
            val name = sanitizeImportFileName(resolved, "pdf")
            return withContext(dispatcher) {
                write(if (isPrivate) privateDir else normalDir, name, "application/pdf") {
                    opened = true
                    download.openBody()
                }
            }
        } finally {
            if (!opened) download.discard()
        }
    }

    /** Deletes normal-tab shares last written before [SHARE_MAX_AGE_MILLIS] ago. Returns how many were removed. */
    fun deleteOlderThan(nowMillis: Long = now()): Int = synchronized(lock) {
        if (!normalDir.isDirectory) return@synchronized 0
        val cutoff = nowMillis - SHARE_MAX_AGE_MILLIS
        var removed = 0
        for (child in normalDir.listFiles().orEmpty()) {
            if (newestMtime(child) < cutoff && deleteTree(child)) removed++
        }
        removed
    }

    fun clearPrivate() {
        synchronized(lock) {
            epoch++
            deleteTree(privateDir)
        }
    }

    fun clearAll() {
        synchronized(lock) {
            epoch++
            deleteTree(root)
        }
    }

    private suspend fun write(base: File, name: String, mime: String, open: () -> InputStream?): ShareOutResult {
        val ticket = synchronized(lock) { epoch }
        if (!base.isDirectory && !base.mkdirs() && !base.isDirectory) return ShareOutResult.Failed
        val dir = File(base, UUID.randomUUID().toString())
        if (!dir.mkdir()) return ShareOutResult.Failed
        val part = File(dir, "$name.part")
        val target = File(dir, name)
        return try {
            val input = open() ?: return discard(dir, ShareOutResult.Failed)
            val within = input.use { copyCapped(it, part) }
            if (!within) return discard(dir, ShareOutResult.TooLarge)
            synchronized(lock) {
                if (ticket != epoch || !part.renameTo(target) || !isPlaced(base, dir, target)) {
                    discard(dir, ShareOutResult.Failed)
                } else {
                    ShareOutResult.Ready(target, mime)
                }
            }
        } catch (e: CancellationException) {
            deleteTree(dir)
            throw e
        } catch (e: IOException) {
            discard(dir, ShareOutResult.Failed)
        } catch (e: RuntimeException) {
            discard(dir, ShareOutResult.Failed)
        }
    }

    /** False when more than [maxBytes] arrive. Checks for cancellation between chunks. */
    private suspend fun copyCapped(input: InputStream, part: File): Boolean {
        val buf = ByteArray(CHUNK)
        var total = 0L
        FileOutputStream(part).use { out ->
            while (true) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) return false
                out.write(buf, 0, n)
            }
        }
        return true
    }

    /** The written file is exactly `<base>/<dir>/<name>` once symlinks and `..` are resolved. */
    private fun isPlaced(base: File, dir: File, target: File): Boolean =
        try {
            val file = target.canonicalFile
            file.isFile && file.parentFile == dir.canonicalFile && file.parentFile?.parentFile == base.canonicalFile
        } catch (e: IOException) {
            false
        }

    private fun discard(dir: File, result: ShareOutResult): ShareOutResult {
        deleteTree(dir)
        return result
    }
}

private fun newestMtime(file: File): Long {
    if (Files.isSymbolicLink(file.toPath()) || !file.isDirectory) return file.lastModified()
    val children = file.listFiles().orEmpty()
    if (children.isEmpty()) return file.lastModified()
    return children.maxOf { newestMtime(it) }
}

/** Deletes [file]. A symlink is removed without following it. */
private fun deleteTree(file: File): Boolean {
    if (Files.isSymbolicLink(file.toPath())) return file.delete()
    if (file.isDirectory) {
        for (child in file.listFiles().orEmpty()) deleteTree(child)
    }
    return file.delete() || !file.exists()
}
