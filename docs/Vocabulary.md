# Vocabulary

The words the app shows you are not always the words the code uses. This page maps one to the other, so a screen, a
doc and a source file can be matched. Change a word on the screen here first; code identifiers, persisted keys and
protocol fields do not follow it.

| The app says | The code and technical docs say | Where you meet it |
| --- | --- | --- |
| **Story** | timeline (`RunTimeline`, `overview.timeline.*` keys) | The hive's **Story** pill: Waterfall, Events, Memory, Log, Flowchart. |
| **Edit** | Build (the view of a swarm, `building` state, `build_*` keys; not Setup: the setup file keeps that word) | The Live / Edit switch; the editor of the swarm on the hive (creatures, links, jobs). The verb "build" and a build version are not this. |
| **Source** | provider (`AgentProvider`, `ProviderCatalog`, `preferredProviderId`; the A2A Agent Card `provider:` skills) | A creature's **Source** row, Connections ("Add a source"), **AI Sources**, the Roster's source routing. |
| **Messages** | inbox (`LiveInboxScreen`, `InboxReadLog`) | The header's **Messages** button and the rail's Messages place: everything waiting on you. |
| **Nanny** | Hall Monitor (`HallMonitorRole`, `HallMonitorGovernanceService`, the `hall-monitor` role id) | The role that watches the swarm's efficiency and can pause the hive for your review. |
| **Host** | where an agent runs (`TaskExecutor`, the Hosts part of the Keeper's room, an orphan's endpoint host) | A creature's **Runs on** row and an orphan's **Host:** line: your device, a paired machine, or the orphan's own server. Different from its **Source** (what it thinks with). |
| **Swarm** | workflow (`WorkflowDefinition`, `WorkflowRun` for one execution; `workflow` in code, files and persisted keys) | The saved, editable plan: the queen, her creatures and the strands between them. The swarm library (the Workflows destination), **New swarm**, **Delete swarm**, **Swarm options**, **Share swarm card**, the Store's **Swarms** tab, Schedules. |
| **Hive** | the goal (today `Project` and the objective; D17: the project becomes the hive and gains a goal) | What the keeper wants done: **Your first hive**, **Feed the hive**, the **Hive** place and the **Hives** rail chip (the district, which still lists swarms grouped by the hive they last served). A hive can have many runs, and a swarm can serve many hives. |
| **Run** | `WorkflowRun` (one execution of a swarm for a hive) | **Start run**, **Stop run**, **Run log**, **RUN n**, Past runs. D3 (Run or another word) is open; Run stays. |
| **Orphan** | drifter (`A2aDrifters`, `DrifterTrouble`, `drifter-` role ids) | A remote A2A agent you can bring in and connect. |

Kept as they are: **Memory**, **Breed**.

Settled earlier: the **keeper** is you; the **queen** is the planner that drafts a swarm for your approval. Settled
now: a **swarm** is the workflow (the saved definition and its plan), a **hive** is the goal the keeper wants achieved
(a swarm pursues it; a hive has many runs and a swarm can serve many hives), and a **run** is one execution of a swarm.
A GitHub Actions workflow keeps GitHub's word. Not yet done: the 47 "project" strings (a project is the hive, D17) and
the two places that still mix the words by design: the district (see above) and the A2A Agent Card's own descriptions,
which other programs read.

## Rules

- A **source** is where an agent's answers come from (OpenAI, a local CLI, a remote A2A agent). Other uses of the
  word stay unambiguous: an *execution source* is how a role runs (AI, GitHub Actions, JavaScript); a *repository*
  is never called a source; "source file" and "source run" mean the code and the run a trial was copied from.
- **Messages** are the things waiting on you. A message sent *to an agent* (Message agent, Send message) is a different
  thing and keeps its name.
- A **protocol term stays technical**: the A2A `provider` field, LLM provider ids, `ProviderCatalog` and the
  `provider:<id>` Agent Card skill tags are what other programs read. THREAT_MODEL, the architecture docs and
  PRIVACY's legal sense of "external provider" name the technical concept and keep the technical word.

`VocabularyInStringsTest` fails if a user-visible string resource uses one of the old words; its allowlist lists each
legitimate exception with the reason.
