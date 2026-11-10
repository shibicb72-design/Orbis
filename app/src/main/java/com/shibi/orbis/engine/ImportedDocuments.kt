package com.shibi.orbis.engine

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Directory under the app cache that holds documents opened from other apps (WhatsApp and the like).
 * One file per random child directory, so a local HTML page's file origin cannot name a sibling.
 */
const val IMPORT_CACHE_DIR = "imports"

/** Largest document copied into [IMPORT_CACHE_DIR]. Larger streams are refused before a tab is opened. */
const val IMPORT_MAX_BYTES = 64L * 1024 * 1024

/** Imported files older than this are deleted at startup. A tab that is still open keeps a younger file. */
const val IMPORT_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000

private const val MAX_URI_LENGTH = 8_192
private const val MAX_NAME_BYTES = 120
private const val COPY_BUFFER_BYTES = 64 * 1024
private const val FALLBACK_NAME = "document"

private val MIME_EXTENSIONS =
    mapOf(
        "application/pdf" to "pdf",
        "text/html" to "html",
        "application/xhtml+xml" to "xhtml",
    )

private val IMPORT_EXTENSIONS = MIME_EXTENSIONS.values.toSet()

private const val ACTION_VIEW = "android.intent.action.VIEW"
private const val ACTION_SEND = "android.intent.action.SEND"

/** What another app asked us to open, after the MIME and scheme checks. [mime] has no parameters. */
data class SharedDocumentRequest(val uri: String, val mime: String)

sealed class ImportResult {
    /** [url] is a `file:` URL of the private copy. Safe to pass to [importFileAllowed]. */
    data class Ready(val url: String) : ImportResult()

    data object TooLarge : ImportResult()
    data object Failed : ImportResult()
}

/**
 * MIME type we can open, without parameters, or null. `text/plain` is intentionally not accepted.
 * Matching is on the type alone (`text/html; charset=utf-8` is html).
 */
fun importedMime(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val base = raw.substringBefore(';').trim().lowercase(Locale.ROOT)
    return base.takeIf { it in MIME_EXTENSIONS }
}

/** File extension (no dot) for [mime], or null when [importedMime] rejects it. */
fun extensionForImportedMime(mime: String?): String? = importedMime(mime)?.let { MIME_EXTENSIONS[it] }

/**
 * A VIEW or SEND intent's document, or null when it is not a PDF/HTML/XHTML `content:` or `file:` URI.
 * [streamUri] is `EXTRA_STREAM` (SEND). SEND falls back to [dataString] when the extra is absent.
 * Web links are never accepted here; they stay on [externalUrlFrom].
 */
fun sharedDocumentRequest(action: String?, dataString: String?, mimeType: String?, streamUri: String?): SharedDocumentRequest? {
    val mime = importedMime(mimeType) ?: return null
    val raw =
        when (action) {
            ACTION_VIEW -> dataString
            ACTION_SEND -> streamUri?.takeIf { it.isNotEmpty() } ?: dataString
            else -> null
        } ?: return null
    if (raw.length > MAX_URI_LENGTH) return null
    if (raw.any { it.isWhitespace() || it.isISOControl() }) return null
    if (!acceptableDocumentUri(raw)) return null
    return SharedDocumentRequest(raw, mime)
}

/** `content:` URI, or a `file:` URI with no host (or localhost). A host on `file:` is a UNC-style path and is refused. */
private fun acceptableDocumentUri(raw: String): Boolean {
    val scheme = schemeOf(raw) ?: return false
    if (scheme == "content") return true
    if (scheme != "file") return false
    val uri = try {
        URI(raw)
    } catch (e: Exception) {
        return false
    }
    val host = uri.host
    return host.isNullOrEmpty() || host.equals("localhost", ignoreCase = true)
}

/**
 * A file name that is safe to create under the import cache and whose final extension matches [extension]
 * (`pdf`, `html`, or `xhtml`). The extension is what GeckoView uses to choose pdf.js versus a web page,
 * so it is forced even when the display name ends in something else.
 * Provider names are untrusted: only the last path segment is kept, control and bidi characters are dropped,
 * and the UTF-8 length is capped while the extension stays intact.
 */
