# UI simplification proposals

Status: proposal only. No code in this change implements anything below.

Scope: every destination in `shared/src/commonMain` as of this branch: Overview, Runs, Workflows (all tabs),
Swarm, Artifacts, Inbox, Repositories, Compute, Memory, Azphalt Store, Settings, the header and navigation,
the task inspector sheet, dialogs, and the empty and error states. File names are relative to
`shared/src/commonMain/kotlin/com/hereliesaz/geministrator/` unless noted.

Each proposal gives what to change, why (with the evidence found), effort (S under a day, M a few days,
L a week or more) and risk. Proposals are ordered by priority within each tier. Ground selection has
already moved to Settings and the header now carries the inbox badge; both are assumed below.

Ground rules any of these must keep: content colour comes from the surface it sits on
(`docs/BRANDING.md`), 48dp touch targets, roles and live regions stay intact, and every change keeps
Android, Desktop and Web at parity.

## Findings in one paragraph

The app has 10 destinations (11 where distributed compute exists), shown as one row of pills that scrolls
sideways on a phone, so the right-hand destinations (Memory, Azphalt Store, Settings) are off-screen and the
selected pill is never scrolled into view. Several things exist twice (the Store, run decisions, compute
configuration, the runtime state). Every pill, record and cap takes a hue from a 14-colour palette by hashing
its seed, so a row of four filter pills is four colours that carry no meaning. Every screen opens with a
46sp title and a paragraph that restates what the nav pill already said. Empty states are text with no next
step, and error states mostly print a raw exception message. Some of this is dead code.

## Tier 1: remove duplication and dead ends (high value, low risk)

### 1. One home for the Azphalt Store

- What: keep the Store in exactly one place. Either drop the `AZPHALT STORE` destination and keep the
  Store tab inside Workflows, or the reverse. Recommended: keep the top-level destination (it is also where
  models and LLMs live, which are not workflows) and remove the Store tab from Workflows, replacing it with
  a "Find more in the Store" action in the empty and filtered-empty library states.
- Why: `LiveWorkflowLibraryScreen.kt` mounts a second `AzphaltStoreScreen` as the Store tab, and
  `ControlRoomUi.kt` mounts a third entry point as `ControlRoomDestination.AddOns`. The embedded one is
  built without `connectedProviderIds`, `onConnectProvider` or `onDisconnectProvider`, so inside Workflows an
  LLM package shows as not connected and its Connect button does nothing. Two copies also carry two sets of
  durable state keys and two refresh cycles.
- Effort: S. Risk: low. The package-import hand-off (`azphaltPackageImportRequest` switches the Workflows tab to
  Store) must be retargeted to navigate to the Store destination.

### 2. Make the navigation fit a phone

- What: on compact widths replace the scrolling pill row with a bottom bar of at most five items:
  Overview, Inbox (with the badge), Workflows, Swarm, More. "More" opens a list of Runs, Artifacts, Memory,
  Store, Repositories, Compute, Settings. On wide layouts keep the side rail but group it: primary (Overview,
  Inbox, Workflows, Swarm) then secondary (the rest). Until then, scroll the selected pill into view.
- Why: `CompactNavigation` is a `horizontalScroll` row of 10 to 11 pills, each at least 48dp tall and
  roughly 110dp wide, so on the order of 1,200dp of pills on a 360dp screen. Navigation by the badge or by "Provider
  settings" lands on a destination whose pill is off-screen, so the user cannot see where they are. The row
  also has no scroll indicator or fading edge, so nothing says more exists. Nav pills report `Role.Button`,
  not `Role.Tab`, and have no position text, unlike `AzphaltTabs`.
- Effort: M. Risk: medium (muscle memory, and every deep link that selects a destination). Interim fix, effort
  S and no risk: a `LaunchedEffect(destination)` that animates the scroll state to the selected pill, plus
  `selectableGroup` and `Role.Tab` with the existing `tab_position` string.

### 3. Merge the three "current run" destinations

- What: fold Runs, Artifacts and the Inbox's run-scoped content under one "Run" area with tabs
  (Live, Decisions, Artifacts, History) built with the existing `AzphaltTabs`. The Inbox stays reachable by
  the header badge, which would open the Decisions tab.
