package com.shibi.orbis.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * Directory under the app cache that holds documents opened from other apps (WhatsApp and the like).
 * One file per random child directory.
 */
const val IMPORT_CACHE_DIR = "imports"

/** Largest document copied into [IMPORT_CACHE_DIR]. Larger streams are refused before a tab is opened. */
const val IMPORT_MAX_BYTES = 64L * 1024 * 1024

/** Imported files older than this are deleted at startup. A tab that is still open keeps a younger file. */
const val IMPORT_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000

/**
 * How long a provider may take to hand over the bytes. When it elapses the stream is closed so a stuck
 * [InputStream.read] returns, and the partial copy is deleted.
 */
const val IMPORT_COPY_TIMEOUT_MILLIS = 30_000L

/**
 * Content-Security-Policy written into every imported HTML and XHTML file.
 *
 * GeckoView 159's NavigationDelegate can deny top-level loads
 * (`onLoadRequest`) and subframes (`onSubframeLoadRequest`) only. It has no callback for `<img>`, `<script>`,
 * `<link>` or CSS `url()`. Mozilla bug 1684947: those `file:` subresources are not blocked from the app data
 * directory. This policy is the block. `default-src 'none'` denies every scheme that a directive does not
 * list, and no directive lists `file:`, `http:`, `https:` or `*`. Inline style is allowed so a saved page can
 * still be read; script is not.
 */
const val IMPORT_CONTENT_SECURITY_POLICY =
    "default-src 'none'; base-uri 'none'; form-action 'none'; script-src 'none'; object-src 'none'; " +
        "frame-src 'none'; child-src 'none'; worker-src 'none'; connect-src 'none'; " +
        "img-src data: blob:; media-src data: blob:; font-src data:; style-src 'unsafe-inline' data:"

private const val MAX_URI_LENGTH = 8_192
private const val MAX_NAME_BYTES = 120
private const val COPY_BUFFER_BYTES = 64 * 1024
private const val FALLBACK_NAME = "document"
private const val STAGING_DIR = "import-staging"

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

/**
 * Type to open a shared document with. An explicit intent type wins, including when it is not one we handle.
 * A missing type uses what the provider reports. Blank is treated as missing.
 */
fun mimeForSharedDocument(explicitType: String?, providerType: String?): String? =
    importedMime(explicitType?.takeIf { it.isNotBlank() } ?: providerType)

/** File extension (no dot) for [mime], or null when [importedMime] rejects it. */
fun extensionForImportedMime(mime: String?): String? = importedMime(mime)?.let { MIME_EXTENSIONS[it] }

/**
 * A VIEW or SEND intent's document, or null when it is not a PDF/HTML/XHTML `content:` URI.
 * [streamUri] is `EXTRA_STREAM` (SEND). SEND falls back to [dataString] when the extra is absent.
 * `file:` is not accepted: on API 24+ the platform does not grant apps a path to another app's files, and a
 * crafted `file:` URI is only a way to point at our own storage. Web links stay on [externalUrlFrom].
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
    if (schemeOf(raw) != "content") return null
    return SharedDocumentRequest(raw, mime)
}

/**
 * True when [uri] is a `content:` URI whose provider belongs to this app. Opening it would display our own
 * private files (the FileProvider can see the cache). A lookup that throws is treated as ours so a failure
 * cannot become a way through. A provider we cannot resolve is not ours.
 */
fun contentProviderIsThisApp(uri: String, ourPackage: String, ownerOfAuthority: (String) -> String?): Boolean {
    if (ourPackage.isEmpty()) return false
    val parsed = try {
        URI(uri)
    } catch (e: Exception) {
        return false
    }
    if (!parsed.scheme.equals("content", ignoreCase = true)) return false
    val authority = parsed.rawAuthority?.takeIf { it.isNotEmpty() && it.length <= 512 } ?: return false
    val owner = try {
        ownerOfAuthority(authority)
    } catch (e: Exception) {
        return true
    }
    return owner == ourPackage
}

