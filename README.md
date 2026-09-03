# Runner

A home-screen widget and app launcher for Termux scripts. One tap → pick a script → run it.

Works with any launcher (tested on KISS). The widget launches the app, which opens a Termux dialog picker listing your scripts.

## Status: WORKING

The widget works. The 62-attempt debugging saga and its root cause are documented in [`solution.md`](solution.md) and the full history is in [`attempts.md`](attempts.md).

## How it works

- **Tap the widget** → launches `MainActivity` (same as tapping the app icon)
- **MainActivity** → fires `com.termux.RUN_COMMAND` with `~/.shortcuts/widget-launcher` → opens the Termux dialog picker → run your script

## ⚠️ Critical gotcha: Motorola battery freezer

On Motorola devices (and similar OEM battery managers), the OS silently **freezes** background app processes. A frozen process cannot receive the `APPWIDGET_UPDATE` broadcast, so `onUpdate()` never runs, the PendingIntent is never set, and the widget does nothing when tapped.

**Fix** (one-time, via adb — see [`solution.md`](solution.md) for full details):

```bash
adb shell cmd deviceidle whitelist +com.runner
adb shell cmd appops set com.runner RUN_ANY_IN_BACKGROUND allow
adb shell cmd appops set com.runner RUN_IN_BACKGROUND allow
# Settings → Apps → Runner → Battery → Unrestricted
```

## Requirements

- Termux (com.termux)
- Termux:API (com.termux.api) + `pkg install termux-api`
- `allow-external-apps = true` in `~/.termux/termux.properties`
- `com.termux.permission.RUN_COMMAND` granted (app requests this at runtime)

## Scripts

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

The widget launcher at `~/.shortcuts/widget-launcher` lists them in a radio dialog and runs the selected one.

## Build

```bash
# Dependencies: javac, dx, aapt, apksigner, zip (all in Termux)
bash build.sh
```

Output: `out/termux-tasks.apk`

## Install

```bash
adb install out/termux-tasks.apk   # or uninstall+install on signature change
# add widget via your launcher, tap it
```