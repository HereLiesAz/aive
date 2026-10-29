# The Keeper's room

The Aive has two places: **the hive**, where all work happens (`Swarm-OS.md`), and **the Keeper's room**, where everything that sets the hive up lives. Consolidating the app into those two places is planned in stages, below; stages 1 and 2 are done.

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
- **The breadcrumb**: Hive › a hive › a creature, or Hive › a place opened from it (Swarm, Inbox, the Keeper's room). The last step is where you are; any earlier step goes back up. Hive is the district, every hive at once; a hive is its swarm; a creature is the one selected. It replaces the page titles. On a phone it takes its own line.
- **The Inbox** badge and **the Keeper's room** gear.

Inside a hive, one row holds its places: its Swarm, the Memory swarm, Artifacts and the Store. The Store is also the district's edge. Runs and Workflows stay retired into the hive.

`ControlRoomDestination.Settings` is the Keeper's room. `Repositories` and `Compute` remain as destinations, so existing links still land, but each opens its part of the room.

## First launch

A first launch opens on the hive, not on a form (`MindMapRunScreen`, `FirstCreature`): the starters to keep one of, the first picked, and one field, **What should it work on?**, with **Feed it**. Feeding keeps the picked starter and names the hive from the first words it was fed. The name, a repository and saved projects are under Options. Until there is a hive, its places' row is hidden.

## Consolidation plan

1. **Stage 1 (done):** the Keeper's room; calm controls (`BRANDING.md`, Controls); Memory as a swarm in the hive; programmatic work as the neuron.
2. **Stage 2 (done):** the hive as home. A first launch opens on the hive with the starter creature and one step to feed it (no "No project" form); a breadcrumb (Hive › a hive › a creature) replaces page titles and the navigation row.
3. **Stage 3:** Inbox, Runs, Artifacts and the Swarm role editor move into the creature's panel (Needs, Work, Mind) and the hive's combs; their destinations retire. The breadcrumb gains the creature's last step: its mind.