/**
 * A file name that is safe to create under the import cache and whose final extension matches [extension]
 * (`pdf`, `html`, or `xhtml`). The extension is what GeckoView uses to choose pdf.js versus a web page,
 * so it is forced even when the display name ends in something else.
 * Provider names are untrusted: only the last path segment is kept, control and bidi characters are dropped,
 * and the UTF-8 length is capped while the extension stays intact. Dots inside the name (`Report..v2`) stay.
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
 * extension, no query, and no `.` or `..` path segment). `Report..v2.pdf` is a normal name and is allowed.
 * This does not check that the file is inside our cache; [importFileAllowed] does.
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
    if (hasDotPathSegment(path)) return false
    val parts = path.split('/').filter { it.isNotEmpty() }
    if (IMPORT_CACHE_DIR !in parts.dropLast(1)) return false
    val name = parts.lastOrNull() ?: return false
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.lastIndex) return false
    return name.substring(dot + 1).lowercase(Locale.ROOT) in IMPORT_EXTENSIONS
}

/** A `.` or `..` path segment. A file name that merely contains two dots is not one. */
private fun hasDotPathSegment(path: String): Boolean = path.split('/').any { it == "." || it == ".." }

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
 * JavaScript for an imported page, or null when [url] is not an import.
 * HTML and XHTML are scriptless. PDF stays on because GeckoView's pdf.js viewer needs it.
 * [com.shibi.orbis.engine] site policy does not apply here: it returns before doing anything for a non-web URL,
 * so the session sets this from the load request.
 */
fun importedPageJavaScript(url: String): Boolean? {
    if (!isImportLoadUrl(url)) return null
    val path = try {
        URI(url).path
    } catch (e: Exception) {
        return null
    } ?: return null
    val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return ext == "pdf"
}

/**
 * Whether a subframe may load [url]. Every `file:` URL is refused.
 *
 * This is the whole of what GeckoView can gate below the top-level document. `<img>`, `<script>` and stylesheets
 * never arrive here (no such callback on `NavigationDelegate` or `ContentDelegate` in GeckoView 159), and bug
 * 1684947 means Gecko will fetch a `file:` subresource from the app data directory. Those are stopped by
 * [IMPORT_CONTENT_SECURITY_POLICY], which is written into the imported HTML before it is shown.
 */
fun fileSubframeAllowed(url: String): Boolean = schemeOf(url) != "file"

/**
 * True when [policy] lets [scheme] be loaded for [directive] (`img-src`, `script-src`, …).
 * A directive that the policy does not mention falls back to `default-src`, matching CSP.
 * Used to prove the policy copied into an import denies `file:` (and the network) for subresources.
 */
fun cspDirectiveAllows(policy: String, directive: String, scheme: String): Boolean {
    val directives = policy.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    fun sources(name: String): List<String>? =
        directives.firstOrNull { it == name || it.startsWith("$name ") }?.split(Regex("\\s+"))?.drop(1)
    val list = sources(directive) ?: sources("default-src") ?: return false
    val wanted = scheme.lowercase(Locale.ROOT)
    return list.any { token ->
        val t = token.lowercase(Locale.ROOT)
        t == "*" || t == "$wanted:"
    }
}

/**
 * [bytes] with [IMPORT_CONTENT_SECURITY_POLICY] applied, or null when the markup cannot be protected.
 * HTML gets the meta element before every other byte (after a UTF-8 BOM, so the parser sees it first and a
 * later, looser policy cannot widen this one). XHTML gets it as the first child of `head`, or a new `head`
 * under `html` when the document has none. UTF-16 and XHTML that is not well-formed enough to hold the meta
 * are refused: serving them would leave the `file:` subresource hole open. PDF bytes are not passed here.
 */
