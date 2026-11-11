package com.shibi.orbis.engine

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
 * How long a provider may go without handing over another byte. Each read resets it. When it elapses the stream
 * is closed so a stuck [InputStream.read] returns, and the partial copy is deleted. Opening the stream and the
 * display-name query sit on the same clock.
 */
const val IMPORT_COPY_IDLE_TIMEOUT_MILLIS = 30_000L

/** Hard stop for one import, even when bytes keep arriving. Generous enough for a slow large PDF. */
const val IMPORT_COPY_OVERALL_TIMEOUT_MILLIS = 5L * 60 * 1000

/**
 * Content-Security-Policy written into every imported HTML file.
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
private const val HTML_SCAN_BYTES = 64 * 1024
private const val HTML_NUL_SCAN_BYTES = 4 * 1024

/**
 * Longest preamble we will keep in front of the charset meta. The HTML prescan only looks at the first 1024
 * bytes, and the meta itself needs room inside that window. A longer run of comments or whitespace inserts
 * the metas right after the BOM instead: quirks mode, but the policy is still the first tag.
 */
private const val HTML_CHARSET_BUDGET = 900
private const val FALLBACK_NAME = "document"
private const val STAGING_DIR = "import-staging"

private val MIME_EXTENSIONS =
    mapOf(
        "application/pdf" to "pdf",
        "text/html" to "html",
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
 * A VIEW or SEND intent's document, or null when it is not a PDF or HTML `content:` URI.
 * `application/xhtml+xml` is not accepted: a CSP meta cannot be made reliable in XHTML.
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
 *
 * An authority containing `@` is also treated as ours. Android keeps a userId prefix on the authority
 * (`content://0@com.shibi.orbis.downloads/...`), and resolving that whole string does not find our provider,
 * which would otherwise let the check through. The part after `@` is not looked up either.
 */
fun contentProviderIsThisApp(uri: String, ourPackage: String, ownerOfAuthority: (String) -> String?): Boolean {
    if (ourPackage.isEmpty()) return false
    val parsed = try {
        URI(uri)
    } catch (e: Exception) {
        return false
    }
    if (!parsed.scheme.equals("content", ignoreCase = true)) return false
    val raw = parsed.rawAuthority?.takeIf { it.isNotEmpty() && it.length <= 512 } ?: return false
    val decoded = parsed.authority ?: raw
    if ('@' in raw || '@' in decoded) return true
    val owner = try {
        ownerOfAuthority(raw)
    } catch (e: Exception) {
        return true
    }
    return owner == ourPackage
}

/**
 * A file name that is safe to create under the import cache and whose final extension matches [extension]
 * (`pdf` or `html`). The extension is what GeckoView uses to choose pdf.js versus a web page,
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

/**
 * True when a top-level load must be refused because an imported document is trying to leave for the web on its own,
 * e.g. `<meta http-equiv="refresh" content="0;url=https://...">`, which the import CSP cannot stop. [committedUrl] is
 * the page the tab shows now. A link the user tapped ([hasUserGesture]), an address the app itself loads
 * ([isDirectNavigation]: typed, bookmark, reload) and a redirect of such a load that has not committed yet
 * ([redirectOfAllowedLoad]) are allowed, as is any target that is not http(s). A redirect on its own is not enough:
 * Gecko may report a short meta refresh as one.
 */
fun blocksImportAutoNavigation(
    committedUrl: String?,
    targetUrl: String,
    hasUserGesture: Boolean,
    isDirectNavigation: Boolean,
    redirectOfAllowedLoad: Boolean,
): Boolean {
    if (hasUserGesture || isDirectNavigation || redirectOfAllowedLoad) return false
    if (committedUrl == null || !isImportLoadUrl(committedUrl)) return false
    return isWebUrl(targetUrl)
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
 * HTML is scriptless. PDF stays on because GeckoView's pdf.js viewer needs it.
 * [com.shibi.orbis.engine] site policy does not apply here: it returns before doing anything for a non-web URL,
 * so the session sets this from the load request, and again when a history navigation commits the URL
 * (Back and Forward do not pass through that request).
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
 * JavaScript to apply when [url] is committed without a fresh load request, if it differs from [javascriptEnabled].
 * Null means leave the session alone: the URL is not an import, or the setting is already what that import needs.
 * Back and Forward can show an imported HTML page while JavaScript is still on from the page the user left.
 */
fun importScriptCorrection(url: String?, javascriptEnabled: Boolean): Boolean? {
    val want = importedPageJavaScript(url ?: return null) ?: return null
    return want.takeIf { it != javascriptEnabled }
}

/**
 * What a commit that skipped `onLoadRequest` (Back, Forward, restore) should do with JavaScript.
 * [Leave] is the steady state: applying it again must not reload, which is what stops a reload loop.
 */
sealed class HistoryScript {
    data object Leave : HistoryScript()

    /** An imported document. [reload] when [enabled] is not the session's current value. */
    data class Force(val enabled: Boolean, val reload: Boolean) : HistoryScript()

    /**
     * A web URL reached from an import. The import overwrote the site's JavaScript setting, and Forward
     * does not pass through `onLoadRequest`, so the site policy has to be put back. The caller reloads once
     * when that changes the setting, then clears the "previous page was an import" flag before the reload.
     */
    data object RestoreSite : HistoryScript()
}

/**
 * [previousForcedImportScript] is true when the page being left had its JavaScript forced because it was an
 * import. A web URL then needs [HistoryScript.RestoreSite]. The same web URL with the flag already cleared is
 * [HistoryScript.Leave], so the reload that applies the site policy does not reload again.
 */
fun scriptAfterHistoryCommit(
    url: String?,
    javascriptEnabled: Boolean,
    previousForcedImportScript: Boolean,
): HistoryScript {
    if (url != null) {
        val want = importedPageJavaScript(url)
        if (want != null) return HistoryScript.Force(want, reload = want != javascriptEnabled)
        if (previousForcedImportScript && isWebUrl(url)) return HistoryScript.RestoreSite
    }
    return HistoryScript.Leave
}

/**
 * Whether a subframe may load [url]. Every `file:` URL is refused.
 *
 * This is the whole of what GeckoView can gate below the top-level document. `<img>`, `<script>` and stylesheets
 * never arrive here (no such callback on `NavigationDelegate` or `ContentDelegate` in GeckoView 159), and bug
 * 1684947 means Gecko will fetch a `file:` subresource from the app data directory. Those are stopped by
 * [IMPORT_CONTENT_SECURITY_POLICY], which is written into the imported HTML before it is shown.
 * XHTML is not imported: a late `head`, a non-XHTML default namespace, or `<?xml-stylesheet?>` can bypass a meta.
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
 * `.html` as a normal local page. The bytes are copied into `import-staging` with no cache lock held, then
 * renamed into [root] under the lock, so Clear browsing data is not stuck behind a provider that never returns.
 * [clearAll] also bumps a generation: a copy that was already in flight is thrown away instead of being published
 * after the clear.
 *
 * pdf.js is the copy built into GeckoView (enabled by default since bug 1810106). The load is a normal `loadUri`
 * of the `file:` URL, not `resource://pdf.js/web/viewer.html` (privileged) and not a loopback server. No
 * file-access preference is changed.
 *
 * A local page is not confined to its directory. GeckoView does not block `file:` subresources into the app
 * data dir (bug 1684947), and a unique file origin does not stop that. HTML is therefore rewritten with
 * [IMPORT_CONTENT_SECURITY_POLICY] and shown with JavaScript off. The policy meta is inserted after the BOM,
 * leading whitespace, comments and an optional doctype, so the page stays in standards mode. PDF is left
 * byte-for-byte so pdf.js can read it, and JavaScript stays on for that viewer. [fileSubframeAllowed] denies
 * every `file:` subframe on top. XHTML is refused.
 *
 * [deleteOlderThan] runs at startup (files older than 24h). [clearAll] runs when the user clears history or the cache.
 */
class ImportedFileCache(
    cacheDir: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxAgeMillis: Long = IMPORT_MAX_AGE_MILLIS,
    private val maxBytes: Long = IMPORT_MAX_BYTES,
    private val copyTimeoutMillis: Long = IMPORT_COPY_IDLE_TIMEOUT_MILLIS,
    private val overallTimeoutMillis: Long = IMPORT_COPY_OVERALL_TIMEOUT_MILLIS,
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
        val watchdog = ImportCopyWatchdog(copyTimeoutMillis, overallTimeoutMillis)
        watchdog.start()
        try {
            writeImported(this, displayName, mime, declaredSize, open, watchdog)
        } finally {
            watchdog.close()
        }
    }

    /**
     * Same copy as [copyIn], but [query] (the display name and size) and [open] share one idle timeout.
     * A provider that stalls in either call fails the import; bytes that keep arriving reset the idle clock
     * up to [overallTimeoutMillis].
     */
    internal suspend fun readShared(
        mime: String,
        query: () -> Pair<String?, Long?>,
        open: () -> InputStream?,
    ): ImportResult = withContext(dispatcher) {
        val watchdog = ImportCopyWatchdog(copyTimeoutMillis, overallTimeoutMillis)
        watchdog.start()
        try {
            val columns = awaitProvider(this, watchdog, query)
            if (columns == null || watchdog.isTimedOut()) return@withContext ImportResult.Failed
            val (name, size) = columns
            if (size != null && size > maxBytes) return@withContext ImportResult.TooLarge
            writeImported(this, name, mime, size, open, watchdog)
        } finally {
            watchdog.close()
        }
    }

    private suspend fun writeImported(
        scope: CoroutineScope,
        displayName: String?,
        mime: String,
        declaredSize: Long?,
        open: () -> InputStream?,
        watchdog: ImportCopyWatchdog,
    ): ImportResult {
        val ext = extensionForImportedMime(mime) ?: return ImportResult.Failed
        if (declaredSize != null && declaredSize > maxBytes) return ImportResult.TooLarge
        val ticket = lock.read { epoch }
        if (!staging.isDirectory && !staging.mkdirs() && !staging.isDirectory) return ImportResult.Failed
        val dir = File(staging, UUID.randomUUID().toString())
        if (!dir.mkdir()) return ImportResult.Failed
        val target = File(dir, sanitizeImportFileName(displayName, ext))
        return try {
            val copied = writeCopy(scope, target, open, watchdog)
            if (copied !is ImportResult.Ready) return discardStaging(dir, copied)
            if (ext == "html") {
                val rewritten = File(dir, target.name + ".tmp")
                val protected = try {
                    writeHtmlWithPolicy(target, rewritten)
                } catch (e: OutOfMemoryError) {
                    false
                }
                if (!protected) return discardStaging(dir, ImportResult.Failed)
                if (rewritten.length() > maxBytes) return discardStaging(dir, ImportResult.TooLarge)
                if (!target.delete() || !rewritten.renameTo(target)) return discardStaging(dir, ImportResult.Failed)
            }
            commit(ticket, dir, target.name)
        } catch (e: kotlinx.coroutines.CancellationException) {
            deleteTree(dir)
            throw e
        } catch (e: OutOfMemoryError) {
            discardStaging(dir, ImportResult.Failed)
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
     * idle timeout closes the stream from a daemon timer. Closing it is what makes the read return. Each read
     * resets that idle clock. [watchdog]'s overall cap still ends a transfer that never goes idle.
     */
    private suspend fun writeCopy(
        scope: CoroutineScope,
        target: File,
        open: () -> InputStream?,
        watchdog: ImportCopyWatchdog,
    ): ImportResult {
        val input = try {
            awaitProvider(scope, watchdog, open)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        }
        if (input == null || watchdog.isTimedOut()) {
            input?.let { runCatching { it.close() } }
            return ImportResult.Failed
        }
        watchdog.watch(input)
        val outcome = try {
            input.use { stream ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val n = stream.read(buffer)
                        if (watchdog.isTimedOut()) return@writeCopy ImportResult.Failed
                        watchdog.bump()
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) return@writeCopy ImportResult.TooLarge
                        out.write(buffer, 0, n)
                        scope.ensureActive()
                    }
                }
            }
            if (watchdog.isTimedOut()) ImportResult.Failed else ImportResult.Ready("")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            scope.ensureActive()
            ImportResult.Failed
        } finally {
            runCatching { input.close() }
        }
        return if (watchdog.isTimedOut() && outcome is ImportResult.Ready) ImportResult.Failed else outcome
    }

    /**
     * Runs [block] on a daemon thread so a stuck provider call cannot hold this coroutine past the idle timeout.
     * The thread itself may still be inside the provider after we give up; the import has already failed.
     */
    private suspend fun <T> awaitProvider(scope: CoroutineScope, watchdog: ImportCopyWatchdog, block: () -> T): T? {
        if (watchdog.isTimedOut()) return null
        val future = CompletableFuture<T>()
        val worker = Thread({
            try {
                val value = block()
                if (watchdog.isTimedOut() && value is InputStream) runCatching { value.close() }
                future.complete(value)
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }, "import-provider")
        worker.isDaemon = true
        worker.start()
        try {
            while (true) {
                scope.ensureActive()
                if (watchdog.isTimedOut() || watchdog.overallRemainingMillis() <= 0L) {
                    watchdog.fire()
                    future.thenAccept { value ->
                        if (value is InputStream) runCatching { value.close() }
                    }
                    return null
                }
                try {
                    val value = future.get(50, TimeUnit.MILLISECONDS)
                    watchdog.bump()
                    if (watchdog.isTimedOut()) {
                        if (value is InputStream) runCatching { value.close() }
                        return null
                    }
                    return value
                } catch (e: TimeoutException) {
                    continue
                } catch (e: ExecutionException) {
                    val cause = e.cause
                    if (cause is kotlinx.coroutines.CancellationException) throw cause
                    return null
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            watchdog.fire()
            throw e
        }
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

/**
 * Idle timeout for one import. [bump] restarts the idle clock. The overall cap is measured from [start] and
 * does not move. Firing closes the stream currently being read, which is what unblocks a stuck `read`.
 */
internal class ImportCopyWatchdog(
    private val idleMillis: Long,
    private val overallMillis: Long,
) {
    private val timer = Timer("import-copy-timeout", true)
    private val startedAt = System.nanoTime()
    private val timedOut = AtomicBoolean(false)
    private val stream = AtomicReference<InputStream?>(null)
    private var task: TimerTask? = null
    private var closed = false

    fun start() = bump()

    fun watch(input: InputStream) {
        stream.set(input)
        if (timedOut.get()) runCatching { input.close() }
    }

    fun isTimedOut(): Boolean = timedOut.get()

    fun overallRemainingMillis(): Long =
        overallMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    fun bump() {
        val expired = synchronized(this) {
            if (closed || timedOut.get()) return
            val left = overallRemainingMillis()
            if (left <= 0L) {
                timedOut.compareAndSet(false, true)
                task?.cancel()
                true
            } else {
                task?.cancel()
                val delay = minOf(idleMillis, left).coerceAtLeast(1L)
                val next = object : TimerTask() {
                    override fun run() = fire()
                }
                task = next
                timer.schedule(next, delay)
                false
            }
        }
        if (expired) stream.get()?.let { runCatching { it.close() } }
    }

    fun fire() {
        val toClose: InputStream?
        synchronized(this) {
            if (closed || !timedOut.compareAndSet(false, true)) return
            task?.cancel()
            toClose = stream.get()
        }
        toClose?.let { runCatching { it.close() } }
    }

    fun close() {
        synchronized(this) {
            closed = true
            task?.cancel()
            timer.cancel()
        }
    }
}

private val POLICY_META: ByteArray = (
    "<meta charset=\"utf-8\">\n" +
        "<meta http-equiv=\"Content-Security-Policy\" content=\"$IMPORT_CONTENT_SECURITY_POLICY\">\n"
    ).toByteArray(Charsets.US_ASCII)

private val DOCTYPE_TOKEN = "<!DOCTYPE".toByteArray(Charsets.US_ASCII)
private val COMMENT_OPEN = "<!--".toByteArray(Charsets.US_ASCII)
private val COMMENT_EMPTY = "<!-->".toByteArray(Charsets.US_ASCII)
private val COMMENT_EMPTY_DASH = "<!--->".toByteArray(Charsets.US_ASCII)
private val COMMENT_CLOSE = "-->".toByteArray(Charsets.US_ASCII)
private val COMMENT_END_BANG = "--!>".toByteArray(Charsets.US_ASCII)

/**
 * Writes [source] to [dest] with a charset meta and [IMPORT_CONTENT_SECURITY_POLICY] inserted after the BOM,
 * leading whitespace, comments and one optional doctype, using HTML's own tokenization. A meta before the
 * doctype would put the page in quirks mode, but a preamble longer than [HTML_CHARSET_BUDGET] inserts right
 * after the BOM so the charset meta stays inside the first 1024 bytes. The insertion point is searched only
 * in the first [HTML_SCAN_BYTES]. The rest is streamed. False when the bytes are not UTF-8 or a NUL sits in
 * the first [HTML_NUL_SCAN_BYTES] (a BOM-less UTF-16 file).
 */
private fun writeHtmlWithPolicy(source: File, dest: File): Boolean {
    FileInputStream(source).use { input ->
        val scan = ByteArray(HTML_SCAN_BYTES)
        val n = readFully(input, scan)
        if (containsNul(scan, minOf(n, HTML_NUL_SCAN_BYTES))) return false
        val extra = if (n == scan.size) input.read() else -1
        val more = extra >= 0
        val insertAt = skipHtmlPreamble(scan, n, more)
        val utf8 = Utf8Check()
        if (!utf8.feed(scan, 0, n, end = !more)) return false
        dest.outputStream().use { out ->
            out.write(scan, 0, insertAt)
            out.write(POLICY_META)
            out.write(scan, insertAt, n - insertAt)
            if (!more) return true
            val first = byteArrayOf(extra.toByte())
            if (!copyValidated(input, out, utf8, first)) return false
        }
    }
    return dest.isFile
}

private fun copyValidated(input: InputStream, out: java.io.OutputStream, utf8: Utf8Check, first: ByteArray): Boolean {
    if (!utf8.feed(first, 0, first.size, end = false)) return false
    out.write(first)
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return utf8.feed(ByteArray(0), 0, 0, end = true)
        if (!utf8.feed(buffer, 0, count, end = false)) return false
        out.write(buffer, 0, count)
    }
}

/**
 * Index where the policy metas may be inserted.
 * [fileContinues] means bytes exist past [length]. Anything that is not a finished comment or a finished
 * doctype stops the scan, and the metas go there. Inserting early is safe: a tag cannot precede them.
 * Past [HTML_CHARSET_BUDGET] the metas go right after the BOM instead.
 */
private fun skipHtmlPreamble(bytes: ByteArray, length: Int, fileContinues: Boolean): Int {
    val bom = if (length >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
    var i = bom
    var seenDoctype = false
    while (i < length) {
        if (isHtmlSpace(bytes[i])) {
            i++
            continue
        }
        // <!--> and <!---> are complete empty comments. Treating them as the start of a longer comment
        // swallows the tag that follows, and that tag opens the body before our meta is seen.
        if (regionStartsWith(bytes, length, i, COMMENT_EMPTY_DASH)) {
            i += COMMENT_EMPTY_DASH.size
            continue
        }
        if (regionStartsWith(bytes, length, i, COMMENT_EMPTY)) {
            i += COMMENT_EMPTY.size
            continue
        }
        if (regionStartsWith(bytes, length, i, COMMENT_OPEN)) {
            val end = commentEnd(bytes, length, i)
            if (end < 0) return placeMeta(bom, i)
            i = end
            continue
        }
        if (!seenDoctype && regionStartsWithIgnoreCase(bytes, length, i, DOCTYPE_TOKEN)) {
            val end = doctypeEnd(bytes, length, i)
            if (end < 0) return placeMeta(bom, i)
            seenDoctype = true
            i = end
            continue
        }
        if (fileContinues && isPartialPreambleToken(bytes, length, i)) return placeMeta(bom, i)
        return placeMeta(bom, i)
    }
    return placeMeta(bom, i)
}

/** [at] when it still leaves the charset meta inside the prescan window, otherwise just after the BOM. */
private fun placeMeta(bom: Int, at: Int): Int = if (at > HTML_CHARSET_BUDGET) bom else at

/**
 * Index just after a comment that opened at [openAt], or -1 when the comment is not safely skippable.
 * A `>` before `-->` or `--!>` is ambiguous, so the caller inserts in front of the comment instead of
 * burying the meta inside it. An unclosed comment is the same.
 */
private fun commentEnd(bytes: ByteArray, length: Int, openAt: Int): Int {
    var i = openAt + COMMENT_OPEN.size
    while (i < length) {
        if (regionStartsWith(bytes, length, i, COMMENT_END_BANG)) return i + COMMENT_END_BANG.size
        if (regionStartsWith(bytes, length, i, COMMENT_CLOSE)) return i + COMMENT_CLOSE.size
        if (bytes[i] == '>'.code.toByte()) return -1
        i++
    }
    return -1
}

/** Index just after the first `>` of a doctype, or -1 when [length] ends first. Quotes do not protect a `>`. */
private fun doctypeEnd(bytes: ByteArray, length: Int, start: Int): Int {
    var i = start + DOCTYPE_TOKEN.size
    while (i < length) {
        if (bytes[i] == '>'.code.toByte()) return i + 1
        i++
    }
    return -1
}

private fun isPartialPreambleToken(bytes: ByteArray, length: Int, start: Int): Boolean {
    if (regionStartsWithIgnoreCase(bytes, length, start, DOCTYPE_TOKEN)) return false
    if (regionStartsWith(bytes, length, start, COMMENT_OPEN)) return false
    return properPrefix(bytes, start, length, DOCTYPE_TOKEN) || properPrefix(bytes, start, length, COMMENT_OPEN)
}

private fun properPrefix(bytes: ByteArray, start: Int, length: Int, token: ByteArray): Boolean {
    val n = length - start
    if (n <= 0 || n >= token.size) return false
    for (k in 0 until n) {
        if (asciiLower(bytes[start + k]) != asciiLower(token[k])) return false
    }
    return true
}

private fun regionStartsWith(bytes: ByteArray, length: Int, start: Int, token: ByteArray): Boolean {
    if (start + token.size > length) return false
    for (k in token.indices) if (bytes[start + k] != token[k]) return false
    return true
}

private fun regionStartsWithIgnoreCase(bytes: ByteArray, length: Int, start: Int, token: ByteArray): Boolean {
    if (start + token.size > length) return false
    for (k in token.indices) if (asciiLower(bytes[start + k]) != asciiLower(token[k])) return false
    return true
}

private fun asciiLower(b: Byte): Byte {
    val c = b.toInt() and 0xFF
    return if (c in 'A'.code..'Z'.code) (c + 32).toByte() else b
}

private fun isHtmlSpace(b: Byte): Boolean {
    val c = b.toInt() and 0xFF
    return c == 0x09 || c == 0x0A || c == 0x0C || c == 0x0D || c == 0x20
}

private fun containsNul(bytes: ByteArray, length: Int): Boolean {
    for (i in 0 until length) if (bytes[i] == 0.toByte()) return true
    return false
}

private fun readFully(input: InputStream, buf: ByteArray): Int {
    var off = 0
    while (off < buf.size) {
        val n = input.read(buf, off, buf.size - off)
        if (n < 0) return off
        off += n
    }
    return off
}

/** Strict UTF-8. An incomplete sequence is held until the next [feed] or rejected at the end. */
private class Utf8Check {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    private var pending = ByteArray(0)

    fun feed(chunk: ByteArray, off: Int, len: Int, end: Boolean): Boolean {
        val input = if (pending.isEmpty()) {
            ByteBuffer.wrap(chunk, off, len)
        } else {
            val merged = ByteArray(pending.size + len)
            pending.copyInto(merged)
            chunk.copyInto(merged, pending.size, off, off + len)
            ByteBuffer.wrap(merged)
        }
        val output = CharBuffer.allocate(maxOf(1, input.remaining()))
        return try {
            val result = decoder.decode(input, output, end)
            if (result.isMalformed || result.isUnmappable) return false
            if (!end && input.hasRemaining()) {
                pending = ByteArray(input.remaining())
                input.get(pending)
            } else {
                pending = ByteArray(0)
            }
            if (end) {
                if (input.hasRemaining() || pending.isNotEmpty()) return false
                val flushed = decoder.flush(output)
                !flushed.isMalformed && !flushed.isUnmappable
            } else {
                true
            }
        } catch (e: CharacterCodingException) {
            false
        }
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
