# The Keeper's room

The Aive has two places: **the hive**, where all work happens (`Swarm-OS.md`), and **the Keeper's room**, where everything that sets the hive up lives. Consolidating the app into those two places is planned in stages, below; all three are done.

## Parts

| Group | Part | Holds | Was |
|---|---|---|---|
| Feeds the hive | **Connections** | AI providers, remote A2A agents, repository hosts (GitHub, GitLab, Entire) | Settings › AI Providers, Repositories |
| | **Compute** | the compute pool, and which work goes to which machine | Settings › Compute Pool, Compute |
| The keeper | **Projects and data** | project files, the setup file, JSON export and import, delete everything | Settings › Projects, Data, Privacy |
| | **Limits** (wide) | the spend caps: a cost cap per run and an hourly spend cap | This device |
| | **This device** | spend caps (on a phone only), remote access, the A2A server (desktop), the local planner, local orchestration specialists, small tasks on this device | Settings › Remote Access, Local Planner, Local Orchestration |
| | **Delegations** | gates a creature passes without asking, each with Revoke; how many approvals in a row earn an offer | (new) |
| | **Appearance** | the ground, chosen from six swatches; Reduce motion; Always show creature labels | Settings › Appearance |
| | **About and privacy** | crash reports, trace export (OpenTelemetry), diagnostics | Settings › Crash Reports, Diagnostics |

Compute shows only where the build offers distributed compute.

## Delegations

When you approve the same creature's plan on the same provider several times in a row (5 by default) and never reject it, a dialog asks once: "Let it proceed without asking for plan approval?". **Let it proceed** delegates that gate; **Keep asking** leaves it for the rest of the streak. A delegated gate approves itself and its run's events show "Approved by delegation". Only a creature that cannot write to a repository, deploy or write anywhere else, on a provider that cannot either, is ever offered; `HumanApproval` tasks and every other gate always ask (`docs/THREAT_MODEL.md`, Progressive delegation). A rejection starts the streak over and ends the delegation. The Delegations part lists each one with **Revoke**. Delegations stay on this device and are never in the setup file or an export.

Memory is not here: it is a swarm of its own, running beside every other, so it lives in the hive (**Memory swarm**, beside Swarm at the top of the hive). Its stages that run without a model wear the neuron (`Node-Creatures.md`, Programmatic work).

## Layout

- **Wide** (820dp and up): a page title (KEEPER'S ROOM, "Make it yours", one line), then an Ink list of the parts on the left (the open one filled Yellow, a part's count beside it) and the open part on its own Ink card on the right, under its group as the eyebrow, its name and its one-line lead. The spend caps have their own part, **Limits**. Appearance there is a grid of ground cards, three across (swatch, name, SELECTED on the current one), then setting rows, each a line about it and a switch; the whole row toggles.
- **Phone:** there is no Limits part; the spend caps stay at the top of This device.
- **Phone:** the list is its own page; a part opens over it with a "‹ Keeper's room" pill, and Back returns to the list. The header's gear opens the room from anywhere.
- The room remembers the open part (`keeper.section`).

## Connections

One row per thing (`ConnectionRow`): its name, one line about it, its status and what can be done to it. Connected things are Ink rows; things not yet connected are outlined. While no AI provider from the catalog is connected (an agent that comes with a repository host, such as OpenCode through GitHub, does not count), a **Try it free, no key** card offers each keyless gateway in one tap. AI providers not yet connected sit behind one **Add a provider** row rather than a card each, keyless ones first; those have no **Get API key**. On desktop a keyless link holds no secret and is stored without the keychain, so it works on Linux without `secret-tool`. Local Git needs no account: a folder is picked when launching. **Add an A2A agent** takes an Agent Card URL and then an optional token; such an agent runs only for roles that prefer it (`A2A.md`). Drifters let in at the hive's edge are the same connections and are listed here too; disconnecting one here forgets it, as at the edge.

## This device

