# Scheduled workflows

A hive can start by itself, on a schedule (`workflow/schedule/`). **Keeper's room › Schedules** lists them: turn one on, change when it runs, run it now, or remove it. The coding suite ([Coding workflows](Coding-Workflows.md)) brings six, off.

## What a schedule is

`WorkflowSchedule`: a hive (by id, plus a **fingerprint** of its definition, below), an objective (optionally followed, when it starts, by the swarm's own evidence: `withEvidence`, used by the self-improvement cycle; the run's objective is then marked as external content for the context-taint rule, because the evidence quotes model-written text), a cadence, on or off (**off by default**), and what it last did (`lastStartedAtEpochMillis`, `lastNote`). Cadences (`ScheduleCadence`): every N hours (1 to 744), daily at an hour, weekly on a day at an hour. The hour of a daily or weekly schedule is in the schedule's own **offset from UTC** (`utcOffsetMinutes`, in half-hour steps with **Zone west / Zone east**, UTC until set): the app reads no time zone from the device. The offset is the schedule's **winter** (standard) offset, and a schedule can follow a **daylight-saving rule** (`DaylightSaving`: none, the US rule, the EU rule; none until the keeper picks one), so the hour stays the same on the wall clock; elsewhere (the southern hemisphere, a trip) it is still moved by hand. A wall time the clocks skip runs just after the gap, once; one they repeat runs at its first occurrence. Every-N-hours schedules are not affected. Schedules are kept on this device (`SettingsWorkflowScheduleStore`, one JSON list). A list that cannot be read is **not** read as empty and then overwritten: its raw text is kept under `<key>.corrupt`, nothing starts, every write is refused, and the Schedules screen offers **Discard the unreadable list** (the keeper's decision to start over). Schedules saved before the fingerprint and daylight-saving fields existed still load, with those fields empty.

## When one starts

`ScheduleTicker` looks every minute while the app is open. `decideSchedules` (pure, `ScheduleClockTest`) decides:

- A schedule is due when it is on and its next slot has come. The next slot is the first one after its last start, or after it was made: **a new schedule never starts for a slot that already passed**.
- A schedule whose slots passed while the app was closed starts **once**, at the next look, not once per missed slot.
- At most one starts per look, the most overdue first.
- A schedule carrying a **run-now request** (`runNowRequested`, set by the Improvement screen's **Run a cycle now** and by the Schedules screen's **Run now**) is due at the next look whether it is on or not; starting it clears the request, and it never turns the schedule on. The request waits, and keeps its place, while another run is active.
- The self-improvement cycle's schedule is not started when its hive no longer keeps the loop's own rules (`LoopIntegrity.startRefusal`: it reads and reports, never waits on a person, examines itself); the schedule's note says which rule. You can still run that hive by hand.
- **A schedule is bound to a hive, and to what it was when you set it.** Each schedule stores a fingerprint (`ScheduleVetting.fingerprint`: a hash of the hive's steps, executors, order, conditions and policies; its name and description are not part of it; the roles it names are separate records and are not). It is taken when the keeper adds the suite or turns the schedule on. If the hive differs at a start (edited, replaced by an import, a package update, an adopted improvement, a control-API edit), the run is **held**: the schedule is turned off and says "The hive changed since you scheduled it, so it was not started. Review the hive, then turn the schedule on again." Turning it on again refreshes the fingerprint. A schedule saved before fingerprints existed has none and **takes the hive as it is on its first start** (a choice: there is nothing earlier to compare with). A run-now request is held the same way. Adopting a change to the self-improvement loop therefore turns its schedule off until you turn it on again.
- **Hives that spend money are never scheduled.** A hive with a `film-frames` or `film-assemble` step (directly, wrapped to run on another node, or in a hive it nests) is refused at the start and when the schedule is turned on: the schedule is turned off with a note, and the coding-suite installer skips it. Run such a hive by hand.
- **One run is active at a time.** If a run has not finished (a run waiting on a person counts), the schedule is held, not skipped: it starts when that run does. The hold is said once in the log, and written on the schedule.
- **A start is claimed before it is launched.** In one read-modify-write on the stored schedule, the ticker records the start (and clears a run-now request) only if the schedule is still due and has not started since it was read; otherwise it does nothing. So a slow launch, a second look, or a second window cannot start the same slot twice, and what the ticker writes afterwards changes only the note, never a copy read earlier: a schedule you turned off, changed or removed during a launch stays that way. The store serialises access inside one app; two app instances sharing the same storage (two browser tabs, two desktop windows) are not serialised against each other by the settings API, so the claim narrows the race to one short read and write rather than closing it.
- A start that fails (no connected agent, the hive was deleted) is written on the schedule with its reason, counts as that slot's attempt, and is not retried every minute.

## What it can and cannot do

It starts the hive **as drawn**: no planner, no plan to confirm, the project that is open, that project's repository. It cannot approve anything. A hive with an approval step stops there and waits for you like any other run, and holds the next schedule behind it. That is why the suite's scheduled hives have no approval step in them. It adds no permission: a launch from a schedule goes through the same `launchSavedWorkflow` as one you start.

## Where to see it

- The schedule card: next start, last start and what happened.
- **Log › App log:** a line for each decision (`Schedule 'Nightly dependency audit': Started.`, the hold, a failed start).
- The run itself, as any run: the hive, its Timeline, its Log.

## Open decisions (what to check against real use)

- **Only while the app is open.** There is no background service: Android has none for this (the ticker runs in the app's composition, so a backgrounded or closed app does not tick), and the desktop app has no tray. A schedule is wrong for "every night at 02:00" if the app is closed at 02:00; it starts once when the app next opens and is in front. The Schedules screen and the coding-suite copy say so. A real background runner is a platform piece (WorkManager on Android, the control API on desktop) and is not built.
- **No device time zone.** The offset is typed by the keeper; the US and EU daylight-saving rules are built in, and nothing else is (no southern-hemisphere rules, no zone database, nothing follows a trip). Reading the device's zone would need a platform call on each target and is not built.
- **One active run.** A scheduled run waiting on a person blocks every other schedule and every run you start. It is wrong if you want parallel runs; the runtime keeps one active run.
- **The project that is open.** A schedule does not name its project or repository; it uses the open one. Wrong for a keeper with several repositories: a schedule would need to keep a project.
- **No history beyond the last note.** The log has the decisions; runs are in the hive's history. A per-schedule list of past runs is not built.
