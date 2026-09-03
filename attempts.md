# Attempts Log

## Symptom
Widget appears on home screen but the provider is NEVER bound — no lifecycle callbacks fire (onEnabled/onUpdate/onReceive). The widget renders from initialLayout XML but the system never contacts the provider class. Tapping does nothing (no PendingIntent was ever set). Other widgets from other apps work fine on the same KISS launcher.

## Attempts

### 1. Broadcast to TaskWidget (getBroadcast)
- **What**: Widget tap → `PendingIntent.getBroadcast()` → `TaskWidget.onReceive()` → fires Termux. No activity involved.
- **Files changed**: `TaskWidget.java` (full rewrite), `MainActivity.java` (simplified, removed fire())
- **Result**: ❌ Broke app-from-drawer (removed fire()). Widget didn't work. Reverted.

### 2. Broadcast to TaskWidget, keep fire() in MainActivity
- **What**: Same as #1 but kept `TaskWidget.fire(this)` in `MainActivity.setupWidget()` so app drawer still works.
- **Result**: ❌ Widget didn't launch at all.

### 3. getActivity with FLAG_IMMUTABLE (original approach + FLAG_IMMUTABLE)
- **What**: Back to `PendingIntent.getActivity()` but with `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`.
- **Result**: ❌ Widget worked once, then stopped.

### 4. moveTaskToBack instead of finish()
- **What**: Keep activity alive in background so PendingIntent doesn't get invalidated. `moveTaskToBack(true)` instead of `finish()`.
- **Result**: ❌ Same problem. Also broke app-from-drawer (only works once too).

### 5. IntentService (getService)
- **What**: Widget tap → `PendingIntent.getService()` → `FireService` → fires Termux + re-sets widget PendingIntent.
- **Result**: ❌ Same as original (works once, then stops).

### 6. Broadcast to WidgetReceiver (separate BroadcastReceiver)
- **What**: Widget tap → `PendingIntent.getBroadcast()` → separate `WidgetReceiver` class → fires Termux.
- **Result**: ❌ Widget never worked (showed resize/remove on tap).

### 7. FrameLayout root + clickable + getActivity
- **What**: Changed widget layout to `FrameLayout` root with `clickable="true"`, `focusable="true"`. Set PendingIntent on `R.id.widget_root` instead of `R.id.btn_run`. Back to `getActivity()`.
- **Result**: ❌ Widget never worked (showed resize/remove on tap).

### 8. onUpdate + updatePeriodMillis + FrameLayout root + Toasts (debug build)
- **What**: `TaskWidget.onUpdate()` sets PendingIntent (instead of empty). `updatePeriodMillis="3600000"` (1hr). Added Toasts to debug `getAppWidgetIds()` count. Back to `getActivity()` with `FLAG_ACTIVITY_CLEAR_TOP`.
- **Result**: ❌ No Toasts appeared (widget never showed them)

### 10. Self-healing broadcast (getBroadcast + re-arm in onReceive)
- **What**: Widget tap → `PendingIntent.getBroadcast()` → `TaskWidget.onReceive(ACTION_LAUNCH)` → fires Termux + **re-arms widget PendingIntent** for next tap. `onUpdate()` also arms it. `MainActivity.setupWidget()` delegates to same `armWidget()`. Added `Log.i` calls for debugging.
- **Key insight**: "removing widget and adding it again makes it work once... seems like a matter of resetting after invocation" — KISS may re-render widget from stale cache after PendingIntent fires, dropping the click handler. Self-healing: every tap calls `updateAppWidget()` again to re-arm.
- **Result**: ❌ Widget never worked (getBroadcast not supported by KISS for widgets)

### 11. Self-healing broadcast + FLAG_IMMUTABLE (Android 16 fix)
- **What**: Same as #10 (getBroadcast + re-arm on tap) BUT added `FLAG_IMMUTABLE` to PendingIntent. Device is Android 16 (API 36) — PendingIntent without FLAG_IMMUTABLE can silently fail on newer Android. Also switched debugging from logcat (not accessible without root) to Toasts. `updatePeriodMillis=1800000` (30min re-arm).
- **Result**: ❌ Widget never worked (resize/remove on tap)

