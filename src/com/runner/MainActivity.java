package com.runner;

import android.app.Activity;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.RemoteViews;

public class MainActivity extends Activity {

    private static final String TAG = "RunnerMain";
    private static final int REQ_RUN_COMMAND = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "onCreate — launched");

        if (checkSelfPermission("com.termux.permission.RUN_COMMAND")
                == PackageManager.PERMISSION_GRANTED) {
            setupWidget();
            fire();
        } else {
            requestPermissions(
                new String[]{"com.termux.permission.RUN_COMMAND"},
                REQ_RUN_COMMAND
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        if (code == REQ_RUN_COMMAND && grants.length > 0
                && grants[0] == PackageManager.PERMISSION_GRANTED) {
            setupWidget();
            fire();
        }
        finish();
    }

    private void setupWidget() {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(this);
            ComponentName cn = new ComponentName(this, TaskWidget.class);
            int[] ids = mgr.getAppWidgetIds(cn);
            Log.i(TAG, "setupWidget: getAppWidgetIds returned " + (ids == null ? "null" : ids.length + " ids"));
            if (ids != null) {
                for (int id : ids) {
                    Log.i(TAG, "setupWidget: arming widget " + id);
                    TaskWidget.setPendingIntent(this, mgr, id);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "setupWidget failed", e);
        }
    }

    private void fire() {
        Intent i = new Intent("com.termux.RUN_COMMAND");
        i.setPackage("com.termux");
        i.putExtra("com.termux.RUN_COMMAND_PATH",
            "/data/data/com.termux/files/home/.shortcuts/widget-launcher");
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR",
            "/data/data/com.termux/files/home");
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);

        try {
            startService(i);
            Log.i(TAG, "fire: RUN_COMMAND sent to Termux");
        } catch (Exception e) {
            Log.e(TAG, "fire failed", e);
        }

        finish();
    }
}