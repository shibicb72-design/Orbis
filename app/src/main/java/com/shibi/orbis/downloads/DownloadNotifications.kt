package com.shibi.orbis.downloads

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shibi.orbis.MainActivity
import com.shibi.orbis.R

/**
 * Android-only half of the download notifications. Every notification is VISIBILITY_PRIVATE with a generic public
 * version, so a file name never shows on the lock screen. Every PendingIntent is FLAG_IMMUTABLE.
 */
object DownloadNotifications {
    const val CHANNEL_ID = "downloads"
    const val ONGOING_ID = 1

    /** Sent to MainActivity by a notification tap; it only navigates to the Downloads screen and carries no data. */
    const val ACTION_SHOW_DOWNLOADS = "com.shibi.orbis.action.SHOW_DOWNLOADS"

    private const val RESULT_ID_FLOOR = 1000

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // Re-creating an existing channel is a no-op, so this is safe to call every time.
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.downloads_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.downloads_notif_channel_description) }
        nm.createNotificationChannel(channel)
    }

    /** The foreground-service notification. A null model (queue already empty) gives a neutral placeholder. */
    fun ongoing(context: Context, model: DownloadNotificationModel?): Notification {
        val title =
            when {
                model == null -> context.getString(R.string.downloads_notif_starting)
                model.isSingle && model.isPrivate -> context.getString(R.string.downloads_notif_private)
                model.isSingle -> model.name ?: context.getString(R.string.downloads_notif_running)
                else -> context.resources.getQuantityString(R.plurals.downloads_notif_many_title, model.count, model.count)
            }
        val builder =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(context.getString(R.string.downloads_notif_running))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion(context, context.getString(R.string.downloads_notif_public_text), ongoing = true))
                .setContentIntent(showDownloadsIntent(context))
        val percent = model?.percent
        builder.setProgress(100, percent ?: 0, model == null || percent == null)
        if (model != null) {
            val label = context.getString(if (model.isSingle) R.string.downloads_cancel else R.string.downloads_cancel_all)
            builder.addAction(0, label, cancelIntent(context, model.ids))
        }
        return builder.build()
    }

    /** One auto-cancelling notification per settled download, on its own id. */
    @SuppressLint("MissingPermission")
    fun postResult(context: Context, entry: DownloadEntry) {
        // The service may never have started (start refused), and it is what normally creates the channel.
        ensureChannel(context)
        val completed = entry.status == DownloadStatus.COMPLETED
        val content = DownloadResultContent.from(entry)
        val title =
            context.getString(
                when (content.title) {
                    DownloadResultTitle.DONE -> R.string.downloads_notif_done
                    DownloadResultTitle.FAILED -> R.string.downloads_status_failed
                    DownloadResultTitle.PRIVATE_DONE -> R.string.downloads_notif_private_done
                    DownloadResultTitle.PRIVATE_FAILED -> R.string.downloads_notif_private_failed
                },
            )
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(if (completed) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(content.text)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion(context, context.getString(R.string.downloads_notif_done_public), ongoing = false))
                .setContentIntent(showDownloadsIntent(context))
                .build()
        // Notifications disabled or permission denied: the system drops it, the download itself is unaffected.
        runCatching { NotificationManagerCompat.from(context).notify(resultId(entry.id), notification) }
    }

    /** Removes the result notifications of cleared downloads (they name the file) and of private ones that left the list. */
    fun cancelResults(context: Context, downloadIds: List<Long>) {
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            downloadIds.forEach { manager.cancel(resultId(it)) }
        }
    }

    private fun resultId(downloadId: Long): Int = RESULT_ID_FLOOR + (downloadId.hashCode() and 0x3FFFFFFF)

    private fun publicVersion(context: Context, text: String, ongoing: Boolean): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (ongoing) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.downloads_notif_public_title))
            .setContentText(text)
            .build()

    private fun showDownloadsIntent(context: Context): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_SHOW_DOWNLOADS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Extras are replaced on every update (FLAG_UPDATE_CURRENT): PendingIntent matching ignores them. */
    private fun cancelIntent(context: Context, ids: LongArray): PendingIntent {
        val intent =
            Intent(context, DownloadService::class.java)
                .setAction(DownloadService.ACTION_CANCEL)
                .putExtra(DownloadService.EXTRA_IDS, ids)
        return PendingIntent.getService(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
