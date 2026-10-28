# aidhd-widget Specification

## Purpose

Provides a TV-widget frontend to the aidhd task management system — lets users type or speak a new task, run the secretary, and launch the web dashboard from the homescreen without opening a terminal.

## Requirements

### Requirement: Widget has two side-by-side buttons
The home-screen widget SHALL display two touchable buttons side by side: the existing "▶ Run" on the left and "aidhd" on the right.

#### Scenario: Widget shows both buttons
- **WHEN** the widget is placed on the homescreen
- **THEN** the user sees both a "▶ Run" button and an "aidhd" button

### Requirement: aidhd button opens a dedicated dialogue
Tapping the aidhd widget button SHALL open the app showing the aidhd input dialogue (not the script list).

#### Scenario: Tap aidhd button opens dialogue
- **WHEN** the user taps the "aidhd" widget button
- **THEN** the app opens showing the aidhd dialogue with a text input field, a Submit button, a Voice button, a Secretary button, and a Serve button

### Requirement: aidhd button is visually distinct
The aidhd button SHALL use green-colored text (distinct from the red "▶ Run" button) so the user can tell them apart at a glance.

#### Scenario: aidhd button appears green
- **WHEN** the widget is rendered
- **THEN** the "aidhd" text is green and the "▶ Run" text remains red

### Requirement: User can add a task by text
The aidhd dialogue SHALL provide a text input field where the user types a task title, and a Submit button that sends it to the aidhd CLI.

#### Scenario: Type a task and submit
- **WHEN** the user types "buy milk" in the text field and taps Submit
- **THEN** the system runs `octx x aidhd add "buy milk"` and shows a confirmation

#### Scenario: Submit with empty text shows an error
- **WHEN** the user taps Submit with the text field empty
- **THEN** the system SHALL NOT run the CLI and SHALL show a "task title cannot be empty" message

### Requirement: User can add a task by voice
The aidhd dialogue SHALL include a Voice button that captures speech via the system speech recognizer and auto-submits the recognized text to aidhd.

#### Scenario: Tap Voice captures speech
- **WHEN** the user taps the Voice button
- **THEN** the system opens the microphone, recognizes speech, and submits the recognized text to `octx x aidhd add`

### Requirement: User can run the secretary
The aidhd dialogue SHALL include a Secretary button that runs the aidhd secretary pass.

#### Scenario: Tap Secretary invokes aidhd secretary
- **WHEN** the user taps the Secretary button
- **THEN** the system runs `octx x aidhd secretary`

### Requirement: User can launch the web dashboard
The aidhd dialogue SHALL include a Serve button that starts `octx x aidhd serve` on port 7317 and opens the dashboard URL in the browser.

#### Scenario: Tap Serve starts server and opens browser
- **WHEN** the user taps the Serve button
- **THEN** the system starts `octx x aidhd serve --port 7317` in the background and opens `http://127.0.0.1:7317` via `termux-open-url`

### Requirement: Dialogue has a back button
The aidhd dialogue SHALL include a back button that returns the user to the script list (the original app view).

#### Scenario: Back button returns to script list
- **WHEN** the user taps the back button in the aidhd dialogue
- **THEN** the app shows the script list view

### Requirement: Missing octx shows a toast
If `octx` is not installed on the device, tapping any aidhd action button SHALL show a notification toast instead of running a command.

#### Scenario: Submit when octx is missing
- **WHEN** the user taps Submit and `octx` is not found
- **THEN** the system shows a toast: "octx not found — aidhd requires octx"

### Requirement: Text passes via clipboard bridge
When the user submits a task, the app SHALL copy the task text to the system clipboard before invoking the wrapper script, and the wrapper script SHALL read the text back with `termux-clipboard-get`.

#### Scenario: Task text round-trips through clipboard
- **WHEN** the user types "buy milk" and taps Submit
- **THEN** the app sets clipboard to "buy milk" before running the script, and the script reads it back via `termux-clipboard-get`

### Requirement: User can enable reminders and toggle wake-lock
The aidhd dialogue SHALL include a Reminders button that idempotently installs the aidhd reminder schedule and toggles the Termux wake-lock.

Tapping the button repeatedly SHALL NOT create duplicate scheduled jobs or leave the wake-lock in an inconsistent state; each tap flips the wake-lock between held and released and re-runs the idempotent reminder install.

#### Scenario: First tap enables reminders and holds the wake-lock
- **WHEN** the user taps the Reminders button and no wake-lock marker exists
- **THEN** the system runs `octx x aidhd reminders enable`, calls `termux-wake-lock`, writes the marker file, and shows a toast reporting "reminders on" and "wake-lock held"

#### Scenario: Second tap keeps reminders installed and releases the wake-lock
- **WHEN** the user taps the Reminders button while the marker file exists
- **THEN** the system runs `octx x aidhd reminders enable` again without creating a duplicate job, calls `termux-wake-unlock`, removes the marker file, and shows a toast reporting "reminders on" and "wake-lock released"

#### Scenario: Reminder install failure is surfaced
- **WHEN** `octx x aidhd reminders enable` exits non-zero
- **THEN** the toast reports "reminders failed" while the wake-lock toggle still takes effect
