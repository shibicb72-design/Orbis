package com.shibi.orbis.torrent

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shibi.orbis.MainActivity
import com.shibi.orbis.R
import com.shibi.orbis.downloads.DownloadNotifications

/**
 * Android-only half of the torrent notifications: the ongoing summary (id 2), the "paused" one with Resume (id 3) and
 * one result per finished or failed torrent. All VISIBILITY_PRIVATE with a generic public version, and a private
 * torrent is never named (see [TorrentNotificationModel]). A tap opens the Downloads screen. Every PendingIntent is
 * FLAG_IMMUTABLE.
 */
object TorrentNotifications {
    const val CHANNEL_ID = "torrents"

    /** Downloads use 1; media and cast use 73xx. */
    const val ONGOING_ID = 2

    /** Not ongoing: a paused torrent keeps no service running, so its Resume action lives here. */
    const val PAUSED_ID = 3

    private const val RESULT_ID_FLOOR = 3000

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.torrents_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.torrents_notif_channel_description) }
        nm.createNotificationChannel(channel)
    }

    fun ongoing(context: Context, model: TorrentNotificationModel?): Notification {
        val title =
            when {
                model == null -> context.getString(R.string.torrents_notif_starting)
                model.isPrivate && model.isSingle -> context.getString(R.string.torrents_notif_private)
                model.isSingle -> model.name ?: context.getString(R.string.torrents_notif_running)
                model.isPrivate -> context.getString(R.string.torrents_notif_private_many, model.count)
                else -> context.resources.getQuantityString(R.plurals.torrents_notif_many_title, model.count, model.count)
            }
        val text =
            when {
                model == null -> null
                model.percent == null -> context.getString(R.string.torrents_notif_fetching)
                else -> context.getString(R.string.torrents_notif_progress, model.percent, Formatter.formatShortFileSize(context, model.downBps))
            }
        val builder =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion(context))
                .setContentIntent(showDownloadsIntent(context))
        val percent = model?.percent
        builder.setProgress(100, percent ?: 0, model == null || percent == null)
        if (model != null) {
            val single = model.ids.singleOrNull()
            builder.addAction(0, context.getString(if (single != null) R.string.torrents_pause else R.string.torrents_pause_all), actionIntent(context, TorrentService.ACTION_PAUSE, single))
            if (single != null) builder.addAction(0, context.getString(R.string.torrents_cancel), actionIntent(context, TorrentService.ACTION_CANCEL, single))
        }
        return builder.build()
    }

    /** Replaces the previous paused notification: one for every torrent the notification or Android paused. */
    @SuppressLint("MissingPermission")
    fun postPaused(context: Context, model: TorrentPausedModel) {
        // The service may be gone (timeout), and it is what normally creates the channel.
        ensureChannel(context)
        val title =
            when {
                model.isPrivate -> context.getString(R.string.torrents_notif_private_paused)
                model.count == 1 -> context.getString(R.string.torrents_notif_paused)
                else -> context.getString(R.string.torrents_notif_paused_many, model.count)
            }
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_pause)
                .setContentTitle(title)
                .setContentText(if (model.byTimeout) context.getString(R.string.torrents_notif_paused_timeout) else null)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion(context, context.getString(R.string.torrents_notif_public_paused), android.R.drawable.ic_media_pause))
                .setContentIntent(showDownloadsIntent(context))
                .addAction(0, context.getString(R.string.torrents_resume), actionIntent(context, TorrentService.ACTION_RESUME, model.resumeId))
                .build()
        runCatching { NotificationManagerCompat.from(context).notify(PAUSED_ID, notification) }
    }

    fun cancelPaused(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(PAUSED_ID) }
    }

    /** One auto-cancelling notification per settled torrent, on its own id. */
    @SuppressLint("MissingPermission")
    fun postResult(context: Context, entry: TorrentEntry) {
        ensureChannel(context)
        val completed = entry.status == TorrentStatus.COMPLETED
        val content = TorrentResultContent.from(entry)
        val title =
            context.getString(
                when (content.title) {
                    TorrentResultTitle.DONE -> R.string.downloads_notif_done
                    TorrentResultTitle.FAILED -> R.string.downloads_status_failed
                    TorrentResultTitle.PRIVATE_DONE -> R.string.downloads_notif_private_done
                    TorrentResultTitle.PRIVATE_FAILED -> R.string.downloads_notif_private_failed
                },
            )
        val icon = if (completed) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(content.text)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion(context, context.getString(R.string.downloads_notif_done_public), icon))
                .setContentIntent(showDownloadsIntent(context))
                .build()
        // Notifications off or permission denied: the system drops it, the torrent is unaffected.
        runCatching { NotificationManagerCompat.from(context).notify(resultId(entry.id), notification) }
    }

    /** Removes the result notifications of torrents that left the list. */
    fun cancelResults(context: Context, ids: List<String>) {
        if (ids.isEmpty()) return
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            ids.forEach { manager.cancel(resultId(it)) }
        }
    }

    internal fun resultId(id: String): Int = RESULT_ID_FLOOR + (id.hashCode() and 0x3FFFFFFF)

    private fun publicVersion(
        context: Context,
        text: String = context.getString(R.string.torrents_notif_public_text),
        icon: Int = android.R.drawable.stat_sys_download,
    ): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(context.getString(R.string.downloads_notif_public_title))
            .setContentText(text)
            .build()

    /** Opens the Downloads screen, where the torrents are listed. Carries no data. */
    private fun showDownloadsIntent(context: Context): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java)
                .setAction(DownloadNotifications.ACTION_SHOW_DOWNLOADS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, REQUEST_OPEN, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** No [id]: the action applies to every torrent it can. Extras are replaced on each update (FLAG_UPDATE_CURRENT). */
    private fun actionIntent(context: Context, action: String, id: String?): PendingIntent {
        val intent = Intent(context, TorrentService::class.java).setAction(action)
        if (id != null) intent.putExtra(TorrentService.EXTRA_ID, id)
        val request =
            when (action) {
                TorrentService.ACTION_CANCEL -> REQUEST_CANCEL
                TorrentService.ACTION_RESUME -> REQUEST_RESUME
                else -> REQUEST_PAUSE
            }
        return PendingIntent.getService(context, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private const val REQUEST_OPEN = 20
    private const val REQUEST_PAUSE = 21
    private const val REQUEST_CANCEL = 22
    private const val REQUEST_RESUME = 23
}