### 12. WidgetConfig activity + delayed re-arms + SharedPreferences + FLAG_IMMUTABLE
- **What**: 
  - `WidgetConfig` activity — system forces it to run when widget is added, sets PendingIntent
  - `TaskWidget.onUpdate()` + `onAppWidgetOptionsChanged()` + `onEnabled()` — all call `armWidget()`
  - `MainActivity` saves widget ID in SharedPreferences, re-arms from saved ID (bypasses getAppWidgetIds())
  - **Key fix**: `MainActivity` schedules delayed re-arms at 1s, 3s, 5s after each tap — covers KISS re-rendering
  - `updatePeriodMillis=1800000` (30min) — periodic onUpdate
  - `FLAG_IMMUTABLE` — Android 16 requirement
- **Result**: ❌ Widget never worked (resize/remove on tap) — delayed re-arms silently failed because they used `MainActivity.this` (activity context) which is invalid after `finish()`

### 13. Delayed re-arm with Application context
- **What**: Removed `setupWidget()`, `scheduleReArm()`, SharedPreferences. `armAndFire()` now: saves widgetId → fires command → finishes → posts delayed re-arm on `Looper.getMainLooper()` using `getApplicationContext()`. 3 second delay gives KISS time to re-render before re-arming.
- **Result**: ❌ Did not work (complex, reverted)

### 14. `Button` layout, no configure activity, onUpdate only
- **What**: Simple widget. Button (inherently clickable) in layout, no FrameLayout wrapper. `onUpdate()` sets PendingIntent without `FLAG_IMMUTABLE`. No configure activity. `WidgetConfig.java` removed.
- **Result**: ❌ Tapping widget does nothing — hold behavior gone (PI IS being set on RemoteViews), but tap doesn't fire.

### 15. Other agent's suggestions: setAction, requestCode=widgetId, CLEAR_TOP
- **What**: Applied: `Intent.setAction(ACTION_MAIN)`, `requestCode=widgetId` (was 0), `FLAG_ACTIVITY_CLEAR_TOP`. No configure activity. `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`.
- **Files changed**: `TaskWidget.java` only
- **Result**: ❌ Still nothing on tap.

### 16. targetSdkVersion 28 → 34 + configure activity
- **What**: Raised `targetSdkVersion` from 28 to 34. Re-added `WidgetConfig` configure activity. Set widget text to "▶ GO 79" to verify `updateAppWidget()` persists.
- **Result**: ❌ Configure activity ran (Toast "config 80"), but widget showed "▶ Run" — **configure activity's `updateAppWidget()` is discarded** by the system/KISS on this Android 16 device.

### 17. onUpdate() text change diagnostic (no configure activity)
- **What**: Removed configure activity. `onUpdate()` changes widget text to "▶ GO " + id to prove it runs.
- **Result**: ❌ Widget showed "▶ Run" — **KISS never calls `onUpdate()`** on this device.

### 18. FLAG_CANCEL_CURRENT + re-arm after finish (app drawer flow)
- **What**: App drawer opens MainActivity → `setupWidget()` + `fire()` + `finish()` + `reArm()` after 2s with app context. Used `FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE` in PendingIntent.
- **Result**: ❌ **First tap also didn't work.** `FLAG_CANCEL_CURRENT` breaks the PendingIntent entirely.

### 19. FLAG_UPDATE_CURRENT + re-arm after finish + FrameLayout
- **What**: `FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE`. FrameLayout+TextView layout. App drawer flow: `setupWidget()` → `fire()` → `finish()` → `reArm()` 2s later via app context.
- **Result**: ❌ Widget shows hold behavior (resize/remove). `setupWidget()` from app drawer isn't sticking.

### 20. AlarmManager broadcast re-arm (survives process death)
- **What**: Uses `AlarmManager.setExactAndAllowWhileIdle()` to schedule a `BroadcastReceiver` 3s after finish, re-arming the widget PendingIntent. Survives process death. Separate `RearmReceiver` class in manifest.
- **Result**: ❌ Never tested — user lost confidence.

