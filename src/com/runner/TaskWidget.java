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

        // PendingIntent for the Run button (script list)
        Intent tapRun = new Intent(context, MainActivity.class);
        tapRun.setAction("com.runner.RUN");
        tapRun.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        android.app.PendingIntent piRun = android.app.PendingIntent.getActivity(
            context, 0, tapRun,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
        );

        // PendingIntent for the aidhd button (task input dialogue)
        Intent tapAidhd = new Intent(context, MainActivity.class);
        tapAidhd.setAction("com.runner.AIDHD");
        tapAidhd.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        tapAidhd.putExtra("command", "aidhd");
        android.app.PendingIntent piAidhd = android.app.PendingIntent.getActivity(
            context, 1, tapAidhd,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
        );

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_layout);
        views.setOnClickPendingIntent(R.id.btn_run, piRun);
        views.setOnClickPendingIntent(R.id.btn_aidhd, piAidhd);
        mgr.updateAppWidget(widgetId, views);
        Log.i(TAG, "updateAppWidget called for widget " + widgetId);
    }
}