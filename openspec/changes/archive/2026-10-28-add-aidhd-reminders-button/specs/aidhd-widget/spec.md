## ADDED Requirements

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