### 21–44. Various iterations of the above (re-arming, layout changes, flag tweaks)
- **Result**: ❌ All failed. Widget never successfully fired its PendingIntent on this device.

### 45. singleTask + moveTaskToBack (keep activity alive, never finish)
- **What**: `singleTask` launch mode. `moveTaskToBack(true)` instead of `finish()` on every invocation. Activity stays in background indefinitely. `setupWidget()` only on app-drawer opens. PendingIntent should stay valid because the hosting activity never dies.
- **Result**: ❌ Never tested — user lost confidence.

## Key Observations (final, after 61 attempts)
- `getActivity()` is the only PendingIntent type that works with KISS; `getBroadcast()` and `getService()` never worked
- **KISS/system never calls `onUpdate()`, `onEnabled()`, or any callback on our provider** — proven by Toast diagnostics in every callback. The widget renders from initialLayout XML but the provider class is never bound.
- **Configure activity's `updateAppWidget()` is discarded** — proven by diagnostic (text change didn't stick)
- **`getAppWidgetIds()` works correctly** — returns the widget ID when called from MainActivity (app drawer open). System knows the widget exists but never binds the provider.
- **The re-arm pattern (setupWidget() from app drawer) no longer works either** — even the one thing that previously set a working PendingIntent stopped working. Possible regression, or earlier "re-arm worked" was a misread (text change stuck, but tap never worked).
- **Other widgets work on this same KISS launcher** — the problem is specific to our implementation
- `FLAG_CANCEL_CURRENT` breaks PendingIntent entirely on this device
- `targetSdkVersion` 28→34, `exported` true→false, package rename, build toolchain changes — nothing made any difference
- **MacroDroid widget works** — but requires overlay permission (blocked for sideloaded apps on this device)
- **`logcat` inaccessible** — shows only ~10 lines without root. `adb` from Termux is broken (protobuf linker error)
- **APK structure is verified correct** — DEX present (035 format), classes verified with javap, resources parsed by aapt2 dump, signature verified (v2/v3 schemes)
- The one variable never eliminated: Android Studio/Gradle build

## Conclusion

After 61 attempts across all known approaches, the widget tap never successfully launched the Activity on this device (KISS launcher, Android 16, Motorola). The root cause is now narrowed to: **the system never binds our AppWidgetProvider class.** The widget renders from initialLayout XML but the provider never receives any lifecycle callbacks (onEnabled/onUpdate/onReceive). getAppWidgetIds() confirms the system knows about the widget, but it never instantiates the provider.

This is highly unusual — the standard contract is: widget placed → provider bound → onEnabled() → onUpdate() → PendingIntent set on RemoteViews. The fact that the widget displays but the provider is never bound suggests either:
1. **Build toolchain issue** — the Termux build (ecj/javac + dx + aapt/aapt2 + apksigner) produces an APK that the system's AppWidgetService silently refuses to bind. The only variable never eliminated.
2. **KISS host quirk** — KISS may be caching the widget from initialLayout without ever binding the provider, for some specific reason related to our APK.

Recommended next step: **Build the identical widget using Android Studio/Gradle** — the one variable never tested.

### 46. ACTION_MAIN + CATEGORY_LAUNCHER + setPackage() (drawer-mimic intent)
- **What**: Changed `TaskWidget.java` PendingIntent from `new Intent(context, MainActivity.class)` to `ACTION_MAIN + CATEGORY_LAUNCHER + setPackage(context.getPackageName())` — the exact same intent the system sends when you tap an app icon in the drawer. Stripped `singleTask` and `moveTaskToBack()` from `MainActivity`, back to simple `finish()`. `setupWidget()` re-arms on every app-drawer open.
- **Theory**: 45 attempts used `Intent(context, MainActivity.class)` — an explicit ComponentName, which is NOT how launchers normally launch apps. KISS may silently filter PendingIntents whose Intent doesn't look like a standard app launch. If KISS delivers the tap but the system can't resolve the intent properly, this fix should work.
- **Build**: versionCode=46
- **Result**: ❌ Widget tap does nothing. Same behavior.

