# Runner

A home-screen widget and app launcher for Termux scripts. One tap → pick a script → run it.

Built for Android, works with any launcher. Works best pinned to KISS launcher favorites or as a home-screen widget.

## How it works

- **Tap the app icon** → requests `RUN_COMMAND` permission once → opens a Termux dialog picker listing your scripts → tap one to run it
- **Tap the widget** (if added) → same flow, opens the app first

The widget is configured by the app itself (since some launchers like KISS don't call `onUpdate`). Just open the app once after adding the widget.

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
# Dependencies: ecj, dx, aapt2, apksigner, zip
bash build.sh
```

Output: `out/termux-tasks.apk`

## Requirements

- Termux (com.termux)
- Termux:API (com.termux.api) + `pkg install termux-api`
- `allow-external-apps = true` in `~/.termux/termux.properties`
- `com.termux.permission.RUN_COMMAND` granted (app requests this at runtime)