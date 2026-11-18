# Consolidation audit: what the retired screens could do, and where it lives now

Read-only audit, 2026-11-18. Sources: `git log -S`, `git log --diff-filter=D`, `git show` on the commits below; current code under `shared/src/commonMain/kotlin/com/hereliesaz/geministrator/` (abbreviated `G/`); `docs/Keepers-Room.md`, `docs/Navigation-Map.md`, `docs/nav-graph.json`, `docs/UI_SIMPLIFICATION_PROPOSALS.md`, `TODO.md`, `tasks/lessons.md` (rule at line 153: consolidate, justify what remains, do not just remove features).

## What was retired, and how

| Commit | What happened |
|---|---|
| `6b94df9f` (Stage 1) | Repositories and Compute become parts of the Keeper's room; the destinations stay as aliases. |
| `4420c77f` (Stage 2) | Navigation row removed; breadcrumb header; hive's places row held Swarm, Memory swarm, Artifacts, Store. |
| `a66395bb` (Stage 3) | Creature panel split into Needs / Work / Mind. "Inbox and Artifacts retire into it." |
| `22560789` | Deleted `RunsScreen` and `ArtifactFileManagerScreen` (`ControlRoomScreens.kt`, -174 lines) and `LiveArtifactBrowserScreen.kt` (-167); removed `Runs`, `Artifacts`, `Repositories` from `ControlRoomDestination`. |
| `5ae06150` | Removed `WorkflowFlowchartView`; a saved hive opens Edit (no capability lost; Edit shows the same graph). |
| `88a0a205` | Removed the separate hive-edge marketplace; A2A discovery moved into Providers (Keeper's room). |

Current enum: Overview, Workflows, Company(Roster), Stable, Inbox, Compute, Memory, AddOns, Settings (`G/ControlRoomUi.kt:144`).

## Capability table

| Retired surface | Capability | Status | Evidence | Suggested home |
|---|---|---|---|---|
| Artifacts (`LiveArtifactBrowserScreen`, `22560789^`) | Browse every artifact of the live run, grouped by task | PARTIAL | Now only one creature at a time: Work tab lists that creature's artifacts, `G/LiveTechnicalInspector.kt:642-668`. Nothing lists a whole hive's artifacts. | A "What it made" section on the hive (it exists for finals only, see next row) listing all artifacts grouped by creature, each linking to that creature's Work. Reuse `AzphaltRecord`. |
| same | See the final result on the hive | PARTIAL | `G/MindMapRunScreen.kt:933-960`: only for a Completed run, only terminal tasks, only artifacts with `textContent`, cut at `MADE_PREVIEW_CHARS = 2_400` (`:1200`). Failed/cancelled runs show nothing. | Same section; show all artifacts for any finished run; "Show all" instead of a silent cut. |
| same | Search/filter artifacts (task, label, kind, uri, media type) | MISSING | The deleted screen had a search field (`artifacts_search_artifacts`) and durable key `artifacts.<run>.query`. No search exists in any Work or hive view (grep of `G/` for `artifact` in search code: none). | A filter field on the proposed all-artifacts section of the hive; no new destination. |
| same | Open an artifact and see id, uri, text | PRESENT | Work tab expands to uri and `textContent`, `LiveTechnicalInspector.kt:644-668`. | n/a |
| same | Open non-text artifacts (Media, PullRequest, CodeChange with a uri) | MISSING | Neither the old nor the new view branches on `ArtifactKind` or `mediaType`; a uri is printed as plain text (`:659`). `LocalUriHandler` is used only in repository/provider credential screens. No image decode for `ArtifactKind.Media`. Only `A2aServer.kt:575-576` treats `mediaType`. | In the Work tab's artifact well: image preview for `image/*`, "Open" via `LocalUriHandler` for any http(s) uri (PR, release), mediaType shown. |
| same | Copy / export / save an artifact | MISSING | No clipboard or save call on artifact text. The only selectable text is the hive's "What it made" (`SelectionContainer`, `MindMapRunScreen.kt:942`); the Work well text is not selectable. The old browser never had copy either, so this was never present; the owner's rule makes it a gap. | `SelectionContainer` plus a Copy pill (pattern: `LocalClipboardManager` in `MemoryHabitsSection.kt:138`) in the Work well; Save where a platform file picker exists. |
| same | Artifacts of earlier runs | PARTIAL | A past run is reachable only by tapping a scar and then opening each creature (`G/RunScars.kt:31`, `ControlRoomUi.kt:1297`). No cross-run listing. | Same hive-level section, with the scars switching the run it reads. |
| Artifacts (`ArtifactFileManagerScreen`) | Browse/Search/Storage pills, tree preview | MISSING (never worked) | The pills had `onClick = {}`, the preview was a fixed string "status: available" (`22560789^`). Real function lived in `LiveArtifactBrowserScreen`. | Nothing to restore beyond rows above. |
| Artifacts | All `ArtifactKind` values (31: Requirement ... HallMonitorReview, `domain/Artifacts.kt:6-31`) | PARTIAL | Every kind renders identically (kind name as eyebrow). Per-creature only, plus final text on the hive; per-hive and cross-run views do not exist. | See rows above. |
| Artifacts | Untrusted-context marker, checkpoint commit | PRESENT | `LiveTechnicalInspector.kt:364`, `:605`, `:865`. | n/a |
| Runs (`RunsScreen`, `22560789^`) | List all runs of all projects with status, objective, id | PARTIAL | Scars list finished runs of the same hive only (`runScars`, `RunScars.kt:24-27`), no running or paused runs, as pills with objective cut at 24 chars. District enters a hive by its latest run only (`ControlRoomUi.kt:1206-1215`). | Keep scars; add "All runs" overflow on the hive's comb row (existing `onLoadRunHistory` already returns every project); no new place. |
| Runs | Switch to / replay a past run | PRESENT | Scar tap calls `onSwitchRun` (`RunScars.kt:52`, `ControlRoomUi.kt:1298`). | n/a |
| Runs | Mark the active run | PARTIAL | Old rows showed "Active"; scars omit the current run by design. | Acceptable. |
| Runs | Run timeline of events | PRESENT | `RunTimeline` (Timeline row: Overview, Waterfall, Events, Log, Memory per `nav-graph.json`), `ControlRoomUi.kt:1301-1305`. | n/a |
| Runs | Filter/search runs, compare two runs | MISSING (never existed) | Not in `22560789^` `RunsScreen`; no `compare` in `G/`. Listed only because the owner asked. | If wanted: status filter on the "All runs" list above; compare is new work. |
| Runs | Open a run's history (events, waterfall) | PRESENT | Timeline panels above, per current run. | n/a |
| Runs | Continue / combine runs | PRESENT | `ContinueRunPanel`, launch Lineage picker (`MindMapRunScreen.kt:700`, `TODO.md:143-144`). | n/a |
| Repositories (destination) | List connected hosts, add/remove credentials | PRESENT | Keeper's room > Connections, `G/RepositoryServiceScreen.kt:54` (`RepositoryConnections`, `EntireConnection`); node `keeper.repo`. | n/a |
| Repositories | Link a repository to a hive, pick local folder, search repos | PRESENT | Launch form Options (`MindMapRunScreen.kt:620-690`), `onPickLocalRepository`, `onSearchRepositories`; Feed gate resolves it in place. | n/a |
| Repositories | Per-run repository record / progress | PRESENT | `RepositoryProgressRecord` on the hive (`MindMapRunScreen.kt:~975`). | n/a |
| Repositories | Per-run files | MISSING (never existed) | No file listing existed in the retired screens (checked `22560789^`). | Covered by the artifact rows. |
| Inbox / Messages | Decisions, plan approval, spend stop | PRESENT | Creature Needs tab; Messages destination kept (`ControlRoomDestination.Inbox`, nav nodes `inbox.*`). Inbox artifact cards are text only (`LiveInboxScreen.kt:675-685`), no link to Work (gap D16, `Navigation-Map.md:210`, open). | Link the card to `Destination.Artifact` as D16 proposes. |
| Swarm role editor | Edit a role | PRESENT | Mind tab (`creature.mind.roleform`), Roster (`roster.role`). | n/a |
| Flowchart view | Flowchart of a hive | PRESENT | Edit view (`5ae06150`). | n/a |

## Specific checks

1. **Artifact display today.** Three places: creature Work tab (all artifacts of the open creature, text or uri as plain text, `LiveTechnicalInspector.kt:642`); hive "What it made" (completed runs, final tasks, text only, 2,400-char cut, selectable; `MindMapRunScreen.kt:933`); Messages cards (label plus 120-char ref, `LiveInboxScreen.kt:675`). `ArtifactKind` has 31 values and the UI never branches on it. Non-text (`Media`, `PullRequest`, `Release`, uri-only artifacts) cannot be opened, previewed or copied. `docs/Navigation-Map.md:123` ("Artifact ... Canonical: none as a place") and `UI_SIMPLIFICATION_PROPOSALS.md:11` ("Done, differently") record this as intended; the capabilities lost are the ones above.
2. **First-launch tutorial without a Source.** PARTIAL. The tour is a goal ("Teach me how to use the Aive", seeded by `first_hive_teach_seed`, `MindMapRunScreen.kt:668`) fed like any goal. `startTutorialIfAsked` (`App.kt:501-520`) needs a queen model; with none it only calls `noteQueenAway`, which returns early when there is no current run (`ApplicationRuntime.kt:217-218`). Feed it is gated by `FeedGate.Provider` (`MindMapRunScreen.kt:721`), so with no Source and no free keyless entry (`ProviderCatalog.firstRunFreeEntries()` empty, or offline) the tour cannot start and nothing says so. Mitigation today: first launch auto-connects the first free Source (`:731-737`). There is also no way to replay the tour except retyping the goal (docs/Keepers-Room.md:145). Suggested home: a "Teach me" pill on the first-hive card and in Keeper's room > About that goes through the same gate, and a visible line when the queen is away instead of silence.
3. **Navigation map test.** `tools/navigation/check_navigation.py` (CI step "Verify navigation map"; ran now: "navigation map ok: 105 nodes reachable from app.start"). It checks edges, reachability of declared nodes, and that every `ControlRoomDestination` and `KeeperSection` enum entry is named by a node. It lists places, not capabilities: nothing in it would fail when a screen's features vanish (this is how Runs and Artifacts could retire without a check). `docs/nav-graph.json` has no `artifact` node and no run-list node; `hive.runscars` is the only run entry. Suggested: add `capabilities` to nodes (e.g. "artifact.open-media", "run.list-all") with the owning symbol, and have the script fail when a symbol is missing.

## Stale docs worth fixing with the work above

- `TODO.md:172-175` still ticks "Artifact browser with provenance" and "Run history" as done; the browser is deleted.
- `docs/Navigation-Map.md:3` header says status 0.9.6; gap D16 open.
- `LiveTechnicalInspector.kt:642` comment says "once the Artifacts place" (the place is gone).