**A2A server** (desktop) is on unless you turn it off; the switch is kept once pressed. It listens only on this computer, shows its address, and has **Copy token** and **New token**. If another program holds its port, the row says so and the rest of The Aive works as usual. Under it, **Open to this network** (off by default) lets other computers reach the server; the warning beside it says traffic is not encrypted. While on, the row lists the Agent Card URL on each network address, shows each computer that tried with the token as "wants to send work" with **Allow**, and lists allowed computers with **Remove** (`A2A.md`).

**Small tasks on this device** (desktop and Android, off by default) sends short text tasks that need no repository access to the instruct model the memory clerks installed (`OnDeviceTextAgentProvider`, docs/Orchestration_layer.md, Agent Router). It downloads nothing: until a memory stage's local model on a shared base is installed from the Memory swarm, the switch is kept but nothing goes to the device. The choice is stored per device (`on-device.small-tasks.enabled`).
**MCP servers** follow, one row each with **Check**, **Always allow** or **Ask each run**, and **Remove**. **Add an MCP server** takes an address and an optional bearer token, or, on desktop, a command. Until a server is always allowed, the first call to it in each run asks the keeper (`docs/architecture/MCP_HOST.md`).
**MCP servers** follow, one row each with **Check**, **Always allow** or **Ask each run**, and **Remove**. **Add an MCP server** takes an address and an optional bearer token, or, on desktop, a command. Until a server is always allowed, the first call to it in each run asks the keeper (`docs/architecture/MCP_HOST.md`). On desktop, **Script sandbox** (off by default) lets agents call these tools from one short sandboxed script and receive only its result; the approvals still apply.

## Appearance

**Ground** is one radio group of six swatches, the current one ringed (`docs/BRANDING.md`, Ground and the shell header). **Reduce motion** (off by default, stored on this device as `aive.appearance.reduce-motion.v1`) holds the creatures and other looping movement still. It is OR-ed with the platform's own signal (Android's animator scale at 0, the browser's `prefers-reduced-motion`, or `AIVE_REDUCE_MOTION` on desktop), so it can turn motion off but never back on against the system; when the device already asks for less motion, the part says so.

**Creature labels** (off by default, stored on this device as `aive.appearance.always-show-creature-labels.v1`): a hive of more than 12 creatures normally shows only the selected creature's label so packed cells stay readable; turned on, every creature keeps its label however full the hive is (`docs/Node-Creatures.md`).

## Inbox

The Inbox page (`LiveInboxScreen.kt`) on a wide window (820dp and up): KEEPER ATTENTION, "Inbox", and **Mark all read**, which marks every item the run lists as seen in the same read record the header's badge counts (`InboxReadLog`), so the badge clears. Under it, an Ink list of everything waiting on you (NEEDS YOU and how many): each entry is its kind (plan or task approval, sub-workflow gate, escalation, question, budget spent), its attempt when not the first, its task and its role, in plan order. The run keeps no time per wait, so no time is shown. The open entry (remembered as `inbox.selected`) fills the Ink card beside it: its creature, faint in the corner, what it says, each artifact it waits with (ARTIFACT WAITING, its label and a line of it) and the real actions, the same calls as the creature's Needs: **Approve plan** and **Reject plan** (asks first), **Approve task**, **Approve** a sub-workflow gate, **Retry task** and **Stop workflow** (asks first). Approving goes through the run's own approval, so every `HumanApproval` still waits for a person (`THREAT_MODEL.md`, invariant 4). A question or a spent budget says where to answer it: on the creature, in the hive. A Hall Monitor pause or a spend cap stop still has its own screen. On a phone the Inbox is unchanged.

## Navigation

On a phone there is no navigation row. One header sits over every screen (`ShellHeader` in `ControlRoomUi.kt`):

