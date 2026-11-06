package com.shibi.orbis.tabs

import com.shibi.orbis.engine.BrowserSession
import com.shibi.orbis.engine.IncomingDownload
import com.shibi.orbis.engine.SessionHost
import com.shibi.orbis.engine.SessionInit
import com.shibi.orbis.engine.SessionState
import com.shibi.orbis.engine.isWebUrl
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
)

/** What the tab switcher draws. */
data class TabUi(val id: Long, val title: String, val url: String, val selected: Boolean)

/** One-shot messages for the UI. */
enum class TabEvent { TAB_LIMIT }

/** A download waiting for the user's yes/no. Exactly one is held at a time. */
data class PendingDownload(val download: IncomingDownload, val sourceTabId: Long)

/** A long-press on a link in tab [tabId]. */
data class LinkMenuRequest(val url: String, val tabId: Long)

/**
 * Owns the tabs and their sessions. Main thread only (it is the [SessionHost] of every session it creates).
 *
 * It never opens or loads anything by itself: a session is created only by [openSelected], [select], [newTab],
 * [openExternal] or a popup, all of which the UI calls once the browser screen exists. That keeps the V1 guarantee of
 * no network before the onboarding decision, even though the manager is built at app start.
 */
class TabManager(
    private val factory: (SessionInit) -> BrowserSession,
    private val store: TabStore,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxTabs: Int = MAX_TABS,
    private val persistDebounceMillis: Long = PERSIST_DEBOUNCE_MILLIS,
) : SessionHost {
    private val list = ArrayList<Tab>()
    private var selectedId: Long
    private var nextId = 1L

    /** True until anything changes the tab list; only then may a restore replace the placeholder tab. */
    private var pristine = true

    /** False while the app is in the background: even the selected session is then inactive. */
    private var foreground = true

    private val observers = HashMap<Long, Job>()
    private var persistJob: Job? = null

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

    private val _linkMenu = MutableStateFlow<LinkMenuRequest?>(null)
    val linkMenu: StateFlow<LinkMenuRequest?> = _linkMenu.asStateFlow()

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
        }
    }

    private fun snapshot(): TabsSnapshot =
        TabsSnapshot(
            tabs = list.map { PersistedTab(it.id, it.url, it.title, it.desktopMode, it.session?.savedState() ?: it.savedState) },
            selectedId = selectedId,
        )

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
        _tabs.value = list.map { TabUi(it.id, it.title, it.url, it.id == selectedId) }
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
                factory(SessionInit(tab.desktopMode, tab.savedState, tab.url.takeIf { isWebUrl(it) }))
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
        if (tab.url == url && tab.title == title && tab.desktopMode == st.desktopMode) return
        list[i] = tab.copy(url = url, title = title, desktopMode = st.desktopMode)
        publish()
        schedulePersist()
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
        list.firstOrNull { it.id == selectedId }?.session?.setActive(value)
    }

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

    /**
     * Opens [url] from another app in a new tab, never in the tab the user is looking at. The one exception is a
     * selected tab that is still completely blank and unloaded: it is used instead of leaving an empty tab behind.
     */
    fun openExternal(url: String) {
        if (!isWebUrl(url)) return
        val current = list.firstOrNull { it.id == selectedId }
        if (current != null && isBlank(current)) {
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

    private fun createTab(url: String?, openerId: Long?, external: Boolean): Long? {
        if (list.size >= maxTabs) {
            notifyLimit()
            return null
        }
        val id = nextId++
        list.firstOrNull { it.id == selectedId }?.session?.setActive(false)
        list += Tab(id, null, "", "", false, null, openerId)
        selectedId = id
        val session = ensureSession(id)?.session
        session?.setActive(foreground)
        if (session != null && url != null && isWebUrl(url)) {
            if (external) session.loadExternalUrl(url) else session.loadUrl(url)
        }
        changed()
        return id
    }

    fun close(id: Long) {
        val index = indexOf(id)
        if (index < 0) return
        val tab = list[index]
        observers.remove(id)?.cancel()
        tab.session?.let {
            it.host = null
            it.close()
        }
        _pendingDownload.value?.takeIf { it.sourceTabId == id }?.let {
            it.download.discard()
            _pendingDownload.value = null
        }
        if (_linkMenu.value?.tabId == id) _linkMenu.value = null
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
                        ?: list[minOf(index, list.lastIndex)]
                selectedId = next.id
                ensureSession(next.id)?.session?.setActive(foreground)
            }
        }
        changed()
    }

    // ---- downloads and link menu ---------------------------------------------------------------------------

    /** The user answered the download dialog (the caller already started or discarded it). */
    fun resolveDownload(pending: PendingDownload) {
        if (_pendingDownload.value == pending) _pendingDownload.value = null
    }

    fun dismissLinkMenu() {
        _linkMenu.value = null
    }

    /** Long-press "open in new tab" from the link menu. */
    fun openLinkInNewTab(request: LinkMenuRequest) {
        _linkMenu.value = null
        if (!isWebUrl(request.url)) return
        createTab(request.url, openerId = request.tabId, external = false)
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
        createTab(url, openerId = openerId, external = false)
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
        )
        selectedId = id
        observe(id, child)
        child.setActive(foreground)
        changed()
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
        _pendingDownload.value = PendingDownload(download, list[i].id)
    }

    override fun onLinkContextMenu(url: String, session: BrowserSession) {
        val i = indexOfSession(session)
        if (i < 0 || list[i].id != selectedId) return
        _linkMenu.value = LinkMenuRequest(url, list[i].id)
    }

    override fun onSessionStateChanged(session: BrowserSession) {
        if (indexOfSession(session) >= 0) schedulePersist()
    }
}
