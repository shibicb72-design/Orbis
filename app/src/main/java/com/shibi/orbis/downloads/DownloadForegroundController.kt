package com.shibi.orbis.downloads

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Glue between the download list, [DownloadForegroundPolicy] and [DownloadService]. Lives in OrbisApp; main thread
 * only (app scope is Main). Restored entries never start the service: restore() marks them FAILED, never IN_PROGRESS.
 *
 * Logs carry status only: never names, hosts or URLs.
 */
class DownloadForegroundController(
    private val context: Context,
    private val downloads: DownloadManager,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    now: () -> Long = System::currentTimeMillis,
) {
    private val policy = DownloadForegroundPolicy(now)
    private val settled = DownloadSettledTracker()

    /** The running service instance, set while it is in the foreground. */
    private var service: DownloadService? = null
    private var appForeground = false
    private var model: DownloadNotificationModel? = null
    private var latestEntries: List<DownloadEntry> = emptyList()
    private var receivedSum = 0L
    private var trailing: Job? = null

    fun start() {
        scope.launch { downloads.entries.collect { onEntries(it) } }
    }

    fun onAppForeground() {
        appForeground = true
        execute(policy.onAppForeground())
    }

    fun onAppBackground() {
        appForeground = false
    }

    // ---- download list ----

    private fun onEntries(entries: List<DownloadEntry>) {
        latestEntries = entries
        val newlySettled = settled.update(entries)
        // Only downloads that finished while nobody was looking: the Downloads screen covers the rest.
        if (!appForeground) newlySettled.forEach { DownloadNotifications.postResult(context, it) }

        val active = entries.filter { it.status == DownloadStatus.IN_PROGRESS }
        val m = DownloadNotificationModel.from(entries)
        model = m
        val sum = active.sumOf { it.receivedBytes.coerceAtLeast(0L) }
        if (sum > receivedSum) service?.renewWakeLock()
        receivedSum = sum

        execute(policy.onActive(active.map { it.id }.toSet(), m))
        scheduleTrailing()
    }

    /** A throttled percent change must still reach the notification if no further progress arrives. */
    private fun scheduleTrailing() {
        if (!policy.needsTrailingUpdate()) {
            trailing?.cancel()
            trailing = null
            return
        }
        if (trailing?.isActive == true) return
        trailing =
            scope.launch {
                delay(DownloadForegroundPolicy.THROTTLE_MS + 50)
                trailing = null
                onEntries(latestEntries)
            }
    }

    // ---- commands ----

    private fun execute(commands: List<DownloadFgCommand>) {
        for (c in commands) {
            when (c) {
                DownloadFgCommand.StartService -> startService()
                DownloadFgCommand.StopService -> service?.stopFromController()
                is DownloadFgCommand.UpdateNotification -> service?.showProgress(c.model)
            }
        }
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_START),
            )
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) and BackgroundServiceStartNotAllowedException both
            // extend IllegalStateException. The download carries on without protection.
            startRefused(e)
        } catch (e: SecurityException) {
            startRefused(e)
        }
    }

    private fun startRefused(e: Exception) {
        log("download service start refused: ${e.javaClass.simpleName}")
        onStartFailed()
    }

    // ---- service callbacks (main thread) ----

    internal fun currentModel(): DownloadNotificationModel? = model

    internal fun isRunning(): Boolean = policy.state == DownloadFgState.RUNNING

    internal fun cancelDownload(id: Long) = downloads.cancel(id)

    internal fun onServiceStarted(s: DownloadService) {
        service = s
        log("download service running")
        execute(policy.onServiceStarted())
        scheduleTrailing()
    }

    internal fun onStartFailed() {
        execute(policy.onStartFailed())
    }

    /** The service stops itself on a timeout; the policy only needs to know. */
    internal fun onTimeout(s: DownloadService) {
        log("download service timed out")
        if (service === s) service = null
        policy.onTimeout()
    }

    internal fun onServiceDestroyed(s: DownloadService) {
        // A stale instance (never attached, or already replaced) must not move the state machine.
        if (service != null && service !== s) return
        service = null
        log("download service stopped")
        execute(policy.onServiceDestroyed())
    }
}