- Why: Runs, Artifacts and Inbox all start from the same `runtimeState`, repeat the same
  Loading, No project, No run, Disconnected and Resume-failed branches (each with its own copy of the same
  strings, for example `inbox_loading_runtime` and `artifacts_loading_runtime`), and each opens with its own 46sp
  title. Overview's "Owner attention" record, Overview's inspector sheet, the Inbox and the new badge all
  express "a decision is waiting".
- Effort: M. Risk: medium (durable-state keys such as `artifacts.<run>.query` must be kept or migrated).

### 4. One runtime-state empty and error component

- What: a single `RuntimeStateNotice(runtimeState, onNavigate)` used by Inbox, Artifacts, Compute and Overview,
  with a primary action: "Start a run" (go to Overview), "Retry", or "Open Settings".
- Why: today each screen has a private `when (runtimeState)` that prints a sentence and stops. Examples:
  `runs_no_projects_yet` ("Open Overview and start a run to create one"), `artifacts_no_workflow_run_yet`,
  `inbox_no_project_yet_there_are_no`, `compute_nothing_to_delegate_yet`. The user is told where to go but
  not given the button. Identical strings exist twice (`inbox_no_pending_decisions` and
  `runs_no_pending_decisions`; four runtime-state pairs between Artifacts and Inbox).
- Effort: S to M. Risk: low.

### 5. Delete dead and demo code

- What: remove `CompanyScreen` and `WorkflowTemplateScreen` (`ControlRoomScreens.kt`), `ArtifactFileManagerScreen`,
  `CompanyProviderScreen` (`ProviderManagementScreens.kt`, the older Swarm screen, including its hard-coded
  department and "Workflow Policies" labels), the `AzphaltScreen` wrapper in `AzphaltScreenKit.kt`, and the
  non-live branch of `InboxScreen` with its three hard-coded `DecisionRecord` demo cards ("infra-release",
  "market-security", "foo-integration").
- Why: none has a caller (`CustomCompanyProviderScreen` and `LiveInboxScreen` replaced them). The demo cards
  still ship in the binary, still carry enabled Approve and Reject pills with empty handlers, and their strings
  are translated resources. Dead screens also count towards the localization backlog.
- Effort: S. Risk: low. Confirm with a build that no Desktop or Web entry point references them.

### 6. Remove controls that do nothing or say nothing new

- What: (a) the Overview "run status" pill (`seed = "run-status"`, `onClick = {}`) becomes plain text; (b) drop
  Overview's "Owner attention" record, which the header badge and Inbox now cover, or reduce it to a one-line
  link to the Inbox shown only when a decision is waiting; (c) drop the "Swarm activity" list or the mind map's
  per-task state, whichever the user checks less (both show the same tasks and statuses).
- Why: a pill that looks tappable and is not misleads (and is a Button for screen readers). "Owner attention"
  shows "No decision required, Clear" most of the time, which is the exception state shown as a permanent card.
- Effort: S. Risk: low.

## Tier 2: reduce visual noise and repeated text

### 7. Give pill colour a meaning

- What: use at most three pill treatments: neutral (one hue), selected (Ink with Yellow cap, as now), and
  destructive or caution (one warm hue). Reserve the 14-hue palette for things that are genuinely distinct
  (agents and roles in the terrarium, workflow records), not for buttons and filters.
- Why: `AzphaltPill` and `AzphaltRecord` take their hue from `Azphalt.hue(seed)`, a hash of the seed string,
  and their cap from `Azphalt.cap(seed)`. A row of filter pills (All, Installed, Authored, Roles) shows four
  unrelated colours, "Approve" and "Reject" differ only by hash, and the Settings provider list is a colour
  per provider. Colour is therefore noise: it cannot signal state, and the user cannot tell a safe action from
  a destructive one. It also makes the contrast audit harder (every hue and every cap is a surface).
