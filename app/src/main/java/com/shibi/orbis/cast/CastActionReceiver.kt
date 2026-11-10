package com.shibi.orbis.cast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shibi.orbis.OrbisApp

/** Play, pause and disconnect from the cast notification. The intent carries no page address. */
class CastActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cast = (context.applicationContext as? OrbisApp)?.container?.cast ?: return
        when (intent.action) {
            CastNotifications.ACTION_PLAY -> cast.play()
            CastNotifications.ACTION_PAUSE -> cast.pause()
            CastNotifications.ACTION_STOP -> cast.disconnect()
        }
    }
}
