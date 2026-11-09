# Scheduled workflows

A hive can start by itself, on a schedule (`workflow/schedule/`). **Keeper's room › Schedules** lists them: turn one on, change when it runs, run it now, or remove it. The coding suite ([Coding workflows](Coding-Workflows.md)) brings six, off.

## What a schedule is

`WorkflowSchedule`: a hive (by id), an objective (optionally followed, when it starts, by the swarm's own evidence: `withEvidence`, used by the self-improvement cycle; the run's objective is then marked as external content for the context-taint rule, because the evidence quotes model-written text), a cadence, on or off (**off by default**), and what it last did (`lastStartedAtEpochMillis`, `lastNote`). Cadences (`ScheduleCadence`): every N hours (1 to 744), daily at an hour, weekly on a day at an hour. The hour of a daily or weekly schedule is in the schedule's own **offset from UTC** (`utcOffsetMinutes`, in half-hour steps with **Zone west / Zone east**, UTC until set): the app reads no time zone from the device. The offset is fixed, so it does not follow daylight saving; move it when the clocks change. Schedules are kept on this device (`SettingsWorkflowScheduleStore`, one JSON list; an unreadable list reads as empty rather than breaking the app).

## When one starts

`ScheduleTicker` looks every minute while the app is open. `decideSchedules` (pure, `ScheduleClockTest`) decides:

- A schedule is due when it is on and its next slot has come. The next slot is the first one after its last start, or after it was made: **a new schedule never starts for a slot that already passed**.
- A schedule whose slots passed while the app was closed starts **once**, at the next look, not once per missed slot.
- At most one starts per look, the most overdue first.
- A schedule carrying a **run-now request** (`runNowRequested`, set by the Improvement screen's **Run a cycle now**) is due at the next look whether it is on or not; starting it clears the request, and it never turns the schedule on. The request waits, and keeps its place, while another run is active.
- The self-improvement cycle's schedule is not started when its hive no longer keeps the loop's own rules (`LoopIntegrity.startRefusal`: it reads and reports, never waits on a person, examines itself); the schedule's note says which rule. You can still run that hive by hand.
- **One run is active at a time.** If a run has not finished (a run waiting on a person counts), the schedule is held, not skipped: it starts when that run does. The hold is said once in the log, and written on the schedule.
- A start that fails (no connected agent, the hive was deleted) is written on the schedule with its reason, counts as that slot's attempt, and is not retried every minute.

## What it can and cannot do

It starts the hive **as drawn**: no planner, no plan to confirm, the project that is open, that project's repository. It cannot approve anything. A hive with an approval step stops there and waits for you like any other run, and holds the next schedule behind it. That is why the suite's scheduled hives have no approval step in them. It adds no permission: a launch from a schedule goes through the same `launchSavedWorkflow` as one you start.

## Where to see it

- The schedule card: next start, last start and what happened.
- **Log › App log:** a line for each decision (`Schedule 'Nightly dependency audit': Started.`, the hold, a failed start).
- The run itself, as any run: the hive, its Timeline, its Log.

## Open decisions (what to check against real use)

- **Only while the app is open.** There is no background service: Android has none for this, and the desktop app has no tray. A schedule is wrong for "every night at 02:00" if the app is closed at 02:00; it starts when the app next opens. A real background runner is a platform piece (WorkManager on Android, the control API on desktop) and is not built.
- **A fixed offset.** It does not follow daylight saving or a trip. Wrong twice a year; reading the device's zone is the fix and needs a platform call on each target.
- **One active run.** A scheduled run waiting on a person blocks every other schedule and every run you start. It is wrong if you want parallel runs; the runtime keeps one active run.
- **The project that is open.** A schedule does not name its project or repository; it uses the open one. Wrong for a keeper with several repositories: a schedule would need to keep a project.
- **No history beyond the last note.** The log has the decisions; runs are in the hive's history. A per-schedule list of past runs is not built.