fun injectImportContentSecurityPolicy(bytes: ByteArray, extension: String): ByteArray? {
    if (isUtf16(bytes)) return null
    val meta = "<meta http-equiv=\"Content-Security-Policy\" content=\"$IMPORT_CONTENT_SECURITY_POLICY\"/>"
    return when (extension.lowercase(Locale.ROOT)) {
        "html" -> prependHtmlPolicy(bytes, meta)
        "xhtml" -> injectXhtmlPolicy(bytes, meta)
        else -> null
    }
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
 * Documents waiting to be opened, in arrival order. One slot used to be overwritten when two shares finished
 * close together, and the first file was then stranded. [head] is what the browser screen is opening now;
 * [add] queues behind it, and [consume] publishes the next one.
 */
class ImportOpenQueue {
    private val gate = Any()
    private val waiting = ArrayDeque<String>()
    val head = MutableStateFlow<String?>(null)

    fun add(url: String) {
        if (url.isEmpty()) return
        synchronized(gate) {
            if (head.value == null) head.value = url else waiting.addLast(url)
        }
    }

    /** Drops [url] when it is the one on screen and publishes the next. False if [url] is no longer the head. */
    fun consume(url: String): Boolean {
        synchronized(gate) {
            if (head.value != url) return false
            head.value = if (waiting.isEmpty()) null else waiting.removeFirst()
            return true
        }
    }

    /** Forgets every document still waiting. The files themselves are deleted separately. */
    fun clear() {
        synchronized(gate) {
            waiting.clear()
            head.value = null
        }
    }
}

/**
 * Copies a document from another app into [root] and deletes stale copies.
 *
 * Layout is `<cache>/imports/<random>/<safe-name>.<ext>`. The extension comes from the MIME type, not from
 * the display name, because GeckoView picks pdf.js for a `file:` response whose name ends in `.pdf` and treats
 * `.html` / `.xhtml` as a normal local page. The bytes are copied into `import-staging` with no cache lock held,
 * then renamed into [root] under the lock, so Clear browsing data is not stuck behind a provider that never
 * returns. [clearAll] also bumps a generation: a copy that was already in flight is thrown away instead of
 * being published after the clear.
 *
 * pdf.js is the copy built into GeckoView (enabled by default since bug 1810106). The load is a normal `loadUri`
 * of the `file:` URL, not `resource://pdf.js/web/viewer.html` (privileged) and not a loopback server. No
 * file-access preference is changed.
 *
 * A local page is not confined to its directory. GeckoView does not block `file:` subresources into the app
 * data dir (bug 1684947), and a unique file origin does not stop that. HTML and XHTML are therefore rewritten
 * with [IMPORT_CONTENT_SECURITY_POLICY] and shown with JavaScript off. PDF is left byte-for-byte so pdf.js can
 * read it, and JavaScript stays on for that viewer. [fileSubframeAllowed] denies every `file:` subframe on top.
 *
 * [deleteOlderThan] runs at startup (files older than 24h). [clearAll] runs when the user clears history or the cache.
 */
class ImportedFileCache(
    cacheDir: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxAgeMillis: Long = IMPORT_MAX_AGE_MILLIS,
    private val maxBytes: Long = IMPORT_MAX_BYTES,
    private val copyTimeoutMillis: Long = IMPORT_COPY_TIMEOUT_MILLIS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val root: File = File(cacheDir, IMPORT_CACHE_DIR)

    private val staging: File = File(cacheDir, STAGING_DIR)
    private val lock = ReentrantReadWriteLock()

    /** Bumped by [clearAll]. A copy publishes only when this is unchanged since it started. */
    private var epoch = 0

    /**
     * Copies one document. [declaredSize], when known and larger than the cap, fails before [open] is called.
     * The stream is capped too, in case the declared size is missing or a lie. The provider is read without
     * [lock] held. On failure, timeout, or a clear that landed during the read, nothing is left behind.
     */
    suspend fun copyIn(
        displayName: String?,
        mime: String,
        declaredSize: Long?,
        open: () -> InputStream?,
    ): ImportResult = withContext(dispatcher) {
        val ext = extensionForImportedMime(mime) ?: return@withContext ImportResult.Failed
        if (declaredSize != null && declaredSize > maxBytes) return@withContext ImportResult.TooLarge
        val ticket = lock.read { epoch }
        if (!staging.isDirectory && !staging.mkdirs() && !staging.isDirectory) return@withContext ImportResult.Failed
        val dir = File(staging, UUID.randomUUID().toString())
        if (!dir.mkdir()) return@withContext ImportResult.Failed
        val target = File(dir, sanitizeImportFileName(displayName, ext))
        try {
            val copied = writeCopy(this, target, open)
            if (copied !is ImportResult.Ready) return@withContext discardStaging(dir, copied)
            if (ext == "html" || ext == "xhtml") {
                val injected = injectImportContentSecurityPolicy(target.readBytes(), ext)
                    ?: return@withContext discardStaging(dir, ImportResult.Failed)
                if (injected.size.toLong() > maxBytes) return@withContext discardStaging(dir, ImportResult.TooLarge)
                target.writeBytes(injected)
            }
            commit(ticket, dir, target.name)
        } catch (e: kotlinx.coroutines.CancellationException) {
            deleteTree(dir)
            throw e
        } catch (e: Exception) {
            discardStaging(dir, ImportResult.Failed)
        }
    }

    /** Deletes child directories last written before [maxAgeMillis] ago. Returns how many imports were removed. */
    fun deleteOlderThan(nowMillis: Long = now()): Int = lock.write {
        val removed = deleteStaleChildren(root, nowMillis)
        deleteStaleChildren(staging, nowMillis)
        removed
    }

    /** Deletes one imported document's directory (`imports/<id>/`). False when [url] is not one of ours. */
    fun deleteImport(url: String): Boolean = lock.write {
        if (!importFileAllowed(root, url)) return@write false
        val path = try {
            URI(url).path
        } catch (e: Exception) {
            return@write false
        } ?: return@write false
        val dir = File(path).parentFile ?: return@write false
        deleteTree(dir)
    }

    /**
     * Deletes the whole import cache, including files younger than [maxAgeMillis]. An in-flight [copyIn] that
     * started before this call does not publish its file. Does not wait on a provider read.
     */
    fun clearAll() {
        lock.write {
            epoch++
            deleteTree(root)
        }
    }

    /**
     * Reads [open] into [target]. A blocked [InputStream.read] does not notice coroutine cancellation, so the
     * timeout closes the stream from a daemon timer. Closing it is what makes the read return.
     */
    private suspend fun writeCopy(scope: CoroutineScope, target: File, open: () -> InputStream?): ImportResult {
        val input = try {
            open()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return ImportResult.Failed
        } ?: return ImportResult.Failed
        val timedOut = AtomicBoolean(false)
        val timer = Timer("import-copy-timeout", true)
        timer.schedule(
            object : TimerTask() {
                override fun run() {
                    timedOut.set(true)
                    runCatching { input.close() }
                }
            },
            copyTimeoutMillis,
        )
        val outcome = try {
            input.use { stream ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) return@writeCopy ImportResult.TooLarge
                        out.write(buffer, 0, n)
                        scope.ensureActive()
                    }
                }
            }
            if (timedOut.get()) ImportResult.Failed else ImportResult.Ready("")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            scope.ensureActive()
            ImportResult.Failed
        } finally {
            timer.cancel()
            runCatching { input.close() }
        }
        return if (timedOut.get() && outcome is ImportResult.Ready) ImportResult.Failed else outcome
    }

    private fun commit(ticket: Int, dir: File, name: String): ImportResult = lock.write {
        if (ticket != epoch) return@write discardStaging(dir, ImportResult.Failed)
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) return@write discardStaging(dir, ImportResult.Failed)
        val destDir = File(root, dir.name)
        if (destDir.exists() || !dir.renameTo(destDir)) return@write discardStaging(dir, ImportResult.Failed)
        val dest = File(destDir, name)
        if (!dest.isFile) return@write discardStaging(destDir, ImportResult.Failed)
        dest.setLastModified(now())
        val url = dest.toURI().toASCIIString()
        if (!importFileAllowed(root, url)) return@write discardStaging(destDir, ImportResult.Failed)
        ImportResult.Ready(url)
    }

    private fun deleteStaleChildren(directory: File, nowMillis: Long): Int {
        if (!directory.isDirectory) return 0
        val cutoff = nowMillis - maxAgeMillis
        var removed = 0
        for (child in directory.listFiles().orEmpty()) {
            if (Files.isSymbolicLink(child.toPath())) {
                if (child.lastModified() < cutoff && child.delete()) removed++
                continue
            }
            if (newestMtime(child) < cutoff && deleteTree(child)) removed++
        }
        return removed
    }
}

