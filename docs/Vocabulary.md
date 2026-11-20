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
| **Swarm** (one execution) | `WorkflowRun` (one execution of a swarm for a hive) | **Work**, **Stop swarm**, **Swarm log**, **SWARM n**, Past swarms. D3 (ruled): the noun for one execution is "swarm"; "run" is not a noun in user text. |
| **Rest** / **Wake** | the keeper's pause of a swarm (`keeperPause`, `hive_pause_run`, `hive_resume_run`) | **Rest** beside Stop; the pill then reads **Rested · Wake**. D4 (ruled). The Nanny's freeze (Resume after her review), the tour's Pause, a paused consolidation and a spend stop are different things and keep their words. |
| **Orphan** | drifter (`A2aDrifters`, `DrifterTrouble`, `drifter-` role ids) | A remote A2A agent you can bring in and connect. |
| **Show engineering names** | `EngineeringNames`, `engineeringName` (D35) | One switch in the Keeper's room, Appearance. On, Nanny reads "Nanny (Hall Monitor)", Orphan "Orphan (A2A agent)" and Swarm "Swarm (workflow)" at the Nanny review title, the Orphans heading and the Swarm library; MCP servers are not renamed. Off by default. |

Kept as they are: **Memory**, **Breed**.

## The settled ruling on swarm, hive, breeding and run (resolves D3, D4)

The owner's words: "swarm is both a noun and verb and to use it for more than one thing. Hive an overall swarm's goal,
where breeding are the goals within a swarm. Run is acceptable as a secondary word for the swarm verb form." And, on
breeding: "Where there are eggs hatching, that is breeding."

- **Swarm** is the workflow as a noun and the act of running it as a verb. It is one word for both, on purpose.
- **Hive** is the overall goal of a swarm. Never the workflow.
- **Breeding** is the goals within a swarm: the part where eggs are hatching into creatures. It is the hatching phase
  inside a swarm, not the swarm and not the hive.
- **Run** is allowed as a secondary word for the verb form only ("run the swarm"). It is not a noun for the workflow.
  D3 (Run or another word) is closed by this.
- **D3, the keeper's later ruling:** the noun for ONE execution is also **swarm** ("It's a verb and a noun"), not
  "run". User text says "Stopping the swarm failed", "SWARM 3", "Past swarms", "Swarms can start again". "Run" stays
  only as a verb (execute or operate: "runs on this computer", "models that run here", "Run a cycle now", "Run tests"),
  in shell commands and version numbers, and in GitHub's own terms. Code identifiers and persisted keys keep `run`.
- **D4:** the keeper's pause of a swarm is **Rest** and **Wake** ("Rest", "Rested · Wake"). Stop stays Stop. The
  Nanny's freeze keeps Resume (it is her review, not the keeper's rest), and the tour's Pause, a paused consolidation
  and a spend stop are not this and keep their words.

What the UI strings do today (audited, `values/*.xml`): no string says "breeding" (the hatching strings say hatched,
hatches, Nursery); no string uses "hive" for the workflow or "swarm" for the goal in a way that is plainly wrong. "Run"
was used as a noun for one execution of a swarm ("Stop run", "Past runs", "Run log"); the keeper's later ruling
(D3, above) replaced it with "swarm" everywhere in user text.

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
| **Work** / **Add to queue** | launch (`hive_launch`, `hive_feed_launch`, `onLaunchWorkflow`), queue (`HiveFeedQueue`) | The one verb for starting a run (decision D4: Work; the five start controls say it): the feed bar, the first run, the plan decision. While a run is going the feed bar reads Add to queue. |
| **Queen** | the built-in orchestrator role (id and stored name `orchestrator` / "Orchestrator", `domain/Roles.kt`) | Her creature on the terrarium, the creature sheet, Messages rows and role labels. One mapping (`roleDisplayName`, `keeperName()` in `domain/RoleDisplayName.kt`) turns the stored name into Queen at display; saved data and logic keep the stored id. |
| **Integrity** | `needs.integrity` (`needs_integrity`); the budget meter reads **Budget** | The creature meter. Not HP, not Mood (D1). |
| **Neighborhood** / **District** | `Project` (a project) / the district screen, rail place `rail_hive` | A project is a neighborhood; anything above a neighborhood is the District (D2). The keeper's goal stays **hive**. |
| **Your instructions** | `RoleDefinition.keeperInstructions` | The keeper's own standing instructions on a creature's form in Breeds (the queen's included, on the Orchestrator entry), added to its prompt for every step. Distinct from **Standing instructions** (the role's own text, which the Nanny may propose changes to) and from Memory, which is read-only. Code names keep "keeper". |
| **Breeds** / **Nursery** / **Shop** | Roster (`rail_roster`, `ControlRoomDestination.Company`) / Stable (`rail_stable`, `Stable`) / Azphalt Store (`rail_store`, `AddOns`) | The rail, page titles and breadcrumbs (D8). The Shop's tab of creatures for sale is **Species**, so it does not collide with the Breeds place; **Breed** stays the verb. |
| **MCP servers** | MCP server (`mcp_*` keys) | Kept with its engineering name, not Tool (D7). |
| **Practice run on a copy** | isolated counterfactual (`HallMonitorTrial`, the "Isolated Nanny counterfactual" description prefix, kept for saved trials) | A Nanny recommendation tried on a separate copy of the frozen swarm. |
| **Frozen** | global pause (`globalPause`, `HallMonitorGlobalPause`) | A swarm the Nanny stopped for your review. Pause (yours) is a different thing: it lets what runs finish. |
| **Running / done / failed or stopped / not run yet** | `NeighbourhoodState` Gestating / Hatched / Withered / Idle; comb labels Done / Stopped / Failed | A hive's state in the district and on its run combs. |
