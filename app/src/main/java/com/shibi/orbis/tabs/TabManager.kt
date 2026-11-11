package com.shibi.orbis.tabs

import com.shibi.orbis.engine.BrowserSession
import com.shibi.orbis.engine.ContextElementInfo
import com.shibi.orbis.engine.IncomingDownload
import com.shibi.orbis.engine.PageContextMenu
import com.shibi.orbis.engine.PageContextType
import com.shibi.orbis.engine.SessionHost
import com.shibi.orbis.engine.SessionInit
import com.shibi.orbis.engine.SessionState
import com.shibi.orbis.engine.isExternalScheme
import com.shibi.orbis.engine.isImportLoadUrl
import com.shibi.orbis.engine.isWebUrl
import com.shibi.orbis.engine.pageContextMenu
import com.shibi.orbis.engine.schemeOf
import com.shibi.orbis.torrent.IntakeLog
import com.shibi.orbis.torrent.IntakeRequest
import com.shibi.orbis.torrent.TorrentIntake
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Over this many tabs, new ones are refused (memory: every open tab is a Gecko session). */
const val MAX_TABS = 50

private const val PERSIST_DEBOUNCE_MILLIS = 1_000L

/**
 * One tab. [session] is null for a tab restored from disk that has not been shown yet: only the selected tab is
 * opened after a process death. [url] and [title] mirror the session (or the stored values while it is null).
 * [savedState] is the stored engine blob, kept until the session has produced a fresher one.
 */
data class Tab(
    val id: Long,
    val session: BrowserSession?,
    val title: String,
    val url: String,
    val desktopMode: Boolean,
    val savedState: String?,
    val openerTabId: Long?,
    /**
     * A private tab. Invariant: never persisted, never restored, never reported to history. Fixed for the life of the
     * tab (a tab id never changes mode), and inherited by every tab it opens.
     */
    val isPrivate: Boolean = false,
)

/** What the tab switcher draws. */
data class TabUi(val id: Long, val title: String, val url: String, val selected: Boolean, val isPrivate: Boolean = false)

/** One-shot messages for the UI. */
enum class TabEvent { TAB_LIMIT }

/** A closed normal tab that can be reopened with its back/forward history. Memory only; never a private tab. */
data class ClosedTab(val url: String, val title: String, val desktopMode: Boolean, val savedState: String?)

/** How many closed tabs are kept for reopening (like Chrome and Firefox). */
const val RECENTLY_CLOSED_LIMIT = 25

/** A download waiting for the user's yes/no. Exactly one is held at a time. */
data class PendingDownload(val download: IncomingDownload, val sourceTabId: Long, val isPrivate: Boolean = false)

/**
 * A torrent waiting for the confirm sheet. Exactly one is held at a time. [sourceTabId] is the tab it came from, or
 * null when another app handed it over (it is then never private).
 */
data class PendingTorrentIntake(val request: IntakeRequest, val sourceTabId: Long?)

/** A long-press on a link in tab [tabId]; [isPrivate] is that tab's mode, so a tab opened from it matches. */
data class LinkMenuRequest(
    /** The link, or the image itself when the long-pressed image is not a link ([hasLink] false). */
    val url: String,
    val tabId: Long,
    val isPrivate: Boolean = false,
    /** Set when an image was long-pressed. */
    val imageUrl: String? = null,
    val hasLink: Boolean = true,
    /** Visible text of the link, when it is not just the address again. */
    val linkText: String? = null,
)

/** The long-press menu for [this], including magnet: and other non-http links. */
internal fun LinkMenuRequest.pageMenu(): PageContextMenu? =
    pageContextMenu(
        ContextElementInfo(
            type = if (imageUrl != null) PageContextType.IMAGE else PageContextType.OTHER,
            linkUri = if (hasLink) url else null,
            srcUri = imageUrl,
            title = null,
            altText = null,
            linkText = linkText,
        ),
    )

/**
 * Owns the tabs and their sessions. Main thread only (it is the [SessionHost] of every session it creates).
 *
 * It never opens or loads anything by itself: a session is created only by [openSelected], [select], [newTab],
 * [openExternal], [openImported] or a popup, all of which the UI calls once the browser screen exists. That keeps the
 * V1 guarantee of no network before the onboarding decision, even though the manager is built at app start.
 */
