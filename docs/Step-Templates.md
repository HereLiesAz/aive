# Step templates

A **step** made from a template is an ordinary creature's task with a contract around it. The creature is still an agent and its work is still not deterministic. What is fixed is the contract: the inputs it needs, the tools it may use, the output it must hand back, what it may change, whether it asks first, and how often it may try. Code checks the contract; no model call is made to check it.

A template is a starting point, not a role. Each step runs as an existing role (the new **Worker**, the Reviewer or the Researcher), and the wording of the step arrives as the task. Roles and templates are meant to become more specific as the Nanny learns what works.

## The six steps

| Step | Role | Inputs | Tools | Hands back | May change |
|---|---|---|---|---|---|
| Write a document | Worker | brief, sources (optional) | none | Markdown with headings | nothing |
| Make a page | Worker | brief, content (optional) | none | one HTML page, no external scripts or stylesheets | nothing |
| Turn text into a table | Worker | text, columns (optional) | none | comma-separated text with a header row | nothing |
| Check the work | Reviewer | work, requirements | none | a `VERDICT:` line, then reasons (the existing gate reads it) | nothing |
| Read from a connected tool | Researcher | question, target (optional) | one tool you name | what it found, with the call named | reads only |
| Change something in a connected tool | Worker | change, target, content | one tool you name | `TARGET:` and `CHANGED:` lines | changes a connected tool |

They appear in the swarm's Edit view under **Add creature**, in a group of their own, and their inputs, tool and effects are rows of the creature's job. The queen may plan with the five that change nothing; **Change something in a connected tool** is added only by you, in Edit.

## What code enforces

- **Inputs.** A step with a required input that has no value stays held, with a plain sentence. An input taken from another step makes it wait for that step.
- **Tools.** The step is offered only the tools its contract names (an empty set means none), and a call to any other is refused before it reaches a server. A step that names a tool runs only on a Source that can limit tools; vendor command-line agents and remote agents bring their own tools and are not used for it.
- **Output.** When the creature finishes, a compiled check reads the output (present, right kind, parses, within its size cap). A miss fails the step like any other failure and goes through its retry and escalation settings. The checks are a fixed list in the app; a proposal cannot add one.
- **Effects and approval.** A step whose contract says it changes something (writes a branch, changes or removes something in a connected tool, sends data off the device) always asks you first, tries once, and cannot have that turned off. A tool that removes things asks on every call. The check that holds an unapproved step is `PolicyAction.CallTool` with a step effect, and the same declaration makes the step count as able to change things for the context-taint rule.
- **Version.** A step records the template version it was made from. A saved swarm keeps that version until you update it.

Older versions of the app ignore the contract and run the step as an ordinary task, without these checks.

### Steps that start from a template

A step that existed before it named a template (the built-in swarms and the Azphalt packages) carries a contract marked `startingPoint`. It names the template, what that template hands back and the step's own inputs, and nothing is enforced: the step keeps the run's whole toolbox, is not held for a missing input and is not failed on its output. The Nanny reads the template from it and can improve the template. The audit of which steps and roles have a template, and the gaps, is in [Azphalt templates](Azphalt-Templates.md).

## How a template improves: lineage

The Nanny can propose a change to a step template or to a role's own definition. It uses the same mechanism for both and the same Improvement part; nothing new is shown.

- **Generation and branch, in plain words.** Every built-in is *generation 1*. A **quick change** (a targeted edit or a transfer from another template) is a new version inside its parent's generation. A **new generation** is made on purpose, at the end of a swarm, from a larger change; it is kept beside its parent as a candidate and does not run until you select it, while the parent keeps running. Two changes made from the same parent are *siblings*: both are kept, and the one you adopted last is the one new steps use. The Improvement row says *quick change* or *new generation*.
- **Intent.** Every change says what it aims to improve (fewer retries, lower cost, faster, fewer rejected outputs, fewer keeper edits), the evidence it answers and how the next runs will judge it. A change without these is refused.
- **Evidence thresholds.** A quick change needs at least 3 steps of evidence; a new generation needs at least 10.
- **Transfer.** A change that worked in one template or role can be proposed for another. It carries a link to where it worked, and follows the same rules and the same approval.
- **Adopt and Revert.** You adopt through the Improvement part. Revert retires the branch (it stays in the lineage, never deleted) and the version it came from runs again. Revisions stay on this device: they are not in the setup file or an export.
- **What a change may never touch.** Effects, the approval floor, privacy limits, the output kind and schema, input ports, the role it uses, and for a role its identity, capabilities, authorities and way of running. Tools may only be narrowed; limits may only be tightened. This is checked on the data when you adopt, and again whenever the catalog resolves a version.
- **Wording may become more specific.** A revision may add what the Nanny has learned works. The first-run tutorial keeps using the built-in roles unless you adopted a revision.

