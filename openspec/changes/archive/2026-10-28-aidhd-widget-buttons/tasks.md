# Tasks

## 1. Widget layout — two buttons

- [x] 1.1 Modify `res/layout/widget_layout.xml`: replace the single `<TextView>` with a `<LinearLayout orientation="horizontal">` containing two `<TextView>` children — "▶ Run" (keeping `btn_run` id and red `#FFE94560` color) and "aidhd" (new `btn_aidhd` id, green `#FF4CAF50` color). Verify the XML is well-formed and aapt accepts it.

- [x] 1.2 Modify `src/com/runner/TaskWidget.java`: add a second `PendingIntent` for the `btn_aidhd` element that opens `MainActivity` with `Intent.putExtra("command", "aidhd")`. Verify both buttons are clickable after rebuilding.

- [x] 1.3 Rebuild the project (`build.sh`) and confirm R.java is regenerated with the new `btn_aidhd` id. Verify the APK installs and the widget shows both buttons.

## 2. aidhd dialogue layout

- [x] 2.1 Create `res/layout/aidhd_dialog.xml` with a vertical layout containing:
  - An `EditText` (task title input, id `task_input`, hint text "What needs doing?")
  - A "Submit" button (id `btn_submit`)
  - A "Voice 🎤" button (id `btn_voice`)
  - A "Secretary ⟳" button (id `btn_secretary`)
  - A "Serve 🌐" button (id `btn_serve`)
  - A "← Back" button (id `btn_back`)
  Verify the XML loads without errors by calling `setContentView(R.layout.aidhd_dialog)` in a test invocation.

- [x] 2.2 Include `aidhd_dialog.xml` in `build.sh` so aapt packages it. Verify the APK build succeeds and the new layout is embedded.

## 3. Wrapper scripts

- [x] 3.1 Create `~/.shortcuts/widget-aidhd-add`: reads the task title from clipboard (`termux-clipboard-get`), checks `command -v octx`, and runs `octx x aidhd add "$title"`. Shows a toast notification on success or failure. Verify by running `termux-clipboard-set "test task" && sh ~/.shortcuts/widget-aidhd-add` and confirming the task appears in `octx x aidhd list`.

- [x] 3.2 Create `~/.shortcuts/widget-aidhd-voice`: checks `command -v octx`, runs `octx x aidhd voice`, shows toast on result. Verify by running the script and confirming voice capture works.

- [x] 3.3 Create `~/.shortcuts/widget-aidhd-secretary`: checks `command -v octx`, runs `octx x aidhd secretary`, shows toast on completion. Verify by running the script and checking `octx x aidhd decisions --limit 3`.

- [x] 3.4 Create `~/.shortcuts/widget-aidhd-serve`: starts `octx x aidhd serve --port 7317 &`, waits 1 second, opens `http://127.0.0.1:7317` via `termux-open-url`. If the server fails to start (port in use), shows a toast. Verify by running the script, confirming the URL opens, and checking `curl http://127.0.0.1:7317` returns a dashboard page.

## 4. MainActivity aidhd mode

- [x] 4.1 In `MainActivity.onCreate()`, read `getIntent().getStringExtra("command")`. When it equals `"aidhd"`, call `setContentView(R.layout.aidhd_dialog)` instead of showing the script list. Verify that tapping the widget's aidhd button opens the dialogue view.

- [x] 4.2 Wire the **Submit** button: on tap, copy the `EditText` content to clipboard via the system clipboard service, disable the button (prevent double-submit), send `RUN_COMMAND` to `~/.shortcuts/widget-aidhd-add`, show a "Task submitted" confirmation, and clear the input field. Verify by typing a task, tapping Submit, and confirming it appears in `octx x aidhd today`.

- [x] 4.3 Wire the **Voice** button: on tap, send `RUN_COMMAND` to `~/.shortcuts/widget-aidhd-voice`. Verify the voice capture prompt appears.

- [x] 4.4 Wire the **Secretary** button: on tap, send `RUN_COMMAND` to `~/.shortcuts/widget-aidhd-secretary`. Verify a toast notification reports the secretary result.

- [x] 4.5 Wire the **Serve** button: on tap, send `RUN_COMMAND` to `~/.shortcuts/widget-aidhd-serve`. Verify the browser opens to the dashboard.

- [x] 4.6 Wire the **Back** button: on tap, switch back to the script-list view (`setContentView(R.layout.activity_main)` and re-run the script list polling). Verify tapping back returns to the familiar script list.

## 5. Integration verification

- [x] 5.1 Full rebuild (`build.sh`), install APK, and place the widget on the homescreen. Confirm:
  - Widget shows two side-by-side buttons: "▶ Run" (red) and "aidhd" (green)
  - "▶ Run" still opens the script list (regression check)
  - "aidhd" opens the dialogue with all controls visible
  - Text input → Submit produces a task in the aidhd store
  - Voice, Secretary, and Serve buttons invoke their respective commands
  - Back button returns to the script list