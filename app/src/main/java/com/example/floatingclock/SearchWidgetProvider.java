package com.example.floatingclock;

import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.widget.RemoteViews;

import androidx.core.os.BundleCompat;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** 桌面小组件仅作为搜索页入口。 */
public class SearchWidgetProvider extends AppWidgetProvider {

    private static final int MAX_EXACT_LAYOUTS = 16;
    private static final float CARD_WIDTH_TO_HEIGHT = 4f / 5f;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        for (int widgetId : widgetIds) {
            updateWidget(context, manager, widgetId, manager.getAppWidgetOptions(widgetId));
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
            int widgetId, Bundle options) {
        updateWidget(context, manager, widgetId, options);
    }

    private void updateWidget(Context context, AppWidgetManager manager, int widgetId, Bundle options) {
        Intent intent = new Intent(context, SearchActivity.class);
        PendingIntent pendingIntent = TaskStackBuilder.create(context)
                .addNextIntentWithParentStack(intent)
                .getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            List<SizeF> sizes = BundleCompat.getParcelableArrayList(
                    options, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF.class);
            if (sizes != null && !sizes.isEmpty() && sizes.size() <= MAX_EXACT_LAYOUTS) {
                Map<SizeF, RemoteViews> layouts = new LinkedHashMap<>();
                for (SizeF size : sizes) {
                    layouts.put(size, createViews(context, pendingIntent, size.getWidth(), size.getHeight()));
                }
                manager.updateAppWidget(widgetId, new RemoteViews(layouts));
                return;
            }
        }
        boolean landscape = context.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        int width = options.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
        int height = options.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
        manager.updateAppWidget(widgetId, createViews(context, pendingIntent, width, height));
    }

    private RemoteViews createViews(Context context, PendingIntent pendingIntent, float width, float height) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.search_widget);
        if (width > 0 && height > 0) {
            float density = context.getResources().getDisplayMetrics().density;
            float cardWidth = Math.min(width, height * CARD_WIDTH_TO_HEIGHT);
            float cardHeight = cardWidth / CARD_WIDTH_TO_HEIGHT;
            int horizontal = Math.round((width - cardWidth) * density / 2);
            int vertical = Math.round(Math.max(0f, height - cardHeight) * density / 2);
            views.setViewPadding(R.id.search_widget_frame, horizontal, vertical, horizontal, vertical);
        }
        views.setOnClickPendingIntent(R.id.search_widget, pendingIntent);
        return views;
    }
}