- Effort: M. Risk: medium (brand character; this is the app's visual signature). Mitigation: keep the palette for
  records and the page ground, change only controls.

### 8. Replace the repeated screen hero with a compact title row

- What: on compact widths drop the 46sp `AzphaltType.hero` title (21 uses) in favour of the `section` or `lead`
  size, and delete the explanatory paragraph under it once the screen has content. Keep the paragraph only for
  the empty state, where it explains what the screen is for.
- Why: on a 640dp phone the header, nav row, 56sp hero line and a three-line paragraph take more than 40% of
  the height before any content. The title duplicates the selected nav pill. Examples of the paragraph:
  Swarm (`swarm_these_are_aive_s_semantic_orchestration`), Repositories
  (`repositories_connect_the_repository_hosts_aive_may`), Settings (`settings_connect_as_many_providers_as_you`,
  60 words, shown on every visit though it only matters before the first provider is connected).
- Effort: S. Risk: low.

### 9. Trim the shell chrome

- What: on compact, drop the "SWARM OS" eyebrow under "THE AIVE" (it is brand text, not information), and show
  the runtime status (`RUNTIME · …`) in the compact layout only when it is not healthy. On wide, keep the
  sidebar footer.
- Why: the compact header never showed runtime status, so users on phones and users on desktop see different
  information; and the sidebar status repeats what Overview prints ("RUNTIME · NO RUN" next to "NO ACTIVE RUN").
- Effort: S. Risk: low.

### 10. One spacing scale, and gutters that suit a phone

- What: define page padding as 16dp compact and 26dp wide in one place (an `AzphaltScreenPadding(compact)`), and
  move vertical rhythm onto `AzphaltSpacing` (xs 4, s 8, m 12, l 16, xl 24).
- Why: 26dp side padding appears in 18 places even on compact (`padding(26.dp)` everywhere except Overview, which
  switches to 16dp), taking 52dp of a 360dp screen. `spacedBy` uses 8 (68 times), 10, 12, 14, 16, 18 and `AzphaltSpacing`
  is referenced 17 times. Adjacent screens therefore have visibly different density.
- Effort: M (mechanical but wide). Risk: low, but needs a screenshot pass on the narrowest and the largest font scale.

### 11. Consistent confirmation pattern

- What: use `AzphaltAlertDialog` for every irreversible action.
- Why: three patterns coexist: dialogs (Settings delete-all, disconnect, Inbox reject and stop), an inline
  record with Reset and Cancel pills (Swarm reset to defaults), and inline pills "Forget everything" then
  "Keep it" (Memory). Users cannot predict whether a tap is final, and the inline ones are not modal, so focus
  and live-region behaviour differ.
- Effort: S. Risk: low.

### 12. Raw exception text is shown in many places

- What: apply the pattern now used by the Store (plain-language sentence naming the step, plus the existing
  `ErrorDetailsToggle` carrying class, message and cause) to the remaining raw `failure.message` sites.
- Why: `ErrorDetailsToggle` is used on Overview's runtime state, Settings' provider health, remote access, and
  now the Store. The workflow library load error, the Overview project-load and repository-search errors,
  Memory actions and Settings export and import still print `failure.message ?: generic`, which can be blank
  (the original Store bug) or an internal sentence.
- Effort: M. Risk: low.

## Tier 3: restructure sections that carry too much

### 13. Fold Repositories into Settings (as "Connections")

- What: remove the Repositories destination. Put GitHub, GitLab and Local Git under a "Connections" section next
  to AI providers, and keep the contextual "link a repository" flow in the Overview launch form.
- Why: the destination has two records and a third that says "No token". It is a credential list, the same job
  as the AI Providers list in Settings (same Reconfigure, Disconnect, Get access token pills, same disconnect
  dialog). One nav slot for it is expensive; the content would take one screen section.
- Effort: S to M. Risk: low (Overview's launch form already offers the connect flow).

### 14. Slim the Settings screen

- What: group the 11 sections (AI Providers, Appearance, Crash Reports, Remote Access, Local Planner, Local
  Orchestration, Compute Pool, Projects, Data, Diagnostics, Privacy) into five
  collapsible groups: Connections, Appearance, This device (planner, orchestration, remote access, compute pool),
  Data and projects, About (diagnostics, privacy, crash reports). Show provider rows collapsed, connected first,
  with Reconfigure and Disconnect behind the row and "Get API key" inside the connect flow.
- Why: the provider catalog renders every provider as a full record with three pills plus a global health
  pill, so Settings opens as a long run of near-identical cards before the first non-provider setting.
  `ProviderSettingsScreen` has more than 30 pill controls and the `settings_` prefix has about 116 strings, and several sections open with a
  multi-sentence paragraph assembled from three strings.
- Effort: M. Risk: low to medium (durable state keys such as `settings.compute.*` stay).

### 15. Decide what "Compute" is

- What: remove the `Compute` destination (only some builds have it) and show assignments as a field on the
  task view in Workflows, with the fleet status inside the Settings compute-pool section.
- Why: configuration is in Settings (Compute Pool) and assignment is in its own destination, with partly
  duplicated labels (`compute_online` and `settings_online`, `compute_observe_only` and `settings_observe_only`,
  `compute_logical_cpus` and `settings_logical_cpus`). A destination that appears or not by build also makes
  navigation differ between platforms.
- Effort: M. Risk: medium (a feature, not just chrome).

### 16. Workflows tabs

- What: reduce the tab set to Library, Details, Tasks, Combine. Make Save an action in the pinned header (it
  already appears only when saving is possible). Rename the "Overview" tab to Details so it is not confused
  with the Overview destination.
- Why: up to six tabs (Library, Store, Overview, Tasks, Combine, Save) plus a pinned header plus a status note;
  two of them share a name or a purpose with other screens (Store above, Overview). On a phone six tabs also
  scroll sideways, like the main nav.
- Effort: S to M. Risk: low (`WorkflowLibraryTabState` and its test cover the rules and would need updating).

### 17. Swarm: move the role editor out of the page

- What: open the role editor as a full-screen sheet or sub-screen rather than inline at the top of the page,
  and move "Reset to defaults" and "Save swarm" into one toolbar row.
- Why: `CompanyCustomizationScreen.kt` is 1,494 lines. Pressing Add role inserts a form of four text fields and
  several sections (execution source, attached surfaces, required capabilities, authority) above the
  role list, pushing the list off-screen; the reset confirmation also appears inline in the same place.
- Effort: M. Risk: medium (long form state must survive the transition, as it does now through durable keys).

### 18. Memory: default to the simple view

- What: show "what the swarm remembers" first (search, forget, export, import), and put Stage, Queue, Tuning,
  On-device models, Summaries and Projects behind an "Advanced" disclosure.
- Why: `MemoryLayerScreen.kt` is 792 lines with seven sections and 29 pills; the engineer's controls (retry
  all parked, apply tuning, defaults) sit above the user's controls, and "Forget everything" shares a row of
  full-width pills with Export and Import.
- Effort: S to M. Risk: low.

### 19. Store header

- What: combine "Installed N" and "N updates" into one summary line, turn Refresh into an icon-sized control
  next to the search field, and keep the category pills as the only filter row.
- Why: the header stacks title, paragraph, Refresh pill, an installed count line, an updates line, the search
  field and the category pills before the first package.
- Effort: S. Risk: low.

## Smaller consistency items

- Destination labels are English string literals in `ControlRoomDestination` and are inconsistent
  ("AZPHALT STORE" is shouted in the label while every other label is title case; pills upper-case everything
  visually but screen readers read the source). Make them resources and use one casing. Effort S, risk low.
- The inspector bottom sheet repeats Approve, Reject and Stop, which the Inbox also offers. Keep them in the
  inspector (context) and make the Inbox a list that opens the inspector, so there is one place to act. Effort M,
  risk medium.
- The header badge, Inbox and Overview should share one definition of "needs you" (`inboxItemKeys`) so the
  count, the list and the Overview hint cannot disagree. Effort S, risk low.
- `docs/LOCALIZATION.md` lists status messages set from click handlers as still hard-coded; each simplification
  above that touches such a message should move it to resources at the same time.

## Suggested order

1. Items 5, 6 and the interim fix in 2 (deletions and a scroll-into-view; one PR, no visual redesign).
2. Items 1, 4, 11 (duplicate Store, shared empty state, one confirmation pattern).
3. Items 8, 9, 10 (hero, chrome, spacing) with a screenshot review at 360dp, 820dp and 200% font.
4. Items 2 and 3 (the navigation and run-area restructure), then 13 to 19 as independent follow-ups.
5. Item 7 last: it changes the look, so decide it once the structure has settled.