private fun discardStaging(dir: File, result: ImportResult): ImportResult {
    deleteTree(dir)
    return result
}

private fun isUtf16(bytes: ByteArray): Boolean {
    if (bytes.size < 2) return false
    val b0 = bytes[0].toInt() and 0xFF
    val b1 = bytes[1].toInt() and 0xFF
    return (b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)
}

private fun prependHtmlPolicy(bytes: ByteArray, meta: String): ByteArray {
    val bom = if (bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
    ) 3 else 0
    val prefix = (meta + "\n").toByteArray(Charsets.US_ASCII)
    val out = ByteArrayOutputStream(bom + prefix.size + bytes.size - bom)
    out.write(bytes, 0, bom)
    out.write(prefix)
    out.write(bytes, bom, bytes.size - bom)
    return out.toByteArray()
}

private fun injectXhtmlPolicy(bytes: ByteArray, meta: String): ByteArray? {
    val text = decodeUtf8Strict(bytes) ?: return null
    val head = findStartTag(text, "head")
    val inserted = if (head != null && !head.selfClosing) {
        text.substring(0, head.end) + meta + text.substring(head.end)
    } else {
        val html = findStartTag(text, "html") ?: return null
        if (html.selfClosing) return null
        text.substring(0, html.end) + "<head>$meta</head>" + text.substring(html.end)
    }
    if (!inserted.contains(IMPORT_CONTENT_SECURITY_POLICY)) return null
    return inserted.toByteArray(Charsets.UTF_8)
}