- **The Aive** and the hive's state in a word: NEW, RESTING, WAKING, STOPPED, CAN'T REACH ITS AGENTS, or its run's status.
- **The breadcrumb**: Hive › a hive › a creature › its tab, or Hive › a place opened from it (Swarm, the Keeper's room › its part). The last step is where you are and is not a button; any earlier step goes back up to it, popping rather than stacking. Hive is the district, every hive at once; from a place opened off the hive, Hive returns to the hive you came from. A creature's step closes its panel. It replaces the page titles. On a phone it takes its own line and scrolls to keep where you are in view.
- **The Inbox** badge and **the Keeper's room** gear.

Once there is a hive, resting or live, one row holds its places: its Swarm, the Memory swarm and the Store. It is hidden on a first launch and while the hive is waking or stopped. The Store is also the district's edge, a sheet over the district (`Swarm-OS.md`). The Store's tabs are **All**, **Agents**, **Hives**, **Breeds**, **Installed**, **Models** and **LLMs**. On wide screens (820dp and up) its tabs sit on an Ink capsule, the first hive the Store lists leads in a large card (**View hive**), and every package is a card (its creature on an art panel of its own hue, what it is, its name, a line, its version or install state, and **Inspect**, which opens its record and install below); phones keep the list. Runs are the hive's combs, and Workflows opens from a hive's More editing.
**Wide windows** (820dp and up, `ControlRoomBreakpoints.Wide`) follow the Figma desktop design instead (`ShellRail.kt`):

- **The rail** down the left, in Ink: the Aive's mark, then Hive, Inbox (with its unread count), Swarm, Stable, Memory and the Azphalt Store, the selected one filled Yellow; at its foot the keeper and the Keeper's room. From 1080dp it is 210dp wide with words; between 820 and 1080dp it is a 72dp column of icons, each still named for screen readers, with the Inbox count shrunk to a dot. Hive goes back to the hive from any other place; on the hive it goes out to the district (every hive at once), and from the district back in. The rail replaces the row of places below, which on a wide window keeps only a live hive's own pills (Timeline, More editing). The app has no keeper name, so the keeper block says "You".
- **The header**: THE AIVE / the breadcrumb as words (earlier steps go back up, the last is where you are), the hive's state with a still dot, and the Inbox. The gear lives on the rail.
- **Page titles** (`AzphaltPageTitle`): a small eyebrow, the big title, one line under it and an optional action at the right.

Titles are set in Archivo Black and everything else in DM Sans, both bundled (`composeResources/font`, SIL Open Font License 1.1, see `THIRD_PARTY_NOTICES.md`) and mapped in `AzphaltType`.

On a phone, once there is a hive, one row holds its places: Hives (the district: every hive, New hive, the Stable and drifters at the edge), its Swarm, the Stable, the Memory swarm and the Store. It is hidden only on a first launch; a waking or stopped hive keeps it, so nothing is out of reach while the hive is in trouble. The Store is also the district's edge. Runs are the hive's combs, and Workflows opens from a hive's More editing.

## A creature's panel

Selecting a creature opens its panel, in three tabs (`TechnicalInspector`, `CreatureTab`); the breadcrumb ends on the open one (Hive › a hive › a creature › Mind). The panel opens with the creature itself, large, as it looks in the hive, its name and the three tabs beside it (mockup H4). Under them, one line gives its state, its provider (when it has one) and its place in the plan, such as "Running · Claude · 3 of 5" (`creatureHeaderLine`, `planPlace`):

- **Needs:** its needs, and what it waits on you for: a plan or task to approve, an escalation, a nested gate, a spent token budget (with **Raise its budget**, which opens its Mind), a question its agent asked you (drawn from the host catalog, `Agent-UI.md`), and messages to its agent. This was the Inbox.
- **Work:** first its job (`HiveJobEditor`, mockup H4), the task fields that lived in the Workflows form editor: its **Objective** in a well (changed text is applied with **Apply**), its **Acceptance criteria** as rows, each removed with its ✕, and **Add a criterion**; then **Approval** (a person approves, or none; tapping switches), **Retries** (how many tries, then what happens: the hive stops, it asks you, or another creature takes over; tapping steps through 1 to 5 tries) and **Runs on** (this device, any paired device or a chosen device; with paired devices, tapping sends it off this device and back, and a person's approval step never leaves it). Each change is saved to the hive's workflow the way Build saves it (`setTerrariumJob`, `WorkflowLibraryHost.saveAuthored`, which validates it first, so a repository change still needs its approval ahead of it); the running run stays as it was, so the change takes effect from its next run, and the panel says so. Then what it's doing, live: its thinking and tool/progress steps for its current provider session, newest last, the latest six shown and older ones collapsed behind **Show N earlier lines**; then the field for messages to its agent, apart from that activity; then its state, executor and provider, progress, what it was sent, and its artifacts, each opening on its contents. This was Artifacts. Once its run has completed, failed or been cancelled, **Run again from here** (`RerunFromTaskPanel`) starts a new run in the same lineage where this creature and everything after it work again and the work before it keeps its results; the finished run stays as it was. It asks what the creature should work on, prefilled with its objective, to change it first. When the creature made text and something follows it, **Change what it made** instead keeps the creature's place with the keeper's text as its work and runs only what follows. See `docs/architecture/ARCHITECTURE.md`, Running again from a task.
- **Mind:** its creature, and its role's form, open from the start, its id fixed; applying it saves the swarm (`CustomCompanyProviderScreen(focusRole = …)`). This was the Swarm's role editor. The Swarm keeps adding, arranging and resetting roles. Each role in the Swarm's list says how many saved hives give it a task ("in N hives"; no count until the hives are read), and **All / Producers / Reviewers** above the list filters it; reviewers are the kinds Echo counts as reviews, every other role produces (`SwarmRoleTally.kt`). Moving roles up and down is offered only under All, since the order is the whole swarm's. A role shows no health or integrity: Integrity is per task in one run. Above the form sit any **proposed changes to its instructions**, drafted from its past work (recurring failures, failures then a fix, your repeated corrections): the current and proposed text with why, and **Approve and apply** or **Dismiss**. Nothing changes until you approve; the Mind tab shows how many wait (`RoleInstructionProposalPanel`, [Procedural memory §10](architecture/PROCEDURAL_MEMORY.md)).
- **Mind:** its creature, and its role's form, open from the start, its id fixed; applying it saves the swarm (`CustomCompanyProviderScreen(focusRole = …)`). This was the Swarm's role editor. The Swarm keeps adding, arranging and resetting roles.

A creature opens on Needs when it waits on you, otherwise on Work; the open tab survives a restart while the same creature stays open. Waiting on you means a plan or task to approve, an escalation, a nested gate, or a spent token budget, one definition for the badge, the panel and the hive's Owner attention card (shown only then, opening that creature). The header's Inbox badge counts the waits not yet seen and opens the next unseen one, on its Needs; opening a creature's panel marks its wait seen. Only the Hall Monitor's pause and a spend cap stop, which speak for the whole hive, keep a place of their own (`Inbox`).
A creature opens on Needs when it waits on you, otherwise on Work; the open tab survives a restart while the same creature stays open. Waiting on you means a plan or task to approve, an escalation, a nested gate, or a question from its agent, one definition for the badge, the panel and the hive's Owner attention card (shown only then, opening that creature). The header's Inbox badge counts the waits not yet seen and opens the next unseen one, on its Needs; opening a creature's panel marks its wait seen. Only the Hall Monitor's pause, which speaks for the whole hive, keeps a place of its own (`Inbox`).

The panel's Needs holds only decisions; messages to its agent sit with its Work.

The activity comes from `SessionActivityFeeds` (`workflow/SessionActivityFeeds.kt`): the provider-backed session gateway records each session's `AgentEvent.Thinking` and non-blank `AgentEvent.Progress` there, keyed by task run and provider run (a retry starts a fresh feed). It is in memory only and bounded (60 lines a session, 32 sessions, 600 characters a line); it is never written into workflow state or the event journal (memory banks thinking on its own) and never sent to the duplex peer (`architecture/DUPLEX_MCP.md`). Messages to and from the agent never enter it. On the hive, under **Jump to active**, one short line reads what the active creature is doing now: its newest thought or step, then the creatures waiting on its output, named by role ("· feeds Implementation Engineer"). A creature waits on it while it depends on the active one and has not started; when none does, the line ends at the step (`creaturesWaitingOn`, `CreatureFeeds.kt`). Reject plan and Stop workflow stay quiet; the confirmation is where red appears (`BRANDING.md`).

Back closes the creature panel, then returns through the places opened off the hive, then zooms from a hive out to the district, then leaves the app. Reaching the hive clears the way back.

`ControlRoomDestination.Settings` is the Keeper's room; `Compute` opens its Compute part. The gear opens the room itself (its list, on a phone). Runs, Artifacts and Repositories no longer exist as destinations.

## First launch

Every launch opens on the hive, not on a form or the Keeper's room (`MindMapRunScreen`, `FirstCreature`): the starters to keep one of, the first picked, and one field, **What should it work on?**, with **Feed it**. Feeding keeps the picked starter and names the hive from the first words it was fed (never from an empty tap). Its name, a repository and saved projects are under Options. Until there is a hive, its places' row is hidden.

Feed it is gated on what the connected agents can reach (`ProviderReach`, `FeedGate`), and the gate sits right above it with its resolution in place:

- **No AI provider:** the free keyless gateways, each a one-tap pill (**Use Kilo Gateway · free, no key**), with the note that a free gateway may log prompts; then a quieter **Use your own key** pill to the Keeper's room. Feed it waits quietly.
- **Every agent works only inside a repository** (OpenCode on GitHub Actions, linked with a GitHub token): the repository fields open above Feed it, offering only the kinds those agents work in, alongside the free gateways for work with no repository.
- **A repository no agent works in:** the fields open there too, to choose another kind, or none.

Pressing a gated Feed it shows the gate's message and starts nothing. A launch that fails stays beside Feed it, the objective still typed, until the next edit or launch.

## Budgets and spend caps

Three circuit breakers stop spending; none estimates, and only what providers report counts (`SpendCircuitBreakers.kt`).

- **A role's token budget** (its Mind, per run): once the tokens reported for every task of that role in the run reach it, its running sessions are cancelled and its tasks parked (`ROLE_BUDGET_SPENT`), a stopped one as a fresh attempt, and its unstarted tasks do not start. Each waits on you, on its Needs. Raising or clearing the budget releases them at the next cycle; the current role wins over the run's snapshot.
- **A cost cap per run** and **a spend cap per hour across runs** (This device, in US dollars, blank for none; kept on the device, `SpendCapStore`): when the run's known cost, or the known cost reported on this device in the last hour, reaches its cap, every live session is cancelled and the run is stopped (`WorkflowRun.spendPause`, tasks parked as `SPEND_CAP_STOP`). The Inbox shows the stop and **Lift the stop**, which puts every task back where it was; a cancelled session starts again as a new attempt. So raise or clear the cap first: a cap still reached stops the run again (the run cost at its next cycle, the hourly spend when it is next summed: when the run spends, or after a minute).

Cost a provider does not report (keyless and free providers, or ones that report tokens only) never trips a cap.

## The hive's state

A hive that stopped reads **The hive stopped**; one that cannot reach its agents, **The hive can't reach its agents**. Both offer **Try again** and **Connections**; a hive stopped by corrupted data offers only **Delete all projects and runs to recover**. Work no connected agent can take fails as "No connected agent can do this work", with what to connect (`UserFacingError`, `ErrorKind.NoAgent`).

A completed hive shows **What it made** under its run's status: the text artifacts of its final tasks (those nothing depends on), selectable. Once its run has completed, failed or been cancelled, its one follow-up is **Feed it more**, which asks what next and launches a continuation in the same project (`ContinueRunPanel`). A resting hive keeps its objective bar (`HiveLaunchBar`); on a phone a live one has none.

## The hive on wide screens

From 820dp wide (`ControlRoomBreakpoints.Wide`, the shell's `compact = false`) a live hive is one page (`HiveWorkspace.kt`, `MindMapRunScreen`); phones keep the layout above.

- **Title row:** the run's state and project, the hive's name, what it was fed, and on the right **RUN n** (which run of this hive it is, counting its runs by start; hidden until the runs are read, `hiveRunNumber`) over **k / n creatures active** (creatures planning, running, verifying or retrying, `hiveActivity`).
- **The hive frame,** on Ink: a controls bar (**Live / Build**, one status line, then **Undo** and **Redo** in Build, **Centre the hive**, **Zoom out**, **Zoom in**), the tissue between Ink sides, the legend along its foot (Working, Complete, Waiting, and "Moving bodies never edits the workflow"), and the feed bar under it. The status line is the "doing now" line, the working creature's state when it has said nothing, else the run's state and objective; tapping it opens the working creature, so **Jump to active** is not repeated. The bar drives the field's zoom and focus (`TerrariumCamera`), so the field's own zoom buttons hide; pinching, panning to a selected creature and zooming out past the widest view to the district work as before. Build keeps its plain connections list under the frame.
- **The feed bar** is always under the hive: **Feed the hive**, one field, and **Launch**. Over a finished swarm it launches the objective at once as its continuation, through the same launch as **Feed it more** (which it replaces here). Over a swarm still running it reads **Queue**: launching then would cancel the running swarm, so the objective is held on this device (`HiveFeedQueue`), shown under the bar with **Take it back**, and started as the continuation once that swarm finishes, wherever the keeper is (`QueuedFeedLauncher`). One objective waits at a time.
- **The creature panel docks** on the right (310dp) instead of rising as a sheet, beside the whole page. Its head reads **CREATURE · i OF n** (its place in the plan) over its job's name, with **Close the creature panel**; Back closes it too. A card shows the creature, its state chip (Working in Yellow, Complete in the clasp green, Waiting on Ink, waiting on you or failed in red), its role and its provider. Needs, Work and Mind are tabs underlined in Yellow. Mind opens with a plain list of what it really is (role, provider when it has one, what it needs to work with, its token budget) before the editors. Needs and Work hold the same as in the sheet.

## The hive's timeline

A live hive's **Timeline** pill swaps the hive for its run's timeline, in two views (remembered, `overview.timeline.view`):

- **Waterfall** (the default): one row per creature, one bar per attempt, on a shared time axis, so work running in parallel overlaps. Each bar is coloured by phase: Planning, Waiting on you (drawn as a thin pause line: a gate), Running, Verifying, Retrying. A white tick ends a finished attempt, a red one a failed attempt; a bar still going runs to now. Hovering or tapping a bar shows its phases and usage (tokens, cost); tapping also selects its creature. Each bar is one labelled button for screen readers. The bars unroll once on arrival and, while the run lives, grow once a second; with reduced motion they do neither and move only when new events arrive.
- **Events:** the run's events in order.

A creature's Work reaches it too: **Open its trace** turns the Timeline on and closes the panel, leaving that creature selected on it.

The waterfall is a pure function of the run's event journal (`runWaterfall` in `RunWaterfall.kt`, tested in `RunWaterfallTest`):

~~~
TaskStarted                         -> an attempt begins (Running)
events before it starts             -> it begins early, in Planning
ApprovalRequired, HumanDecisionRequired -> Waiting on you until the decision
work before an attempt's first gate -> Planning (a plan gate follows planning)
ArtifactCreated                     -> Verifying
VerificationFailed, RetryScheduled  -> Retrying, until the next attempt starts
TaskCompleted / TaskFailed / TaskCancelled -> it ends
the run's end                       -> every open bar ends; otherwise they run to now
ProviderUsageRecorded               -> added to the attempt it fell in
~~~

## Consolidation plan

1. **Stage 1 (done):** the Keeper's room; calm controls (`BRANDING.md`, Controls); Memory as a swarm in the hive; programmatic work as the neuron.
2. **Stage 2 (done):** the hive as home. A first launch opens on the hive with the starter creature and one step to feed it (no "No project" form); a breadcrumb (Hive › a hive › a creature) replaces page titles and the navigation row.
3. **Stage 3 (done):** Inbox, Runs, Artifacts and the Swarm role editor move into the creature's panel (Needs, Work, Mind) and the hive's combs; their destinations retire. The breadcrumb gains the creature's last step.
