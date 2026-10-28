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

public class MainActivity extends Activity {

    private static final String TAG = "RunnerMain";
    private static final int REQ_RUN_COMMAND = 1001;
    private static final int REQ_RECORD_AUDIO = 1002;
    private static final long POLL_INTERVAL = 300;
    private static final long POLL_TIMEOUT = 8_000;

    private ListView scriptList;
    private View loadingView;
    private TextView statusText;
    private Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer speechRecognizer;
    private long pollStart;

    // Snapshot of clipboard content BEFORE we set it — to detect our own write
    private String clipboardBefore = "";

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

    // ────────────────────────────── aidhd dialogue

    private void showAidhdDialogue() {
        setContentView(R.layout.aidhd_dialog);

        final EditText taskInput = findViewById(R.id.task_input);
        final TextView submitBtn = findViewById(R.id.btn_submit);
        final TextView voiceBtn = findViewById(R.id.btn_voice);
        final TextView secretaryBtn = findViewById(R.id.btn_secretary);
        final TextView serveBtn = findViewById(R.id.btn_serve);
        final TextView backBtn = findViewById(R.id.btn_back);

        // Submit: pass title as RUN_COMMAND argument to widget-aidhd-add
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

                // Disable button to prevent double-submit
                submitBtn.setEnabled(false);

                // Send title as argument directly
                sendAidhdAddCommand(title);

                // Clear input and re-enable
                taskInput.setText("", android.widget.TextView.BufferType.EDITABLE);
                submitBtn.setEnabled(true);
            }
        });

        // Voice — Android's built-in SpeechRecognizer (app is in foreground)
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

        // Secretary
        secretaryBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendAidhdCommand("widget-aidhd-secretary");
            }
        });

        // Serve
        serveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendAidhdCommand("widget-aidhd-serve");
            }
        });

        // Back: switch back to script-list view
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Open a new Intent without "command" extra to show script list
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

    /// Send a task title directly as a RUN_COMMAND argument to widget-aidhd-add.
    /// This bypasses the clipboard bridge, which is unreliable from background services.
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

    /// Use Android's built-in SpeechRecognizer to capture voice.
    /// The app is in the foreground so this works reliably across Android versions.
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

                    // Auto-submit: pass title as argument directly
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