### 47. FLAG_ACTIVITY_CLEAR_TASK (force fresh instance)
- **What**: Changed flags to `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` — "force fresh app instance" like MacroDroid's option. Never tried before (we had CER_TOP and RESET_TASK_IF_NEEDED but not CLEAR_TASK).
- **Result**: ❌ Widget tap does nothing.

### 48. Explicit FLAG_IMMUTABLE + widgetId as requestCode
- **What**: Per other agent's recommendation: `PendingIntent.getActivity(context, widgetId, tap, FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE)` with explicit `Intent(context, MainActivity.class)` + `CLEAR_TASK`.
- **Result**: ❌ Widget tap does nothing.

### 49. Root TextView layout + FLAG_MUTABLE
- **What**: Layout root changed to plain `TextView` (no FrameLayout wrapper) so PendingIntent target IS the root view. PendingIntent used FLAG_MUTABLE (0x02000000) in case KISS needs to modify the intent.
- **Result**: ❌ Widget tap does nothing.

### 50-52. Overlay approach (abandoned)
- **What**: Rebuilt as overlay service (SYSTEM_ALERT_WINDOW) — replicating MacroDroid's approach. Foreground service with floating button, tap → startActivity.
- **Result**: ❌ Google blocks SYSTEM_ALERT_WINDOW for sideloaded apps on this device. Overlay permission cannot be granted. sc:
  - `appops set` → not found
  - `cmd appops set` → SecurityException (no INTERACT_ACROSS_USERS)
- Abandoned and removed.

### 53. Toast diagnostics in all callbacks
- **What**: Added Toasts to `onEnabled()`, `onUpdate()`, `onReceive()` — every possible callback.
- **Result**: ❌ **ZERO Toasts appeared when adding the widget.** The system NEVER talks to our provider. Not onEnabled, not onUpdate, not onReceive, nothing. The widget appears (from initialLayout XML) but the provider class is never bound/instantiated.

### 54. Removed overlay permissions (clean manifest)
- **What**: Removed ALL overlay/foreground-service cruft. Back to clean AppWidget-only APK. Kept Toast diagnostics.
- **Result**: ❌ Zero Toasts. Provider never bound.

### 55. Receiver exported=false + getAppWidgetIds() diagnostic
- **What**: Changed receiver `exported="true"` → `exported="false"` (standard Android Studio template uses false). Added Toast showing getAppWidgetIds() count in MainActivity.
- **Result**: ❌ Still zero Toasts from provider. BUT key finding: opening app from drawer shows "Widget IDs: 1" AFTER widget placed (system knows widget exists), "No widget IDs found" BEFORE widget placed. **getAppWidgetIds() works and tracks the widget correctly.**

### 56. Full revert to original config
- **What**: Reverted setPendingIntent to ORIGINAL: `Intent(context, MainActivity.class)`, `requestCode=0`, `FLAG_UPDATE_CURRENT`, FrameLayout+TextView layout. Removed all experimental flags.
- **Result**: ❌ Re-arm from app drawer **no longer works either** — even the one thing that previously set a working PendingIntent. Something fundamental changed, OR the earlier "re-arm works" was never actually verified as making the TAP work (it made the text change stick, which was misread).

### 57. Absolute minimal widget (no Termux, no permissions, just Toast)
- **What**: Stripped EVERYTHING. MainActivity just shows Toast "LAUNCHED!" + finish(). No RUN_COMMAND, no permissions, no Termux. Widget → PendingIntent → MainActivity → Toast.
- **Result**: ❌ Widget tap does nothing. No "LAUNCHED!" Toast.

### 58. Renamed package com.termuxwidget → com.runner
- **What**: User theory: package name didn't match app label "Runner". Moved src to com/runner, updated manifest + build.sh.
- **Result**: ❌ Widget tap does nothing.

### 59-61. Build toolchain experiments
- **What**: Switched ecj → javac (JDK 21, -source 1.8 -target 1.8). Then aapt2 → aapt (old tool).
- **Results**: ❌ All built and installed cleanly but widget tap does nothing. DEX verified present (dex.035 format), classes verified (j avap), resources verified (aapt2 dump), signature verified (v2/v3 schemes). APK structure is 100% correct.

