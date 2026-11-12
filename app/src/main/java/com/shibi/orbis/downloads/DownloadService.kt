package com.shibi.orbis.downloads

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.shibi.orbis.OrbisApp

/**
 * Keeps the process alive while downloads run (dataSync foreground service, same process). It owns no download
 * state: [DownloadForegroundController] decides when it runs and tells it what to show.
 *
 * Rules that keep it from crashing the app:
 * - Every ACTION_START calls startForeground first, even with an empty queue, then stops itself.
 * - Only this service stops itself, with stopSelfResult(startId) so a newer start wins. Nobody calls stopService.
 * - ACTION_CANCEL comes from a notification, not startForegroundService, so it owes no startForeground.
 * - Android 15 calls onTimeout for dataSync after 6h: it must stop promptly.
 */
class DownloadService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastWakeLockAt = 0L
    private var lastStartId = 0

    /** Set once a stop was requested; nothing may be posted after that or the notification would be orphaned. */
    private var stopping = false
    private var foregrounded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_CANCEL) handleCancel(intent, startId) else handleStart(startId)
        return START_NOT_STICKY
    }

    private fun handleStart(startId: Int) {
        val controller = controller()
        try {
            ServiceCompat.startForeground(
                this,
                DownloadNotifications.ONGOING_ID,
                DownloadNotifications.ongoing(this, controller?.currentModel()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException is an IllegalStateException; catching the parent also
            // covers any other refusal of the same type.
            startRefused(controller, startId)
            return
        } catch (e: SecurityException) {
            startRefused(controller, startId)
            return
        }
        foregrounded = true
        stopping = false
        acquireWakeLock()
        if (controller == null) {
            // No container (should not happen): we are foreground, so we may leave again.
            stopFromController()
            return
        }
        controller.onServiceStarted(this)
    }

    private fun startRefused(controller: DownloadForegroundController?, startId: Int) {
        controller?.onStartFailed()
        stopping = true
        stopSelfResult(startId)
    }

    private fun handleCancel(intent: Intent, startId: Int) {
        val controller = controller()
        val ids = intent.getLongArrayExtra(EXTRA_IDS)
        if (controller != null && ids != null) ids.forEach { controller.cancelDownload(it) }
        // Not started through startForegroundService, so there is nothing to enter; just do not linger.
        if (controller == null || !controller.isRunning()) {
            stopping = true
            stopSelfResult(startId)
        }
    }

    /** Controller asks for a stop: it is the service that ends itself, and only up to the newest start it has seen. */
    internal fun stopFromController() {
        stopping = true
        releaseWakeLock()
        stopSelfResult(lastStartId)
    }

    @SuppressLint("MissingPermission")
    internal fun showProgress(model: DownloadNotificationModel) {
        if (stopping || !foregrounded) return
        runCatching {
            NotificationManagerCompat.from(this).notify(DownloadNotifications.ONGOING_ID, DownloadNotifications.ongoing(this, model))
        }
    }

    /** Called when bytes arrive. A stalled transfer stops renewing, so the lock lapses after [WAKE_LOCK_TIMEOUT_MS]. */
    internal fun renewWakeLock() {
        if (stopping || !foregrounded) return
        // Progress ticks every 250ms and each acquire is a binder call; the timeout is minutes, so renew rarely.
        if (SystemClock.elapsedRealtime() - lastWakeLockAt < WAKE_LOCK_RENEW_MS) return
        acquireWakeLock()
    }

    // Overrides the API 35 callback; the system calls it only on API 35+, older devices never see it.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopping = true
        controller()?.onTimeout(this)
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Plain stopSelf: the system requires the service to stop now, whatever else is queued.
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        foregrounded = false
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        controller()?.onServiceDestroyed(this)
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        runCatching {
            val lock =
                wakeLock
                    ?: getSystemService(PowerManager::class.java)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                        .apply { setReferenceCounted(false) }
                        .also { wakeLock = it }
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            lastWakeLockAt = SystemClock.elapsedRealtime()
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    // The container exists only in the main process after OrbisApp.onCreate; the service runs there.
    private fun controller(): DownloadForegroundController? = (application as? OrbisApp)?.downloadForeground

    companion object {
        const val ACTION_START = "com.shibi.orbis.downloads.START"
        const val ACTION_CANCEL = "com.shibi.orbis.downloads.CANCEL"
        const val EXTRA_IDS = "ids"
        private const val WAKE_LOCK_TAG = "orbis:download"
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = 60 * 1000L
    }
}
