package com.shibi.orbis.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.MediaIntentReceiver
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.shibi.orbis.MainActivity

/**
 * Google Cast starts on the Default Media Receiver (app id CC1AD845). A YouTube page switches the id to the YouTube
 * receiver for that session only ([com.google.android.gms.cast.framework.CastContext.setReceiverApplicationId]).
 * The Cast session's own notification is the media notification for a Google Cast playback.
 */
class OrbisCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val actions =
            listOf(
                MediaIntentReceiver.ACTION_TOGGLE_PLAYBACK,
                MediaIntentReceiver.ACTION_STOP_CASTING,
            )
        val notification =
            NotificationOptions.Builder()
                .setActions(actions, intArrayOf(0, 1))
                .setTargetActivityClassName(MainActivity::class.java.name)
                .build()
        val media = CastMediaOptions.Builder().setNotificationOptions(notification).build()
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(media)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
