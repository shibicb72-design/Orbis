package com.shibi.orbis.bookmarks

import com.shibi.orbis.engine.cleanStoredTitle
import com.shibi.orbis.engine.isStorableWebUrl
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The bookmark list. Main thread only.
 *
 * [load] reads the stored list once at startup. Until it finishes, every change is applied to the in-memory view (so the
 * caller gets an answer at once) and also queued by URL; when the stored list arrives the queue is replayed on top of
 * it, in order. A change made before the load can therefore never be lost or undone by it, and when a URL turns out to
 * be stored already, the stored bookmark wins. Nothing is written before the load has finished.
 */
class BookmarkManager(
    private val store: BookmarkStore,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val now: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Newest first. */
    private var items = ArrayList<Bookmark>()
    private var urls = HashSet<String>()
    private var nextId = 1L

    private var loadStarted = false
    private var loaded = false
    private val queued = ArrayList<Change>()

    /** Snapshots queue here and one writer coroutine saves them in order, so an old write can never land last. */
    private val writes = Channel<List<Bookmark>>(Channel.CONFLATED)
    private var writerStarted = false

    /**
     * Set by [clearAll]: once a write after it has succeeded, the store also deletes any set-aside unreadable copy (it
     * still holds the old addresses). Written on the main thread, read by the writer.
     */
    @Volatile
    private var discardSetAsideAfterWrite = false

    private val _entries = MutableStateFlow<List<Bookmark>>(emptyList())
    val entries: StateFlow<List<Bookmark>> = _entries.asStateFlow()

    /** A change that can be replayed on a list it was not made on, so it is keyed by URL, never by id. */
    private sealed interface Change {
        class Add(val url: String, val title: String, val createdAt: Long) : Change
        class Remove(val url: String) : Change
        class Edit(val oldUrl: String, val title: String, val newUrl: String) : Change
        class Import(val parsed: List<ParsedBookmark>, val at: Long) : Change
        class Move(val url: String, val folder: String) : Change
        object Clear : Change
    }

    // ---- load / persist ------------------------------------------------------------------------------------

    /** Reads the stored list off the main thread, replays queued changes on it and publishes it. Call once. */
    fun load() {
        if (loadStarted) return
        loadStarted = true
        scope.launch {
            val stored =
                try {
                    withContext(io) { store.load() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("bookmarks load failed: ${e.javaClass.simpleName}")
                    emptyList()
                }
            items = ArrayList(stored)
            urls = HashSet<String>().also { set -> stored.forEach { set += it.url } }
            nextId = (stored.maxOfOrNull { it.id } ?: 0L) + 1
            val replay = ArrayList(queued)
            queued.clear()
            loaded = true
            for (change in replay) apply(change)
            publish()
            log("bookmarks loaded ${stored.size}")
            if (replay.isNotEmpty()) enqueueWrite()
        }
    }

    private fun enqueueWrite() {
        // Before the stored list has been read, the in-memory one is just a placeholder: never overwrite with it.
        if (!loaded) return
        if (!writerStarted) {
            writerStarted = true
            scope.launch(io) {
                for (snap in writes) {
                    try {
                        store.save(snap)
                        if (discardSetAsideAfterWrite) {
                            discardSetAsideAfterWrite = false
                            store.discardSetAside()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("bookmarks save failed: ${e.javaClass.simpleName}")
                    }
                }
            }
        }
        writes.trySend(items.toList())
    }

    /** Writes the list now (Activity.onStop). The write itself runs off the main thread. */
    fun flush() = enqueueWrite()

    private fun publish() {
        _entries.value = items.toList()
    }

    /** Publishes and persists after a change, or remembers the change until the load has finished. */
    private fun committed(change: Change) {
        publish()
        if (loaded) enqueueWrite() else queued += change
    }

    // ---- queries -------------------------------------------------------------------------------------------

    /** Exact URL match. */
    fun isBookmarked(url: String): Boolean = url in urls

    // ---- changes -------------------------------------------------------------------------------------------

    fun add(url: String, title: String): AddResult {
        if (!isStorableWebUrl(url)) return AddResult.INVALID
        val change = Change.Add(url, cleanStoredTitle(title).ifEmpty { url }, now())
        val result = applyAdd(change)
        if (result == AddResult.ADDED) committed(change)
        return result
    }

    fun remove(id: Long) {
        val url = items.firstOrNull { it.id == id }?.url ?: return
        val change = Change.Remove(url)
        apply(change)
        committed(change)
    }

    fun removeByUrl(url: String) {
        // Before the load the stored bookmarks are not known yet, so the removal is kept for the replay either way.
        if (loaded && url !in urls) return
        val change = Change.Remove(url)
        apply(change)
        committed(change)
    }

    /** [title] empty falls back to the URL. [url] must already be storable (see parseEditedBookmarkUrl). */
    fun update(id: Long, title: String, url: String): UpdateResult {
        val current = items.firstOrNull { it.id == id } ?: return UpdateResult.MISSING
        if (!isStorableWebUrl(url)) return UpdateResult.INVALID
        if (url != current.url && url in urls) return UpdateResult.DUPLICATE
        val change = Change.Edit(current.url, cleanStoredTitle(title).ifEmpty { url }, url)
        apply(change)
        committed(change)
        return UpdateResult.UPDATED
    }

    /** Files bookmark [id] in [folder] (cleaned; empty takes it out of any folder). */
    fun moveToFolder(id: Long, folder: String) {
        val url = items.firstOrNull { it.id == id }?.url ?: return
        val change = Change.Move(url, cleanFolderName(folder))
        apply(change)
        committed(change)
    }

    /** Adds what is new. URLs already saved (the stored one wins), repeats within [parsed] and overflow are skipped. */
    fun importAll(parsed: ParsedBookmarks): ImportResult {
        val change = Change.Import(parsed.items, now())
        val added = applyImport(change)
        if (added > 0) committed(change)
        return ImportResult(added, parsed.skipped + (parsed.items.size - added))
    }

    /**
     * Removes every bookmark and writes the empty list at once. Made before the load has finished it is replayed on the
     * stored list, so the stored bookmarks go too (and are written over as soon as the load is done).
     */
    fun clearAll() {
        discardSetAsideAfterWrite = true
        apply(Change.Clear)
        committed(Change.Clear)
    }

    // ---- the one place the list is edited -----------------------------------------------------------------

    /** Replays [change] on the current list (the result of the original call was already given). */
    private fun apply(change: Change) {
        when (change) {
            is Change.Add -> applyAdd(change)
            is Change.Remove -> applyRemove(change)
            is Change.Edit -> applyEdit(change)
            is Change.Import -> applyImport(change)
            is Change.Move -> {
                val i = items.indexOfFirst { it.url == change.url }
                if (i >= 0) items[i] = items[i].copy(folder = change.folder)
            }
            Change.Clear -> {
                items.clear()
                urls.clear()
            }
        }
    }

    private fun applyAdd(change: Change.Add): AddResult =
        when {
            change.url in urls -> AddResult.EXISTS
            items.size >= MAX_BOOKMARKS -> AddResult.FULL
            else -> {
                items.add(0, Bookmark(nextId++, change.url, change.title, change.createdAt))
                urls += change.url
                AddResult.ADDED
            }
        }

    private fun applyRemove(change: Change.Remove) {
        val i = items.indexOfFirst { it.url == change.url }
        if (i >= 0) {
            items.removeAt(i)
            urls -= change.url
        }
    }

    private fun applyEdit(change: Change.Edit) {
        val i = items.indexOfFirst { it.url == change.oldUrl }
        if (i < 0 || (change.newUrl != change.oldUrl && change.newUrl in urls)) return
        items[i] = items[i].copy(url = change.newUrl, title = change.title)
        urls -= change.oldUrl
        urls += change.newUrl
    }

    /** Returns how many were added. */
    private fun applyImport(change: Change.Import): Int {
        val fresh = ArrayList<Bookmark>()
        for (p in change.parsed) {
            if (items.size + fresh.size >= MAX_BOOKMARKS) break
            if (!isStorableWebUrl(p.url) || p.url in urls) continue
            urls += p.url
            fresh += Bookmark(
                id = nextId++,
                url = p.url,
                title = cleanStoredTitle(p.title).ifEmpty { p.url },
                createdAt = p.addedAtMillis ?: change.at,
            )
        }
        items.addAll(0, fresh)
        return fresh.size
    }
}
