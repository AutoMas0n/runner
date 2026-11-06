package com.runner;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * MainActivity for the Runner home-screen widget.
 *
 * Script list is fetched from Termux via push-based IPC:
 *
 *   Primary — Broadcast IPC:
 *     1. App registers a BroadcastReceiver for "com.runner.SCRIPTS"
 *     2. App sends RUN_COMMAND to run widget-list on Termux
 *     3. widget-list sends an am broadcast back with the script
 *        names URL-encoded in the "scripts" String extra
 *     4. App's receiver decodes and displays — no clipboard used
 *
 *   Fallback — Clipboard marker + restore:
 *     If the broadcast doesn't arrive within 10s, the app falls
 *     back to the proven clipboard polling mechanism.
 */
public class MainActivity extends Activity {

    private static final String TAG = "RunnerMain";
    private static final int REQ_RUN_COMMAND = 1001;
    private static final int REQ_RECORD_AUDIO = 1002;
    // Clipboard fallback constants
    private static final long POLL_INTERVAL = 400;
    private static final long POLL_TIMEOUT = 10_000;
    private static final String CLIP_MARKER = "RUNNER\u001F\u001E";
    // Broadcast timeout
    private static final long BROADCAST_TIMEOUT = 10_000;

    private ListView scriptList;
    private View loadingView;
    private TextView statusText;
    private Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer speechRecognizer;
    private volatile boolean dataReceived = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String cmd = getIntent().getStringExtra("command");
        if ("aidhd".equals(cmd)) {
            showAidhdDialogue();
            return;
        }

        setContentView(R.layout.activity_main);

        scriptList = findViewById(R.id.script_list);
        loadingView = findViewById(R.id.loading_view);
        statusText = findViewById(R.id.status_text);

