# Tasks

## 1. Wrapper script

- [x] 1.1 Create `~/.shortcuts/widget-aidhd-reminders`, executable, with the `octx` presence guard used by the other wrappers.
- [x] 1.2 Make reminder setup idempotent by calling `octx x aidhd reminders enable` and tolerating repeat invocations.
- [x] 1.3 Toggle the wake-lock with a marker file in `~/.local/share/octx/aidhd/wakelock-on`, calling `termux-wake-lock` / `termux-wake-unlock`.
- [x] 1.4 Report both results in one `termux-toast`.

## 2. Dialogue UI

- [x] 2.1 Add a "⏰ Reminders + Wake-lock" button row to `res/layout/aidhd_dialog.xml`.
- [x] 2.2 Declare `btn_reminders` in `MainActivity` and bind its click listener to `widget-aidhd-reminders`.
- [x] 2.3 Regenerate `R.java` through the build so the new ID exists.

## 3. Build and verify

- [x] 3.1 Run `bash build.sh` and confirm the APK is produced without errors.
- [x] 3.2 Confirm the new string/id appears in the built APK (`strings` / `unzip -p`).
