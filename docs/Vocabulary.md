# Vocabulary

The words the app shows you are not always the words the code uses. This page maps one to the other, so a screen, a
doc and a source file can be matched. Change a word on the screen here first; code identifiers, persisted keys and
protocol fields do not follow it.

| The app says | The code and technical docs say | Where you meet it |
| --- | --- | --- |
| **Story** | timeline (`RunTimeline`, `overview.timeline.*` keys) | The hive's **Story** pill: Waterfall, Events, Memory, Log, Flowchart. |
| **Source** | provider (`AgentProvider`, `ProviderCatalog`, `preferredProviderId`; the A2A Agent Card `provider:` skills) | A creature's **Source** row, Connections ("Add a source"), **AI Sources**, the Roster's source routing. |
| **Messages** | inbox (`LiveInboxScreen`, `InboxReadLog`) | The header's **Messages** button and the rail's Messages place: everything waiting on you. |
| **Nanny** | Hall Monitor (`HallMonitorRole`, `HallMonitorGovernanceService`, the `hall-monitor` role id) | The role that watches the swarm's efficiency and can pause the hive for your review. |
| **Host** | where an agent runs (`TaskExecutor`, the Compute part, an orphan's endpoint host) | A creature's **Runs on** row and an orphan's **Host:** line: your device, a paired machine, or the orphan's own server. Different from its **Source** (what it thinks with). |
| **Orphan** | drifter (`A2aDrifters`, `DrifterTrouble`, `drifter-` role ids) | A remote A2A agent you can bring in and connect. |

Kept as they are: **Memory**, **Breed**.

Settled earlier: the **keeper** is you; the **queen** is the planner that drafts a hive for your approval; a **swarm**
is a workflow and a **hive** is a goal in the technical docs (the screens still say hive and swarm where they did).

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
