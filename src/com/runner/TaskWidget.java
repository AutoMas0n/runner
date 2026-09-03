package com.runner;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.RemoteViews;

public class TaskWidget extends AppWidgetProvider {

    private static final String TAG = "RunnerWidget";

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        Log.i(TAG, "onUpdate called with " + ids.length + " ids");
        for (int id : ids) {
            setPendingIntent(context, mgr, id);
        }
    }

    static void setPendingIntent(Context context, AppWidgetManager mgr, int widgetId) {
        Log.i(TAG, "setPendingIntent for widget " + widgetId);

        Intent tap = new Intent(context, MainActivity.class);
        tap.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
            context, 0, tap,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
        );

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_layout);
        views.setOnClickPendingIntent(R.id.btn_run, pi);
        mgr.updateAppWidget(widgetId, views);
        Log.i(TAG, "updateAppWidget called for widget " + widgetId);
    }
}