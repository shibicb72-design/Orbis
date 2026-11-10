package com.shibi.orbis.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.shibi.orbis.MainActivity
import com.shibi.orbis.R

/**
 * Media notification for a DLNA session, and for a YouTube Cast session whose receiver does not publish standard media
 * status. A direct Google Cast playback uses the Cast SDK notification instead, so this one is not shown then.
 * A private tab is named only as a private tab: the page title is not put on the shade or the lock screen.
 */
internal object CastNotifications {
    const val ACTION_SHOW = "com.shibi.orbis.cast.SHOW"
    const val ACTION_PLAY = "com.shibi.orbis.cast.PLAY"
    const val ACTION_PAUSE = "com.shibi.orbis.cast.PAUSE"
    const val ACTION_STOP = "com.shibi.orbis.cast.STOP"
    private const val CHANNEL_ID = "cast"
    private const val NOTIFICATION_ID = 7302

    fun show(context: Context, deviceName: String, title: String, playing: Boolean) {
        ensureChannel(context)
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).setAction(ACTION_SHOW).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val playPause = if (playing) ACTION_PAUSE else ACTION_PLAY
        val playPauseLabel = context.getString(if (playing) R.string.cast_pause else R.string.cast_play)
        val notification =
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title.ifBlank { context.getString(R.string.cast_notification_casting) })
                .setContentText(deviceName)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    Notification.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_media_play)
                        .setContentTitle(context.getString(R.string.cast_notification_casting))
                        .build(),
                )
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(action(context, playPause, playPauseLabel))
                .addAction(action(context, ACTION_STOP, context.getString(R.string.cast_disconnect)))
                .build()
        context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun action(context: Context, action: String, label: String): Notification.Action {
        val intent = Intent(context, CastActionReceiver::class.java).setAction(action)
        val pending =
            PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Action.Builder(0, label, pending).build()
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.cast_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }
}
