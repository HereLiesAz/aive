# The Keeper's room

The Aive has two places: **the hive**, where all work happens (`Swarm-OS.md`), and **the Keeper's room**, where everything that sets the hive up lives. Consolidating the app into those two places is planned in stages, below; all three are done.

## Parts

| Group | Part | Holds | Was |
|---|---|---|---|
| Feeds the hive | **Connections** | AI providers, repository hosts (GitHub, GitLab, Entire) | Settings › AI Providers, Repositories |
| | **Compute** | the compute pool, and which work goes to which machine | Settings › Compute Pool, Compute |
| The keeper | **Projects and data** | project files, the setup file, JSON export and import, delete everything | Settings › Projects, Data, Privacy |
| | **This device** | remote access, the local planner, local orchestration specialists | Settings › Remote Access, Local Planner, Local Orchestration |
| | **Appearance** | the ground | Settings › Appearance |
| | **About and privacy** | crash reports, diagnostics | Settings › Crash Reports, Diagnostics |

Compute shows only where the build offers distributed compute.

Memory is not here: it is a swarm of its own, running beside every other, so it lives in the hive (**Memory swarm**, beside Swarm at the top of the hive). Its stages that run without a model wear the neuron (`Node-Creatures.md`, Programmatic work).

## Layout

- **Wide:** the part list on the left (two groups, the open part filled), the open part on the right under its title and one-line lead.
- **Phone:** the list is its own page; a part opens over it with a "‹ Keeper's room" pill, and Back returns to the list. The header's gear opens the room from anywhere.
- The room remembers the open part (`keeper.section`).

## Connections

One row per thing (`ConnectionRow`): its name, one line about it, its status and what can be done to it. Connected things are Ink rows; things not yet connected are outlined. AI providers not yet connected sit behind one **Add a provider** row rather than a card each; with none connected, the free, keyless gateways come first. Local Git needs no account: a folder is picked when launching.

## Navigation

There is no navigation row. One header sits over every screen, wide and on a phone (`ShellHeader` in `ControlRoomUi.kt`):

- **The Aive** and how its runtime is.
- **The breadcrumb**: Hive › a hive › a creature › its tab, or Hive › a place opened from it (Swarm, the Keeper's room › its part). The last step is where you are and is not a button; any earlier step goes back up to it, popping rather than stacking. Hive is the district, every hive at once; from a place opened off the hive, Hive returns to the hive you came from. A creature's step closes its panel. It replaces the page titles. On a phone it takes its own line and scrolls to keep where you are in view.
- **The Inbox** badge and **the Keeper's room** gear.

Inside a hive, one row holds its places: its Swarm, the Memory swarm and the Store. The Store is also the district's edge. Runs are the hive's combs, and Workflows opens from a hive's More editing.

## A creature's panel

Selecting a creature opens its panel, in three tabs (`TechnicalInspector`, `CreatureTab`); the breadcrumb ends on the open one (Hive › a hive › a creature › Mind):

- **Needs:** its needs, and what it waits on you for: a plan or task to approve, an escalation, a nested gate, and messages to its agent. This was the Inbox.
- **Work:** its state, executor and provider, progress, what it was sent, and its artifacts, each opening on its contents. This was Artifacts.
- **Mind:** its creature, and its role's form, open from the start, its id fixed; applying it saves the swarm (`CustomCompanyProviderScreen(focusRole = …)`). This was the Swarm's role editor. The Swarm keeps adding, arranging and resetting roles.

A creature opens on Needs when it waits on you, otherwise on Work; the open tab survives a restart while the same creature stays open. Waiting on you means a plan or task to approve, an escalation, or a nested gate, one definition for the badge, the panel and the hive's Owner attention card (shown only then, opening that creature). The header's Inbox badge counts the waits not yet seen and opens the next unseen one, on its Needs; opening a creature's panel marks its wait seen. Only the Hall Monitor's pause, which speaks for the whole hive, keeps a place of its own (`Inbox`).

The panel's Needs holds only decisions; messages to its agent sit with its Work. Reject plan and Stop workflow stay quiet; the confirmation is where red appears (`BRANDING.md`).

Back closes the creature panel, then returns through the places opened off the hive, then zooms from a hive out to the district, then leaves the app. Reaching the hive clears the way back.

`ControlRoomDestination.Settings` is the Keeper's room; `Compute` opens its Compute part. The gear opens the room itself (its list, on a phone). Runs, Artifacts and Repositories no longer exist as destinations.

## First launch

Every launch opens on the hive, not on a form or the Keeper's room (`MindMapRunScreen`, `FirstCreature`): the starters to keep one of, the first picked, and one field, **What should it work on?**, with **Feed it**. With no AI provider connected, **Connect a provider** comes first and Feed it waits quietly. Feeding keeps the picked starter and names the hive from the first words it was fed (never from an empty tap). Its name, a repository and saved projects are under Options. Until there is a hive, its places' row is hidden.

## Consolidation plan

1. **Stage 1 (done):** the Keeper's room; calm controls (`BRANDING.md`, Controls); Memory as a swarm in the hive; programmatic work as the neuron.
2. **Stage 2 (done):** the hive as home. A first launch opens on the hive with the starter creature and one step to feed it (no "No project" form); a breadcrumb (Hive › a hive › a creature) replaces page titles and the navigation row.
3. **Stage 3 (done):** Inbox, Runs, Artifacts and the Swarm role editor move into the creature's panel (Needs, Work, Mind) and the hive's combs; their destinations retire. The breadcrumb gains the creature's last step.
