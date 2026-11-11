package com.shibi.orbis.torrent

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Glue between the torrent list, [TorrentFgPolicy] and [TorrentService]; the torrent twin of
 * downloads/DownloadForegroundController. Lives in OrbisApp; main thread only (app scope is Main).
 * Logs carry status only.
 */
class TorrentForegroundController(
    private val context: Context,
    private val torrents: TorrentManager,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    now: () -> Long = System::currentTimeMillis,
) {
    private val policy = TorrentFgPolicy(now)
    private var service: TorrentService? = null
    private var model: TorrentNotificationModel? = null
    private var latestEntries: List<TorrentEntry> = emptyList()
    private var doneSum = 0L
    private var trailing: Job? = null
    private val settled = TorrentSettledTracker()

    /** Paused from the notification or by Android's timeout: they get the "paused" notification with Resume. */
    private var pausedIds: Set<String> = emptySet()
    private var pausedByTimeout = false
    private var pausedModel: TorrentPausedModel? = null

    fun start() {
        scope.launch { torrents.entries.collect { onEntries(it) } }
    }

    fun onAppForeground() {
        execute(policy.onAppForeground())
    }

    /** The process may be killed any time after this: resume data goes to disk now. */
    fun onAppBackground() {
        torrents.onServiceStopping()
    }

    private fun onEntries(entries: List<TorrentEntry>) {
        latestEntries = entries
        settled.update(entries).forEach { TorrentNotifications.postResult(context, it) }
        val active = entries.filter { it.status.isActive }
        val m = TorrentNotificationModel.from(entries)
        // The ongoing notification comes back: the paused one has done its job.
        if (m != null && model == null && pausedIds.isNotEmpty()) pausedIds = emptySet()
        model = m
        updatePaused(entries)
        val sum = active.sumOf { it.doneBytes.coerceAtLeast(0L) }
        if (sum > doneSum) service?.renewWakeLock()
        doneSum = sum
        execute(policy.onActive(active.map { it.id }.toSet(), m))
        scheduleTrailing()
    }

    /** Posts, replaces or cancels the paused notification; it follows the list (resume, remove, private browsing end). */
    private fun updatePaused(entries: List<TorrentEntry>) {
        val p = if (pausedIds.isEmpty()) null else TorrentPausedModel.from(entries, pausedIds, pausedByTimeout)
        if (p == null) pausedIds = emptySet()
        if (p == pausedModel) return
        pausedModel = p
        if (p != null) TorrentNotifications.postPaused(context, p) else TorrentNotifications.cancelPaused(context)
    }

    private fun scheduleTrailing() {
        if (!policy.needsTrailingUpdate()) {
            trailing?.cancel()
            trailing = null
            return
        }
        if (trailing?.isActive == true) return
        trailing =
            scope.launch {
                delay(TorrentFgPolicy.THROTTLE_MS + 50)
                trailing = null
                onEntries(latestEntries)
            }
    }

    private fun execute(commands: List<TorrentFgCommand>) {
        for (c in commands) {
            when (c) {
                TorrentFgCommand.StartService -> startService()
                TorrentFgCommand.StopService -> service?.stopFromController()
                is TorrentFgCommand.UpdateNotification -> service?.showProgress(c.model)
            }
        }
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, TorrentService::class.java).setAction(TorrentService.ACTION_START))
        } catch (e: IllegalStateException) {
            startRefused(e)
        } catch (e: SecurityException) {
            startRefused(e)
        }
    }

    private fun startRefused(e: Exception) {
        log("service start refused: ${e.javaClass.simpleName}")
        execute(policy.onStartFailed())
    }

    // ---- service callbacks (main thread) ----

    internal fun currentModel(): TorrentNotificationModel? = model

    internal fun isRunning(): Boolean = policy.state == TorrentFgState.RUNNING

    /** From the notification. No id: every active torrent. The paused notification then carries Resume. */
    internal fun pause(id: String?) {
        val ids = if (id != null) listOf(id) else latestEntries.filter { it.status.isActive || it.status == TorrentStatus.WAITING_FOR_WIFI }.map { it.id }
        ids.forEach { torrents.pause(it) }
        notePaused(ids, byTimeout = false)
    }

    /** From the paused notification. No id: every torrent paused by the user or the timeout (never a queued one). */
    internal fun resume(id: String?) {
        val ids =
            if (id != null) {
                listOf(id)
            } else {
                latestEntries.filter { it.status == TorrentStatus.PAUSED && (it.pauseReason == PauseReason.USER || it.pauseReason == PauseReason.TIMEOUT) }.map { it.id }
            }
        ids.forEach { torrents.resume(it) }
        pausedIds = emptySet()
        updatePaused(latestEntries)
    }

    internal fun cancel(id: String) = torrents.remove(id, deleteFiles = true)

    private fun notePaused(ids: List<String>, byTimeout: Boolean) {
        if (ids.isEmpty()) return
        pausedIds = pausedIds + ids
        pausedByTimeout = byTimeout || (pausedByTimeout && pausedModel != null)
        // Posted once the list shows them paused (the pause runs on the manager's lock).
    }

    /** Debug builds only (the service checks BuildConfig.DEBUG). */
    internal fun debugAdd(magnet: String, isPrivate: Boolean) {
        torrents.add(TorrentSource.Magnet(magnet), isPrivate = isPrivate)
        log("debug add")
    }

    internal fun onServiceStarted(s: TorrentService) {
        service = s
        log("service running")
        execute(policy.onServiceStarted())
        scheduleTrailing()
    }

    internal fun onStartFailed() {
        execute(policy.onStartFailed())
    }

    internal fun onTimeout(s: TorrentService) {
        log("service timed out")
        if (service === s) service = null
        notePaused(latestEntries.filter { it.status.wantsToRun || it.status == TorrentStatus.WAITING_FOR_WIFI }.map { it.id }, byTimeout = true)
        torrents.onTimeout()
        policy.onTimeout()
    }

    internal fun onServiceDestroyed(s: TorrentService) {
        torrents.onServiceStopping()
        if (service != null && service !== s) return
        service = null
        log("service stopped")
        execute(policy.onServiceDestroyed())
    }
}