private fun decodeUtf8Strict(bytes: ByteArray): String? = try {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    decoder.decode(ByteBuffer.wrap(bytes)).toString()
} catch (e: CharacterCodingException) {
    null
}

private class StartTag(val end: Int, val selfClosing: Boolean)

/** The first start tag named [name], skipping comments, processing instructions, declarations and CDATA. */
private fun findStartTag(text: String, name: String): StartTag? {
    var i = 0
    val n = text.length
    while (i < n) {
        if (text[i] != '<') {
            i++
            continue
        }
        if (text.startsWith("<!--", i)) {
            val close = text.indexOf("-->", i + 4)
            if (close < 0) return null
            i = close + 3
            continue
        }
        if (text.startsWith("<![CDATA[", i)) {
            val close = text.indexOf("]]>", i + 9)
            if (close < 0) return null
            i = close + 3
            continue
        }
        if (i + 1 < n && text[i + 1] == '?') {
            val close = text.indexOf("?>", i + 2)
            if (close < 0) return null
            i = close + 2
            continue
        }
        if (i + 1 < n && text[i + 1] == '!') {
            i = skipBracket(text, i) ?: return null
            continue
        }
        if (i + 1 < n && text[i + 1] == '/') {
            i = skipBracket(text, i) ?: return null
            continue
        }
        val tag = readStartTag(text, i) ?: return null
        if (tag.name.equals(name, ignoreCase = true)) return StartTag(tag.end, tag.selfClosing)
        i = tag.end
    }
    return null
}

private class ReadTag(val name: String, val end: Int, val selfClosing: Boolean)

private fun readStartTag(text: String, start: Int): ReadTag? {
    var i = start + 1
    if (i >= text.length || !text[i].isLetter()) return null
    val nameStart = i
    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == ':' || text[i] == '-' || text[i] == '_')) i++
    val tagName = text.substring(nameStart, i)
    var quote: Char? = null
    var slash = false
    while (i < text.length) {
        val c = text[i]
        if (quote != null) {
            if (c == quote) quote = null
            i++
            continue
        }
        when (c) {
            '"', '\'' -> {
                quote = c
                slash = false
                i++
            }
            '/' -> {
                slash = true
                i++
            }
            '>' -> return ReadTag(tagName, i + 1, slash)
            else -> {
                if (!c.isWhitespace()) slash = false
                i++
            }
        }
    }
    return null
}

/** Index just after the next unquoted `>`, or null when the text ends first. */
private fun skipBracket(text: String, start: Int): Int? {
    var quote: Char? = null
    var i = start + 1
    while (i < text.length) {
        val c = text[i]
        if (quote != null) {
            if (c == quote) quote = null
        } else when (c) {
            '"', '\'' -> quote = c
            '>' -> return i + 1
        }
        i++
    }
    return null
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
