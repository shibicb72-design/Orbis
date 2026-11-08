package com.shibi.orbis.bookmarks

import com.shibi.orbis.storage.AtomicTextFile
import java.io.IOException

/** Where the bookmark list lives. Blocking calls: run them off the main thread. */
interface BookmarkStore {
    /** Everything stored, or an empty list when nothing usable is (missing or corrupt file). */
    fun load(): List<Bookmark>

    fun save(items: List<Bookmark>)

    /**
     * Deletes any copy of an unreadable file that [load] set aside earlier. Called only after the user explicitly cleared
     * all bookmarks, because that copy still holds the old addresses. Ordinary deletes keep it for recovery.
     */
    fun discardSetAside() {}
}

/**
 * `filesDir/bookmarks/bookmarks.json`. A file that cannot be read or decoded is renamed to `bookmarks.json.corrupt` and
 * the list starts empty; the bad file is never overwritten (if it cannot be moved away, saving is refused).
 */
class FileBookmarkStore(private val file: AtomicTextFile) : BookmarkStore {
    @Volatile
    private var blocked = false

    override fun load(): List<Bookmark> =
        try {
            val text = file.read()
            if (text.isNullOrBlank()) emptyList() else BookmarkCodec.decode(text)
        } catch (e: Exception) {
            if (!file.setAside()) blocked = true
            emptyList()
        }

    override fun save(items: List<Bookmark>) {
        if (blocked) throw IOException("unreadable bookmark file could not be set aside")
        file.write(BookmarkCodec.encode(items))
    }

    override fun discardSetAside() = file.deleteAside()
}
