package com.example.floatingclock

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class ShoppingWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.shopping_widget)
        views.setOnClickPendingIntent(R.id.shopping_widget_add, open(context, quickAdd = true))
        views.setOnClickPendingIntent(R.id.shopping_widget_list, open(context, quickAdd = false))
        widgetIds.forEach { manager.updateAppWidget(it, views) }
    }

    private fun open(context: Context, quickAdd: Boolean): PendingIntent {
        val intent = Intent(context, ShoppingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(ShoppingActivity.EXTRA_ADD, quickAdd)
        if (quickAdd) {
            intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context, if (quickAdd) 1 else 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