## Starting points: presets and the artifact set

The quickstart intents, artifact types, actions and capabilities of the Artifact tool (36 steps) are **starting points**, not a checklist. A hive needs what it needs; these are the shapes of what a creature can be asked to make, so the queen, the planner and you can begin from a step that already has a checked output instead of a blank task. They are in the catalog as templates and **presets**. A preset is a template of its own id made from a built-in by changing data only (wording, the compiled check on the output, the output kind, the input ports, the reach of the tool); it has no executor of its own, runs through the same contract machinery, and the Nanny improves it as its own lineage (`template/StepPresets.kt`). The ledger of where each of the 36 stands is data too (`template/ArtifactStepCoverage.kt`) and a test accounts for every one.

Two templates joined the six: **Draw a diagram** (a Mermaid flowchart, checked by the same reader the Flowchart view uses) and **Prepare files for a repository** (files written on a new dedicated branch; the result must name a branch that is not a shared one and list the files; it changes a branch, so it asks first and tries once, and the existing gated repository steps commit and push; no hosted page). The presets:

| Preset | From | What it is for | Output check |
|---|---|---|---|
| `deck-outline` | write a document | an outline of a slide deck, one heading per slide (no slide renderer) | Markdown, two headings at least |
| `docs-draft` | write a document | a draft ready to paste into a document tool | Markdown, two headings at least |
| `design-tokens` | write a document | design tokens of a design system | one JSON object |
| `design-page`, `explainer-page`, `data-page` | make a page | a mock-up, an explainer, a page of given data with its sources | one page, nothing loaded from outside |
| `sheet-table` | turn text into a table | rows for a spreadsheet | comma-separated, every column named |
| `read-one-item`, `list-connected-source`, `query-connected-rows` | read from a connected tool | one item, a list, records (the last two hand back a table) | text, or a table |
| `read-repository-files` | read from a connected tool | read the linked repository's files, nothing written | text |
| `change-connected-rows` | change something in a connected tool | change records in a database or sheet tool | `TARGET:` and `CHANGED:` |
| `remove-in-connected-source` | change something in a connected tool | remove one thing with a tool that asks on every call | `TARGET:` and `CHANGED:` |
| `update-repository-files`, `add-assets-to-repository`, `prepare-download-files` | prepare files for a repository | change files on an existing branch, add assets, files to download | `BRANCH:` and `FILES:` |
| `change-code` | prepare files for a repository | a code change with its tests on a dedicated branch | `BRANCH:` and `FILES:` |
| `render-image`, `render-video`, `compose-media`, `edit-audio`, `make-slides` | change something in a connected tool | make or edit media or a deck with one tool (a copy, never the original) | `TARGET:` and `CHANGED:` |
| `localize-content` | turn text into a table | every string translated into a named locale, a row each | comma-separated, every column named |
| `fixed-format-result`, `capped-text-result` | write a document | a result code reads: one JSON object, or one block of plain text | one JSON object, or text |

A preset that changes something keeps its approval floor and its single attempt, and the queen never makes one by herself. Each carries a token allowance in its template body (carried, not yet enforced). The last four rows are the starting templates of the steps the Azphalt audit listed as gaps ([Azphalt templates](Azphalt-Templates.md)); a step that only *began* at an effectful one declares no effect, so nothing about how it runs changes. The presets are not in Add creature (they would crowd it); they are what a blocker names.

## Blockers

A **blocker** is anything that must be created or obtained for a particular hive to be complete: a document, a page, a table, files on a branch, a read or a change of a connected tool, a thing only you can supply. It is typed data on the run: an id, one plain sentence, optionally a starting template from the closed catalog (a template or preset above, validated; "none" otherwise), optionally the step that makes it, and a state: open, in progress, done or dropped (`queen/Blockers.kt`).

