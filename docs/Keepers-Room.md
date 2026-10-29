# The Keeper's room

The Aive has two places: **the hive**, where all work happens (`Swarm-OS.md`), and **the Keeper's room**, where everything that sets the hive up lives. This is stage 1 of consolidating the app into those two places; the plan is below.

## Parts

| Group | Part | Holds | Was |
|---|---|---|---|
| Feeds the hive | **Connections** | AI providers, repository hosts (GitHub, GitLab, Entire) | Settings › AI Providers, Repositories |
| | **Memory** | the memory layer | Memory |
| | **Compute** | the compute pool, and which work goes to which machine | Settings › Compute Pool, Compute |
| The keeper | **Projects and data** | project files, the setup file, JSON export and import, delete everything | Settings › Projects, Data, Privacy |
| | **This device** | remote access, the local planner, local orchestration specialists | Settings › Remote Access, Local Planner, Local Orchestration |
| | **Appearance** | the ground | Settings › Appearance |
| | **About and privacy** | crash reports, diagnostics | Settings › Crash Reports, Diagnostics |

Compute shows only where the build offers distributed compute.

## Layout

- **Wide:** the part list on the left (two groups, the open part filled), the open part on the right under its title and one-line lead.
- **Phone:** the list is its own page; a part opens over it with a "‹ Keeper's room" pill, and Back returns to the list. The header's gear opens the room from anywhere.
- The room remembers the open part (`keeper.section`).

## Connections

One row per thing (`ConnectionRow`): its name, one line about it, its status and what can be done to it. Connected things are Ink rows; things not yet connected are outlined. AI providers not yet connected sit behind one **Add a provider** row rather than a card each; with none connected, the free, keyless gateways come first. Local Git needs no account: a folder is picked when launching.

## Navigation

`ControlRoomDestination.Settings` is the Keeper's room. `Repositories`, `Compute` and `Memory` remain as destinations, so existing links still land, but each opens its part of the room and none has its own navigation entry. On a phone the room is the header's gear, not a pill in the row.

## Consolidation plan

1. **Stage 1 (this):** the Keeper's room; calm controls (`BRANDING.md`, Controls).
2. **Stage 2:** the hive as home. A first launch opens on the hive with the starter creature and one step to feed it (no "No project" form); a breadcrumb (Hive › a hive › a creature › its mind) replaces page titles and the navigation row.
3. **Stage 3:** Inbox, Runs, Artifacts and the Swarm role editor move into the creature's panel (Needs, Work, Mind) and the hive's combs; their destinations retire.
