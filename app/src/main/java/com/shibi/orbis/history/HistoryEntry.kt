package com.shibi.orbis.history

/**
 * One visited page. [url] is the key (one row per URL); [visitedAt] is epoch milliseconds of the latest visit; [visits]
 * counts visits (at least 1, capped at [MAX_VISIT_COUNT]) and ranks Top sites on the start page.
 */
data class HistoryEntry(val url: String, val title: String, val visitedAt: Long, val visits: Int = 1)

/** Visit counts stop here; enough to rank, small enough never to overflow. */
const val MAX_VISIT_COUNT = 100_000

/** Past this many rows the oldest are dropped. */
const val MAX_HISTORY_ENTRIES = 5_000
