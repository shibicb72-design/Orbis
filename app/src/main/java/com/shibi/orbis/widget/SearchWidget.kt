package com.shibi.orbis.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.shibi.orbis.MainActivity
import com.shibi.orbis.R

/** The intent action of the home screen search widget: Orbis opens a new tab with the address bar ready to type in. */
const val ACTION_WIDGET_SEARCH = "com.shibi.orbis.action.WIDGET_SEARCH"

/** Home screen search bar. A tap opens Orbis on a new tab with the keyboard up. No data is shown in or kept by it. */
class SearchWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).setAction(ACTION_WIDGET_SEARCH).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_search)
            views.setOnClickPendingIntent(R.id.widget_search_root, open)
            manager.updateAppWidget(id, views)
        }
    }
}
