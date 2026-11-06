package com.shibi.orbis.bookmarks

/** One saved page. [url] is unique across the list; [createdAt] is epoch milliseconds. */
data class Bookmark(val id: Long, val url: String, val title: String, val createdAt: Long)

/** Over this many bookmarks, adds and imports are refused. */
const val MAX_BOOKMARKS = 10_000

enum class AddResult { ADDED, EXISTS, FULL, INVALID }

enum class UpdateResult { UPDATED, DUPLICATE, INVALID, MISSING }

/** [skipped] counts everything not added: unusable entries, URLs already saved, and what no longer fit. */
data class ImportResult(val added: Int, val skipped: Int)
