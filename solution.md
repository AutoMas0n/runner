# Solution — The Widget Mystery, Solved

## TL;DR

**Root cause was NOT the code.** It was **Motorola's `moto_freezer`** (battery process freezer) freezing `com.runner`'s process in the background, which silently dropped the system's `APPWIDGET_UPDATE` broadcast before it could reach the widget provider.

**The fix:** tell the OS to stop freezing the app → whitelist Doze, allow background runs, set battery to Unrestricted, fresh install.

---

## The 62-attempt saga in one picture

| # | Approach | Why it failed |
|---|----------|---------------|
| 1–45 | PendingIntent flags, getActivity/getBroadcast/getService, layout tweaks, configure activities, re-arm strategies, launch modes | All irrelevant. The provider's `onUpdate()` was never even called — the process was frozen, so the broadcast never arrived. |
| 46–61 | ACTION_MAIN mimic, CLEAR_TASK, IMMUTABLE/MUTABLE, overlay service, package rename, javac/aapt toolchain switches | Same — all attempts to change what the PendingIntent DID, when the PendingIntent was never set in the first place. |

**The one diagnostic that mattered:** the Toast/Log inside `onUpdate()`, `onEnabled()`, `onReceive()` — **nothing ever fired**. The widget rendered (from `initialLayout` XML) but the provider was never invoked. That pointed to broadcast delivery failure, not widget configuration failure.

## Discovered with adb

```bash
# Fix Termux adb linker error (termux-adb-self skill):
apt-get install abseil-cpp=20250814.1 libprotobuf=2:33.1-1 --allow-downgrades -y

# Connect via Wireless debugging (Settings → Developer Options → Wireless debugging):
adb connect 192.168.x.x:<port>
```

Then three smoking guns:

### 1. The freezer
```
adb shell dumpsys activity processes | grep -A2 com.runner
→ isFreezeExempt=false  isPendingFreeze=false  isFrozen=true
```
The process is **frozen** (suspended by Motorola's battery manager). A frozen process receives no broadcasts, no lifecycle callbacks, nothing.

### 2. The broadcast being dropped
```log
14:31:54.853  D ActivityManager: freezing 9382 com.runner, reason = moto_freezer
14:32:06.962  D ActivityManager: unfreezing 9382 com.runner ... sync unfroze for 3
14:32:15.719  I AppWidgetServiceImpl: Bound widget 108 to provider com.runner.TaskWidget
14:32:15.728  D AppWidgetServiceImpl: Trying to notify widget update for package com.runner
14:32:25.889  D ActivityManager: freezing 9382 com.runner again (moto_freezer)
```
The system bound the widget and tried to send `APPWIDGET_UPDATE` — but the temporary unfreeze ("sync unfroze for 3") expired 9 seconds earlier. The broadcast was dropped because the process was frozen again.

### 3. Why other widgets worked
`org.fossify.calendar`, `org.breezyweather`, MacroDroid, etc. — their providers' processes are kept alive (notifications, services, or already whitelisted), so their broadcasts got delivered. `com.runner` had nothing keeping it alive, so it was a prime freezer target.

---

## The fix (exact commands)

```bash
# 1. Doze whitelist (immediate, persistent)
adb shell cmd deviceidle whitelist +com.runner

# 2. Allow background processing (immediate, persistent)
adb shell cmd appops set com.runner RUN_ANY_IN_BACKGROUND allow
adb shell cmd appops set com.runner RUN_IN_BACKGROUND allow

# 3. Battery optimization → Unrestricted (UI setting)
#    Settings → Apps → com.runner/Runner → Battery → Unrestricted
#    (or via intent: adb shell am start -a android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -d "package:com.runner")

# 4. Fresh install to clear the frozen state
adb uninstall com.runner
adb install out/termux-tasks.apk
```

## Proof it works

```
adb logcat -s RunnerWidget:* RunnerMain:*
09-03 14:51:41  I RunnerWidget: onReceive: android.appwidget.action.APPWIDGET_ENABLED
09-03 14:51:41  I RunnerWidget: onEnabled called
09-03 14:51:41  I RunnerWidget: onReceive: android.appwidget.action.APPWIDGET_UPDATE
09-03 14:51:41  I RunnerWidget: onUpdate called with 1 ids
09-03 14:51:41  I RunnerWidget: setPendingIntent for widget 110
09-03 14:51:41  I RunnerWidget: updateAppWidget called for widget 110
09-03 14:51:42  I RunnerMain: onCreate — launched!        ← tap fires the app
09-03 14:51:44  I RunnerMain: onCreate — launched!        ← tap again, again
09-03 14:51:45  I RunnerMain: onCreate — launched!
```

`APPWIDGET_ENABLED` → `onEnabled` → `APPWIDGET_UPDATE` → `onUpdate` → PendingIntent set → tap → app launches. Textbook behavior, finally.

---

## Lessons learned (for future Motorola devices)

1. **Add `Log.i` to every widget callback FIRST** — it tells you in 2 minutes whether the provider is being invoked, instead of 60 failed builds.
2. **Get adb working before debugging widgets** — `dumpsys appwidget` and `dumpsys activity processes` turn "black box" widget debugging into a solvable problem. (termux-adb-self skill covers the linker fix + wireless setup.)
3. **OEM freezer/battery managers are the #1 cause of "widget/app dead in background"** on Motorola/Xiaomi/OnePlus/Samsung. Check `isFrozen` before blaming PendingIntent.
4. **`isFrozen=true` + `reason=moto_freezer` + `APPWIDGET broadcast dropped`** is the signature. Don't chase intent flags until you've ruled it out.

## State of the code

- **Build:** javac + dx + aapt(old) + apksigner (Termux toolchain — was never the problem)
- **Package:** `com.runner` (renamed during debugging; works)
- **Working widget code:** `TaskWidget.java` with `onUpdate()` that sets a `getActivity()` PendingIntent (`MainActivity`), plus `Log.i` diagnostics
- **To restore next:** real `MainActivity` behavior (Termux script picker flow from git history) — currently the diagnostic Toast version is installed