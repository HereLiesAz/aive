# Figma Make mock vs. the app

The mock is "Agentic Workflow Companion Game", a one-file React prototype in Figma Make with all its data hard-coded: https://www.figma.com/make/DUgc0ARwvJVwtPAhZj1T9d/Agentic-Workflow-Companion-Game

What follows takes only what is new. Paths are under `shared/src/commonMain/kotlin/com/hereliesaz/geministrator/` unless they start with `docs/` or `shared/`.

Verdicts:

- **Same**: the app already does it.
- **New — port**: a capability or signal the app lacks that fits the current design with no layout change, backed by data the app already has.
- **Redesign — look into**: changes layout, navigation or structure, or conflicts with a documented decision. Listed for later, not ported.
- **Mock-only**: fake data or decoration with nothing real behind it. The real signal it would need is named.

## Navigation and shell

| Mock | App today | Verdict |
|---|---|---|
| Left rail (Hive, Inbox, Stable, Memory, Store, Keeper's room); icon rail under 1080px, bottom tab bar under 820px | No navigation row. One header on every width: the Aive, the breadcrumb, the Inbox badge and the Keeper's room gear (`ControlRoomUi.kt:498-532`; `docs/Keepers-Room.md:41-49`). The hive's places sit in a row across its top (`ControlRoomUi.kt:110-117`, `:683-712`) | **Redesign — look into** |
| Breadcrumb "THE AIVE / screen" | Hive › a hive › a creature › its tab; the last step is where you are (`ControlRoomUi.kt:447-484`, `:534-570`) | **Same** (the app's goes deeper) |
| "SWARM AWAKE" with a pulsing dot | The runtime state as one word: NEW, RESTING, WAKING, STOPPED, CAN'T REACH ITS AGENTS, or the run's status (`ControlRoomUi.kt:517-524`, `:946-954`) | **Same**; the dot is decoration |
| Ink Inbox button with a count | `InboxBadge` counts unseen waits and opens the next one on its Needs (`ControlRoomUi.kt:250-276`, `InboxBadge.kt:115-167`) | **Same** |
| Keeper chip "K · KEEPER Az" | The gear only (`InboxBadge.kt:247`). The app has no keeper identity | **Mock-only**: would need a keeper profile, which the app does not have |
| Stable as a top-level place | The stable of creature bodies sits in the district (`DistrictScreen.kt:154`) | **Redesign — look into** |

## Hive canvas

| Mock | App today | Verdict |
|---|---|---|
| Title "HIVE 03 · PRODUCT WORK" / "Ship the living workflow" | The breadcrumb names the hive; under it, what it was fed (`MindMapRunScreen.kt:707-708`) | **Same** (the "03" numbering is mock-only) |
| "RUN 18 · 3/5 creatures active" | "STATUS · completed/total" as a live region (`MindMapRunScreen.kt:703-714`) | **Same** (a run ordinal "18" has no backing: runs have ids, not numbers) |
| LIVE / BUILD tabs | `TerrariumModeToggle` (`TerrariumConnections.kt:40-61`, `WorkflowTerrarium.kt:143-149`) | **Same** |
| Status line "Architecture is firing. Its next output will feed the engineer." | "<name> · DOING NOW" with its newest thought or step (`MindMapRunScreen.kt:792-806`, `:848-858`). It does not say who the output feeds | **New — port** (port 2) |
| Undo / redo (build only) | The tide, Undo and Redo (`WorkflowTerrariumAuthoring.kt:76-97`, `TerrariumConnections.kt:100-101`) | **Same** |
| Centre, zoom out, zoom in | Zoom in, zoom out, and a percent button that resets zoom and focus; zooming out past the widest view surfaces to the district (`MascotNodeTerrarium.kt:693-705`, `:717-765`) | **Same** |
| Tissue ground: dark radial gradient with grain | Tissue ground and vasculature (`TissueRendering.kt:18-56`, `Vasculature.kt:17`) | **Same** |
| Creatures with "task / role · state" label cards | Each creature wears its task on its state colour. Past 12 creatures, only the selected one keeps its label (`MascotNodeTerrarium.kt:643-659`, `:824`). The full state is always in the semantics (`:465-472`) | **Same** (the "always show" switch is port 8) |
| Running bobs, waiting dims, reviewing wobbles, action pulse ring, selected halo | Per-state posture and motion, and a selection ring (`MascotNodeTerrarium.kt:181-207`, `:600-620`; `docs/Node-Creatures.md:32`) | **Same** |
| Synapses drawn as axons: green glow when complete, yellow dashes when active, grey dots when waiting, a red "gated" receptor, travelling vesicles | Tendrils that meet in clasps. A pulse shows waiting or crossing; a clasp glows green on success and red on failure or block (`TendrilTethers.kt:90-140`, `MascotNodeTerrarium.kt:316-342`, `:662-680`) | **Same** in meaning. The axon look is **Redesign — look into** |
| Two capillaries labelled CLAUDE / GEMINI | Providers are capillaries (`TissueRendering.kt:36-55`, `TissueProjection.kt:38-42`), but none is named on the field or in the semantics | **New — port** (port 1) |
| Run egg "RUN 18 · 62% gestated" | The egg hangs on the working creature's tail and grows with completed tasks (`TissueRendering.kt:59-80`, `MascotNodeTerrarium.kt:683-690`). The percent is the header's completed/total | **Same** |
| Build: "ADD CREATURE" (from the Stable, or a deterministic tool as a neuron) | Build can Duplicate, Detach, Hold output, Cluster and wire (`TerrariumConnections.kt:85-161`, `WorkflowTerrariumAuthoring.kt:173-184`). It has no way to add a new creature, though `docs/Swarm-OS.md:19` lists "add and release creatures" | **New — port** (port 9) |
| Legend (Working / Complete / Waiting) and "Moving bodies never edits the workflow" | No legend. The state is on each label and in the semantics | **Redesign — look into** |

## Feed bar

| Mock | App today | Verdict |
|---|---|---|
| "FEED THE HIVE" input and LAUNCH, always under the canvas | Feed it shows only while there is no live run (`MindMapRunScreen.kt:247-700`). A finished run offers "Feed it more" (`:744-751`). A live hive deliberately has no second launcher: one Heroic act per screen (`ControlRoomUi.kt:829-830`) | **Redesign — look into** |

## Creature panel (Needs / Work / Mind)

| Mock | App today | Verdict |
|---|---|---|
| A 310px panel docked on the right | `ModalBottomSheet` on every width (`ControlRoomUi.kt:413-443`) | **Redesign — look into** |
| Header: "CREATURE · 3 OF 5", task, close, image, state chip, role, provider | Header shows the role (or executor) and the objective (`LiveTechnicalInspector.kt:200-201`). State and provider are only lines inside Work (`:346-352`). There is no place in plan order | **New — port** (port 3) |
| Tabs NEEDS / WORK / MIND | `CreatureTab`, opening on Needs when the creature waits on you (`LiveTechnicalInspector.kt:608-645`; `ControlRoomUi.kt:239-249`) | **Same** |
| "These are runtime signals, not game stats." | Enforced in code: a need with no data is "not reported", never estimated (`CreatureNeeds.kt:10-14`, `CreatureNeedsPanel.kt:60-73`) | **Same** |
| ECHO, INTEGRITY, BUDGET, OUTPUT USED meters | The same four from run data, plus near-clone and self-review lines (`CreatureNeeds.kt:18-127`, `CreatureNeedsPanel.kt:48-82`). The mock shows Echo inverted ("x% self-similar") | **Same** |
| REVIEW ITS WORK (Echo falls) | Not built. Echo is computed only from wiring: its own config's share of its inputs and reviewers (`CreatureNeeds.kt:86-93`). No keeper review is recorded anywhere | **Mock-only**: needs a recorded keeper review per task run that Echo counts as an outside voice. Designed in `docs/Swarm-OS.md:56`, not yet backed |
| WORK: description, INPUT and OUTPUT handoff cards | Inputs with provenance (`LiveTechnicalInspector.kt:379-388`) and artifacts that open (`:389-414`). Who its output feeds, and whether they took it, is not shown; outside Build that Connections list does not exist (`docs/Swarm-OS.md:74`) | **New — port** (port 4) |
| LIVE TRACE list | Live activity: thinking and steps, latest six, older ones collapsed (`LiveTechnicalInspector.kt:517-560`) | **Same** |
| OPEN FULL TRACE | The run's timeline and waterfall exist at hive level and can select a creature (`ControlRoomUi.kt:794-802`, `HiveScreens.kt:118`), but nothing in the panel leads there | **New — port** (port 5) |
| MIND: ROLE / PROVIDER / TOOLS / MEMORY list, EDIT TEMPERAMENT | Its creature picker, pending instruction proposals and the role's full form, open from the start (`LiveTechnicalInspector.kt:465-486`) | **Same** (the app shows the editable fields, not a summary) |

## Inbox

| Mock | App today | Verdict |
|---|---|---|
| Inbox as its own page: list plus detail | Retired into each creature's Needs (stage 3, `docs/Keepers-Room.md:55`, `:60`; `ControlRoomUi.kt:119-124`). The list survives as the fallback place when no creature waits (`LiveInboxScreen.kt:25-67`, `ControlRoomScreens.kt:348-421`) | **Redesign — look into** |
| Kinds APPROVAL / ESCALATION / REVIEW with coloured dots | Plan approval or failure escalation on the record's eyebrow (`ControlRoomScreens.kt:405-421`) | **Same** |
| MARK ALL READ | An item is read once its creature's panel has been open (`ControlRoomUi.kt:254-263`, `InboxBadge.kt:130-160`) | **Redesign — look into** |
| APPROVE & FEED / SEND BACK; OPEN DECISION / HOLD OUTPUT | Approve, Reject plan, Retry, Stop, with confirmation (`LiveTechnicalInspector.kt:228-285`); Hold output in Build (`TerrariumConnections.kt:120-123`) | **Same** |
| "Echo is rising in Research": four of five sources share one model lineage | The Hall Monitor's pause and trials (`HallMonitorInboxScreen.kt:44`, `:147`). Echo per creature is role plus provider id. Nothing knows a model's family | **Mock-only**: needs the model family actually served per task run. Gateway providers (OpenRouter and similar) hide it, and `ProviderCatalog.kt:9-15` has no family field |

## Stable

| Mock | App today | Verdict |
|---|---|---|
| "Stable": a page of role lineages with CREATE ROLE | Three things in two places: creature bodies in the district (`StableScreens.kt:83-130`, `Breeds.kt:84-96`), the lineage of hatched configs (`DistrictScreen.kt:243-263`), and roles in the Swarm (`CompanyCustomizationScreen.kt:807-831`, Add role) | **Redesign — look into** |
| Filters All / Producers / Reviewers / At risk | No filter on roles. Reviewer kinds are already classified (`CreatureNeeds.kt:46-54`) | **New — port** (port 10; "At risk" excluded, see below) |
| Card: "GEN n", kind, name, "N hives · Integrity x%", health bar, THRIVING / LOW ECHO | Body tiles show generation, rarity and count (`StableScreens.kt:113-128`). A role row shows department, execution, authority and Working/Available (`CompanyCustomizationScreen.kt:810-831`). How many hives use a role is not shown | "N hives": **New — port** (port 10). Integrity, health and THRIVING: **Mock-only**: Integrity is defined per task in one run (`CreatureNeeds.kt:95-98`); a role score across runs would need its own definition over run history |
| "Bring in a drifter: add a different model lineage" | District "Import agent" and the hive's edge Drifters shelf (`DistrictScreen.kt:117-122`, `HiveEdge.kt:28-100`) | **Same** |

## Memory

| Mock | App today | Verdict |
|---|---|---|
| SIMPLE / TECHNICAL toggle | One technical page: switches, the memory terrarium, stage engines, queue, tuning, models, data, summaries, projects (`MemoryLayerScreen.kt:152-280`, `:393-716`) | **Redesign — look into** (it is `docs/UI_SIMPLIFICATION_PROPOSALS.md` #18) |
| MEMORY TONE "Clear: balanced across 4 lineages" | None. Episodes carry the role and task run, not a model (`memory/MemoryModels.kt:51-73`) | **Mock-only**: needs the model family per episode. Same gap as the Inbox lineage finding |
| Four stages: Observe 142 / Section 18 / Consolidate 6 / Recall 91% useful | The memory terrarium shows eight real stages, from Sectioning to Condensation (`MemoryTerrariumProjection.kt:37-48`). Episode, section and node counts exist (`memory/MemoryModels.kt:320-333`) | Stage counts: **Same**. "91% useful": **Mock-only**: only recall counts exist (`Recalled` edges, `memory/MemoryLayerController.kt:186-206`), with no measure of usefulness |
| SLEEP STATUS "last consolidated 2h ago", PROCEDURAL PROPOSALS "3 waiting", CONSOLIDATE NOW | A consolidation running/paused switch (`MemoryLayerScreen.kt:246-251`). No last-consolidated line, no total of waiting proposals (only per role, on Mind), and no way to consolidate now, though `drainSoon()` exists (`memory/MemoryLayerController.kt:312-341`) | **New — port** (port 11) |
| DOMINANT VOICE "Mixed · healthy" | None | **Mock-only** (needs model family per episode) |
| RECENT ENGRAM and KEEPER NOTE cards | Summaries and the summary tree (`MemoryLayerScreen.kt:633-716`) | **Same** (the copy is mock text) |

## Store

| Mock | App today | Verdict |
|---|---|---|
| Tabs Workflows / Agents / Installed | Categories All / Workflows / Roles / Models / LLMs with counts, and an Installed step (`AzphaltStoreScreen.kt:155`, `:327-332`, `:404-417`) | **Same** |
| Featured hive "Software Delivery Organism", VIEW HIVE | None. `AzphaltPackageSummary` has no featured flag (`azphalt/AzphaltStoreModels.kt:42-66`) | **Mock-only**: needs a featured or curated field from the Store index |
| Cards with "12 creatures" | The package summary has no task count; the count is known only after the detail is fetched | **Mock-only**: needs a task count in the index summary |
| "VERIFIED WORKFLOW" badge, INSPECT | Trusted / unknown signer / unsigned trust, and Inspect install (`AzphaltStoreScreen.kt`, `store_trusted`, `store_inspect_install`) | **Same** |

## Keeper's room

| Mock | App today | Verdict |
|---|---|---|
| Side list: Appearance / Connections / Limits / This device / Privacy | Connections, Compute, Projects and data, Delegations, This device (spend caps live here), Appearance, About and privacy, in two groups (`KeeperRoom.kt:67-88`; `docs/Keepers-Room.md:5-17`) | **Same** (the app has more parts; "Limits" as its own part is a regrouping, **Redesign — look into**) |
| "Choose your ground": six swatches | The same six grounds (`AzphaltUi.kt:83-90`), chosen by one pill that cycles to the next (`ProviderManagementScreens.kt:720-735`, `AzphaltUi.kt:102-106`) | **New — port** (port 6) |
| Reduce motion switch | The platform signal only (`AzphaltReduceMotion.kt:7-25`); no switch in the app | **New — port** (port 7) |
| "Creature labels: always show task, role and state" | Labels hide past 12 creatures (`MascotNodeTerrarium.kt:646`, `:824`) | **New — port** (port 8) |

## Motion and accessibility

| Mock | App today | Verdict |
|---|---|---|
| `prefers-reduced-motion` stops animation | The same signal on web, the animator scale on Android, an env var or property on desktop; looping clocks freeze (`AzphaltReduceMotion.kt:7-25`, `MascotNodeTerrarium.kt:201-207`) | **Same** |
| Buttons have aria-labels; creatures read "task, role, state" | Creatures are buttons with label, activity and task in their semantics, nudge actions in Build (`MascotNodeTerrarium.kt:465-490`); zoom buttons are 48dp and labelled (`:752-765`); the breadcrumb names where you are (`ControlRoomUi.kt:534-570`) | **Same** |
| Capillaries carry visible names | Capillaries have no label and no semantics node | **New — port** (port 1) |

## Visual tokens

| Mock | App today | Verdict |
|---|---|---|
| Ink `#1e1a17`, yellow `#f0d42a` | `Azphalt.Ink` `0xFF1E1A17`, `Azphalt.Yellow` `0xFFF0D42A` (`AzphaltUi.kt:57-58`) | **Same** |
| Mustard ground with a gradient | Mustard `0xFFE8C81E` with fold light and dark (`AzphaltUi.kt:75-90`) | **Same** |
| Ink panels | Ink records and the Ink inspector (`LiveTechnicalInspector.kt:190-197`) | **Same** |
| Archivo Black titles, DM Sans body | System sans serif throughout (`AzphaltUi.kt:155-195`) | **Redesign — look into** |

## Port list

1. **Name the capillaries.** Write each provider's name along its capillary on the field, and give each capillary a semantics node ("Claude, feeding Architect, Engineer"). Where: `TissueRendering.kt` `drawTissueGround` (`:18-56`), and a semantics overlay beside the creatures in `MascotNodeTerrarium.kt`. Data: `TissueCapillary.providerId` (`TissueProjection.kt:38-42`), `ProviderCatalog.entry(id).displayName`, and the creatures it feeds (`tissue.providerOf`, `TissueRendering.kt:37`). Size: S.
2. **Say who the active creature's output feeds.** Extend the hive's "DOING NOW" line to name the creatures waiting on it ("Architect · doing now: … · feeds Engineer"). Where: `CreatureDoingNow`, `MindMapRunScreen.kt:848-858`. Data: the downstream tasks, `definition.tasks.filter { activeId in it.dependsOn }`, named by role. Size: S.
3. **Panel header: state, provider, place in the plan.** Under the creature's name, one line: its status, its provider, and "3 of 5". Where: `LiveTechnicalInspector.kt:200-201`. Data: `taskRun.status.label()`, `taskRun.assignedProviderId` through `ProviderCatalog`, and the task's index in `definition.tasks`. Size: S.
4. **Work: feeds and fed by, with take-up.** After Inputs, list the creatures it feeds and whether each has taken its output, and the creatures that feed it. This is the plain Connections list `docs/Swarm-OS.md:74` asks for outside Build. Where: the Work tab, `LiveTechnicalInspector.kt:379-414`. Data: `task.dependsOn`, the dependents, and `run.taskRuns[dep].status` against the `TAKEN_UP` set (`CreatureNeeds.kt:56-63`, the same data as Output used). Size: S.
5. **Work: open its trace.** A pill in Work that closes the sheet and opens the hive's Timeline with this creature selected. Where: the Work tab, plus the `overview.timeline` durable flag read at `ControlRoomUi.kt:676`. Data: `RunTimeline` with `selectedTaskId` (`ControlRoomUi.kt:794-802`, `HiveScreens.kt:118`) over `onLoadRunTimeline` events. Size: S.
6. **Ground swatches.** Replace the cycling ground pill with the six grounds shown as swatches, the current one selected (radio roles). Where: `ProviderManagementScreens.kt:720-735`, plus `Azphalt.setGround(name)` next to `rerollGround` (`AzphaltUi.kt:102-106`). Data: `Azphalt.grounds` (`AzphaltUi.kt:83-90`), saved under the same key. Size: S.
7. **Reduce motion in the room.** A switch in Appearance, stored on the device and OR-ed with the platform signal, so it can turn motion off but never back on against the system. Where: Appearance (`ProviderManagementScreens.kt:720-735`) and `AzphaltMotion.reduceMotion` (`AzphaltReduceMotion.kt:14-25`). Data: a keeper preference; no run data is needed. It also fills the desktop gap, where only an env var sets it today. Size: S.
8. **Always show creature labels.** A switch in Appearance that lifts the 12-creature cap so every creature keeps its label. Where: `MascotNodeTerrarium.kt:646` (`NAMED_CREATURE_LIMIT`, `:824`). Data: a keeper preference; the labels are already projected. `docs/Node-Creatures.md:87` says each creature is labelled. Size: S.
9. **Add a creature in Build.** "Add creature" beside Duplicate: pick a role from the swarm, or a programmatic step that wears the neuron, and it joins unwired. Where: `TerrariumConnections.kt` (Build controls, `:85-161`), and a new `addTerrariumCreature` next to `budTerrariumCreature` (`WorkflowTerrariumAuthoring.kt:173-184`) that records on the tide. Data: `RoleDefinition`s, `TaskDefinition`, and the `TaskExecutor` kinds. `docs/Swarm-OS.md:19` lists add and release in Build. Size: M.
10. **Swarm roles: hive count and a kind filter.** On each role, "in N hives". Above the list, All / Producers / Reviewers. Where: the Swarm role list, `CompanyCustomizationScreen.kt:807-831`. Data: `onLoadWorkflowDefinitions()` tasks by `roleId`, and `classifyNodeCreatureRole` with `REVIEWER_KINDS` (`CreatureNeeds.kt:46-54`). No integrity or health on it (Mock-only, see above). Size: S.
11. **Memory status line and Consolidate now.** At the top of the Memory swarm: when consolidation last ran, how much waits in its queue, how many instruction proposals wait across all roles, and **Consolidate now**. Where: `MemoryLayerScreen.kt`, above the switches (`:240-252`). Data: `MemoryActivity.lastAtEpochMillis` (`memory/MemoryLayerController.kt:26-35`; in memory only, so it reads "since this start" until it is persisted); `snapshot.queue`; `RoleInstructionProposalsHost.ledger` pending (`RoleInstructionProposalPanel.kt:41-57`); `MemoryLayerController.drainSoon()` (`:312-318`), which already respects pause and off. Size: S.

## Redesigns to look into

- **Left rail and bottom tab bar** (Hive, Inbox, Stable, Memory, Store). Every place is one tap away and you always see where you are, but it brings back the navigation row `docs/Keepers-Room.md:43` retired in favour of the breadcrumb and one header.
- **Inbox as its own page, list plus detail.** One place to clear every wait, but it undoes stage 3, where each wait lives on its creature's Needs (`docs/Keepers-Room.md:55`, `:60`).
- **Mark all read.** It clears the badge fast, but it marks waits seen that nobody opened, breaking the badge's walk to the next unseen one.
- **Creature panel docked on the right.** On wide screens the hive stays visible beside the panel, but it is a second layout next to the bottom sheet used on every width today (`ControlRoomUi.kt:413-443`).
- **Feed bar always under a live hive.** You can queue the next objective at any time, but it puts two Heroic acts on one screen, which the app rules out (`ControlRoomUi.kt:829-830`).
- **Stable as a top-level page of role lineages.** One home for bodies, hatched configs and roles, but it means merging three things now split across the district, the Lineage and the Swarm.
- **Memory Simple / Technical.** A keeper-first view (proposal #18), but the mock's four stages simplify the eight real ones, so the simple view needs its own honest mapping.
- **Synapses as axons with vesicles.** Reads more like neural tissue, but it means redrawing the tendril and clasp system and its Build gestures (draw from the tail, slice to cut).
- **Legend under the canvas.** It teaches the colours once, but it is passive chrome doing one job (Conveyance Employment and Trusting chrome). The labels and semantics already carry the state.
- **Bundled fonts (Archivo Black, DM Sans).** Stronger brand titles, but the fonts ship on every platform and the type scale needs re-checking for contrast and size.
- **"Limits" as its own Keeper's room part.** Spend caps are easier to find, but This device and Delegations need regrouping, and the room's two groups were just settled (`docs/Keepers-Room.md:5-17`).