fun sanitizeImportFileName(raw: String?, extension: String): String {
    val ext = extension.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() && it.code < 0x80 }
    val suffix = if (ext.isEmpty()) "" else ".$ext"
    val segment = (raw ?: "").substringAfterLast('/').substringAfterLast('\\')
    val cleaned = StringBuilder()
    var i = 0
    while (i < segment.length) {
        val cp = segment.codePointAt(i)
        i += Character.charCount(cp)
        if (cp == '/'.code || cp == '\\'.code) continue
        val type = Character.getType(cp)
        val bad =
            type == Character.CONTROL.toInt() ||
                type == Character.FORMAT.toInt() ||
                type == Character.SURROGATE.toInt()
        if (!bad) cleaned.appendCodePoint(cp)
    }
    var base = cleaned.toString().trim { it == '.' || it.isWhitespace() }
    if (suffix.isNotEmpty() && base.endsWith(suffix, ignoreCase = true)) {
        base = base.dropLast(suffix.length).trim { it == '.' || it.isWhitespace() }
    }
    if (base.isEmpty()) base = FALLBACK_NAME
    if (suffix.isEmpty()) return capUtf8(base, MAX_NAME_BYTES).ifEmpty { FALLBACK_NAME }
    val room = MAX_NAME_BYTES - utf8Length(suffix)
    val head = capUtf8(base, room).trim { it == '.' || it.isWhitespace() }.ifEmpty { FALLBACK_NAME }
    return head + suffix
}

/**
 * True when [url] is a `file:` URL shaped like an imported document (the `imports` directory, a safe
 * extension, no query and no `..`). This does not check that the file is inside our cache; [importFileAllowed] does.
 */
fun isImportLoadUrl(url: String): Boolean {
    if (url.length > MAX_URI_LENGTH) return false
    if (url.any { it.isWhitespace() || it.isISOControl() }) return false
    val uri = try {
        URI(url)
    } catch (e: Exception) {
        return false
    }
    if (!uri.scheme.equals("file", ignoreCase = true)) return false
    val host = uri.host
    if (!host.isNullOrEmpty() && !host.equals("localhost", ignoreCase = true)) return false
    if (uri.rawQuery != null) return false
    val path = uri.path ?: return false
    if (path.contains("..")) return false
    val parts = path.split('/').filter { it.isNotEmpty() }
    if (IMPORT_CACHE_DIR !in parts.dropLast(1)) return false
    val name = parts.lastOrNull() ?: return false
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.lastIndex) return false
    return name.substring(dot + 1).lowercase(Locale.ROOT) in IMPORT_EXTENSIONS
}

/** The file's own name for the address line, or null when [url] is not an import URL. */
fun importDisplayName(url: String): String? {
    if (!isImportLoadUrl(url)) return null
    val path = try {
        URI(url).path
    } catch (e: Exception) {
        null
    } ?: return null
    return path.substringAfterLast('/').takeIf { it.isNotEmpty() }
}

/**
 * True when [url] is an import-shaped `file:` URL whose canonical file is a regular file sitting in a
 * direct child of [root] (that is, `root/<id>/<name>`). Anything else, including a path that canonicalizes
 * out of [root], is refused.
 */
fun importFileAllowed(root: File, url: String): Boolean {
    if (!isImportLoadUrl(url)) return false
    val path = try {
        URI(url).path
    } catch (e: Exception) {
        return false
    } ?: return false
    val file = try {
        File(path).canonicalFile
    } catch (e: IOException) {
        return false
    }
    if (!file.isFile) return false
    val parent = file.parentFile?.canonicalFile ?: return false
    val grand = parent.parentFile?.canonicalFile ?: return false
    val rootCan = try {
        root.canonicalFile
    } catch (e: IOException) {
        return false
    }
    return grand == rootCan
}

/**
 * The local file an incoming `file:` URI may be copied from, or null. Paths that canonicalize into
 * [forbiddenRoots] (the app's private data) are refused, so a crafted intent cannot display our own files.
 * `content:` URIs are not handled here; those are opened with the temporary grant through ContentResolver.
 */
fun readableSharedFile(uriString: String, forbiddenRoots: List<File>): File? {
    if (uriString.length > MAX_URI_LENGTH) return null
    if (uriString.any { it.isWhitespace() || it.isISOControl() }) return null
    val uri = try {
        URI(uriString)
    } catch (e: Exception) {
        return null
    }
    if (!uri.scheme.equals("file", ignoreCase = true)) return null
    val host = uri.host
    if (!host.isNullOrEmpty() && !host.equals("localhost", ignoreCase = true)) return null
    val path = uri.path ?: return null
    val file = try {
        File(path).canonicalFile
    } catch (e: IOException) {
        return null
    }
    if (!file.isFile || !file.canRead()) return null
    if (forbiddenRoots.any { isUnderDir(it, file) }) return null
    return file
}

private fun isUnderDir(root: File, child: File): Boolean {
    val base = try {
        root.canonicalFile
    } catch (e: IOException) {
        return false
    }
    val target = try {
        child.canonicalFile
    } catch (e: IOException) {
        return true
    }
    val basePath = base.path
    val targetPath = target.path
    return targetPath == basePath || targetPath.startsWith(basePath + File.separator)
}

