package com.shibi.orbis.engine

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Copying a picked file failed. The message never carries a name or path. */
class UploadCopyException(message: String, cause: Throwable? = null) : IOException(message, cause)

private const val FALLBACK_NAME = "upload"
private const val MAX_NAME_BYTES = 120
private const val MAX_EXTENSION_BYTES = 32
private const val COPY_BUFFER_BYTES = 64 * 1024
private const val WATCHDOG_TICK_MS = 250L

/**
 * How long [UploadCache] waits for a picked document's name, size or type. Longer than an intent's 3 s: a slow answer
 * is not an error, and without it the file goes to the site as `upload` with no extension.
 */
const val UPLOAD_METADATA_TIMEOUT_MS = 10_000L

/** How long [UploadCache] waits for a provider to hand over a picked document's stream. See [UploadCache]. */
const val UPLOAD_OPEN_TIMEOUT_MS = 120_000L

/** How long an upload copy may go without a byte before the stream is closed and the copy fails. */
const val UPLOAD_IDLE_TIMEOUT_MS = 30_000L

private fun utf8Size(codePoint: Int): Int =
    when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

private fun String.trimDotsAndSpaces(): String = trim { it == '.' || it.isWhitespace() }

/** Keeps whole code points while the UTF-8 size stays within [maxBytes]. */
private fun String.takeUtf8(maxBytes: Int): String {
    var bytes = 0
    var end = 0
    while (end < length) {
        val cp = codePointAt(end)
        val size = utf8Size(cp)
        if (bytes + size > maxBytes) break
        bytes += size
        end += Character.charCount(cp)
    }
    return substring(0, end)
}

private fun String.utf8Length(): Int {
    var bytes = 0
    var i = 0
    while (i < length) {
        val cp = codePointAt(i)
        bytes += utf8Size(cp)
        i += Character.charCount(cp)
    }
    return bytes
}

/**
 * A file name safe to create under the upload cache. Provider display names are untrusted: keep only the last path
 * segment, drop control and format characters (NUL, bidi overrides) and lone surrogates, strip leading and trailing
 * dots and spaces, add [extensionForMime] (with or without a dot) when the name has no extension, and cap at
 * 120 UTF-8 bytes while keeping the extension.
 */
fun sanitizeUploadName(raw: String?, extensionForMime: String?): String {
    val segment = (raw ?: "").substringAfterLast('/').substringAfterLast('\\')
    val cleaned = StringBuilder()
    var i = 0
    while (i < segment.length) {
        val cp = segment.codePointAt(i)
        i += Character.charCount(cp)
        val type = Character.getType(cp)
        // A lone surrogate reports its own type; a valid pair arrives as one supplementary code point.
        val bad =
            type == Character.CONTROL.toInt() ||
                type == Character.FORMAT.toInt() ||
                type == Character.SURROGATE.toInt()
        if (!bad) cleaned.appendCodePoint(cp)
    }
    var name = cleaned.toString().trimDotsAndSpaces()
    if (name.isEmpty()) name = FALLBACK_NAME

    if (!name.contains('.')) {
        val ext = extensionForMime?.trim()?.trimStart('.')?.filter { it.isLetterOrDigit() && it.code < 0x80 }.orEmpty()
        if (ext.isNotEmpty() && ext.length <= MAX_EXTENSION_BYTES - 1) name = "$name.$ext"
    }

    if (name.utf8Length() <= MAX_NAME_BYTES) return name

    val dot = name.lastIndexOf('.')
    val extPart = if (dot > 0) name.substring(dot) else ""
    // A giant "extension" is just part of the name: cut the whole thing instead.
    val keepExt = extPart.isNotEmpty() && extPart.utf8Length() <= MAX_EXTENSION_BYTES
    val tail = if (keepExt) extPart else ""
    val head = (if (keepExt) name.substring(0, dot) else name).takeUtf8(MAX_NAME_BYTES - tail.utf8Length())
    val base = head.trimDotsAndSpaces().ifEmpty { FALLBACK_NAME }
    return base + tail
}

