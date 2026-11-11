package com.shibi.orbis.torrent

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.shibi.orbis.BuildConfig
import com.shibi.orbis.OrbisApp

/**
 * Keeps the process alive while torrents run (dataSync foreground service, same process); the pattern of
 * downloads/DownloadService. It owns no torrent state: [TorrentForegroundController] decides when it runs.
 *
 * - ACTION_START (and the debug add) call startForeground first, always, then may stop again.
 * - Only this service stops itself (stopSelfResult with the newest start id). Nobody calls stopService.
 * - PAUSE / RESUME / CANCEL come from the notification (startService), so they owe no startForeground.
 * - Android 15+ calls onTimeout for dataSync: resume data is saved, everything pauses, the service stops at once.
 */
class TorrentService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastWakeLockAt = 0L
    private var lastStartId = 0
    private var stopping = false
    private var foregrounded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TorrentNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_PAUSE, ACTION_RESUME, ACTION_CANCEL -> handleAction(intent, startId)
            ACTION_DEBUG_ADD -> {
                // Came through startForegroundService (adb), so it owes startForeground like ACTION_START.
                if (handleStart(startId) && BuildConfig.DEBUG) {
                    intent.getStringExtra(EXTRA_MAGNET)?.let { controller()?.debugAdd(it) }
                }
            }
            else -> handleStart(startId)
        }
        return START_NOT_STICKY
    }

    /** False when the service could not enter the foreground (and is stopping). */
    private fun handleStart(startId: Int): Boolean {
        val controller = controller()
        try {
            ServiceCompat.startForeground(
                this,
                TorrentNotifications.ONGOING_ID,
                TorrentNotifications.ongoing(this, controller?.currentModel()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) is an IllegalStateException.
            startRefused(controller, startId)
            return false
        } catch (e: SecurityException) {
            startRefused(controller, startId)
            return false
        }
        foregrounded = true
        stopping = false
        acquireWakeLock()
        if (controller == null) {
            stopFromController()
            return false
        }
        controller.onServiceStarted(this)
        return true
    }

    private fun startRefused(controller: TorrentForegroundController?, startId: Int) {
        controller?.onStartFailed()
        stopping = true
        stopSelfResult(startId)
    }

    private fun handleAction(intent: Intent, startId: Int) {
        val controller = controller()
        val id = intent.getStringExtra(EXTRA_ID)
        if (controller != null) {
            when (intent.action) {
                ACTION_PAUSE -> controller.pause(id)
                ACTION_RESUME -> if (id != null) controller.resume(id)
                ACTION_CANCEL -> if (id != null) controller.cancel(id)
            }
        }
        if (controller == null || !controller.isRunning()) {
            stopping = true
            stopSelfResult(startId)
        }
    }

    internal fun stopFromController() {
        stopping = true
        releaseWakeLock()
        stopSelfResult(lastStartId)
    }

    @SuppressLint("MissingPermission")
    internal fun showProgress(model: TorrentNotificationModel) {
        if (stopping || !foregrounded) return
        runCatching {
            NotificationManagerCompat.from(this).notify(TorrentNotifications.ONGOING_ID, TorrentNotifications.ongoing(this, model))
        }
    }

    /** Called when bytes arrive; a stalled torrent stops renewing, so the lock lapses after [WAKE_LOCK_TIMEOUT_MS]. */
    internal fun renewWakeLock() {
        if (stopping || !foregrounded) return
        if (SystemClock.elapsedRealtime() - lastWakeLockAt < WAKE_LOCK_RENEW_MS) return
        acquireWakeLock()
    }

    // Overrides the API 35 callback; the system calls it only on API 35+.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopping = true
        controller()?.onTimeout(this)
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
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

    private fun controller(): TorrentForegroundController? = (application as? OrbisApp)?.torrentForeground

    companion object {
        const val ACTION_START = "com.shibi.orbis.torrent.START"
        const val ACTION_PAUSE = "com.shibi.orbis.torrent.PAUSE"
        const val ACTION_RESUME = "com.shibi.orbis.torrent.RESUME"
        const val ACTION_CANCEL = "com.shibi.orbis.torrent.CANCEL"

        /**
         * Debug builds only (BuildConfig.DEBUG): adds [EXTRA_MAGNET] as a normal torrent, for emulator checks. The
         * service is not exported, so the shell needs root (`adb root`, then
         * `adb shell am start-foreground-service -n com.shibi.orbis/.torrent.TorrentService -a <this> --es magnet '<uri>'`).
         */
        const val ACTION_DEBUG_ADD = "com.shibi.orbis.torrent.DEBUG_ADD"
        const val EXTRA_ID = "id"
        const val EXTRA_MAGNET = "magnet"
        private const val WAKE_LOCK_TAG = "orbis:torrent"
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = 60 * 1000L
    }
}