/**
 * Copies a document from another app into [root] and deletes stale copies.
 *
 * Layout is `<cache>/imports/<random>/<safe-name>.<ext>`. The extension comes from the MIME type, not from
 * the display name, because GeckoView picks pdf.js for a `file:` response whose name ends in `.pdf` and treats
 * `.html` / `.xhtml` as a normal local page. Each file has its own directory. Gecko gives every `file:` URL a
 * unique origin and does not allow a local page to read files outside its directory; this layout does not widen
 * that, and [importFileAllowed] refuses navigations that leave the directory.
 *
 * pdf.js itself is the copy built into GeckoView (enabled by default since GeckoView bug 1810106). The load is a
 * normal `loadUri` of the `file:` URL, not `resource://pdf.js/web/viewer.html` (that viewer is privileged) and
 * not a loopback server (that would be a network origin). No file-access preference is changed: embedder loads
 * of the app cache are readable by this process, and pages still cannot broaden that.
 *
 * [deleteOlderThan] runs at startup (files older than 24h). [clearAll] runs when the user clears the cache.
 */
class ImportedFileCache(
    cacheDir: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxAgeMillis: Long = IMPORT_MAX_AGE_MILLIS,
    private val maxBytes: Long = IMPORT_MAX_BYTES,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val root: File = File(cacheDir, IMPORT_CACHE_DIR)

    private val lock = ReentrantReadWriteLock()

    /**
     * Copies one document. [declaredSize], when known and larger than the cap, fails before [open] is called.
     * The stream is capped too, in case the declared size is missing or a lie. On failure nothing is left behind.
     */
    suspend fun copyIn(
        displayName: String?,
        mime: String,
        declaredSize: Long?,
        open: () -> InputStream?,
    ): ImportResult =
        withContext(dispatcher) {
            lock.read { copyLocked(this, displayName, mime, declaredSize, open) }
        }

    /** Deletes child directories last written before [maxAgeMillis] ago. Returns how many were removed. */
    fun deleteOlderThan(nowMillis: Long = now()): Int =
        lock.write {
            if (!root.isDirectory) return@write 0
            val cutoff = nowMillis - maxAgeMillis
            var removed = 0
            for (child in root.listFiles().orEmpty()) {
                if (Files.isSymbolicLink(child.toPath())) {
                    if (child.lastModified() < cutoff && child.delete()) removed++
                    continue
                }
                if (newestMtime(child) < cutoff && deleteTree(child)) removed++
            }
            removed
        }

    /** Deletes the whole import cache, including files younger than [maxAgeMillis]. */
    fun clearAll() {
        lock.write { deleteTree(root) }
    }

    private fun copyLocked(
        scope: CoroutineScope,
        displayName: String?,
        mime: String,
        declaredSize: Long?,
        open: () -> InputStream?,
    ): ImportResult {
        val ext = extensionForImportedMime(mime) ?: return ImportResult.Failed
        if (declaredSize != null && declaredSize > maxBytes) return ImportResult.TooLarge
        var created: File? = null
        try {
            if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) return ImportResult.Failed
            val dir = File(root, UUID.randomUUID().toString())
            if (!dir.mkdir()) return ImportResult.Failed
            created = dir
            val target = File(dir, sanitizeImportFileName(displayName, ext))
            val input = open() ?: return discard(dir, ImportResult.Failed)
            input.use { stream ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) return discard(dir, ImportResult.TooLarge)
                        out.write(buffer, 0, n)
                        scope.ensureActive()
                    }
                }
            }
            if (!target.isFile) return discard(dir, ImportResult.Failed)
            target.setLastModified(now())
            val url = target.toURI().toASCIIString()
            if (!importFileAllowed(root, url)) return discard(dir, ImportResult.Failed)
            return ImportResult.Ready(url)
        } catch (e: CancellationException) {
            created?.let { deleteTree(it) }
            throw e
        } catch (e: Exception) {
            created?.let { deleteTree(it) }
            return ImportResult.Failed
        }
    }

    private fun discard(dir: File, result: ImportResult): ImportResult {
        deleteTree(dir)
        return result
    }
}

/** Age of an imported directory is the files inside it. The directory's own mtime changes when it is created and is not the copy time. */
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

private fun utf8Size(codePoint: Int): Int =
    when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

private fun utf8Length(text: String): Int {
    var bytes = 0
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        bytes += utf8Size(cp)
        i += Character.charCount(cp)
    }
    return bytes
}

/** Keeps whole code points while the UTF-8 size stays within [maxBytes]. */
private fun capUtf8(text: String, maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    var bytes = 0
    var end = 0
    while (end < text.length) {
        val cp = text.codePointAt(end)
        val size = utf8Size(cp)
        if (bytes + size > maxBytes) break
        bytes += size
        end += Character.charCount(cp)
    }
    return text.substring(0, end)
}
