# Fix: Widget tap stops working after first use

## Symptom
- **First time**: Open the "Runner" app from the drawer → grant permission → widget works (tap widget → script picker appears)
- **After that**: Widget tap does nothing. Only opening the app from the drawer works.

## Current architecture

### App (MainActivity.java)
Opening the app does:
1. Check/request `RUN_COMMAND` permission
2. Call `setupWidget()` — finds existing widget IDs via `AppWidgetManager`, sets a `PendingIntent.getActivity()` pointing back to `MainActivity`
3. Call `fire()` — fires `startService(RUN_COMMAND)` to Termux, which runs `~/.shortcuts/widget-launcher`
4. `finish()` — closes the activity

### Widget (TaskWidget.java)
The widget class is empty — `onUpdate` is intentionally left as a no-op because KISS launcher doesn't call it. The widget's PendingIntent is supposed to be set by `MainActivity.setupWidget()` each time the app opens.

## Root cause hypothesis

The widget's PendingIntent is set correctly the first time the app opens, and the widget tap works once. But after that first tap, the widget stops responding. This suggests:

1. **The PendingIntent is consumed/delivered once and then invalidated** — `FLAG_UPDATE_CURRENT` should prevent this, but maybe with `FLAG_IMMUTABLE` not set (targetSdkVersion 28), something goes wrong.

2. **The widget's RemoteViews are not persisted** — after the first tap triggers `MainActivity` → it runs `setupWidget()` again? No, `setupWidget()` only runs when the app is opened from the drawer, not when the widget fires. Wait — the widget tap opens `MainActivity`, which calls `setupWidget()` and `fire()`, then finishes. So each widget tap should re-setup the widget. This might actually be the bug: the widget opens MainActivity, which calls `setupWidget()` on itself, but `getAppWidgetIds()` returns empty because the widget was already consumed? Or `updateAppWidget()` doesn't work because the widget host (KISS) ignores it?

3. **The widget's `btn_run` PendingIntent is not sticky** — KISS might not persist RemoteViews updates after the first interaction. When the widget tap opens MainActivity and MainActivity calls `updateAppWidget()`, KISS might ignore this update because the widget is in a "tapped" state.

## Current code

### MainActivity.java
```java
private void setupWidget() {
    try {
        AppWidgetManager mgr = AppWidgetManager.getInstance(this);
        ComponentName cn = new ComponentName(this, TaskWidget.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids != null) {
            for (int id : ids) {
                Intent tap = new Intent(this, MainActivity.class);
                tap.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                PendingIntent pi = PendingIntent.getActivity(
                    this, id, tap,
                    PendingIntent.FLAG_UPDATE_CURRENT
                );

                RemoteViews views = new RemoteViews(getPackageName(), R.layout.widget_layout);
                views.setOnClickPendingIntent(R.id.btn_run, pi);
                mgr.updateAppWidget(id, views);
            }
        }
    } catch (Exception e) {
        // ignore
    }
}
```

### TaskWidget.java
```java
public class TaskWidget extends AppWidgetProvider {
    // Widget is configured by MainActivity when the app opens.
    // onUpdate intentionally left empty — KISS launcher doesn't call it.
}
```

### AndroidManifest.xml
```xml
<receiver
    android:name=".TaskWidget"
    android:label="Runner"
    android:exported="true">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
    </intent-filter>
    <meta-data
        android:name="android.appwidget.provider"
        android:resource="@xml/widget_info" />
</receiver>
```

## What to try (in order)

1. **Move `setupWidget()` out of `MainActivity`** — put it in `TaskWidget.onUpdate()` instead. Since the widget tap opens MainActivity, and `onUpdate` is called when the widget is added and at the update interval, setting `updatePeriodMillis` to a non-zero value might help KISS re-deliver the PendingIntent.

2. **Make TaskWidget non-empty** — override `onUpdate` to set the PendingIntent directly, like the original design. Even if KISS doesn't call it, it's worth testing with a non-zero update interval.

3. **Add `FLAG_MUTABLE`** — if the compilation environment supports it (API 31+), try `PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT`.

4. **Use `getBroadcast` instead of `getActivity`** — broadcast to self (TaskWidget.onReceive) which then fires the Termux command. This avoids the activity lifecycle issue.

5. **Check if `getAppWidgetIds()` returns empty on second call** — the widget might be removed from the widget host's list after first interaction with KISS.

## Files

| File | Path |
|------|------|
| Widget provider | `src/com/termuxwidget/TaskWidget.java` |
| Main activity | `src/com/termuxwidget/MainActivity.java` |
| Manifest | `AndroidManifest.xml` |
| Widget layout | `res/layout/widget_layout.xml` |
| Widget info | `res/xml/widget_info.xml` |
| Build script | `build.sh` |

## Repo

https://github.com/AutoMas0n/runner