class TabManager(
    private val factory: (SessionInit) -> BrowserSession,
    private val store: TabStore,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxTabs: Int = MAX_TABS,
    private val persistDebounceMillis: Long = PERSIST_DEBOUNCE_MILLIS,
    private val visits: VisitSink = VisitSink.None,
    /** Called once when the last private tab has closed, so the engine can drop what the private set stored. */
    private val onPrivateBrowsingEnded: () -> Unit = {},
    /** A single private tab was closed. Set after construction; Cast drops that tab's in-memory target. */
    var onPrivateTabDisposed: ((Long) -> Unit)? = null,
    /**
     * Deletes one imported document's directory once no open tab can show it again. Called when the tab that
     * opened it closes, and when a navigation leaves it with no back/forward history that could return to it.
     */
    private val releaseImport: (String) -> Unit = {},
    /** The torrent log (intake lines only: kinds, enum names, booleans). */
    private val torrentLog: (String) -> Unit = {},
) : SessionHost {
    private val list = ArrayList<Tab>()
    private var selectedId: Long
    private var nextId = 1L

    /** The normal tab the user last looked at; what a saved snapshot selects while a private tab is selected. */
    private var lastNormalSelectedId: Long? = null

    /** True until anything changes the tab list; only then may a restore replace the placeholder tab. */
    private var pristine = true

    /** False while the app is in the background: even the selected session is then inactive. */
    private var foreground = true

    private val observers = HashMap<Long, Job>()
    private var persistJob: Job? = null

    /** Import URLs a tab has shown. Removed when the tab closes or history can no longer reach them. */
    private val importsShown = HashMap<Long, MutableSet<String>>()

    /** Last history flags seen for a tab, so a url change is not treated as "cannot go back" before Gecko says so. */
    private val lastCanGoBack = HashMap<Long, Boolean>()
    private val lastCanGoForward = HashMap<Long, Boolean>()

    /**
     * True after the committed URL changes until [SessionState.canGoBack] or [SessionState.canGoForward] changes.
     * The URL is written first and the old flags are still in that emission, so they must not decide a delete.
     */
    private val historyPending = HashMap<Long, Boolean>()

    /**
     * The import each tab was asked to load and has not committed yet. History cannot reach it, but the load is about
     * to read it, so [pruneUnreachableImports] must leave it alone. On a cold start Gecko's content process takes about
     * two seconds, and the session reports progress (url still empty) all that time; deleting the file then made the
     * load fail with ERROR_FILE_NOT_FOUND. Cleared when the URL commits; a load that never commits keeps the file until
     * the tab closes (or the 24 h sweep).
     */
    private val importLoading = HashMap<Long, String>()

    /** Snapshots queue here and one writer coroutine saves them in order, so an old write can never land last. */
    private val writes = Channel<TabsSnapshot>(Channel.CONFLATED)
    private var writerStarted = false

    private val _ready = MutableStateFlow(false)

    /** False until the stored tabs have been read. The UI waits for this before touching tabs. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _tabs = MutableStateFlow<List<TabUi>>(emptyList())
    val tabs: StateFlow<List<TabUi>> = _tabs.asStateFlow()

    private val _selected: MutableStateFlow<Tab>

    /** The selected tab; its [Tab.session] is null only until [openSelected] has run. */
    val selected: StateFlow<Tab>

    private val _events = Channel<TabEvent>(Channel.BUFFERED)
    val events: Flow<TabEvent> = _events.receiveAsFlow()

    private val _pendingDownload = MutableStateFlow<PendingDownload?>(null)
    val pendingDownload: StateFlow<PendingDownload?> = _pendingDownload.asStateFlow()

    private val _pendingTorrentIntake = MutableStateFlow<PendingTorrentIntake?>(null)

    /** The torrent the confirm sheet asks about. Only the sheet's Download button adds it. */
    val pendingTorrentIntake: StateFlow<PendingTorrentIntake?> = _pendingTorrentIntake.asStateFlow()

    private val _recentlyClosed = MutableStateFlow<List<ClosedTab>>(emptyList())

    /** Normal tabs closed this session, newest first. Cleared with history or with the open tabs. */
    val recentlyClosed: StateFlow<List<ClosedTab>> = _recentlyClosed.asStateFlow()

    private val _linkMenu = MutableStateFlow<LinkMenuRequest?>(null)
    val linkMenu: StateFlow<LinkMenuRequest?> = _linkMenu.asStateFlow()

    private val externalLinkFailuresChannel = Channel<String>(Channel.BUFFERED)

    /** A link no installed app could open. The browser offers to copy it. */
    val externalLinkFailures: Flow<String> = externalLinkFailuresChannel.receiveAsFlow()

    init {
        val first = Tab(nextId++, null, "", "", false, null, null)
        list += first
        selectedId = first.id
        _selected = MutableStateFlow(first)
        selected = _selected.asStateFlow()
        publish()
    }

    // ---- restore / persist ---------------------------------------------------------------------------------

    /**
     * Reads the stored tabs (off the main thread) and installs them without creating any session. A missing or
     * corrupt file leaves the one blank tab. Safe to call once at startup.
     */
    fun restore() {
        scope.launch {
            val snapshot =
                try {
                    withContext(io) { store.load() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("tab restore failed: ${e.javaClass.simpleName}")
                    null
                }
            if (pristine && snapshot != null && snapshot.tabs.isNotEmpty()) {
                list.clear()
                for (p in snapshot.tabs) {
                    list += Tab(p.id, null, p.title, p.url, p.desktop, p.savedState, null)
                }
                selectedId = snapshot.selectedId
                nextId = maxOf(nextId, (snapshot.tabs.maxOf { it.id }) + 1)
                publish()
                log("restored ${list.size} tabs")
            }
            _ready.value = true
            // The tabs changed before the stored list arrived (for example they were all closed): the file still holds
            // the old ones, so replace them now rather than at the next change.
            if (!pristine) enqueueWrite()
        }
    }

    private fun snapshot(): TabsSnapshot = persistableSnapshot(list, selectedId, lastNormalSelectedId)

    private fun enqueueWrite() {
        // Before the stored list has been read, the in-memory one is just a placeholder: never overwrite with it.
        if (!_ready.value) return
        if (!writerStarted) {
            writerStarted = true
            scope.launch(io) {
                for (snap in writes) {
                    try {
                        store.save(snap)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("tab save failed: ${e.javaClass.simpleName}")
                    }
                }
            }
        }
        writes.trySend(snapshot())
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(persistDebounceMillis)
            enqueueWrite()
        }
    }

    /** Writes the tab list now (Activity.onStop). The write itself runs off the main thread. */
    fun flush() {
        persistJob?.cancel()
        persistJob = null
        enqueueWrite()
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private fun indexOf(id: Long) = list.indexOfFirst { it.id == id }

    private fun indexOfSession(session: BrowserSession) = list.indexOfFirst { it.session === session }

    private fun publish() {
        val sel = list.firstOrNull { it.id == selectedId } ?: list.first().also { selectedId = it.id }
        _tabs.value = list.map { TabUi(it.id, it.title, it.url, it.id == selectedId, it.isPrivate) }
        if (!sel.isPrivate) lastNormalSelectedId = sel.id
        _selected.value = sel
    }

    private fun changed() {
        pristine = false
        publish()
        schedulePersist()
    }

    private fun notifyLimit() {
        _events.trySend(TabEvent.TAB_LIMIT)
    }

    /** Creates the session for [id] if it has none yet (restored tabs are lazy). Null if the tab is gone. */
    private fun ensureSession(id: Long): Tab? {
        val i = indexOf(id)
        if (i < 0) return null
        val tab = list[i]
        if (tab.session != null) return tab
        val session =
            try {
                factory(
                    if (tab.isPrivate) {
                        // Never restored from disk: a private tab is not stored, so there is nothing to restore.
                        SessionInit(desktopMode = tab.desktopMode, isPrivate = true)
                    } else {
                        SessionInit(tab.desktopMode, tab.savedState, tab.url.takeIf { isWebUrl(it) })
                    },
                )
            } catch (e: Exception) {
                // Leave the tab session-less; the UI shows an empty page rather than crashing.
                log("session create failed: ${e.javaClass.simpleName}")
                return tab
            }
        session.host = this
        val opened = tab.copy(session = session)
        list[i] = opened
        observe(id, session)
        return opened
    }

    private fun observe(id: Long, session: BrowserSession) {
        observers[id]?.cancel()
        observers[id] = scope.launch {
            session.state.collect { onSessionState(id, it) }
        }
    }

    private fun onSessionState(id: Long, st: SessionState) {
        val i = indexOf(id)
        if (i < 0) return
        val tab = list[i]
        // A fresh session reports an empty url/title until its first page: keep the stored ones (they are the
        // fallback if the app is killed before then).
        val url = st.url.ifEmpty { tab.url }
        val title = st.title ?: tab.title
        val urlChanged = url != tab.url
        val historyChanged = lastCanGoBack[id] != st.canGoBack || lastCanGoForward[id] != st.canGoForward
        if (urlChanged) historyPending[id] = true
        if (historyChanged) historyPending[id] = false
        lastCanGoBack[id] = st.canGoBack
        lastCanGoForward[id] = st.canGoForward
        if (isImportLoadUrl(url)) rememberImport(id, url)
        if (importLoading[id] == st.url) importLoading.remove(id)
        if (tab.url != url || tab.title != title || tab.desktopMode != st.desktopMode) {
            list[i] = tab.copy(url = url, title = title, desktopMode = st.desktopMode)
            publish()
            if (!tab.isPrivate) schedulePersist()
        }
        pruneUnreachableImports(id, url, st)
    }

    private fun rememberImport(id: Long, url: String) {
        if (!isImportLoadUrl(url)) return
        importsShown.getOrPut(id) { LinkedHashSet() }.add(url)
    }

    /**
     * Drops import files this tab can no longer reach: the current page is the only history entry, and that was
     * reported by a history-flag change rather than by the stale flags that ride along with a new URL. Back to the
     * file stays possible until then, so the bytes stay too.
     */
    private fun pruneUnreachableImports(id: Long, url: String, st: SessionState) {
        if (st.canGoBack || st.canGoForward) return
        if (historyPending[id] == true) return
        val shown = importsShown[id] ?: return
        val drop = shown.filter { it != url && it != importLoading[id] }
        if (drop.isEmpty()) return
        shown.removeAll(drop.toSet())
        for (old in drop) releaseIfUnused(old)
    }

    private fun releaseTabImports(id: Long) {
        val mine = importsShown.remove(id).orEmpty()
        importLoading.remove(id)
        lastCanGoBack.remove(id)
        lastCanGoForward.remove(id)
        historyPending.remove(id)
        for (url in mine) releaseIfUnused(url)
    }

    private fun releaseIfUnused(url: String) {
        if (importsShown.values.any { url in it }) return
        releaseImport(url)
    }

    private fun isBlank(tab: Tab): Boolean {
        if (tab.savedState != null) return false
        val st = tab.session?.state?.value
        return (st?.url ?: tab.url).let { it.isEmpty() || it == "about:blank" } && st?.isLoading != true
    }

    // ---- tab operations ------------------------------------------------------------------------------------

    /**
     * Opens the selected tab's session if it has none (restore after process death) and marks it active.
     * Idempotent. The UI calls this once the browser screen is on screen.
     */
    fun openSelected() {
        val tab = ensureSession(selectedId) ?: return
        tab.session?.setActive(foreground)
        publish()
    }

    /**
     * Called from Activity.onStart/onStop. An invisible session should be inactive so its page does not keep running
     * at foreground priority. Every other operation here activates the selected session only while [foreground].
     */
    fun setForeground(value: Boolean) {
        foreground = value
        val session = list.firstOrNull { it.id == selectedId }?.session
        // Background playback: a tab playing media stays active when the app goes to the background, so the audio (or a
        // picture-in-picture video) keeps going. Paused or silent pages are put to sleep as before.
        if (!value && session?.state?.value?.media?.playing == true) return
        session?.setActive(value)
    }

    /** The open session of tab [id], or null when it has none yet (restored, not visited). */
    fun sessionOf(id: Long): BrowserSession? = list.firstOrNull { it.id == id }?.session

    fun select(id: Long) {
        if (indexOf(id) < 0) return
        if (id != selectedId) {
            list.firstOrNull { it.id == selectedId }?.session?.setActive(false)
            selectedId = id
        }
        ensureSession(id)?.session?.setActive(foreground)
        changed()
    }

    /** A new blank tab (or [url]) that becomes the selected one. Null if the tab cap stopped it. */
    fun newTab(url: String? = null): Long? = createTab(url, openerId = null, external = false)

    /** A new private tab (or [url]) that becomes the selected one. Null if the tab cap stopped it. */
    fun newPrivateTab(url: String? = null): Long? = createTab(url, openerId = null, external = false, isPrivate = true)

    /**
     * Opens [url] from another app in a new tab, never in the tab the user is looking at. The one exception is a
     * selected tab that is still completely blank and unloaded: it is used instead of leaving an empty tab behind.
     * A blank private tab is never used: a link from another app always lands in a normal tab.
     */
    fun openExternal(url: String) {
        if (!isWebUrl(url)) return
        val current = list.firstOrNull { it.id == selectedId }
        if (current != null && !current.isPrivate && isBlank(current)) {
            val session = ensureSession(current.id)?.session
            if (session != null) {
                session.setActive(foreground)
                session.loadExternalUrl(url)
                changed()
                return
            }
        }
        createTab(url, openerId = null, external = true)
    }

    /**
     * Opens a document copied from another app in a new normal tab. Same blank-tab rule as [openExternal]: a loaded
     * tab is left alone, and a blank private tab is never reused. [url] must already be an import-cache file URL;
     * the session refuses anything that is not actually inside that cache. False when nothing was opened (the
     * caller deletes the copy).
     */
    fun openImported(url: String): Boolean {
        if (!isImportLoadUrl(url)) return false
        val current = list.firstOrNull { it.id == selectedId }
        if (current != null && !current.isPrivate && isBlank(current)) {
            val session = ensureSession(current.id)?.session
            if (session != null) {
                session.setActive(foreground)
                importLoading[current.id] = url
                session.loadImportedFile(url)
                rememberImport(current.id, url)
                changed()
                return true
            }
        }
        if (list.size >= maxTabs) {
            notifyLimit()
            return false
        }
        val id = nextId++
        list.firstOrNull { it.id == selectedId }?.session?.setActive(false)
        list += Tab(id, null, "", "", false, null, null, isPrivate = false)
        selectedId = id
        val session = ensureSession(id)?.session
        session?.setActive(foreground)
        if (session != null) importLoading[id] = url
        session?.loadImportedFile(url)
        rememberImport(id, url)
        changed()
        return true
    }

    /**
     * Loads [url] in the selected tab (a bookmark or history row the user tapped). Web URLs only. A restored tab that
     * has no session yet gets one first.
     */
    fun loadInSelected(url: String) {
        if (!isWebUrl(url)) return
        val session = ensureSession(selectedId)?.session ?: return
        session.setActive(foreground)
        session.loadUrl(url)
        changed()
    }

    private fun createTab(url: String?, openerId: Long?, external: Boolean, isPrivate: Boolean = false): Long? {
        if (list.size >= maxTabs) {
            notifyLimit()
            return null
        }
        val id = nextId++
        list.firstOrNull { it.id == selectedId }?.session?.setActive(false)
        list += Tab(id, null, "", "", false, null, openerId, isPrivate)
        selectedId = id
        val session = ensureSession(id)?.session
        session?.setActive(foreground)
        if (session != null && url != null && isWebUrl(url)) {
            if (external) session.loadExternalUrl(url) else session.loadUrl(url)
        }
        changed()
        return id
    }

    /** Ends [tab]'s session and drops what the UI still holds for it (a pending download, its link menu, its file). */
    private fun dispose(tab: Tab) {
        val privateId = tab.id.takeIf { tab.isPrivate }
        observers.remove(tab.id)?.cancel()
        tab.session?.let {
            it.host = null
            it.close()
        }
        // The session is closed, so its back/forward list is gone. The file goes with it unless another tab still
        // has it. A recently-closed entry is only stored for web URLs, so reopening cannot need this file.
        releaseTabImports(tab.id)
        _pendingDownload.value?.takeIf { it.sourceTabId == tab.id }?.let {
            it.download.discard()
            _pendingDownload.value = null
        }
        _pendingTorrentIntake.value?.takeIf { it.sourceTabId == tab.id }?.let {
            (it.request as? IntakeRequest.TorrentResponse)?.download?.discard()
            _pendingTorrentIntake.value = null
            torrentLog(IntakeLog.CANCELLED)
        }
        if (_linkMenu.value?.tabId == tab.id) _linkMenu.value = null
        if (privateId != null) onPrivateTabDisposed?.invoke(privateId)
    }

    fun close(id: Long) {
        val index = indexOf(id)
        if (index < 0) return
        val tab = list[index]
        if (!tab.isPrivate && isWebUrl(tab.url)) {
            val saved = runCatching { tab.session?.savedState() }.getOrNull() ?: tab.savedState
            val entry = ClosedTab(tab.url, tab.title, tab.desktopMode, saved)
            _recentlyClosed.value = (listOf(entry) + _recentlyClosed.value).take(RECENTLY_CLOSED_LIMIT)
        }
        dispose(tab)
        list.removeAt(index)
        when {
            list.isEmpty() -> {
                // Closing the last tab leaves one fresh blank tab instead of an empty window.
                val fresh = Tab(nextId++, null, "", "", false, null, null)
                list += fresh
                selectedId = fresh.id
                ensureSession(fresh.id)?.session?.setActive(foreground)
            }
            id == selectedId -> {
                val next =
                    list.firstOrNull { it.id == tab.openerTabId }
                        ?: nearestOfMode(index, tab.isPrivate)
                        // The last private tab: back to the normal tab the user came from, if it is still open.
                        ?: lastNormalSelectedId?.takeIf { tab.isPrivate }?.let { last -> list.firstOrNull { it.id == last } }
                        ?: list[minOf(index, list.lastIndex)]
                selectedId = next.id
                ensureSession(next.id)?.session?.setActive(foreground)
            }
        }
        changed()
        if (tab.isPrivate && list.none { it.isPrivate }) endPrivateBrowsing()
    }

    /** The tab of the given mode nearest to where a closed tab was: searching forward from [index], then backward. */
    private fun nearestOfMode(index: Int, isPrivate: Boolean): Tab? {
        for (i in index until list.size) if (list[i].isPrivate == isPrivate) return list[i]
        for (i in minOf(index, list.size) - 1 downTo 0) if (list[i].isPrivate == isPrivate) return list[i]
        return null
    }

    /**
     * Opens [entry] again as a new selected tab, with its back/forward history, and takes it off the recently closed
     * list. Null at the tab cap.
     */
    fun reopen(entry: ClosedTab): Long? {
        if (list.size >= maxTabs) {
            notifyLimit()
            return null
        }
        _recentlyClosed.value = _recentlyClosed.value - entry
        val id = nextId++
        list.firstOrNull { it.id == selectedId }?.session?.setActive(false)
        list += Tab(id, null, entry.title, entry.url, entry.desktopMode, entry.savedState, null)
        selectedId = id
        ensureSession(id)?.session?.setActive(foreground)
        changed()
        return id
    }

    /** Forgets the recently closed tabs (their addresses and history). */
    fun forgetClosed() {
        _recentlyClosed.value = emptyList()
    }

    /** Closes every private tab; the engine's private data is dropped once, with the last of them. */
    fun closeAllPrivate() {
        val ids = list.filter { it.isPrivate }.map { it.id }
        if (ids.isEmpty()) return
        // Back to the normal tab the user came from first, so closing does not step through the private tabs.
        if (list.firstOrNull { it.id == selectedId }?.isPrivate == true) {
            lastNormalSelectedId?.takeIf { indexOf(it) >= 0 }?.let { select(it) }
        }
        for (id in ids) close(id)
    }

    /** True while any private tab is open. */
    val hasPrivateTabs: Boolean get() = list.any { it.isPrivate }

    /**
     * Closes every normal tab (Clear browsing data) and leaves exactly one fresh blank normal tab. Private tabs and their
     * sessions are untouched, and the end-of-private-browsing callback never fires. A selected private tab stays
     * selected, and the blank tab then has no session until it is selected; otherwise the blank tab is selected and
     * opened. The stored tab list is rewritten at once, so no old address stays on disk.
     */
    fun closeAllNormal() {
        val privateSelected = list.firstOrNull { it.id == selectedId }?.isPrivate == true
        forgetClosed()
        for (tab in list.filter { !it.isPrivate }) dispose(tab)
        list.removeAll { !it.isPrivate }
        val fresh = Tab(nextId++, null, "", "", false, null, null)
        list += fresh
        lastNormalSelectedId = fresh.id
        if (!privateSelected) {
            selectedId = fresh.id
            ensureSession(fresh.id)?.session?.setActive(foreground)
        }
        changed()
        // Not ready yet: the write is skipped, and restore() writes once the stored list has been read.
        if (_ready.value) flush()
    }

    private fun endPrivateBrowsing() {
        try {
            onPrivateBrowsingEnded()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("private data clear failed: ${e.javaClass.simpleName}")
        }
    }

    // ---- downloads and link menu ---------------------------------------------------------------------------

    /** The user answered the download dialog (the caller already started or discarded it). */
    fun resolveDownload(pending: PendingDownload) {
        if (_pendingDownload.value == pending) _pendingDownload.value = null
    }

    /**
     * Offers a torrent to the confirm sheet. One at a time: while a sheet (or a download question) is open, another
     * request is dropped (a response's body is discarded) and false is returned.
     */
    fun offerTorrentIntake(request: IntakeRequest, sourceTabId: Long? = null): Boolean {
        if (_pendingTorrentIntake.value != null || _pendingDownload.value != null) {
            (request as? IntakeRequest.TorrentResponse)?.download?.discard()
            torrentLog(IntakeLog.BUSY)
            return false
        }
        _pendingTorrentIntake.value = PendingTorrentIntake(request, sourceTabId)
        torrentLog(IntakeLog.received(request))
        return true
    }

    /** The sheet closed (the caller already added the torrent, or discarded what needed discarding). */
    fun resolveTorrentIntake(pending: PendingTorrentIntake) {
        if (_pendingTorrentIntake.value == pending) _pendingTorrentIntake.value = null
    }

    /** Long-press "Download with Orbis" on a magnet: link. Same sheet as a tapped one, in the tab's mode. */
    fun downloadTorrentLink(request: LinkMenuRequest) {
        _linkMenu.value = null
        if (schemeOf(request.url) != "magnet") return
        if (indexOf(request.tabId) < 0) return
        offerTorrentIntake(IntakeRequest.Magnet(request.url, request.isPrivate), request.tabId)
    }

    fun dismissLinkMenu() {
        _linkMenu.value = null
    }

    /** Long-press "open in new tab" from the link menu. */
    fun openLinkInNewTab(request: LinkMenuRequest) {
        _linkMenu.value = null
        if (!isWebUrl(request.url)) return
        createTab(request.url, openerId = request.tabId, external = false, isPrivate = request.isPrivate)
    }

    /** Long-press "open image in new tab". Same mode as the tab it came from. */
    fun openImageInNewTab(request: LinkMenuRequest) {
        _linkMenu.value = null
        val image = request.imageUrl ?: return
        if (!isWebUrl(image)) return
        createTab(image, openerId = request.tabId, external = false, isPrivate = request.isPrivate)
    }

    /** Long-press "save image": the tab's own session fetches it and offers it like any download. */
    fun saveImage(request: LinkMenuRequest) {
        _linkMenu.value = null
        val image = request.imageUrl ?: return
        val i = indexOf(request.tabId)
        if (i < 0) return
        list[i].session?.saveUrl(image)
    }

    /** Long-press "open in new private tab". Always private, even when the link was on a normal tab. */
    fun openLinkInPrivateTab(request: LinkMenuRequest) {
        _linkMenu.value = null
        if (!isWebUrl(request.url)) return
        createTab(request.url, openerId = request.tabId, external = false, isPrivate = true)
    }

    /** Long-press "download link". Same confirm-and-save path as saving an image. Http(s) only. */
    fun downloadLink(request: LinkMenuRequest) {
        _linkMenu.value = null
        if (!isWebUrl(request.url)) return
        val i = indexOf(request.tabId)
        if (i < 0) return
        list[i].session?.saveUrl(request.url)
    }

    /** Long-press "open with external app" for magnet: and other non-http links. */
    fun openExternalLink(request: LinkMenuRequest) {
        _linkMenu.value = null
        if (isWebUrl(request.url) || !isExternalScheme(request.url)) return
        val i = indexOf(request.tabId)
        if (i < 0) return
        list[i].session?.openExternalScheme(request.url)
    }

    // ---- SessionHost ---------------------------------------------------------------------------------------

    override fun canOpenTab(): Boolean {
        if (list.size < maxTabs) return true
        notifyLimit()
        return false
    }

    override fun onOpenInNewTab(url: String, opener: BrowserSession) {
        if (!isWebUrl(url)) return
        val openerId = indexOfSession(opener).takeIf { it >= 0 }?.let { list[it].id }
        // The session's own flag, so a private opener makes a private tab even if its tab is already gone.
        createTab(url, openerId = openerId, external = false, isPrivate = opener.isPrivate)
    }

    override fun onPopupSession(child: BrowserSession, opener: BrowserSession) {
        if (list.size >= maxTabs) {
            notifyLimit()
            child.close()
            return
        }
        val openerIndex = indexOfSession(opener)
        // The tab holds the only strong reference to the child; Gecko opens it, we never do.
        child.host = this
        val id = nextId++
        list.firstOrNull { it.id == selectedId }?.session?.setActive(false)
        list += Tab(
            id = id,
            session = child,
            title = "",
            url = "",
            desktopMode = child.state.value.desktopMode,
            savedState = null,
            openerTabId = if (openerIndex >= 0) list[openerIndex].id else null,
            isPrivate = child.isPrivate,
        )
        selectedId = id
        observe(id, child)
        child.setActive(foreground)
        changed()
    }

    /**
     * Takes over an OPEN session from outside the tab list (a Custom Tab's "Open in Orbis"): it becomes the selected tab,
     * with its page and history. Refused (false) before the stored tabs are read (adding one then would drop them), at
     * the tab cap, or for a private session.
     */
    fun adoptSession(session: BrowserSession): Boolean {
        if (!_ready.value || session.isPrivate) return false
        if (list.size >= maxTabs) {
            notifyLimit()
            return false
        }
        session.host = this
        val id = nextId++
        list.firstOrNull { it.id == selectedId }?.session?.setActive(false)
        val st = session.state.value
        list += Tab(
            id = id,
            session = session,
            title = st.title.orEmpty(),
            url = st.url,
            desktopMode = st.desktopMode,
            savedState = null,
            openerTabId = null,
            isPrivate = false,
        )
        selectedId = id
        observe(id, session)
        session.setActive(foreground)
        changed()
        return true
    }

    override fun onCloseRequest(session: BrowserSession) {
        val i = indexOfSession(session)
        if (i >= 0) close(list[i].id)
    }

    override fun onDownload(download: IncomingDownload, session: BrowserSession) {
        val i = indexOfSession(session)
        // One question at a time, and only for the tab the user is looking at: a background tab or a page spamming
        // downloads must not fill the disk or stack dialogs.
        if (i < 0 || list[i].id != selectedId || _pendingDownload.value != null) {
            download.discard()
            return
        }
        // A .torrent goes to the torrent sheet instead of being saved as a file.
        if (TorrentIntake.isTorrentResponse(download.contentType, download.contentDisposition, download.url)) {
            offerTorrentIntake(IntakeRequest.TorrentResponse(download, list[i].isPrivate), list[i].id)
            return
        }
        if (_pendingTorrentIntake.value != null) {
            download.discard()
            return
        }
        _pendingDownload.value = PendingDownload(download, list[i].id, list[i].isPrivate)
    }

    override fun onTorrentIntake(request: IntakeRequest, session: BrowserSession): Boolean {
        val i = indexOfSession(session)
        // Like downloads: only the tab the user is looking at may raise the sheet. Handled either way: a page in a
        // background tab gets neither the sheet nor another app.
        if (i < 0 || list[i].id != selectedId) {
            (request as? IntakeRequest.TorrentResponse)?.download?.discard()
            return true
        }
        offerTorrentIntake(request, list[i].id)
        return true
    }

    override fun onExternalAppMissing(url: String) {
        if (url.isNotBlank()) externalLinkFailuresChannel.trySend(url)
    }

    override fun onPageContextMenu(menu: PageContextMenu, session: BrowserSession) {
        val i = indexOfSession(session)
        if (i < 0 || list[i].id != selectedId) return
        val url = menu.linkUrl ?: menu.imageUrl ?: return
        _linkMenu.value =
            LinkMenuRequest(
                url = url,
                tabId = list[i].id,
                isPrivate = list[i].isPrivate,
                imageUrl = menu.imageUrl,
                hasLink = menu.linkUrl != null,
                linkText = menu.linkText,
            )
    }

    override fun onLinkContextMenu(url: String, session: BrowserSession) {
        val i = indexOfSession(session)
        if (i < 0 || list[i].id != selectedId) return
        _linkMenu.value = LinkMenuRequest(url, list[i].id, list[i].isPrivate)
    }

    override fun onImageContextMenu(imageUrl: String, linkUrl: String?, session: BrowserSession) {
        val i = indexOfSession(session)
        if (i < 0 || list[i].id != selectedId) return
        _linkMenu.value =
            LinkMenuRequest(linkUrl ?: imageUrl, list[i].id, list[i].isPrivate, imageUrl = imageUrl, hasLink = linkUrl != null)
    }

    override fun onSessionStateChanged(session: BrowserSession) {
        // A private tab is never stored, so its state changes schedule nothing.
        val i = indexOfSession(session)
        if (i >= 0 && !list[i].isPrivate) schedulePersist()
    }

    // Only sessions this manager still owns: a closed tab's late callback must not add history. Never a private tab's.
    override fun onVisit(session: BrowserSession, url: String, title: String) {
        val i = indexOfSession(session)
        if (i >= 0 && !list[i].isPrivate) visits.onVisit(url, title)
    }

    override fun onVisitTitle(session: BrowserSession, url: String, title: String) {
        val i = indexOfSession(session)
        if (i >= 0 && !list[i].isPrivate) visits.onTitle(url, title)
    }
}

/**
 * What goes to disk for [tabs]: normal tabs only, and the engine state is read from no private session at all. The
 * selection is [selectedId] when that tab is normal, else [lastNormalSelectedId] if it is still listed, else the first
 * normal tab. With no normal tab the snapshot is empty and selects -1; the codec then yields nothing and the next launch
 * starts with one blank tab.
 */
internal fun persistableSnapshot(tabs: List<Tab>, selectedId: Long, lastNormalSelectedId: Long?): TabsSnapshot {
    val normal = tabs.filter { !it.isPrivate }
    if (normal.isEmpty()) return TabsSnapshot(emptyList(), -1L)
    val selected =
        normal.firstOrNull { it.id == selectedId }
            ?: normal.firstOrNull { it.id == lastNormalSelectedId }
            ?: normal.first()
    return TabsSnapshot(
        tabs = normal.map { PersistedTab(it.id, it.url, it.title, it.desktopMode, it.session?.savedState() ?: it.savedState) },
        selectedId = selected.id,
    )
}
