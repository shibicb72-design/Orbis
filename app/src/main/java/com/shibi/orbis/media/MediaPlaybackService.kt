package com.shibi.orbis.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import com.shibi.orbis.MainActivity
import com.shibi.orbis.OrbisApp
import com.shibi.orbis.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps page media playing with the app in the background and shows its controls in a media notification (also on the
 * lock screen and for headset buttons). Runs only while a tab has active media; a private tab is shown only as
 * "Playing in a private tab". Framework MediaSession and Notification.MediaStyle, no extra library.
 */
class MediaPlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null
    private var session: MediaSession? = null
    private var foreground = false

    private val center: MediaCenter? get() = (application as? OrbisApp)?.container?.media

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        session =
            MediaSession(this, "orbis").apply {
                setCallback(
                    object : MediaSession.Callback() {
                        override fun onPlay() { center?.play() }

                        override fun onPause() { center?.pause() }

                        override fun onStop() { center?.stop() }
                    },
                )
                isActive = true
            }
        watch =
            scope.launch {
                center?.current?.collectLatest { tab ->
                    if (tab == null) {
                        stopSelfCleanly()
                    } else {
                        show(tab)
                    }
                }
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> center?.play()
            ACTION_PAUSE -> center?.pause()
            ACTION_STOP -> center?.stop()
        }
        // A start through startForegroundService must reach startForeground in time, even when the media already ended.
        val tab = center?.current?.value
        if (tab != null) {
            show(tab)
        } else {
            runCatching {
                startForeground(
                    NOTIFICATION_ID,
                    Notification.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(getString(R.string.media_playing)).build(),
                )
            }
            stopSelfCleanly()
        }
        return START_NOT_STICKY
    }

    private fun show(tab: MediaTab) {
        val (title, text) = mediaNotificationText(tab, getString(R.string.media_private), getString(R.string.media_playing))
        val s = session ?: return
        s.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).putString(MediaMetadata.METADATA_KEY_ARTIST, text).build())
        val state = if (tab.media.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (tab.media.playing) 1f else 0f)
                .build(),
        )
        val toggle =
            if (tab.media.playing) {
                Notification.Action.Builder(android.R.drawable.ic_media_pause, getString(R.string.media_pause), action(ACTION_PAUSE)).build()
            } else {
                Notification.Action.Builder(android.R.drawable.ic_media_play, getString(R.string.media_play), action(ACTION_PLAY)).build()
            }
        val stop = Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.media_stop), action(ACTION_STOP)).build()
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val n =
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(tab.media.playing)
                // Private on the lock screen for every tab, like the download notifications: a page title can be as
                // revealing as an address. The lock screen gets a generic line instead.
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    Notification.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_media_play)
                        .setContentTitle(getString(R.string.media_playing)).build(),
                )
                .addAction(toggle)
                .addAction(stop)
                .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1))
                .build()
        if (tab.media.playing || !foreground) {
            runCatching {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                foreground = true
            }.onFailure { stopSelfCleanly() }
        } else {
            // Paused: the notification stays (so playback can resume) but the service no longer pins the process.
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n)
            stopForeground(STOP_FOREGROUND_DETACH)
            foreground = false
        }
    }

    private fun action(a: String): PendingIntent =
        PendingIntent.getService(this, a.hashCode(), Intent(this, MediaPlaybackService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)

    private fun stopSelfCleanly() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        foreground = false
        stopSelf()
    }

    override fun onDestroy() {
        watch?.cancel()
        scope.cancel()
        session?.release()
        session = null
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "media"
        private const val NOTIFICATION_ID = 7301
        private const val ACTION_PLAY = "com.shibi.orbis.media.PLAY"
        private const val ACTION_PAUSE = "com.shibi.orbis.media.PAUSE"
        private const val ACTION_STOP = "com.shibi.orbis.media.STOP"

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.media_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
            )
        }

        /** Starts (or nudges) the service; called when a tab starts playing. Failure (background start limits) is harmless. */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, MediaPlaybackService::class.java)) }
        }
    }
}
