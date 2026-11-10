package com.shibi.orbis.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import com.shibi.orbis.MainActivity
import com.shibi.orbis.OrbisApp
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
    private var mediaSession: MediaSession? = null

    fun show(
        context: Context,
        deviceName: String,
        title: String,
        playing: Boolean,
        positionMs: Long,
        durationMs: Long,
    ) {
        ensureChannel(context)
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).setAction(ACTION_SHOW).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val shownTitle = title.ifBlank { context.getString(R.string.cast_notification_casting) }
        val session = session(context, shownTitle, deviceName, playing, positionMs, durationMs)
        val playPause = if (playing) ACTION_PAUSE else ACTION_PLAY
        val playPauseLabel = context.getString(if (playing) R.string.cast_pause else R.string.cast_play)
        val notification =
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(shownTitle)
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
                .setStyle(
                    Notification.MediaStyle()
                        .setMediaSession(session.sessionToken)
                        .setShowActionsInCompactView(0, 1),
                )
                .addAction(action(context, playPause, playPauseLabel))
                .addAction(action(context, ACTION_STOP, context.getString(R.string.cast_disconnect)))
                .build()
        context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        mediaSession?.let { session ->
            session.isActive = false
            session.release()
        }
        mediaSession = null
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun session(
        context: Context,
        title: String,
        deviceName: String,
        playing: Boolean,
        positionMs: Long,
        durationMs: Long,
    ): MediaSession {
        val current = mediaSession ?: MediaSession(context, "orbis-cast").also { created ->
            created.setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() {
                        cast(context)?.play()
                    }

                    override fun onPause() {
                        cast(context)?.pause()
                    }

                    override fun onStop() {
                        cast(context)?.disconnect()
                    }

                    override fun onSeekTo(pos: Long) {
                        cast(context)?.seekTo(pos / 1000.0)
                    }
                },
            )
            @Suppress("DEPRECATION")
            created.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            mediaSession = created
        }
        current.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, deviceName)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs.coerceAtLeast(0L))
                .build(),
        )
        val state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP
        if (durationMs > 0L) actions = actions or PlaybackState.ACTION_SEEK_TO
        current.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, positionMs.coerceAtLeast(0L), if (playing) 1f else 0f, SystemClock.elapsedRealtime())
                .build(),
        )
        current.isActive = true
        return current
    }

    private fun cast(context: Context) = (context.applicationContext as? OrbisApp)?.container?.cast

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
