# Aive navigation map and "everything is a link" gap analysis

> **Status (0.9.6).** The typed `Destination` (`Destination.kt`) and the one `navigate(destination)` in `App.kt` are built, as are gaps D9, D14, D15 (the spend-stop sentence) and E10. The rest of the gap list below is still open. The machine-readable graph is [`nav-graph.json`](nav-graph.json); `tools/navigation/check_navigation.py` (run in CI as "Verify navigation map") fails when a node cannot be reached from app launch over present edges, when an edge names an unknown node, or when a `ControlRoomDestination` or `KeeperSection` entry is missing from the graph. A node that is deliberately unreachable is listed under `meta.exempt` with its reason. Update this page and the graph together with any new place.

Scope: `shared/src/commonMain/kotlin/com/hereliesaz/geministrator/` (abbreviated `G/`), `docs/Keepers-Room.md`. Read-only analysis; all line numbers verified against the current tree.
Owner rule being served: EVERYTHING, EVERYWHERE is clickable and goes to where it can be changed; no in-place option pickers that compete with the canonical place.

## 0. How navigation works today (the five overlapping mechanisms)

1. **`ControlRoomDestination` enum** (`G/ControlRoomUi.kt:107`): Overview, Workflows, Company(=Roster), Stable, Inbox, Compute, Memory, AddOns(=Store), Settings(=Keeper's room). Held in `App.kt:501` as `destinationName` (in-process only, `ControlRoomSession`, `App.kt:1163`). `onDestinationSelected` (`App.kt:727-741`) maintains `navigationHistory` (a flat `List<ControlRoomDestination>`), clears history when the target is Overview, pops to an earlier entry if re-visited, and **clears `selectedTaskIdValue` whenever the target is not Overview** (`App.kt:738-740`).
2. **Sub-place state in durable string/boolean keys** (`DurableUiState`, `G/DurableUiStateStore.kt`): `overview.zoom.district`, `overview.resting-hive`, `overview.timeline`, `keeper.section`, `keeper.open`, `creature.panel.tab`, `creature.panel.task`, `navigation.selected-task-id`, `workflows.tab`, `workflows.selected-key`, `workflows.selected-task-id`, `workflows.draft(.origin)`, `inbox.selected`, `hive.view.swarm`, `overview.timeline.view`, `overview.timeline.log.source`, `MemoryView.STORAGE_KEY`. A "deep link" today = hand-writing a subset of these keys then calling `onDestinationSelected`.
3. **Ad-hoc opener lambdas**, each one a hand-rolled partial deep link (listed in section 3).
4. **Back handlers** (5 independent `PlatformBackHandler`s, no shared stack): `App.kt:519` (clears selection, pops `navigationHistory`, falls to Overview), `ControlRoomUi.kt:350` (hive -> district), `:355` (timeline -> hive), `:443` (wide docked creature panel -> set aside), `KeeperRoom.kt:144` (phone: open part -> list), plus `WorkflowTerrarium.kt:275` (camera zoom step-out). Plus ModalBottomSheet dismiss for the phone creature sheet (`ControlRoomUi.kt:607`).
5. **Breadcrumb** (`shellCrumbs`, `ControlRoomUi.kt` ~L480-530): Hive > repository > hive > creature > tab, or Hive > place, or Keeper's room > part. Only Hive, hive-name, creature (close) and Keeper's room (phone) crumbs have clicks; repository, tab, timeline, keeper-part crumbs are plain text.

There is **no URL/intent deep link** into the UI. The only external navigation inputs are Azphalt package import (`azphaltPackageImportRequest` -> forces AddOns, `App.kt:536-545`; Android intent filters `AndroidManifest.xml:46-59` for package mime types) and the Remote API (no navigation commands in `control/`).

---

## 1. PLACES

Legend: **Reach** = what opens it (file:line); **Back**; **Deep-link** = can it open on a specific entity?

### 1.1 Top-level destinations

| Place | Reach | Back | Deep-link |
|---|---|---|---|
| **Overview / Hive** (live hive, resting hive, district) | Default (`startupControlRoomDestination` always Overview, `App.kt:1178`); rail Hive (`ControlRoomUi.kt` rail `onPlace`, railHiveStep `ShellRail.kt:133`); crumb Hive/hive-name (`onOpenDistrict`/`onOpenHive`); Hives pill (`MainDestination` ~L930) | District<-hive (`:350`), hive<-timeline (`:355`); at Overview with nothing open Back does nothing special | Partial: resting hive by id via `restingHive` (`overview.resting-hive`); live hive by `onSwitchRun(runId)`; creature by `selectedTaskId`. No single entry. |
| **District** (all hives, New hive, Stable, Lineage) | `district` flag (`overview.zoom.district`); `zoomOut` (`ControlRoomUi.kt:301`); Hives pill; crumb "Hive" when in a hive | Back = into hive is NOT a back (only the rail Hive toggles IntoHive) | No (a list; cells open a hive via `onEnterNeighbourhood`, `ControlRoomUi.kt` ~L960) |
| **Workflows** (library editor; tabs Library/Store/Overview/Bowl/Tasks/Combine/Save, `G/WorkflowLibraryTabs.kt:10`) | "More editing" pill (live hive and resting hive) -> `openHiveInWorkflows(defId?)` (`ControlRoomUi.kt:901`), validation-error record `onEditWorkflow` (`MindMapRunScreen.kt:891`) | App-level: pops `navigationHistory` or goes to Overview | **Yes, hive-level**: `DurableUiStateStore.openWorkflowsOn(entry)` (`WorkflowLibraryTabs.kt:94`) pins workflow + Overview tab. Task-level key `workflows.selected-task-id` exists (`:84`, consumed `LiveWorkflowLibraryScreen.kt:144,475-480`) but nothing writes it from outside. Not on the rail, not in the places row: only reachable via "More editing". |
| **Roster / Swarm** (`ControlRoomDestination.Company`, `CustomCompanyProviderScreen`) | Rail Swarm (`ShellRailPlace.Swarm`, `ShellRail.kt:90`); phone places row | Back -> history/Overview | **No** (no `focusRole` from outside the creature panel; role cards cannot be scrolled/opened to). `focusRole` exists only as embedded form (`CompanyCustomizationScreen.kt:219`). |
| **Stable** | Rail Stable; phone row; also embedded in District (`DistrictScreen.kt` `StableSection()`) | same | No |
| **Inbox** | Badge/rail -> `openInbox` (`ControlRoomUi.kt:332`): if some creature needs you it does NOT open Inbox but opens that creature's Needs; else `onDestinationSelected(Inbox)`. Inbox destination only meaningfully shows spend stop (`SpendStopInboxScreen`), Hall Monitor pause/trial (`HallMonitorInboxScreen`), or the wide/phone list. | -> history | Within Inbox: `inbox.selected` key (wide list). No external deep link. |
| **Compute** (retired destination, still routable) | Creature Work "Runs on / Distributed / blocked by REMOTE_COMPUTE_UNAVAILABLE" -> `onOpenCompute` (`ControlRoomUi.kt:427`) -> `onDestinationSelected(Compute)` -> `KeeperSection.of(Compute)` opens Keeper part `Compute` (`KeeperRoom.kt:96-101`, `LaunchedEffect(opening)` `:132`) | -> history (back lands on Overview, creature lost, see 4) | Part-level only |
| **Memory swarm** | Rail Memory; last tab of live hive Timeline (`RunTimeline(memoryContent=...)`) | rail: -> history; in Timeline: Back -> hive | No (Simple/Technical `MemoryView` key only) |
| **Azphalt Store** (`AddOns`) | Rail Store; places row; package import intent (`App.kt:536`); also a **second copy** as Workflows tab Store (`LiveWorkflowLibraryScreen.kt:319`, without provider connect callbacks) | -> history | Package import request id; categories (All/Agents/Hives/Breeds/Installed/Models/LLMs, `AzphaltStoreScreen.kt:168`) not addressable |
| **Keeper's room** (`Settings`) | Gear (`openKeeper`, `ControlRoomUi.kt:448`), rail foot | Phone: Back closes open part -> list (`KeeperRoom.kt:144`) then history; wide: history | **Yes, part-level**: `keeper.section`+`keeper.open` written by `openConnections` (`:893`), `openConnectionsFromPanel` (`:260`), Inbox `onOpenLimits` (`:1199`), `KeeperSection.of(Compute)`. Not entity-level. |

### 1.2 Keeper's room parts (`KeeperSection`, `G/KeeperRoom.kt:79`)

Connections (providers, A2A agents, repo services, MCP servers: `ProviderManagementScreens.kt:558`, `RepositoryConnections`, `McpServerConnections`), Compute (`ComputeDelegationScreen` embedded), Projects (`:1462`), Delegations, Schedules, LocalAgents (desktop), Improvement, Limits (wide only; `SpendCapsSettings`, `:917`), Device, Appearance, About. All are reached only via part-level `keeper.section`. No entity-level targeting (cannot open "Connections, provider X" or "Schedules, schedule Y"), except the provider credential dialog `onReconfigureProvider(providerId)` which is a real entity-level action (used by Store `AzphaltStoreScreen.kt:771`, first-run free gateways `MindMapRunScreen.kt:~735`).

### 1.3 Hive sub-places

| Place | Reach | Back |
|---|---|---|
| **Live hive** (Live/Build/Flowchart modes; Setup/Swarm switch) | default | -> district |
| **Resting hive** (`RestingHive`, `HiveScreens.kt:288`) | district cell, New hive (`MainDestination` L957-L963), HiveCard import | -> district |
| **Timeline** (Overview/Waterfall/Events/Memory/Log) | Timeline pill -> `showTimeline` (`overview.timeline`); creature Work "Open its trace" (`ControlRoomUi.kt:433`: sets timeline + `panelSetAside`) | `:355` back to hive |
| **Creature panel** (phone ModalBottomSheet `ControlRoomUi.kt:607`; wide dock 310dp `:593`) tabs Needs/Work/Mind (`CreatureTab`, `LiveTechnicalInspector.kt:854`) | `selectCreature` (toggling, `:274`), `openCreature` (non-toggling, `:283`), `openInbox` (selects creature + Needs tab, `:332-345`); tab = `creature.panel.tab` | sheet dismiss / wide Back -> `closePanel` (set aside, stays selected, `:293`); App-level Back clears selection (`App.kt:526`) |
| **Share hive card / Import hive card panels** (`HiveCardPanels.kt`) | pill `sharingHiveCard` / District `importCard` | toggle |
| **Hive options menu** (long-press, `HiveActionsMenu.kt`) and **Delete hive** dialog (`HiveDeletion.kt`) | long-press / red pill | dismiss |
| **Hive launch bar / feed bar / ContinueRunPanel** | in-page | n/a |
| **RunScars** (combs: past runs of the hive) | in hive; `onReplay = onSwitchRun` | n/a |

### 1.4 Dialogs, overlays, modal gates

- Stop run confirm (`MainDestination` ~L1000), Reject plan / Stop workflow confirm (`LiveTechnicalInspector.kt:322`, `LiveInboxScreen.kt` WideInboxPage), Delete hive, `DelegationOfferDialog` (`DelegationsPart.kt:47`, shown from ControlRoom), `McpConsentDialog` (`McpServerConnections.kt:329`, `App.kt:721`), `McpElicitationDialog` (`McpElicitationDialog.kt:82`), `PlanDecisionOverlay`/`PlanningOverlay` (plan review before run exists), provider credential setup (`ProviderCredentialUi.kt:44`), repository credential UI, `RerunFromTaskPanel`, `AgentQuestionPanel`. None accept navigation; none link to the entity they discuss (role, provider, MCP server, delegation).

---

## 2. ENTITIES AND SETTINGS

Key: LINK = display already navigates to the canonical place (callback cited). TEXT = display is not clickable. COMPETES = a second editor exists.

### 2.1 Hive (workflow definition)
- **Canonical edit place**: Workflows editor (Overview/Tasks/Combine/Save tabs) for structure/metadata; hive Build mode (terrarium) for structure (add/remove/knot/hold/fold: `WorkflowTerrariumAuthoring.kt`, `TerrariumConnections.kt:140-290`). Two structural editors exist (Workflows vs Build); neither links to the other except "More editing".
- Displays: District cells (LINK to open hive, `DistrictScreen.kt:170`, not to edit); breadcrumb hive name (LINK to hive view `onOpenHive`); Wide hive title (`HivePageTitle`, TEXT); Schedules card title (TEXT, `SchedulesPart.kt` ScheduleCard; schedule has `workflowId` but no link); Improvement `proposal.target` and "Add to <hive>" pills (add-in-place, `ImprovementPart.kt:256`; no link to open); Compute part rows (TEXT); Roster "in N hives" (TEXT, `CompanyCustomizationScreen.kt:~886`); Store Installed (TEXT); Delete refusal "from a package" / "used as circuit" (TEXT prose, `strings.xml:781`).

### 2.2 Task / creature
- **Canonical edit place**: for the job (objective, criteria, approval, retries, runs-on) the code has TWO in-place editors, both `HiveJobEditor`: creature panel Work (`LiveTechnicalInspector.kt:431`) and hive Build side panel (`TerrariumConnections.kt:234`). Workflows Tasks tab edits only role reassign and expand-into-hive (`LiveWorkflowLibraryScreen.kt:474-570`). So today the canonical place is Build, with the creature panel as a duplicate (stated as intended in docs/Keepers-Room.md "A creature's panel": Work = "the task fields that lived in the Workflows form editor"). This is the key conflict with the new rule.
- Displays: terrarium cell (LINK select), Timeline Waterfall bar (`RunWaterfallChart.kt:169`, LINK select, toggling), Events row (`HiveScreens.kt:96`, LINK), Log row (`LogView.kt:182-189`, LINK), repository progress record (`RepositoryProgressUi.kt:105`, LINK to working creature only), owner-attention card (`MindMapRunScreen.kt` L~860, LINK `openCreature`), status line (`onStatusClick`, LINK), Inbox rows (separate view of the same creature, TEXT: no "open creature"), Hall Monitor findings (TEXT, `finding.subject`/`solution.target`), Compute rows (TEXT), Improvement (TEXT), Roster (no creature view), "fed by/feeds" pills (LINK, `LiveTechnicalInspector.kt:567,578`), "same creature, other tasks" (LINK, `:748`).
- Caveats: Timeline/Waterfall/Log use the toggling `selectCreature`, not non-toggling `openCreature`, so tapping the open creature closes it (`ControlRoomUi.kt:274-292` vs RunTimeline `onTaskSelected`); `onSelectTask = onTaskSelected` (`:425`) is the raw App toggle.

### 2.3 Role
- **Canonical edit place**: Roster (list, add, arrange, reset) and the role form (`CustomCompanyProviderScreen`, form at `:438`). The creature Mind tab embeds the same form (`LiveTechnicalInspector.kt:698-706`, `focusRole`).
- Displays: Roster cards; creature header/crumb identity (TEXT, `identity` `LiveTechnicalInspector.kt:~270`); Work "Responsible role" (TEXT, `:510`); "Payload context / role instructions" (TEXT, `:662-680`); Inbox row role (TEXT); Delegations rows `record.roleName` (TEXT, `DelegationsPart.kt:70`); Improvement proposals target (TEXT) and **Adopt (edits role instructions in place via `onSaveRoleInstructions`, `ControlRoomUi.kt:~1330`)** (COMPETES with Mind); Mind `RoleInstructionProposalPanel` (`:695`, COMPETES with Improvement); Workflows Tasks reassign pills (`:511`, COMPETES: role pick); Build "Add creature" role pills (`TerrariumConnections.kt:269`; legitimately a picker); NewHiveRoles/RoleSuggestions (hive authoring pickers); Compute part role rows (TEXT); Stable `stable_in_agent` captions (TEXT); Role activity (Roster only).
- Role fields: `preferredProviderId`, `tokenBudget`, `instructions`, `executionSource`, `surfaces`, `capabilitiesRequired`, `authorities` (`domain/Roles.kt:157-169`).

### 2.4 Provider (AI provider / connection)
- **Canonical edit place**: Keeper's room > Connections (`ProviderManagementScreens.kt:558-640`: Reconfigure/Disconnect/Connect rows, health) and the credential dialog `onReconfigureProvider(id)`.
- Displays (LINK = to Connections generically unless noted):
  - Creature header line "state · provider · 3 of 5" (LINK to Work tab only, `LiveTechnicalInspector.kt:309-317`, not to Connections).
  - Work "Provider" LiveInspectorLine (LINK to Connections generic, `:512-515` -> `onOpenConnections`).
  - Work "Provider target" (`:680`) TEXT.
  - **CreatureProviderRow** (Work tab, `CreatureProviderRow.kt:36-80`): inline Auto/connected-provider pill picker (`ProviderChoiceRow`, `ProviderChoiceRow.kt:20`) that WRITES the role's `preferredProviderId` via `onSaveRoleCollection`. COMPETES with Roster role form, Roster role card pills (`CompanyCustomizationScreen.kt:~955-972`), and Mind. Only its "unconnected pinned provider" branch links out (`onOpenConnections`, generic).
  - Terrarium creature badge "Pinned: Claude"/"Auto" (`MascotNodeTerrarium.kt:822-825`, TEXT, drawn in canvas).
  - Roster role card execution label (TEXT).
  - Delegations row `providerLabel` (TEXT, `DelegationsPart.kt:39`).
  - Memory stage engine editor: provider pills + model text field per memory clerk (`MemoryLayerScreen.kt:409-490`) - COMPETES (a provider/model picker outside Connections; arguably legitimate binding, needs link to Connections for unconnected ones).
  - Store LLMs tab Connect/Disconnect (LINK-ish: calls `onReconfigureProvider`, `AzphaltStoreScreen.kt:489,771`); the Workflows-tab copy of the Store passes none (`LiveWorkflowLibraryScreen.kt:319-325`).
  - Compute / hive feed errors "Check connections" (LINK generic, `HiveWorkspace.kt:555`, `MindMapRunScreen.kt:651`).
  - First-launch free gateway pills (action, `onConnectFreeProvider`).
- **Model**: no per-role/creature model selection exists in the UI (grep: only memory layer `engine.model`, `MemoryLayerScreen.kt:455`). "Models"/"LLMs" appear only as Store categories. Nothing to link, but the memory model field has no link to the provider it belongs to.

### 2.5 Budgets and spend caps
- Role token budget: canonical = role form field `roleTokenBudgetDraft` (`CompanyCustomizationScreen.kt:343,817`), shown/edited in Mind and Roster form.
  - Displays: Needs "Budget" meter (LINK to Mind, `CreatureNeedsPanel.kt:76-79,93-100`; no 48dp target, no underline/chevron cue); Needs "Raise its budget" pill (LINK to Mind `LiveTechnicalInspector.kt:417-420`); Work blocked line `ROLE_BUDGET_SPENT` (LINK to Mind, `:537`); Needs "budget spent" text (`:407-412`, TEXT); Inbox `BudgetSpent` entry (**TEXT prose "Raise it on this creature's Mind, in the hive", no action**, `LiveInboxScreen.kt` `inboxDetailCopy`, `strings.xml:293`).
- Cost cap per run / spend cap per hour: canonical = Keeper's room > Limits (wide) or This device (phone) (`SpendCapsSettings`, `ProviderManagementScreens.kt:917`; `SpendCapStore`).
  - Displays: Spend-stop Inbox (LINK "change caps", `SpendStopInboxScreen.kt:71-77` via `onOpenLimits` `ControlRoomUi.kt:1199`); Roster/Role activity cost (TEXT); no cost/cap display on the hive or creature at all.

### 2.6 Approval gates / delegations
- Per-task approval policy: edited in place by cycling in `HiveJobEditor` "Approval" row (`HiveJobEditor.kt:162-168`; toggles human approval only; Role approval can't be reached) in creature Work AND Build panel.
- Gate decisions (Approve/Reject/Retry/Stop): creature Needs (`LiveTechnicalInspector.kt:339-420`) AND Inbox detail (`LiveInboxScreen.kt` InboxDetailActions) - duplicate decision surfaces by design.
- Delegations (gates passed without asking): canonical = Keeper's room > Delegations (`DelegationsPart`), created via `DelegationOfferDialog`. Displays: Approval events show "Approved by delegation" (TEXT). Delegation rows show role + provider (TEXT).

### 2.7 Schedule
- Canonical: Keeper's room > Schedules (`SchedulesPart`): On/Off, Run now, zone/DST, cadence editor all in place on the card.
- Displays: none elsewhere. Hive/district/creature never say a hive is scheduled; schedule card does not link to its hive (`workflowId` unused for navigation). Docs mention "Add the coding suite" in Improvement empty-state prose (`strings.xml:571`, TEXT).

### 2.8 Connection: repository host / repository
- Canonical: Keeper's room > Connections > `RepositoryConnections` (`onConfigureRepositoryService`, `ControlRoomUi.kt` ~L1300); repository choice per hive in launch Options (`MindMapRunScreen.kt` `repositoryFields`) and Projects part.
- Displays: breadcrumb repo crumb (TEXT, `onClick = null`), `RepositoryProgressRecord` title (TEXT; clicks open working creature, `:105`; has outbound `openUri` for PR/branch), Work executor lines for GitHubAction/ExternalService (LINK to Connections, `LiveTechnicalInspector.kt:500-506`), validation of "Every agent works only inside a repository" gate (prose + fields in place).

### 2.9 Remote A2A agent / MCP server / local CLI agent / compute node
- Canonical: Connections (A2A agents, MCP servers: `McpServerConnections.kt`), Local agents part, Compute part.
- Displays: `McpConsentDialog` / `McpElicitationDialog` name the server (TEXT; consent offers Always allow in the dialog, duplicating the row's "Always allow"); Roster/Store role execution (TEXT); Compute node records (TEXT, `ComputeDelegationScreen.kt:229`); creature Work "Runs on" row **cycles Local/AnyRemote in place** (`HiveJobEditor.kt:195-205`, COMPETES with Compute part's per-workflow/role/task `DelegationTargets`, `ComputeDelegationScreen.kt:268-305`); creature executor lines LINK to Compute (`:500`).

### 2.10 Memory (episodes, summaries, engines, retention)
- Canonical: Memory swarm screen (rail Memory; Timeline Memory tab). Displays: Memory log source in Timeline Log (TEXT), role-instruction proposals derived from memory habits (in Mind and Improvement, TEXT evidence with run/task ids, `RoleInstructionProposalPanel.kt:132-140` not links), Projects retention list in Memory (project names TEXT), Settings About trace export (separate).

### 2.11 Artifact
- Canonical: none as a place (retired). Displays: creature Work artifacts (expand in place, `LiveTechnicalInspector.kt:583-607`), "What it made" (`MindMapRunScreen.kt` ~L830, selectable TEXT), Inbox detail artifact cards (TEXT, `LiveInboxScreen.kt` InboxDetail), repository records (external URL). No artifact deep link; Inbox artifacts do not open the creature's Work.

### 2.12 Run / event / timeline
- Canonical: Timeline (Overview/Waterfall/Events/Log) in the live hive. Displays: RunScars (LINK `onSwitchRun`), Events (LINK to creature), Waterfall (LINK), run number/state in title (TEXT), Role activity (counts, TEXT), "Open its trace" (LINK, `:433`). Runs of other hives only via District.

### 2.13 Proposal (improvement / role-instruction)
- Canonical: Keeper's room > Improvement (hive/role/habit proposals; `ImprovementPart.kt:256`) and Mind > `RoleInstructionProposalPanel` (role-instruction proposals). Two ledgers, two surfaces; tab badge `pendingProposals` on the Mind tab (`LiveTechnicalInspector.kt:930`) is TEXT.

### 2.14 Package / breed / stable creature
- Canonical: Azphalt Store (install/remove packages, buy breeds), Stable (keeps/hatches). Role-to-creature assignment (`CreaturePicker`, `StableScreens.kt:563`) is edited **in the creature Mind tab** (`LiveTechnicalInspector.kt:690`) and displayed in the Stable (TEXT captions). Hive delete refusal for packages points to Store in prose only.

### 2.15 Project
- Canonical: Keeper's room > Projects (files/export) + hive launch Options (name/repository). Displays: District group headers (`DistrictScreen.kt` `project.uppercase()`, TEXT), hive eyebrow "RUNNING · project" (TEXT), launch fields. No project destination.

### 2.16 Appearance / device settings
- Canonical: Keeper's room > Appearance/Device. Displays: reduce motion / creature labels referenced in docs only; Hive Setup/Swarm switch (`SetupSwarmSwitch`) and Live/Build/Flowchart are view state, not settings.

---

## 3. Existing partial link mechanisms (to unify)

| Mechanism | Defined | Goes to | Entity-aware? |
|---|---|---|---|
| `openConnections` | `ControlRoomUi.kt:893` | Keeper > Connections | No (generic) |
| `openConnectionsFromPanel` | `:260` | same (duplicated; also sets `keeper.open`) | No |
| `onOpenCompute` | `:427` | `Compute` destination -> Keeper Compute part | No |
| `onOpenLimits` | `:1199` | Keeper > Limits/Device | Part-level (wide/compact switch embedded) |
| `openKeeper` | `:448` | Keeper list (resets `keeper.open` when coming from elsewhere) | No |
| `openHiveInWorkflows(defId?)` | `:901` | Workflows pinned on a hive's Overview | Hive-level only |
| `openCreature(id)` / `selectCreature(id)` / `releaseCreature` | `:283/:274/:~455` | creature panel (does not leave the current *place*; it only works when destination == Overview) | Creature-level, no tab |
| `openInbox` | `:332` | creature Needs of the first waiting creature, else Inbox; sets district=false, resting="", tab, task | Creature+tab (the only place doing full reset correctly) |
| `onOpenMind` | `CreatureNeedsPanel.kt:54` -> `onTabChange(Mind)` | Mind tab, not the Roster | Tab-level |
| `onOpenTrace` | `:433` | Timeline with creature selected, panel set aside | Creature-level |
| `onReconfigureProvider(id)` / `onConnectFreeProvider(id)` | `App.kt:1097,1119` | provider credential dialog | **Provider-level (true entity link)** |
| `onSwitchRun(runId)` | `ControlRoomUi.kt` ~L960 | run of hive | Run-level |
| `onEnterNeighbourhood` | ~L960 | hive (live by latest run, else resting) | Hive-level |
| `azphaltPackageImportRequest` | `App.kt:536` | Store | Package-level |
| `KeeperSection.of(Compute)` | `KeeperRoom.kt:96` | Compute part (the only "retired destination -> part" alias) | Part-level |

Observed bugs in these mechanisms:
- Leaving Overview clears the selected creature (`App.kt:738-740`) and `creature.panel.task` (`ControlRoomUi.kt` LaunchedEffect ~L317), so **every link out of the creature panel (Connections, Compute, Limits) returns to the whole hive, not the creature/tab the user left** (the opener does push Overview onto `navigationHistory`, but selection/tab are not part of history).
- `openConnections` is implemented twice with the same body (`:260` and `:893`).
- Links from non-Overview places to a creature do not exist (no destination switch inside `openCreature`).
- `needs_budget` meter and `LiveInspectorLine` links use different cues (see 4.3).

---

## 4. GAP LIST (ranked by user impact)

Impact: H = blocks the owner rule outright or loses user context; M = duplicate/competing editor or dead end; L = polish.

### 4.1 In-place editors that compete with the canonical place (what the owner named)

| # | Item | Where | Impact | Proposed fix | New target needed? |
|---|---|---|---|---|---|
| E1 | **CreatureProviderRow**: inline Auto/provider picker writes role `preferredProviderId` | `CreatureProviderRow.kt:36`, used `LiveTechnicalInspector.kt:440` | H | Replace picker with a read-only value row "Provider: Auto / Pinned: Claude (role X)" that links to the role's provider setting (Roster role, provider field). Keep only the status text; delete `ProviderChoiceRow` use here. Unconnected pin links to `Provider(providerId)` not generic Connections. | Yes: `Role(roleId, field=provider)`, `Provider(id)` |
| E2 | **Mind tab embeds the whole role form** + proposals + stable CreaturePicker | `LiveTechnicalInspector.kt:685-709` | H | Mind tab becomes a summary list (`MindDefinitionList` already exists, `:687`) where each row (name, instructions, provider, budget, authorities, stable creature) links to `Role(roleId, field=...)` in the Roster. Move `RoleInstructionProposalPanel` and `CreaturePicker` to the Roster role page. | Yes: Roster must open on one role (`focusRole` as a route, not an embedded form) |
| E3 | **HiveJobEditor** (objective, criteria, approval, retries, runs-on) edited in creature Work and Build panel | `HiveJobEditor.kt:79`, `LiveTechnicalInspector.kt:431`, `TerrariumConnections.kt:234` | H | Work shows the job read-only (objective text, criteria list, approval, retries, runs-on) each linking to `Task(workflowId, taskId, field)`: Build with that creature selected (already the real editor) or a new Workflows Tasks row editor. Remove the second editor. Approval row stops cycling in place. | Yes: `Task(defId, taskId, field)`; Workflows Tasks tab must write/read `workflows.selected-task-id` (already exists) |
| E4 | **Needs "Budget" meter / Raise budget / Inbox BudgetSpent** | `CreatureNeedsPanel.kt:76`, `LiveTechnicalInspector.kt:417`, `LiveInboxScreen.kt inboxDetailCopy` | H | All three link to `Role(roleId, field=tokenBudget)`; Inbox BudgetSpent gets an action (it is the only kind with prose "Raise it on this creature's Mind" and zero buttons). | Yes (`Role` field) |
| E5 | **Work "Runs on" row cycles Local/AnyRemote in place** | `HiveJobEditor.kt:195-205` | M | Link to `Compute(task=...)` (Compute part already has per-task `DelegationTargets`). | Yes: `Compute(entity)` focusing the task |
| E6 | **Role provider also pickable on Roster card well** and role form | `CompanyCustomizationScreen.kt:~955-972` | M | Roster is the canonical place; keep. But card well should not host a second picker once the form is the single place (collapse to one). | No |
| E7 | **Improvement "Adopt" edits role instructions in place** vs Mind proposals vs Roster | `ControlRoomUi.kt:~1330` (`onSaveRoleInstructions`), `ImprovementPart.kt:256`, `RoleInstructionProposalPanel.kt` | M | Proposal card header links to `Role(roleId, field=instructions)` / `Task(...)`; keep Adopt/Dismiss but show the diff there only. Unify both ledgers on the Role page. | Yes (`Role` field) |
| E8 | **Workflows Tasks tab "reassign role" pills** | `LiveWorkflowLibraryScreen.kt:~500-530` | L | Keep (it is the Workflows editor), but role name labels link to `Role(roleId)`. | No |
| E9 | **Memory stage engine provider/model pills** | `MemoryLayerScreen.kt:409-490` | M | Keep binding in Memory (it is the memory place) but unconnected provider -> `Provider(id)`; provider chip label linkable. | No |
| E10 | **McpConsentDialog "Always allow"** duplicates the Connections row switch | `McpServerConnections.kt:329` | L | Dialog title/server name links to `McpServer(id)` in Connections. | Yes (`McpServer(id)`) |
| E11 | **Stable creature assignment edited in Mind** (`CreaturePicker`) | `LiveTechnicalInspector.kt:690`, `StableScreens.kt:563` | M | Move to Roster role page; Stable rows ("living in agent X") link to `Role(roleId)`. | Yes |
| E12 | **Compute part vs creature "Runs on"**, **Delegations part vs approval dialog** | see E5 | M | as E5. | |

### 4.2 Displays that are not links

| # | Display | Where | Impact | Proposed link |
|---|---|---|---|---|
| D1 | Creature header line "state · provider · 3 of 5" links only to Work tab | `LiveTechnicalInspector.kt:309-317` | M | Split into segments: provider -> `Provider(id)`; "3 of 5" -> creature in hive; state -> Timeline for that creature (`onOpenTrace`). |
| D2 | Work "Responsible role", "Provider target", "Payload context" (role + instructions snippet) | `:510,662-682` | H | `Role(roleId)`, `Provider(id)`; payload redaction policy -> `Workflow(defId, tab=Overview)`. |
| D3 | Work "Provider" link is generic Connections, not the provider | `:512-515` | H | `Provider(providerId)` (use `onReconfigureProvider(id)` or Connections row focus). |
| D4 | Inbox detail: no "Open creature" / no links for Question and BudgetSpent | `LiveInboxScreen.kt` InboxDetail/Actions | H | Add "Open creature" (`Creature(taskId, tab=Needs)`) to every entry, and route Question/Budget to Needs/Role. |
| D5 | Hall Monitor findings (`finding.subject`, `solution.target`) | `HallMonitorInboxScreen.kt:~85-125` | M | `target` -> `Creature`/`Role`/`Workflow` per scope. |
| D6 | Schedule cards: title is not a link to its hive; hives never show they are scheduled | `SchedulesPart.kt` ScheduleCard; district/hive | H | Card title -> `Workflow(workflowId)`; add a "Scheduled" note (AzphaltNote with onClick) on hive/district -> `Schedule(id)`. |
| D7 | Repository crumb and `RepositoryProgressRecord` title | `shellCrumbs` (repo crumb `onClick=null`), `RepositoryProgressUi.kt:105` | M | -> `Connection(repoServiceId)`; keep working-creature link as a secondary action. |
| D8 | Breadcrumb tab/timeline/keeper-part crumbs | `shellCrumbs` | L | Make creature-tab crumb switch tab, timeline crumb -> timeline, keeper-part crumb -> part (already `onCloseKeeperPart` for phone only). |
| D9 | Terrarium creature badge "Pinned: Claude"/"Auto" | `MascotNodeTerrarium.kt:822` | L | Tap target needs canvas hit region: -> `Role(roleId, field=provider)`; at minimum creature select already works. |
| D10 | Delegations rows (role, provider) | `DelegationsPart.kt:65-85` | M | `Role(roleId)`, `Provider(id)`; "Approved by delegation" event line -> `Delegation(key)`. |
| D11 | Roster "in N hives" and role activity | `CompanyCustomizationScreen.kt:~886` | M | count -> list of hives (`Workflow(id)` links). |
| D12 | Roster "Workflow policies" records (integration, concurrency, test design, prompt reuse) are read-only with no link to where they change | `:996-1038` | M | -> `Workflow(defId, tab=Overview)`; or remove from Roster. |
| D13 | Compute part workflow/role/task rows and nodes | `ComputeDelegationScreen.kt:~190-300` | M | `Workflow`, `Role`, `Creature`/`Task` links. |
| D14 | Store (Workflows tab copy) lacks provider connect/disconnect; two Stores | `LiveWorkflowLibraryScreen.kt:319` | M | Replace Workflows Store tab with link to `Store(category)`. |
| D15 | Prose "Raise it ... in the hive", "Remove the package in the Store", "Settings", "Switch some on in the Roster", "Raise or clear the cap in the Keeper's room, under This device" | `strings.xml:292-293,781,210,608,1382,571` | M | Convert each sentence into text + adjacent link (`Destination`). |
| D16 | Artifacts: "What it made", Inbox artifact cards | `MindMapRunScreen.kt:~830`, `LiveInboxScreen.kt` | L | -> `Artifact(taskId, artifactId)` (Work tab, artifact expanded). |
| D17 | District project headers, hive eyebrow project | `DistrictScreen.kt`, `HivePageTitle` | L | -> `Project(id)` (Keeper Projects). |
| D18 | Needs "Budget" meter has no min touch target or link cue (underline/chevron) | `CreatureNeedsPanel.kt:93-100` | L | Apply the `LiveInspectorLine`/`AzphaltNote` cue. |
| D19 | Improvement proposal `target`, "Add to <hive>" | `ImprovementPart.kt:256-300` | M | target -> `Role/Workflow`; hive name -> `Workflow(id)`. |
| D20 | Events "Approved by delegation", run numbers, repository branch/commit | Timeline | L | link to `Delegation`, `Run(id)`. |
| D21 | Other-place -> creature: no place can open a creature (works only while destination == Overview) | `ControlRoomUi.kt:283` | H | `navigate(Creature(...))` performs the full reset `openInbox` already does. |

### 4.3 Back-stack gaps

- B1 (H): Link out of a creature panel loses creature+tab on return (`App.kt:738-740`, `creature.panel.task` cleared). Fix: history entries must store a `Destination` snapshot, not just the enum.
- B2 (M): Five uncoordinated Back handlers; App-level one clears selection (`App.kt:526`) whereas the dock/sheet only set aside (`:293`, `:443`), contradicting docs ("closing only sets it aside"). Verify order of registration; unify.
- B3 (M): `selectCreature` toggles; Timeline/Waterfall/Log use it (`HiveScreens.kt:96`, `RunWaterfallChart.kt:169`, `LogView.kt:187`), so a link to the open creature closes it. Use `openCreature` for all links.

---

## 5. PROPOSED NAVIGATION MODEL

### 5.1 One typed Destination

~~~kotlin
/** Everything a person can be taken to. Ids are the domain ids already in use. */
sealed interface Destination {
    // Hive side
    data object District : Destination
    data class Hive(val definitionId: String, val runId: String? = null, val view: HiveView? = null) : Destination   // Live/Build/Flowchart/Timeline(+view)
    data class Creature(val taskId: String, val tab: CreatureTab = Work, val definitionId: String? = null) : Destination
    data class Run(val runId: String) : Destination                    // => Hive(runId) on its Timeline
    data class Artifact(val taskId: String, val artifactId: String) : Destination
    // Editors (canonical places)
    data class Workflow(val definitionId: String, val tab: WorkflowLibraryTab = Overview, val taskId: String? = null) : Destination
    data class Task(val definitionId: String, val taskId: String, val field: TaskField? = null) : Destination   // Build with task selected, or Workflows Tasks
    data class Role(val roleId: String, val field: RoleField? = null) : Destination                           // Roster, one role
    data object Roster : Destination
    data class Stable(val breedKey: String? = null) : Destination
    // Keeper's room
    data class Keeper(val part: KeeperSection, val entityId: String? = null) : Destination                   // Connections+providerId, Schedules+scheduleId, Delegations+key, Limits, Compute+taskId
    data class Provider(val providerId: String) : Destination         // Keeper(Connections, providerId); may open credential dialog
    data class Connection(val kind: ConnectionKind, val id: String) : Destination  // repo service, A2A agent, MCP server, local CLI
    data class Schedule(val id: String) : Destination
    data object Limits : Destination
    // Others
    data class Memory(val view: MemoryView? = null, val stage: String? = null) : Destination
    data class Store(val category: StoreCategory? = null, val packageId: String? = null) : Destination
    data object Inbox : Destination                                    // resolves per openInbox rules
}
~~~

`RoleField` = Provider, TokenBudget, Instructions, Execution, Authorities, Surfaces, StableCreature; `TaskField` = Objective, Criteria, Approval, Retries, RunsOn.

### 5.2 One entry: `navigate(destination)`

- Owned by `ControlRoom` (state lives there already: district, restingHive, keeper.*, creature.*, timeline). It replaces `onDestinationSelected`, `openConnections` x2, `openConnectionsFromPanel`, `openKeeper`, `openHiveInWorkflows`, `openInbox`, `openCreature`, `onOpenCompute`, `onOpenLimits`, `KeeperSection.of`, and the `rail onPlace` branches. `App.kt` keeps only the persisted `destinationName` + history as the stack store.
- A pure resolver `resolve(destination): NavigationState` (unit-testable like `railHiveStep`, `ShellRail.kt:133`) writes the durable keys (listed in 0.2) and the top-level `ControlRoomDestination` in one step. Existing helpers (`openWorkflowsOn`, `openInbox` body) become its cases.
- Passed down once as `LocalNavigator.current` (CompositionLocal, like `LocalReleaseCreature`, `LocalWorkflowLibraryHost`) so every composable (`CreatureNeedsPanel`, `HiveJobEditor`, Inbox, Schedules...) calls `navigator.go(Destination.Role(roleId, RoleField.TokenBudget))` instead of threading `onOpenX` lambdas. This also deletes the lambda plumbing through `MainDestination` (30+ params).
- Deep links (Android intent / desktop CLI / web hash) later parse into the same `Destination` (`aive://role/<id>?field=provider`); the existing package-import request becomes `Store(packageId)`.

### 5.3 Back stack

- Stack of `Destination` snapshots (not the enum). `navigate()` pushes the *current* snapshot (including Creature + tab, Keeper part + entity, Workflows tab + task, Hive + view) before moving, unless the target is the same place at a different field (replace top).
- Back pops one snapshot and restores it via the same resolver (so returning from Connections lands on the creature and its Work tab: fixes B1). Re-visiting a place already in the stack pops to it (existing rule, `App.kt:731-737`).
- Order of precedence on Back (one handler in `ControlRoom`): transient overlays/dialogs -> creature panel set-aside (stays selected) -> sub-view (timeline -> hive; keeper part -> list) -> stack pop -> zoom out to district -> OS. Delete the four separate handlers and the App-level one; keep `KeeperRoom`'s compact list/part state as stack sub-state.
- "Hive" crumb/rail Hive keep clearing the stack (hive is home; `App.kt:733`).
- Persist the stack (small list of strings) in `DurableUiState` so a restart is consistent with the persisted tab keys (today `ControlRoomSession` is process-only).

### 5.4 One convention for "linkable" things

Existing precedents to standardise on: `LiveInspectorLine(onClick)` (underline + `Role.Button` + `AzphaltMinTouchTarget`, `LiveTechnicalInspector.kt:827-851`), `AzphaltNote(onClick)` (chevron `›` + 48dp, `AzphaltUi.kt:541-575`), `AzphaltRecord(onClick)` (button semantics merged, `AzphaltUi.kt:426-460`), `AzphaltMinTouchTarget = 48.dp` (`AzphaltUi.kt:169`). Gaps: `NeedMeter` and `CreatureTab` header line have partial cues; no `Role.Link` anywhere (grep: 0 hits; 35 uses of Role.Button).

Convention (one composable `Linked`/modifier `Modifier.azphaltLink(destination, description)`):
- **Cue**: inline text links are underlined and, when the value is a single entity, followed by `›`; cards use the `›` end-cap. Never colour-only. Entity chips in canvas (badges) get a hit region >= 48dp and an accessibility action even if drawn in a canvas.
- **Target**: `defaultMinSize(48.dp x 48.dp)` (`AzphaltMinTouchTarget`), padding not margin.
- **Semantics**: `role = Role.Button` for in-app navigation (Compose has no Link role for non-URL on all targets; use `Role.Button` plus `onClickLabel = "Open <entity> in <place>"`, e.g. "Open Implementation Engineer in the Roster"). Reserve `Role.Link` only for `openUri` external links (`RepositoryProgressRecord`, Connections "Get API key").
- **Description**: `contentDescription = "<label>: <value>, opens <place>"`; merged descendants like `AzphaltRecord`.
- **Where it lands is shown before the tap** for ambiguous labels: eyebrow "ROLE · BUDGET" or tooltip/hint; the destination crumb must read what the link promised.
- **Disabled/unavailable**: render as plain text with a reason (never a dead link), e.g. Compute when not offered (`computeAvailable`).
- **Read-only values stay values**: counters, timestamps, provider run ids are text; the rule applies to anything the user could change.
- Lint/test: a Compose semantics test asserting every `AzphaltRecord`/`LiveInspectorLine` showing a `Destination`-backed entity has `onClick` and an `onClickLabel` (follows the existing `AzphaltContrast`/touch-target test style).

### 5.5 Migration order (smallest steps first)

1. Add `Destination` + `navigate` + `LocalNavigator`, re-implement existing openers on top of it (no UI change). Fix B1 here.
2. Convert prose "where" strings and Inbox (D4, D15, E4 Inbox part), Schedules (D6), Delegations (D10), Hall Monitor (D5): pure additions of links.
3. Entity-level targets: `Role(roleId, field)` page in Roster (E1, E2, E4, E7, E11), `Provider(id)` (D1-D3), `Task(...)` (E3, E5).
4. Remove the competing editors once their link targets exist (CreatureProviderRow picker, Mind embed, HiveJobEditor in creature Work, Work "Runs on" cycling).
5. Unify Back handlers and persist the stack.

---

## 6. Appendix: things verified as already good (keep)

- Creature panel: fed-by/feeds pills and "same creature, other tasks" navigate between creatures (`LiveTechnicalInspector.kt:567,578,748`).
- `AzphaltNote(onClick)` is the documented link style (chevron + 48dp). `LiveInspectorLine` underline cue is consistent.
- `openInbox` is the model for a full-context navigation (district, resting hive, panel set-aside, tab, destination).
- `DurableUiState` already persists most sub-place state; a resolver only has to write existing keys.
