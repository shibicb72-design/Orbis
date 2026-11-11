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
        val active = entries.filter { it.status.isActive }
        val m = TorrentNotificationModel.from(entries)
        model = m
        val sum = active.sumOf { it.doneBytes.coerceAtLeast(0L) }
        if (sum > doneSum) service?.renewWakeLock()
        doneSum = sum
        execute(policy.onActive(active.map { it.id }.toSet(), m))
        scheduleTrailing()
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

    /** No id: every active torrent. */
    internal fun pause(id: String?) {
        if (id != null) {
            torrents.pause(id)
        } else {
            latestEntries.filter { it.status.isActive }.forEach { torrents.pause(it.id) }
        }
    }

    internal fun resume(id: String) = torrents.resume(id)

    internal fun cancel(id: String) = torrents.remove(id, deleteFiles = true)

    /** Debug builds only (the service checks BuildConfig.DEBUG). */
    internal fun debugAdd(magnet: String) {
        torrents.add(TorrentSource.Magnet(magnet), isPrivate = false)
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
