package com.shibi.orbis.history

import com.shibi.orbis.storage.AtomicTextFile
import java.io.IOException

/** Where the history lives. Blocking calls: run them off the main thread. */
interface HistoryStore {
    /** Everything stored, or an empty list when nothing usable is (missing or corrupt file). */
    fun load(): List<HistoryEntry>

    fun save(items: List<HistoryEntry>)
}

/**
 * `filesDir/history/history.json`. A file that cannot be read or decoded is renamed to `history.json.corrupt` and the
 * list starts empty; the bad file is never overwritten (if it cannot be moved away, saving is refused).
 */
class FileHistoryStore(private val file: AtomicTextFile) : HistoryStore {
    @Volatile
    private var blocked = false

    override fun load(): List<HistoryEntry> =
        try {
            val text = file.read()
            if (text.isNullOrBlank()) emptyList() else HistoryCodec.decode(text)
        } catch (e: Exception) {
            if (!file.setAside()) blocked = true
            emptyList()
        }

    override fun save(items: List<HistoryEntry>) {
        if (blocked) throw IOException("unreadable history file could not be set aside")
        file.write(HistoryCodec.encode(items))
        // An emptied history means cleared: a file set aside earlier still holds visited addresses.
        if (items.isEmpty()) file.deleteAside()
    }
}
