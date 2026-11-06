package com.shibi.orbis.history

import java.util.Locale

/** History rows whose title or URL contains [query] (trimmed, case-insensitive). A blank query returns everything. */
fun filterHistory(list: List<HistoryEntry>, query: String): List<HistoryEntry> {
    val q = query.trim().lowercase(Locale.ROOT)
    if (q.isEmpty()) return list
    return list.filter {
        it.title.lowercase(Locale.ROOT).contains(q) || it.url.lowercase(Locale.ROOT).contains(q)
    }
}
