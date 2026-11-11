package com.shibi.orbis.torrent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import com.shibi.orbis.MainActivity
import com.shibi.orbis.R

/**
 * Android-only half of the torrent notification: one ongoing summary. VISIBILITY_PRIVATE with a generic public
 * version, and a private torrent is never named (see [TorrentNotificationModel]). Every PendingIntent is FLAG_IMMUTABLE.
 */
object TorrentNotifications {
    const val CHANNEL_ID = "torrents"

    /** Downloads use 1; media and cast use 73xx. */
    const val ONGOING_ID = 2

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
                .setContentIntent(openAppIntent(context))
        val percent = model?.percent
        builder.setProgress(100, percent ?: 0, model == null || percent == null)
        if (model != null) {
            val single = model.ids.singleOrNull()
            builder.addAction(0, context.getString(if (single != null) R.string.torrents_pause else R.string.torrents_pause_all), actionIntent(context, TorrentService.ACTION_PAUSE, single))
            if (single != null) builder.addAction(0, context.getString(R.string.torrents_cancel), actionIntent(context, TorrentService.ACTION_CANCEL, single))
        }
        return builder.build()
    }

    private fun publicVersion(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.downloads_notif_public_title))
            .setContentText(context.getString(R.string.torrents_notif_public_text))
            .build()

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, REQUEST_OPEN, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** No [id]: the action applies to every active torrent. Extras are replaced on each update (FLAG_UPDATE_CURRENT). */
    private fun actionIntent(context: Context, action: String, id: String?): PendingIntent {
        val intent = Intent(context, TorrentService::class.java).setAction(action)
        if (id != null) intent.putExtra(TorrentService.EXTRA_ID, id)
        val request = if (action == TorrentService.ACTION_CANCEL) REQUEST_CANCEL else REQUEST_PAUSE
        return PendingIntent.getService(context, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private const val REQUEST_OPEN = 20
    private const val REQUEST_PAUSE = 21
    private const val REQUEST_CANCEL = 22
}
