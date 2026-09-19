package com.runner;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String TAG = "RunnerMain";
    private static final int REQ_RUN_COMMAND = 1001;
    private static final long POLL_INTERVAL = 300;
    private static final long POLL_TIMEOUT = 8_000;

    private ListView scriptList;
    private View loadingView;
    private TextView statusText;
    private Handler handler = new Handler(Looper.getMainLooper());
    private long pollStart;

    // Snapshot of clipboard content BEFORE we set it — to detect our own write
    private String clipboardBefore = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        scriptList = findViewById(R.id.script_list);
        loadingView = findViewById(R.id.loading_view);
        statusText = findViewById(R.id.status_text);

        if (checkSelfPermission("com.termux.permission.RUN_COMMAND")
                == PackageManager.PERMISSION_GRANTED) {
            armWidget();
            startFetch();
        } else {
            statusText.setText("Grant permission…");
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
            armWidget();
            startFetch();
        } else {
            statusText.setText("Permission denied");
            finishDelayed(1500);
        }
    }

    private void armWidget() {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(this);
            ComponentName cn = new ComponentName(this, TaskWidget.class);
            int[] ids = mgr.getAppWidgetIds(cn);
            if (ids != null) {
                for (int id : ids) {
                    TaskWidget.setPendingIntent(this, mgr, id);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "armWidget failed", e);
        }
    }

    private void startFetch() {
        showLoading("Loading scripts…");

        // Snapshot current clipboard so we can detect our own write
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = cm.getPrimaryClip();
            clipboardBefore = (clip != null && clip.getItemCount() > 0)
                    ? clip.getItemAt(0).coerceToText(this).toString() : "";
        } catch (Exception e) {
            clipboardBefore = "";
        }

        // Send RUN_COMMAND to run widget-list (writes script names to clipboard)
        Intent i = new Intent("com.termux.RUN_COMMAND");
        i.setClassName("com.termux", "com.termux.app.RunCommandService");
        i.putExtra("com.termux.RUN_COMMAND_PATH",
            "/data/data/com.termux/files/home/.shortcuts/widget-list");
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR",
            "/data/data/com.termux/files/home");
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);

        try {
            startService(i);
            Log.i(TAG, "RUN_COMMAND sent for widget-list");
        } catch (Exception e) {
            Log.e(TAG, "startService failed", e);
            showError("Termux not installed?");
            finishDelayed(3000);
            return;
        }

        // Start polling clipboard for the result
        pollStart = System.currentTimeMillis();
        pollClipboard();
    }

    private void pollClipboard() {
        long elapsed = System.currentTimeMillis() - pollStart;
        if (elapsed > POLL_TIMEOUT) {
            Log.w(TAG, "pollClipboard timed out after " + POLL_TIMEOUT + "ms");
            // Fall back: just show an empty list with a message
            showError("Could not reach Termux.\nTap app drawer to retry.");
            finishDelayed(3000);
            return;
        }

        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = cm.getPrimaryClip();
            String current = (clip != null && clip.getItemCount() > 0)
                    ? clip.getItemAt(0).coerceToText(this).toString() : "";

            // If clipboard changed from our snapshot, it's our script list
            if (!current.equals(clipboardBefore) && !current.isEmpty()) {
                Log.i(TAG, "Clipboard updated with script list");
                List<String> scripts = parseScriptList(current);
                if (scripts.isEmpty()) {
                    showEmpty();
                } else {
                    showScriptList(scripts);
                }
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "clipboard read error", e);
        }

        // Poll again
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                pollClipboard();
            }
        }, POLL_INTERVAL);
    }

    private List<String> parseScriptList(String text) {
        List<String> scripts = new ArrayList<>();
        if (text == null) return scripts;
        for (String line : text.split("\n")) {
            line = line.trim();
            if (!line.isEmpty()) {
                scripts.add(line);
            }
        }
        return scripts;
    }

    // ────────────────────────────────────────── UI helpers

    private void showLoading(String msg) {
        loadingView.setVisibility(View.VISIBLE);
        scriptList.setVisibility(View.GONE);
        statusText.setText(msg);
    }

    private void showScriptList(final List<String> scripts) {
        loadingView.setVisibility(View.GONE);
        scriptList.setVisibility(View.VISIBLE);

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_list_item_1, scripts) {
            @Override
            public View getView(int pos, View convert, ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, convert, parent);
                tv.setTextColor(0xFFFFFFFF);
                tv.setPadding(24, 18, 24, 18);
                tv.setTextSize(16);
                return tv;
            }
        };
        scriptList.setAdapter(adapter);

        scriptList.setOnItemClickListener(
                new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent,
                    View view, int pos, long id) {
                String script = scripts.get(pos);
                runScript(script);
            }
        });
    }

    private void showEmpty() {
        loadingView.setVisibility(View.VISIBLE);
        scriptList.setVisibility(View.GONE);
        statusText.setText("No scripts in widget-tasks/");
        finishDelayed(2500);
    }

    private void showError(String msg) {
        loadingView.setVisibility(View.VISIBLE);
        scriptList.setVisibility(View.GONE);
        statusText.setText(msg);
    }

    private void runScript(String name) {
        String path = "/data/data/com.termux/files/home/.shortcuts/widget-tasks/" + name;
        Log.i(TAG, "runScript: " + path);

        Intent i = new Intent("com.termux.RUN_COMMAND");
        i.setClassName("com.termux", "com.termux.app.RunCommandService");
        i.putExtra("com.termux.RUN_COMMAND_PATH", path);
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR",
            "/data/data/com.termux/files/home");
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);

        try {
            startService(i);
        } catch (Exception e) {
            Log.e(TAG, "runScript failed", e);
            Toast.makeText(this, "Failed to run " + name, Toast.LENGTH_SHORT).show();
        }

        finish();
    }

    private void finishDelayed(long millis) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, millis);
    }
}