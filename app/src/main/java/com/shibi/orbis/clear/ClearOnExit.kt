package com.shibi.orbis.clear

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How long the engine part of the wipe may take before Orbis goes on without its confirmation. */
const val CLEAR_ON_EXIT_ENGINE_TIMEOUT_MILLIS = 10_000L

/** What only clear on exit reaches into. The shared parts (history) go through [ClearTargets]. */
interface ClearOnExitHooks {
    /** The process has just started and no tab list was read yet: deletes the stored tab list. */
    suspend fun discardStoredTabs()

    /**
     * The process outlived the closed task: closes every open tab, private ones too, and drops their previews. One blank
     * normal tab is left. Main thread.
     */
    fun closeOpenTabs()

    /** Reads the stored tab list; the tab manager's `ready` follows. Called once per process. */
    fun restoreTabs()

    /**
     * Clears the engine's cookies, site data and cache. The cookies of [exceptions] (and their subdomains) are written
     * back afterwards. True only when the engine confirmed the wipe itself; keeping the exceptions may fail without
     * making it false.
     */
    suspend fun clearEngine(exceptions: Set<String>): Boolean
}

/**
 * Settings > Clear on exit. When it is on and Orbis was fully closed (see [UiSessionTracker]), browsing data is wiped
 * at the next start, before the tab list is restored and before any session is created: open tabs, Recently closed,
 * history, cookies, site data and cache. Settings, bookmarks, saved passwords, the Downloads list, torrents, site
 * permissions and add-ons are never touched. Android gives no reliable callback at the moment of closing, so this is the
 * earliest point.
 *
 * Every Orbis activity calls [onActivityCreated] first thing in onCreate and [onActivityDestroyed] in onDestroy.
 * Whatever an activity would open (a link, a file, a Custom Tab page) goes through [afterWipe]. Main thread only.
 *
 * Logs name steps and outcomes only, never an address or a site.
 */
class ClearOnExit(
    /** The setting and its exceptions list; the settings screen reads and writes them here. */
    val prefs: ClearOnExitPrefs,
    private val targets: ClearTargets,
    private val hooks: ClearOnExitHooks,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val engineTimeoutMillis: Long = CLEAR_ON_EXIT_ENGINE_TIMEOUT_MILLIS,
    private val tracker: UiSessionTracker = UiSessionTracker(),
) {
    private var restoreStarted = false
    private val waiting = ArrayList<() -> Unit>()

    private val _wiping = MutableStateFlow(false)

    /** True from the moment a wipe starts until it has finished (or given up on the engine). */
    val wiping: StateFlow<Boolean> = _wiping.asStateFlow()

    /**
     * At process start. With the setting off the tab list is read at once, as without this feature. With it on, the
     * first activity decides: restore, or wipe first.
     */
    fun onAppStart() {
        if (!prefs.enabled) restore()
    }

    /** See [UiSessionTracker.onActivityCreated] for [browserTaskInRecents]. */
    fun onActivityCreated(hasSavedState: Boolean, browserTaskInRecents: Boolean = false) {
        val fresh = tracker.onActivityCreated(hasSavedState, browserTaskInRecents)
        val wipe = fresh && prefs.enabled
        when {
            // A wipe is already running (a link router that finished before the browser came up): it covers this start.
            _wiping.value -> Unit
            wipe -> startWipe(processStarted = !restoreStarted)
            !restoreStarted -> restore()
        }
    }

    fun onActivityDestroyed() = tracker.onActivityDestroyed()

    /** Runs [action] now, or once the running wipe has finished. */
    fun afterWipe(action: () -> Unit) {
        if (_wiping.value) waiting += action else action()
    }

    private fun restore() {
        if (restoreStarted) return
        restoreStarted = true
        hooks.restoreTabs()
    }

    private fun startWipe(processStarted: Boolean) {
        _wiping.value = true
        log("ClearOnExit: wipe started")
        // The process outlived the task: its tabs are live. Closed now, before any screen of this activity shows them.
        if (!processStarted) step("tabs") { hooks.closeOpenTabs() }
        // History, Recently closed and opened files. Queued in the history store if it has not finished loading.
        step("history") { targets.clearHistory() }
        scope.launch {
            var storedTabsGone = !processStarted
            try {
                if (processStarted) storedTabsGone = stepSuspend("tabs") { hooks.discardStoredTabs() }
                val confirmed =
                    try {
                        withTimeoutOrNull(engineTimeoutMillis) { hooks.clearEngine(prefs.exceptions) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("ClearOnExit: engine failed: ${e.javaClass.simpleName}")
                        false
                    }
                // Not confirmed: the next fresh start wipes again (every fresh start with the setting on does).
                log(if (confirmed == true) "ClearOnExit: engine done" else "ClearOnExit: unconfirmed")
            } finally {
                if (processStarted) {
                    // The stored list could not be deleted: a change before the restore makes the tab manager ignore
                    // the stored list and overwrite it, so the old tabs never come back.
                    if (!storedTabsGone) step("tabs") { hooks.closeOpenTabs() }
                    // With the stored list gone, the restore leaves one blank tab and ready becomes true.
                    restore()
                }
                finish()
            }
        }
    }

    private fun finish() {
        _wiping.value = false
        log("ClearOnExit: wipe finished")
        val run = waiting.toList()
        waiting.clear()
        run.forEach { it() }
    }

    private inline fun step(name: String, action: () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("ClearOnExit: $name failed: ${e.javaClass.simpleName}")
        }
    }

    /** True when [action] returned normally. */
    private suspend inline fun stepSuspend(name: String, action: suspend () -> Unit): Boolean =
        try {
            action()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("ClearOnExit: $name failed: ${e.javaClass.simpleName}")
            false
        }
}
