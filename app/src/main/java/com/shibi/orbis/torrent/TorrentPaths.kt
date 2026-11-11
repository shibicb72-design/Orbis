package com.shibi.orbis.torrent

import com.shibi.orbis.downloads.DownloadFileNames
import java.io.File
import java.net.URLDecoder

/**
 * Turns a torrent's attacker-controlled file list into safe local paths. Pure and Android-free.
 *
 * Checked before add (source size), after the metadata arrives (the whole list) and again before publishing (every
 * file still inside its directory). A list that breaks any rule is refused whole, never repaired: one bad path in a
 * torrent is reason enough not to trust the rest.
 */
object TorrentPaths {
    enum class Rejection {
        EMPTY_PATH,
        TRAVERSAL,
        SEPARATOR,
        DRIVE_PREFIX,
        SYMLINK,
        TOO_DEEP,
        TOO_MANY_FILES,
        TOO_MANY_PIECES,
        TOO_LARGE,
        EMPTY_NAME,
    }

    /** Where each file goes. [relativePaths] is '/'-separated below the save directory; null for a pad file. */
    class Plan(
        val displayName: String,
        val relativePaths: List<String?>,
        /** Index -> new path for every file whose safe path differs from the torrent's own. */
        val renames: Map<Int, String>,
        /** Below Download/Orbis/<displayName>/: the path inside the torrent without its top folder. Null for a pad file. */
        val publishPaths: List<String?>,
    ) {
        override fun toString(): String = "Plan(files=${relativePaths.size}, renames=${renames.size})"
    }

    sealed interface Result {
        class Ok(val plan: Plan) : Result

        class Rejected(val reason: Rejection) : Result
    }

    private val DRIVE = Regex("^[A-Za-z]:")
    /** Shown until the metadata names a torrent whose magnet had no usable `dn`. */
    const val UNNAMED = "Torrent"

    /** Null when the source may be handed to the engine; otherwise why it may not. */
    fun checkSource(source: TorrentSource): TorrentErrorKind? =
        when (source) {
            is TorrentSource.Magnet ->
                when {
                    source.uri.length > TorrentLimits.MAX_MAGNET_CHARS -> TorrentErrorKind.UNSAFE_METADATA
                    !source.uri.startsWith("magnet:?", ignoreCase = true) -> TorrentErrorKind.INVALID_SOURCE
                    else -> null
                }
            is TorrentSource.TorrentFile ->
                when {
                    source.bytes.size > TorrentLimits.MAX_TORRENT_FILE_BYTES -> TorrentErrorKind.UNSAFE_METADATA
                    source.bytes.isEmpty() -> TorrentErrorKind.INVALID_SOURCE
                    else -> null
                }
            TorrentSource.FromResumeData -> null
        }

    /** The magnet's `dn` made safe, for the list until the metadata names the torrent; null when absent or unusable. */
    fun magnetDisplayName(uri: String): String? {
        val query = uri.substringAfter('?', "")
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0 || !part.substring(0, eq).equals("dn", ignoreCase = true)) continue
            val decoded = runCatching { URLDecoder.decode(part.substring(eq + 1), "UTF-8") }.getOrNull() ?: continue
            return DownloadFileNames.safeName(decoded).takeIf { it.isNotEmpty() }
        }
        return null
    }

    fun plan(files: TorrentFiles): Result {
        if (files.files.size > TorrentLimits.MAX_FILES) return Result.Rejected(Rejection.TOO_MANY_FILES)
        if (files.pieceCount > TorrentLimits.MAX_PIECES) return Result.Rejected(Rejection.TOO_MANY_PIECES)
        if (files.totalBytes > TorrentLimits.MAX_TOTAL_BYTES || files.totalBytes < 0) return Result.Rejected(Rejection.TOO_LARGE)
        val name = DownloadFileNames.safeName(files.name)
        if (name.isEmpty()) return Result.Rejected(Rejection.EMPTY_NAME)
        if (files.files.isEmpty()) return Result.Rejected(Rejection.EMPTY_PATH)
        for (f in files.files) check(f)?.let { return Result.Rejected(it) }

        // A raw folder must map to the same safe folder in every file, so folders are keyed by their raw path.
        val assigned = HashMap<String, String>()
        val used = HashMap<String, MutableSet<String>>()
        val relative = ArrayList<String?>(files.files.size)
        val renames = LinkedHashMap<Int, String>()
        files.files.forEachIndexed { index, f ->
            if (f.isPadFile) {
                relative += null
                return@forEachIndexed
            }
            val out = ArrayList<String>(f.pathSegments.size)
            f.pathSegments.forEachIndexed { i, raw ->
                // NUL cannot occur in a segment (checked above), so it is a safe separator for the key.
                val kind = if (i == f.pathSegments.lastIndex) "f" else "d"
                val key = kind + "\u0000" + f.pathSegments.subList(0, i + 1).joinToString("\u0000")
                // Files and folders share one name space per folder: a file never takes a folder's name.
                val siblings = used.getOrPut(out.joinToString("/").lowercase()) { HashSet() }
                val safe =
                    assigned.getOrPut(key) {
                        unique(DownloadFileNames.safeName(raw).ifEmpty { DownloadFileNames.FALLBACK_NAME }, siblings)
                    }
                out += safe
            }
            val path = out.joinToString("/")
            relative += path
            if (path != f.pathSegments.joinToString("/")) renames[index] = path
        }

        val real = files.files.filterNot { it.isPadFile }
        val top = real.firstOrNull()?.pathSegments?.firstOrNull()
        val hasTopFolder = top != null && real.all { it.pathSegments.size > 1 && it.pathSegments[0] == top }
        val publish = relative.map { p -> p?.let { if (hasTopFolder) it.substringAfter('/') else it } }
        return Result.Ok(Plan(name, relative, renames, publish))
    }

    /** Every planned file resolves below [saveDir] (symlinks on disk included). */
    fun allInside(saveDir: File, plan: Plan): Boolean =
        plan.relativePaths.all { p -> p == null || DownloadFileNames.isInside(saveDir, File(saveDir, p)) }

    /** The MediaStore relative directory and file name for one published file. */
    fun publishTarget(displayName: String, publishPath: String): Pair<String, String> {
        val dir = publishPath.substringBeforeLast('/', "")
        val file = publishPath.substringAfterLast('/')
        val base = "Orbis/$displayName"
        return Pair(if (dir.isEmpty()) base else "$base/$dir", file)
    }

    private fun check(f: TorrentFileEntry): Rejection? {
        if (f.isSymlink) return Rejection.SYMLINK
        if (f.pathSegments.isEmpty()) return Rejection.EMPTY_PATH
        if (f.pathSegments.size > TorrentLimits.MAX_DEPTH) return Rejection.TOO_DEEP
        for (s in f.pathSegments) {
            if (s.isEmpty()) return Rejection.EMPTY_PATH
            if (s == "." || s == "..") return Rejection.TRAVERSAL
            if (s.indexOf('/') >= 0 || s.indexOf('\\') >= 0 || s.indexOf('\u0000') >= 0) return Rejection.SEPARATOR
            if (DRIVE.containsMatchIn(s)) return Rejection.DRIVE_PREFIX
        }
        return null
    }

    private fun unique(base: String, used: MutableSet<String>): String {
        var name = base
        var n = 1
        while (name.lowercase() in used) {
            name = DownloadFileNames.withCounter(base, n++)
        }
        used += name.lowercase()
        return name
    }
}
