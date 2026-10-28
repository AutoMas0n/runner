# Proposal

## Why

The widget's aidhd dialogue can add tasks, run the secretary, and open the dashboard — but it has no way to make sure reminders are actually installed and that the device stays awake long enough for the hourly reminder job to fire. Users who never install the schedule (or whose Termux process gets killed on screen-off) get no notifications at all. A dedicated button closes that gap in one tap and makes the state visible.

## What Changes

- **Widget dialogue layout**: Add a "⏰ Reminders + Wake-lock" button row between the quick actions and the Serve/Back row in `res/layout/aidhd_dialog.xml`.
- **MainActivity**: Wire `btn_reminders` to run the new `widget-aidhd-reminders` wrapper via the existing command bridge.
- **Wrapper script**: Create `~/.shortcuts/widget-aidhd-reminders` that (a) runs `octx x aidhd reminders enable` idempotently — repeated taps are safe because `enable` overwrites the same job id — and (b) toggles the Termux wake-lock using a marker file in the aidhd state directory.
- **New ID in R.java** (auto-generated via aapt): `btn_reminders`.

## Out of Scope

- No change to the aidhd CLI's reminder semantics or the hourly gating logic.
- No change to the notification delivery path in the `aidhd` arm itself.