        if (checkSelfPermission("com.termux.permission.RUN_COMMAND")
                == PackageManager.PERMISSION_GRANTED) {
            armWidget();
            startFetch();
        } else {
            statusText.setText("Grant permission\u2026");
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
        } else if (code == REQ_RECORD_AUDIO && grants.length > 0
                && grants[0] == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecognition();
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

    // ────────────────────────────── Fetch strategy ───────────────────────────────

    private void startFetch() {
        showLoading("Loading scripts\u2026");

        // Primary: push-based broadcast IPC
        try {
            startBroadcastFetch();
            return;
        } catch (Exception e) {
            Log.w(TAG, "Broadcast setup failed: " + e);
        }

        // Fallback: clipboard polling with marker + restore
        startClipboardFetch();
    }

    // ────────────────────────────── Broadcast IPC (push-based) ──────────────────

    private BroadcastReceiver scriptsReceiver;

    private void startBroadcastFetch() {
        dataReceived = false;

        // Register receiver for script list broadcasts
        scriptsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (dataReceived) return;
                Log.i(TAG, "broadcast: " + intent.getAction());
                String encoded = intent.getStringExtra("scripts");
                if (encoded != null && !encoded.isEmpty()) {
                    Log.i(TAG, "broadcast data (" + encoded.length() + " chars)");
                    final String data = urlDecode(encoded);
                    final List<String> scripts = parseScriptList(data);
                    dataReceived = true;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (scripts.isEmpty()) {
                                showEmpty();
                            } else {
                                showScriptList(scripts);
                            }
                        }
                    });
                } else {
                    Log.w(TAG, "broadcast with no scripts extra");
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction("com.runner.SCRIPTS");
        registerReceiver(scriptsReceiver, filter);
        Log.i(TAG, "receiver registered");

        // Start timeout timer — fall back to clipboard if no broadcast arrives
        final long startTime = System.currentTimeMillis();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!dataReceived) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    Log.w(TAG, "broadcast timeout after " + elapsed + "ms");
                    try {
                        unregisterReceiver(scriptsReceiver);
                    } catch (Exception ignored) {}
                    startClipboardFetch();
                }
            }
        }, BROADCAST_TIMEOUT);

        // Send RUN_COMMAND to run widget-list on Termux
        sendWidgetListCommand();
    }

    private void sendWidgetListCommand() {
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
            if (!dataReceived) {
                dataReceived = true;
                showError("Termux not installed?");
                finishDelayed(3000);
            }
        }
    }

    // ────────────────────────────── Clipboard fallback ──────────────────────────

    private void startClipboardFetch() {
        if (dataReceived) return;

        Log.i(TAG, "starting clipboard fallback");
        final String clipboardBefore = getClipboardText();

        sendWidgetListCommand();

        pollForMarker(clipboardBefore, System.currentTimeMillis());
    }

    private void pollForMarker(final String userClipboard,
                               final long startTime) {
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed > POLL_TIMEOUT) {
            Log.w(TAG, "poll timeout");
            showError("Could not reach Termux.\nTap app drawer to retry.");
            finishDelayed(3000);
            return;
        }

        String current = getClipboardText();

        if (current != null && current.startsWith(CLIP_MARKER)) {
            Log.i(TAG, "marker found");
            String data = current.substring(CLIP_MARKER.length());
            setClipboardText(userClipboard);

            List<String> scripts = parseScriptList(data);
            if (scripts.isEmpty()) {
                showEmpty();
            } else {
                showScriptList(scripts);
            }
            return;
        }

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                pollForMarker(userClipboard, startTime);
            }
        }, POLL_INTERVAL);
    }

    // ────────────────────────────── Clipboard helpers ──────────────────────────────

    private ClipboardManager getClipboardManager() {
        try {
            return (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        } catch (Exception e) {
            Log.e(TAG, "getClipboardManager failed", e);
            return null;
        }
    }

    private String getClipboardText() {
        try {
            ClipboardManager cm = getClipboardManager();
            if (cm != null && cm.hasText()) {
                CharSequence t = cm.getText();
                return t != null ? t.toString() : "";
            }
        } catch (Exception e) {
            Log.e(TAG, "getClipboardText error", e);
        }
        return "";
    }

    private void setClipboardText(String text) {
        try {
            ClipboardManager cm = getClipboardManager();
            if (cm != null) {
                cm.setText(text);
            }
        } catch (Exception e) {
            Log.e(TAG, "setClipboardText error", e);
        }
    }

    // ────────────────────────────── Parse ──────────────────────────────

    private String urlDecode(String s) {
        try {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '%' && i + 2 < s.length()) {
                    int hi = hexVal(s.charAt(i + 1));
                    int lo = hexVal(s.charAt(i + 2));
                    if (hi >= 0 && lo >= 0) {
                        sb.append((char) ((hi << 4) | lo));
                        i += 3;
                        continue;
                    }
                }
                if (c == '+') {
                    sb.append(' ');
                } else {
                    sb.append(c);
                }
                i++;
            }
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "urlDecode error, using raw", e);
            return s;
        }
    }

    private int hexVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
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

    // ────────────────────────────── aidhd dialogue

    private void showAidhdDialogue() {
        setContentView(R.layout.aidhd_dialog);

        final EditText taskInput = findViewById(R.id.task_input);
        final TextView submitBtn = findViewById(R.id.btn_submit);
        final TextView voiceBtn = findViewById(R.id.btn_voice);
        final TextView secretaryBtn = findViewById(R.id.btn_secretary);
        final TextView remindersBtn = findViewById(R.id.btn_reminders);
        final TextView serveBtn = findViewById(R.id.btn_serve);
        final TextView backBtn = findViewById(R.id.btn_back);

        submitBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CharSequence input = taskInput.getText();
                if (input == null || input.length() == 0) {
                    Toast.makeText(MainActivity.this,
                        "Task title cannot be empty", Toast.LENGTH_SHORT).show();
                    return;
                }
                String title = input.toString();
                submitBtn.setEnabled(false);
                sendAidhdAddCommand(title);
                taskInput.setText("", android.widget.TextView.BufferType.EDITABLE);
                submitBtn.setEnabled(true);
            }
        });

        voiceBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (checkSelfPermission("android.permission.RECORD_AUDIO")
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                        new String[]{"android.permission.RECORD_AUDIO"},
                        REQ_RECORD_AUDIO);
                } else {
                    startVoiceRecognition();
                }
            }
        });

        secretaryBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendAidhdCommand("widget-aidhd-secretary");
            }
        });

        remindersBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendAidhdCommand("widget-aidhd-reminders");
            }
        });

        serveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendAidhdCommand("widget-aidhd-serve");
            }
        });

        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent back = new Intent(MainActivity.this, MainActivity.class);
                back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(back);
                finish();
            }
        });
    }

    private void sendAidhdCommand(String scriptName) {
        String path = "/data/data/com.termux/files/home/.shortcuts/" + scriptName;
        Log.i(TAG, "sendAidhdCommand: " + path);

        Intent i = new Intent("com.termux.RUN_COMMAND");
        i.setClassName("com.termux", "com.termux.app.RunCommandService");
        i.putExtra("com.termux.RUN_COMMAND_PATH", path);
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR",
            "/data/data/com.termux/files/home");
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);

        try {
            startService(i);
        } catch (Exception e) {
            Log.e(TAG, "sendAidhdCommand failed", e);
            Toast.makeText(this, "Failed: " + scriptName, Toast.LENGTH_SHORT).show();
        }
    }

    private void sendAidhdAddCommand(String title) {
        String path = "/data/data/com.termux/files/home/.shortcuts/widget-aidhd-add";
        Log.i(TAG, "sendAidhdAddCommand: " + path + " '" + title + "'");

        Intent i = new Intent("com.termux.RUN_COMMAND");
        i.setClassName("com.termux", "com.termux.app.RunCommandService");
        i.putExtra("com.termux.RUN_COMMAND_PATH", path);
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{title});
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR",
            "/data/data/com.termux/files/home");
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);

        try {
            startService(i);
        } catch (Exception e) {
            Log.e(TAG, "sendAidhdAddCommand failed", e);
            Toast.makeText(this, "Failed to add task", Toast.LENGTH_SHORT).show();
        }
    }

    private void startVoiceRecognition() {
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }

        final Activity self = this;
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                Log.i(TAG, "voice: ready");
                self.runOnUiThread(new Runnable() {
                    public void run() {
                        Toast.makeText(self, "\uD83C\uDFA4 Speak now",
                            Toast.LENGTH_SHORT).show();
                    }
                });
            }
            @Override public void onBeginningOfSpeech() {
                Log.i(TAG, "voice: began");
            }
            @Override public void onRmsChanged(float rms) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() {
                Log.i(TAG, "voice: ended");
            }
            @Override public void onError(int error) {
                String msg = "Voice error: " + error;
                Log.e(TAG, msg);
                self.runOnUiThread(new Runnable() {
                    public void run() {
                        Toast.makeText(self, msg,
                            Toast.LENGTH_SHORT).show();
                    }
                });
                if (speechRecognizer != null) {
                    speechRecognizer.destroy();
                    speechRecognizer = null;
                }
            }
            @Override
            public void onResults(Bundle results) {
                List<String> matches = results
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    final String text = matches.get(0);
                    Log.i(TAG, "voice result: " + text);
                    sendAidhdAddCommand(text);
                    self.runOnUiThread(new Runnable() {
                        public void run() {
                            Toast.makeText(self,
                                "\uD83C\uDFA4 \u201C" + text + "\u201D",
                                Toast.LENGTH_SHORT).show();
                        }
                    });
                }
                if (speechRecognizer != null) {
                    speechRecognizer.destroy();
                    speechRecognizer = null;
                }
            }
            @Override
            public void onPartialResults(Bundle partial) {}
            @Override
            public void onEvent(int type, Bundle params) {}
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your task");

        speechRecognizer.startListening(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
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