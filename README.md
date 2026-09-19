# Runner

A home-screen widget and app launcher for Termux scripts. One tap → pick a script → run it.

Works with any launcher (tested on KISS, Pixel Launcher). The widget launches the Runner app, which lists your scripts in its own GUI and runs the selected one.

## Status: WORKING

Tested on:
- **Motorola G86 (Android 14)** — requires battery whitelist (see [Motorola gotcha](#motorola-battery-freezer))
- **Pixel 7 Pro (Android 14+)** — works out of the box after permission grant

The 62-attempt debugging saga for the Motorola freezer issue is documented in [`solution.md`](solution.md) and the full history in [`attempts.md`](attempts.md).

## How it works

```
Widget tap (or app drawer)
       ↓
  MainActivity opens
       ↓
  Sends RUN_COMMAND → Termux runs ~/.shortcuts/widget-list
       ↓                                    ↓
  Polls clipboard ←──── termux-clipboard-set ─── writes script names
       ↓
  Shows script list in its own ListView
       ↓
  Tap a script → RUN_COMMAND for ~/.shortcuts/widget-tasks/<script>
       ↓
  Script runs in Termux, app closes
```

**Why not termux-dialog?** On Android 14+, the system blocks background activity launches — `termux-dialog` can't open its dialog from a background Termux process. The clipboard bridge (`termux-clipboard-set` → polling from our foreground app) bypasses this restriction entirely.

## Requirements

- **Termux** (`com.termux`) — v0.118.0+
- **Termux:API** (`com.termux.api`) — `pkg install termux-api`
- `allow-external-apps = true` in `~/.termux/termux.properties`
- `com.termux.permission.RUN_COMMAND` granted (app requests this at runtime)

## Setup

### 1. Helper scripts

Two scripts must exist in `~/.shortcuts/`:

**`~/.shortcuts/widget-list`** — lists script names to the clipboard (used by the app):

```bash
#!/data/data/com.termux/files/usr/bin/bash
TASK_DIR="$HOME/.shortcuts/widget-tasks"
OUTPUT=""
for f in "$TASK_DIR"/*; do
  [ -f "$f" ] || continue
  OUTPUT="${OUTPUT}$(basename "$f")"$'\n'
done
echo -n "$OUTPUT" | termux-clipboard-set
```

**`~/.shortcuts/widget-launcher`** — legacy interactive picker (for manual use in Termux):

```bash
#!/data/data/com.termux/files/usr/bin/bash
TASK_DIR="$HOME/.shortcuts/widget-tasks"
mkdir -p "$TASK_DIR"
tasks=()
for f in "$TASK_DIR"/*; do
  [ -f "$f" ] || continue; tasks+=("$(basename "$f")")
done
[ ${#tasks[@]} -eq 0 ] && termux-toast "No scripts" && exit 1
vals=$(IFS=,; echo "${tasks[*]}")
result="$(termux-dialog radio -t "Run script" -v "$vals")"
idx="$(echo "$result" | grep -o '"index":[[:space:]]*[0-9]*' | grep -o '[0-9][0-9]*')"
[ -n "$idx" ] && [ "$idx" -ge 0 ] 2>/dev/null && bash "$TASK_DIR/${tasks[$idx]}" &
```

### 2. Scripts directory

Scripts go in `~/.shortcuts/widget-tasks/`:

```
~/.shortcuts/widget-tasks/
├── Diaper Both
├── Diaper Pee
├── Diaper Poo
├── Feed L
├── Feed R
└── test
```

Each script is a plain bash script run by Termux.

### 3. Grant permission

When you first open Runner (from the widget or app drawer), it asks for **`com.termux.permission.RUN_COMMAND`**. Tap **Allow**. Without this, the app cannot send commands to Termux.

---

## ⚠️ Motorola battery freezer

On Motorola devices (and similar OEMs like Xiaomi, OnePlus), the OS silently **freezes** background app processes. A frozen process cannot receive the `APPWIDGET_UPDATE` broadcast, so `onUpdate()` never runs, the PendingIntent is never set, and the widget does nothing when tapped.

**Fix** (one-time, via adb):

```bash
adb shell cmd deviceidle whitelist +com.runner
adb shell cmd appops set com.runner RUN_ANY_IN_BACKGROUND allow
adb shell cmd appops set com.runner RUN_IN_BACKGROUND allow
# Settings → Apps → Runner → Battery → Unrestricted
```

Then uninstall and reinstall the APK to clear the frozen state.

See [`solution.md`](solution.md) for the full debugging story.

---

## ⚠️ Android 14+ background activity launch block

On Android 14+, Termux:API's `termux-dialog` **cannot launch its dialog** when called from a background process (like `RUN_COMMAND` with `BACKGROUND=true`). The app's clipboard-bridge approach avoids this — but if you ever see the app stuck on "Loading scripts…", it means:
- `termux-clipboard-set` is not writing to the clipboard (check `termux-clipboard-set` works in Termux)
- The `widget-list` script is missing or not executable in `~/.shortcuts/`
- Termux:API is not installed (`pkg install termux-api`)

---

## Build

Dependencies: `javac`, `dx`, `aapt`, `apksigner`, `zip` (all available via `pkg install` in Termux).

```bash
bash build.sh
```

Output: `out/termux-tasks.apk`

## Install

```bash
adb install out/termux-tasks.apk       # fresh install
adb install -r out/termux-tasks.apk    # upgrade (same signature)
```

If the signature changed (e.g. keystore regenerated), uninstall first:

```bash
adb uninstall com.runner
adb install out/termux-tasks.apk
```

Then add the **Runner** widget to your home screen via your launcher's widget picker.