/**
 * Copies user-picked documents into app-private cache so Gecko can read them as plain files: it cannot resolve most
 * `content://` Uris itself. Files live under `<cacheDir>/uploads/<random>/<sanitized name>`; the real name is kept
 * because Gecko sends the basename to the server. Never external storage, never in a FileProvider path.
 *
 * Files are NOT deleted after a successful pick: Gecko reads the path at form-submit time. [clearAll] runs once at
 * engine start instead.
 *
 * The provider behind a picked document is another app and may hang. Each [Source] property and [Source.open] runs
 * through [providerCall] (its own daemon thread, the [ProviderDeadline.picked] pool, a deadline), never on a
 * dispatcher thread:
 * [metadataTimeoutMs] for the name, size and extension, [openTimeoutMs] for the open. The open gets far longer than
 * a metadata call because cloud providers fetch the whole file before they return the stream. The copy itself is
 * bounded by [idleTimeoutMs] without a byte, not by an overall deadline: a large upload may take minutes.
 */
class UploadCache(
    cacheDir: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val metadataTimeoutMs: Long = UPLOAD_METADATA_TIMEOUT_MS,
    private val openTimeoutMs: Long = UPLOAD_OPEN_TIMEOUT_MS,
    private val idleTimeoutMs: Long = UPLOAD_IDLE_TIMEOUT_MS,
    private val watchdogDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** One document to copy. Properties and [open] may block: each is read once, on a provider thread. */
    interface Source {
        val name: String?
        val size: Long?

        /** File extension for this document's mime type, without a dot; used when [name] has none. */
        val extension: String? get() = null

        fun open(): InputStream?
    }

    val root: File = File(cacheDir, "uploads")

    // Copies share the read side; clearAll takes the write side, so it never deletes under a running copy.
    private val lock = ReentrantReadWriteLock()

    /**
     * Copies every source, all or nothing: on any failure everything this call created is deleted and a single
     * [UploadCopyException] is thrown. On cancellation the same cleanup runs and the cancellation is rethrown.
     */
    suspend fun copy(sources: List<Source>): List<File> =
        withContext(dispatcher) {
            val created = ArrayList<File>()
            try {
                // Provider calls suspend, so they stay outside [lock]: a read lock must be released on the thread that
                // took it, and a coroutine may resume on another.
                val described = sources.map { describe(it) }
                lock.read {
                    if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) {
                        throw UploadCopyException("cache unavailable")
                    }
                    var needed = 0L
                    for (source in described) {
                        val size = source.size ?: continue
                        if (size > 0) needed = if (Long.MAX_VALUE - needed < size) Long.MAX_VALUE else needed + size
                    }
                    if (needed > root.usableSpace) throw UploadCopyException("not enough space")
                }

                val files = ArrayList<File>(sources.size)
                for (source in described) {
                    ensureActive()
                    files += copyOne(source, created)
                }
                ensureActive()
                files
            } catch (e: CancellationException) {
                created.forEach { it.deleteRecursively() }
                throw e
            } catch (e: UploadCopyException) {
                created.forEach { it.deleteRecursively() }
                // A cancel that closed a blocked read surfaces as an IOException: report it as the cancel it was.
                ensureActive()
                throw e
            } catch (e: Exception) {
                // IOException, SecurityException, or anything a provider throws: never leave a partial set.
                created.forEach { it.deleteRecursively() }
                ensureActive()
                throw UploadCopyException("copy failed: ${e.javaClass.simpleName}", e)
            }
        }

    /** What [copy] knows about a source; the provider is never asked twice. */
    private class Described(val source: Source, val name: String?, val size: Long?, val extension: String?)

    /**
     * Name and size, then the extension, as two provider calls: in [ContentUriSource] they are a query and a getType,
     * so a provider that hangs in one still answers the other. A call that timed out or failed counts as unknown,
     * which the copy already handles (generic name, no space check).
     */
    private suspend fun describe(source: Source): Described {
        val nameAndSize = providerCall(metadataTimeoutMs, pool = ProviderDeadline.picked) { source.name to source.size }
        val extension = providerCall(metadataTimeoutMs, pool = ProviderDeadline.picked) { source.extension }
        return Described(source, nameAndSize?.first, nameAndSize?.second, extension)
    }

    private suspend fun CoroutineScope.copyOne(source: Described, created: MutableList<File>): File {
        val failure = AtomicReference<Throwable?>(null)
        val input =
            providerCall(openTimeoutMs, pool = ProviderDeadline.picked) {
                try {
                    source.source.open()
                } catch (e: Exception) {
                    failure.set(e)
                    null
                }
            } ?: throw UploadCopyException("cannot open source", failure.get())
        return input.use { stream ->
            val lastProgress = AtomicLong(System.nanoTime())
            val stalled = AtomicBoolean(false)
            val finished = AtomicBoolean(false)
            // A read blocked in the provider ignores cancellation and only returns once the stream is closed (an
            // Android file descriptor stream wakes a blocked reader on close). On its own dispatcher, so a busy copy
            // dispatcher cannot starve it.
            val watchdog =
                launch(watchdogDispatcher) {
                    while (true) {
                        delay(minOf(idleTimeoutMs, WATCHDOG_TICK_MS).coerceAtLeast(1L))
                        if ((System.nanoTime() - lastProgress.get()) / 1_000_000 >= idleTimeoutMs) {
                            stalled.set(true)
                            runCatching { stream.close() }
                            break
                        }
                    }
                }
            // Cancelled with the copy (the prompt went away): close so a blocked read returns. A completion handler,
            // not a finally in the body: a coroutine cancelled before its body starts never runs the body.
            watchdog.invokeOnCompletion { if (!finished.get()) runCatching { stream.close() } }
            try {
                ensureActive()
                lock.read {
                    val dir = File(root, UUID.randomUUID().toString())
                    if (!dir.mkdir()) throw UploadCopyException("cache unavailable")
                    created += dir
                    val target = File(dir, sanitizeUploadName(source.name, source.extension))
                    try {
                        target.outputStream().use { out ->
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            while (true) {
                                val n = stream.read(buffer)
                                if (stalled.get()) throw UploadCopyException("source stalled")
                                if (n < 0) break
                                if (n > 0) lastProgress.set(System.nanoTime())
                                out.write(buffer, 0, n)
                                ensureActive()
                            }
                        }
                    } catch (e: IOException) {
                        if (stalled.get() && e !is UploadCopyException) throw UploadCopyException("source stalled", e)
                        throw e
                    }
                    target
                }
            } finally {
                finished.set(true)
                watchdog.cancel()
            }
        }
    }

    /** Deletes the files [copy] returned (their private directories) when the prompt ended before they were used. */
    suspend fun discard(files: List<File>) {
        withContext(dispatcher) { discardNow(files) }
    }

    /** [discard] for a caller that is already off the main thread. */
    fun discardNow(files: List<File>) {
        lock.read {
            for (file in files) {
                val dir = file.parentFile ?: continue
                // Only ever a direct child of the root: never a path the caller made up.
                if (dir.parentFile == root) dir.deleteRecursively()
            }
        }
    }

    /** Deletes the whole cache. Engine start only, on a background thread. */
    fun clearAll() {
        lock.write { root.deleteRecursively() }
    }
}

/**
 * Android glue: reads name, size and mime type through the resolver. A failed query is not fatal. Every member calls
 * into the provider; [UploadCache.copy] reads each one once, on a provider thread with a deadline.
 */
internal class ContentUriSource(private val resolver: ContentResolver, private val uri: Uri) : UploadCache.Source {
    private val meta: Pair<String?, Long?> by lazy {
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s).takeIf { it >= 0 }
                }
            }
        }
        name to size
    }

    override val name: String? get() = meta.first
    override val size: Long? get() = meta.second

    override val extension: String? by lazy {
        runCatching {
            resolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        }.getOrNull()
    }

    // Only document-picker results are accepted: a file: Uri would let the picker result read any file we can.
    override fun open(): InputStream? = if (uri.scheme == ContentResolver.SCHEME_CONTENT) resolver.openInputStream(uri) else null
}
