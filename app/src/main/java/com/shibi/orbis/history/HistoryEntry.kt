package com.shibi.orbis.history

/** One visited page. [url] is the key (one row per URL); [visitedAt] is epoch milliseconds of the latest visit. */
data class HistoryEntry(val url: String, val title: String, val visitedAt: Long)

/** Past this many rows the oldest are dropped. */
const val MAX_HISTORY_ENTRIES = 5_000
