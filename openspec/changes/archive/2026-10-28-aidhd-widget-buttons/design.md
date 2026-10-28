# Design

## Context

The termux-widget-apk is an Android homescreen widget (AppWidgetProvider) with a companion app (MainActivity). Currently it shows one button ("▶ Run") that opens a script-runner screen. The project builds with Termux's `aapt` (android app packaging tool) against `android.jar` from the `termux-api` package. User input flows to Termux through the Intent-based `RUN_COMMAND` service (`com.termux.RUN_COMMAND`). The clipboard serves as a simple data bridge between the app and Termux scripts — the existing `widget-list` script writes to it, and the app polls it.

This change adds a second button that opens an aidhd dialogue inside the same app. See `proposal.md` for motivation.

## Goals / Non-Goals

**Goals:**
- Add an "aidhd" button to the widget, visually distinct from "▶ Run"
- Open a dedicated aidhd dialogue when the button is tapped
- Support five actions from the dialogue: add task (text), voice, secretary, serve, go back
- Pass task text from the app to Termux via the proven clipboard bridge
- Keep the existing script-runner behaviour unchanged

**Non-Goals:**
- Not building a full task list viewer (the web dashboard handles that)
- Not replacing or duplicating the aidhd CLI — all actions delegate to `octx x aidhd ...`
- Not managing the serve process lifecycle beyond start-and-forget
- Not supporting audio capture inside the Android app itself — delegation to `octx x aidhd voice` is sufficient until proven unreliable

## Decisions

### 1. Widget layout: Horizontal LinearLayout over nested RemoteViews
- **Decision**: Change `widget_layout.xml` to a `LinearLayout` with `orientation="horizontal"`, containing two `TextView` children (▶ Run, aidhd).
- **Rationale**: RemoteViews supports `LinearLayout` and `TextView` natively. A single containment level keeps the layout flat and avoids the `setOnClickPendingIntent` ambiguity that nested layouts can introduce.
- **Alternatives considered**: A custom composite view — adds complexity without benefit since the widget only needs two tap targets.

### 2. Mode routing: Intent extras over separate Activity classes
- **Decision**: Use `Intent.putExtra("command", "aidhd")` on the aidhd PendingIntent, and check `getIntent().getStringExtra("command")` in `MainActivity.onCreate()` to decide which view to show.
- **Rationale**: Avoids duplicating the Activity boilerplate (manifest registration, intent filters, lifecycle). The two modes share the same app lifecycle and the same `RUN_COMMAND` integration path.
- **Alternatives considered**: A separate `AIDHDbActivity` class — cleaner separation but doubles the Activity plumbing. Not worth it for a dialogue that shares most of the integration code.

### 3. Text bridge: Clipboard between app and Termux
- **Decision**: The app copies the user's task text to the clipboard using `android.content.Context.ClipboardManager` (or the system clipboard via `termux-clipboard-set` equivalent), then runs a wrapper script that reads it back with `termux-clipboard-get`.
- **Rationale**: The clipboard is already the established data bridge (used for script-list output). File I/O from the Android sandbox to Termux's tmpdir is unreliable across Android versions. Intent extras like `RUN_COMMAND_ARGUMENTS` would need comma-safe escaping for arbitrary user text.
- **v1 simplification**: The app saves the current clipboard contents before setting the task text and restores them after the script completes — but only where the round-trip matters (the script list polling path and the aidhd-add path never run concurrently, so this is a `ponytail:` deferral).

### 4. Wrapper scripts: Thin `bash` wrappers
- **Decision**: One script per action under `~/.shortcuts/widget-aidhd-{add,voice,secretary,serve}`, each 3–8 lines.
- **Rationale**: The existing widget-runner already uses `~/.shortcuts` for its scripts. Thin wrappers keep the CLI call explicit and give a natural place to add prerequisite checks (e.g., "is `octx` available?").
- **Alternatives considered**: Calling `octx` directly through `RUN_COMMAND_PATH` — works for simple commands but `serve` needs process backgrounding and the `add` wrapper needs to read the clipboard.

### 5. serve lifecycle: Background-and-forget
- **Decision**: The `widget-aidhd-serve` script starts `octx x aidhd serve --port 7317 &`, waits 1 second for the server to bind, then opens `http://127.0.0.1:7317` via `termux-open-url`. No process manager, no stop button.
- **Rationale**: The web server is a debugging/dashboard tool, not a production service. If the user taps Serve again, a second instance will fail (port in use), which is fine — the first is already running and the URL will re-open. A "stop serve" feature can be added if the user asks for it.
- **ponytail**: PID tracking and a "Stop" button skipped. Add when users report leaving serve running indefinitely.

### 6. aidhd dialogue layout: Dedicated XML layout file
- **Decision**: Create `res/layout/aidhd_dialog.xml` defining a vertical layout with:
  - EditText (task title input, takes most of the width)
  - Submit button (below the input)
  - Voice button
  - Secretary button
  - Serve button
  - Back button (top or bottom)
- **Rationale**: A separate layout file keeps the view definitions clean. `MainActivity` loads it via `setContentView(R.layout.aidhd_dialog)` when in aidhd mode.

## Risks / Trade-offs

| Risk | Mitigation |
|------|-----------|
| **`termux-speech-to-text` blocked from background** like `termux-dialog` | If aidhd voice fails, the Submit and Serve buttons still work. Voice degradation is isolated. The Serve button opens the web dashboard which has its own "Add task" text input as a fallback. |
| **octx not installed** | Each wrapper script checks `command -v octx` and calls `termux-toast` with a clear error message. No silent failures. |
| **Clipboard race with script-list** | The script-list workflow and aidhd workflow never run simultaneously — the user is in one view or the other. The clipboard is set atomically just before the script runs, so no overlap. |
| **aidhd serve port conflict** | `octx x aidhd serve` already handles this — it fails with a clear error if port 7317 is in use. The wrapper captures stderr and shows it via toast. |