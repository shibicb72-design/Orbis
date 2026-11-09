package com.shibi.orbis.bookmarks

/** One saved page. [url] is unique across the list; [createdAt] is epoch milliseconds. */
data class Bookmark(
    val id: Long,
    val url: String,
    val title: String,
    val createdAt: Long,
    /** The folder it is filed in (one level, like Chrome on phones); empty for none. */
    val folder: String = "",
)

/** Longest folder name kept. */
const val MAX_FOLDER_CHARS = 60

/** A folder name as stored: trimmed, single line, capped; empty means no folder. */
fun cleanFolderName(raw: String): String =
    raw.replace(Regex("[\\r\\n\\t]+"), " ").trim().take(MAX_FOLDER_CHARS).trim()

/** The folders in use, sorted (case-insensitive). */
fun folderNames(items: List<Bookmark>): List<String> =
    items.map { it.folder }.filter { it.isNotEmpty() }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

/** Over this many bookmarks, adds and imports are refused. */
const val MAX_BOOKMARKS = 10_000

enum class AddResult { ADDED, EXISTS, FULL, INVALID }

enum class UpdateResult { UPDATED, DUPLICATE, INVALID, MISSING }

/** [skipped] counts everything not added: unusable entries, URLs already saved, and what no longer fit. */
data class ImportResult(val added: Int, val skipped: Int)