- **Who adds one.** The planner, in its plan (`blockers`, each as a line, optionally ` => template-id` and ` @ step-id`; unknown templates and steps are dropped, at most eight, the queen's pick never a step that changes something). The queen, in the middle of a run. You, in the existing hive form. The Nanny's findings, for the next hive. Recording one calls no model.
- **Where you see it.** In Messages as what is missing (the existing "Something is missing" row, with the blocker's own words on a quoted line and a link to its step, else the Story), and in the finish note when the swarm finished with one still open.
- **What it holds.** The hive is not called reached while a blocker is open or in progress (`hiveReached`); a finished swarm with every sign met but a blocker open is said to be finished, not reached. A blocker with a step follows that step (done when it is done, in progress while it works, open again when it fails); you can mark one done or dropped.
- **Giving it a step.** `Blockers.assignStep` makes a step from the starting template (the template's own contract, the version the catalog has now, its first required input set to the sentence) and checks the swarm with the existing validation; an effectful template is yours to start and still needs its approval before it validates.
- **Carry-forward.** `Blockers.carryForward` turns what a finished hive left unmet into open blockers for the next one.

Built: the data, the planner's and the queen's and your way of adding one (as functions), the state following the run, the check on the hive, the sentences in Messages and the finish note, the memory of a run's blockers. Not yet wired: the plan's blockers are remembered per run in memory only (they are lost with the app); nothing yet calls `assignStep` or `carryForward` from a live run, the queen does not yet add one mid-run by herself, your add and mark controls are not in the hive form, and the Nanny's findings do not yet become blockers. A saved swarm for a goal is not provided: a goal's blockers come from its plan.

## Steps that cannot run in Aive

These are simply not available as starting points. None of them is a blocker or something you must waive. Thirteen things of the artifact set need a claude.ai-hosted artifact, a capability of a hosted page, or a device feature Aive has no code for (twelve whole steps, and the hosted halves of publish and upload-assets, which are built in their repository form above):

1. actions/quickstart: the router of that tool; the catalog and the queen's pick are the equivalent.
2. actions/publish as a hosted page with a private address and versions; the repository form is `prepare-files-for-repository`.
3. actions/open: it only shows a hosted artifact; the Story already shows what a step made.
4. actions/pin-unpin: the claude.ai sidebar.
5. actions/upload-assets as a hosted asset store; the repository form is `add-assets-to-repository`.
6. actions/artifact-comments: claude.ai comment threads (a change step with a Slack or GitHub tool can post a comment elsewhere).
7. capabilities/assets: a hosted asset store.
8. capabilities/artifact, and its deprecated alias capabilities/self: a hosted page republishing itself.
9. capabilities/comments: a hosted comment store.
10. capabilities/room: live presence on a hosted page.
11. capabilities/user: the identity of the viewer of a hosted page.
12. capabilities/dictation: no speech-recognition code.
13. capabilities/tts: no speech code.

Options if you want one of the hosted ones: connect a tool that offers it (for example the Artifact tool exposed as an MCP server you connect), after which the read and change steps can use it; or do without. Nothing is faked.

## Not done in this slice

- The Nanny's counts per template are computed from one run when a finding is carried; they are not yet part of the cross-run evidence the self-improvement cycle reads.
- A mid-run finding about a template does not reach the queen's menu yet; findings are carried to the next swarm.
- A change that loosens a limit (a looser check or allowance) is refused rather than confirmed.
- A candidate generation is kept and evaluated by the catalog rules, but there is no control yet to select it after the evidence is in.
- Role revisions change a role's instructions and token budget when a step resolves it. Their tool ceiling and attempts are carried and not yet applied.
- The tool a connected-tool step may use is typed by name in the job; a pick from the live list is not built.
- The per-step token allowance is carried in the contract; no breaker enforces it yet.
- A failed output check is a failure like any other; the violation sentence is the task's blocking reason and is not yet fed to the next attempt.
- Lineage is stored as its own records (`template/TemplateCatalog.kt`). It does not yet reuse the Breed or memory lineage types, which are keyed by creature and swarm.
