package com.shibi.orbis.history

import com.shibi.orbis.engine.cleanStoredTitle
import com.shibi.orbis.engine.isStorableWebUrl
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val HISTORY_DEBOUNCE_MILLIS = 2_000L

/**
 * The browsing history, one row per URL, newest first. Main thread only.
 *
 * Like the bookmarks, [load] reads the stored list once; changes made before it finishes are applied to the in-memory
 * view and queued, then replayed in order on top of the stored list. So a clear-all made at startup wipes what was
 * stored, and a visit made at startup is never lost. Nothing is written before the load has finished.
 *
 * Visits and title updates are written after a short quiet period (a page can report many in a row). Deletes and
 * clears are written at once and cancel the pending write, so removed history cannot come back.
 */
class HistoryManager(
    private val store: HistoryStore,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val now: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMillis: Long = HISTORY_DEBOUNCE_MILLIS,
) {
    /** Newest first. */
    private var items = ArrayList<HistoryEntry>()

    private var loadStarted = false
    private var loaded = false
    private val queued = ArrayList<Change>()

    private var persistJob: Job? = null

    /** Snapshots queue here and one writer coroutine saves them in order, so an old write can never land last. */
    private val writes = Channel<List<HistoryEntry>>(Channel.CONFLATED)
    private var writerStarted = false

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    private sealed interface Change {
        class Visit(val url: String, val title: String, val at: Long) : Change
        class Title(val url: String, val title: String) : Change
        class Delete(val url: String) : Change
        object Clear : Change
    }

    // ---- load / persist ------------------------------------------------------------------------------------

    /** Reads the stored history off the main thread, replays queued changes on it and publishes it. Call once. */
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
                    log("history load failed: ${e.javaClass.simpleName}")
                    emptyList()
                }
            items = ArrayList(stored)
            val replay = ArrayList(queued)
            queued.clear()
            loaded = true
            for (change in replay) apply(change)
            publish()
            log("history loaded ${stored.size}")
            if (replay.any { it is Change.Delete || it is Change.Clear }) {
                writeNow()
            } else if (replay.isNotEmpty()) {
                schedulePersist()
            }
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
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("history save failed: ${e.javaClass.simpleName}")
                    }
                }
            }
        }
        writes.trySend(items.toList())
    }

    private fun schedulePersist() {
        if (!loaded) return
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(debounceMillis)
            persistJob = null
            enqueueWrite()
        }
    }

    private fun writeNow() {
        persistJob?.cancel()
        persistJob = null
        enqueueWrite()
    }

    /** Writes the history now (Activity.onStop). The write itself runs off the main thread. */
    fun flush() = writeNow()

    private fun publish() {
        _entries.value = items.toList()
    }

    // ---- changes -------------------------------------------------------------------------------------------

    /** A page was visited. Non-storable URLs are refused. A blank [title] keeps the title the row already had. */
    fun recordVisit(url: String, title: String) {
        if (!isStorableWebUrl(url)) return
        val change = Change.Visit(url, cleanStoredTitle(title), now())
        apply(change)
        publish()
        if (loaded) schedulePersist() else queued += change
    }

    /** Sets the title of an existing row. Never creates a row, never moves one; a blank title is ignored. */
    fun updateTitle(url: String, title: String) {
        val clean = cleanStoredTitle(title)
        if (clean.isEmpty()) return
        // Before the load the stored rows are not known yet, so the change is kept for the replay either way.
        if (loaded) {
            val current = items.firstOrNull { it.url == url } ?: return
            if (current.title == clean) return
        }
        val change = Change.Title(url, clean)
        apply(change)
        publish()
        if (loaded) schedulePersist() else queued += change
    }

    fun delete(url: String) {
        if (loaded && items.none { it.url == url }) return
        val change = Change.Delete(url)
        apply(change)
        publish()
        if (loaded) writeNow() else queued += change
    }

    fun clearAll() {
        val change = Change.Clear
        apply(change)
        publish()
        if (loaded) writeNow() else queued += change
    }

    private fun apply(change: Change) {
        when (change) {
            is Change.Visit -> {
                val i = items.indexOfFirst { it.url == change.url }
                val oldTitle = if (i >= 0) items.removeAt(i).title else ""
                items.add(0, HistoryEntry(change.url, change.title.ifEmpty { oldTitle }, change.at))
                while (items.size > MAX_HISTORY_ENTRIES) items.removeAt(items.lastIndex)
            }

            is Change.Title -> {
                val i = items.indexOfFirst { it.url == change.url }
                if (i >= 0) items[i] = items[i].copy(title = change.title)
            }

            is Change.Delete -> items.removeAll { it.url == change.url }

            Change.Clear -> items.clear()
        }
    }
}