## NEW Findings (attempts 46-61)

### THE CRITICAL FINDING
**The system never sends ANY broadcast to our AppWidgetProvider.** Zero Toasts from onEnabled/onUpdate/onReceive when adding the widget. But `getAppWidgetIds()` (called from MainActivity via app drawer) correctly returns the widget ID.

This means:
- The system CAN see the widget (getAppWidgetIds finds it)
- The widget RENDERS (from initialLayout XML)
- But the provider is NEVER bound — no lifecycle callbacks fire

### Other new findings
- `exported="false"` vs `exported="true"` on receiver: no difference
- Package rename com.termuxwidget → com.runner: no difference
- javac vs ecj, aapt vs aapt2: no difference (all APKs are structurally valid per aapt2 dump)
- `logcat` from Termux: shows almost nothing (10 lines) without root — system-level AppWidgetService logs inaccessible
- `adb` from Termux: installs but is BROKEN (protobuf linker error, android-tools 35.0.2-7)
- **MacroDroid widget WORKS on this device** — proves a properly-built widget can launch apps via KISS. It requires overlay permission (SYSTEM_ALERT_WINDOW) which Google blocks for sideloaded apps.
- Other working widgets on the same KISS launcher (user says "3 widgets that all open their respective apps")
- KISS release notes include recent fixes for "widgets not working" and "widget size not applied on Android 12+" — suggests known host-side issues
- The other agent's analysis (question.md/response.md): KISS likely restores/replaces RemoteViews from initialLayout after a re-render, wiping any PendingIntent set via updateAppWidget(). This matches: re-arm works briefly, then tap stops working.

### Unresolved questions
1. Why does KISS/system never fire onEnabled/onUpdate/onReceive to our provider, when it works for other apps' widgets?
2. Could this be a build tooling artifact (ecj/dx/aapt2 in Termux) that the system host silently tolerates differently than Android Studio builds?
3. Is MacroDroid's widget actually using AppWidget mechanism, or its own overlay/accessibility trick?

### Untested remaining options
- Build the identical widget via a real Android Studio/Gradle project (the only variable never eliminated)
- Install the Fector101 wallpaper-carousel widget APK (working example) as a control — does IT show Toasts/onUpdate on this device?
- Try Termux:GUI Widget entirely

### 62. ✅ WORKS — Root cause: Motorola moto_freezer
- **What**: Installed via adb (after fix the linker error from the termux-adb-self skill: `apt-get install abseil-cpp=20250814.1 libprotobuf=2:33.1-1 --allow-downgrades`). Connected via Wireless debugging. Confirmed via logcat that Motorola's `moto_freezer` was freezing the com.runner process (`isFrozen=true`, `reason = moto_freezer`) which prevented `APPWIDGET_UPDATE` broadcasts from being delivered.
- **Fix applied**:
  1. `adb shell cmd deviceidle whitelist +com.runner`
  2. `adb shell cmd appops set com.runner RUN_ANY_IN_BACKGROUND allow`
  3. `adb shell cmd appops set com.runner RUN_IN_BACKGROUND allow`
  4. Set Runner to battery Unrestricted (via settings)
  5. Full uninstall + fresh install via adb
- **Result**: ✅ **WORKS!** Logcat confirms: `onEnabled` → `APPWIDGET_UPDATE` → `onUpdate called with 1 ids` → `setPendingIntent widget 110` → `updateAppWidget` → tap → `MainActivity onCreate launched`. Widget now opens the app like from the app drawer.
- **Root cause after 62 attempts**: NOT the code, NOT PendingIntent flags, NOT layout, NOT build toolchain. The Motorola Moto freezer was freezing the app process so the system's AppWidgetService broadcast never reached the provider. All the earlier "no Toast, no onUpdate" diagnostics were the frozen process refusing broadcasts.

## Uninstall from Termux (no root)

`pm uninstall` fails with `SecurityException` on this device. Use:

```
am start -a android.intent.action.DELETE -d "package:com.runner"
```

This opens the system settings uninstaller. Tap OK.