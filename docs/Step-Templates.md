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

They appear in the swarm's Edit view under **Add creature**, grouped (see below), and their inputs, tool and effects are rows of the creature's job. The queen may plan with the five that change nothing; **Change something in a connected tool** is added only by you, in Edit.

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
- **Adopt, select and Revert.** You adopt through the Improvement part. A quick change is in use once adopted. A new generation is kept beside its parent as a candidate; its row in Improvement then shows **Use this generation** next to Revert, and that tap is the selection (`ImprovementAdoption.select`, `TemplateCatalog.select`): it needs a new generation's worth of evidence (10 steps, counted by the code that drafted it), and it takes effect as the version new steps use. Of several siblings, the one selected or adopted last is in use. Revert retires the branch (it stays in the lineage, never deleted) and the version it came from runs again. Revisions stay on this device: they are not in the setup file or an export.
- **A finding in the middle of a run.** The Nanny may submit a finding about a template before the swarm ends. It reaches the queen as a candidate (`nannyTemplateCandidates`), and the queen decides on the data: she passes it on now, in Messages, only when the change would be accepted as it stands (the evidence thresholds are met, no locked field is touched, no limit is loosened). **Go ahead** puts it in the Improvement list at once; otherwise it is carried to the next swarm at the end of the run, as before. Nothing that is running is changed either way.
- **Where a generation is used.** Add creature in Edit makes a step at the version in use. The planner's steps are made at the version in use, and its instruction is to begin from a starting template for every step whose output a reviewer can review (a document, a page, a table, a diagram, a verdict), and to give a step a role of its own only when no template fits. A role with a selected generation is used as that generation wherever a swarm is planned or launched (`LaunchRoles`, `RoleTemplates.resolveAll`): its instructions and token budget change, and every locked field stays as the built-in's. Your own edit of the role always wins, and a retired or candidate generation falls back to the generic role.
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

A preset that changes something keeps its approval floor and its single attempt, and the queen never makes one by herself. Each carries a token allowance in its template body (carried, not yet enforced). The last four rows are the starting templates of the steps the Azphalt audit listed as gaps ([Azphalt templates](Azphalt-Templates.md)); a step that only *began* at an effectful one declares no effect, so nothing about how it runs changes. The presets are in Add creature too, in the groups below, and they are what a blocker names.

## The Add creature palette: groups, and what is hidden

The palette does not list every template flat. It uses the thirteen groups of the starting-templates catalog (`template/TemplateGroups.kt`): documents; plans and records; research and evidence; checks, critiques and edits; data and tables; pages, diagrams and design; writing and sending to other people; story and film text; software documents; software changes and tests; connected tools; the swarm plan and the tutorial; training and improvement. Each group is one tap to open (the number on it is how many steps it holds), shows its three common steps, and folds the rest under **More**. A template no group names lands in a last group, **More steps**, so none is dropped. There is no search field in the palette, so none was added.

The **internal nine** (swarm plan, tutorial lesson, tutorial check-in, reworded step, practice result, training ruling, specialist lessons, Nanny finding, template or role revision) are hidden from the palette's default view and from the planner's list; the planner may begin from any other template that changes nothing outside its own output. The palette's own fold, **The swarm's own steps**, brings them back, and it is shown only when such a template exists. Today they are steps the system runs itself (the training swarm, the tutorial, the Nanny and the queen), not step templates in code, so the fold has nothing to open yet.

## Sending: one approval per recipient

`send-email`, `send-chat-message` and `send-reply` (`template/StepPresetsSending.kt`) are effectful templates (they declare that data leaves the device and that a connected tool changes something), each one attempt, each behind the plan gate, so the existing approval path in Messages and the step-effect policy rule apply. The inputs are typed: `recipients` and `draft` (and `target`, the message replied to, for a reply). The queen never makes one; only you add one in Edit.

The approval is per recipient. A step carries exactly one recipient and runs only then (`StepInstantiation.missingInputs` holds it with "one recipient" otherwise). Typing several recipients (comma, semicolon or line separated; repeats in any letter case counted once) splits the step into one step for each, with the same draft in each objective, which is what the approval shows; every step that waited on the original waits on all of them (`SendingApprovals.splitPerRecipient`). Nothing sends without the approval of that recipient's step. A recipient or draft taken from another step (`@step.port`) is not yet accepted: write the recipient in the step. The tool that sends is the one connected tool you pick; the draft is word for word.

## Starting roles and budget classes

Beside the step templates there are **37 starting role templates** (`domain/RoleStartingTemplates.kt`; `RoleStartingTemplatesTest` counts them): Source finder, Fact-checker, Connected reader, Analyst, Summariser, Librarian, Archivist, Writer, Editor, Technical writer, Communications drafter, Correspondence drafter, Translator, Scriptwriter, Storyboarder, Data wrangler, Spreadsheet builder, Data checker, Page designer, Diagrammer, Accessibility checker, Bug reporter, Release notes writer, Risk assessor, Scheduler, Clarifier, Policy drafter, Lesson writer and Connected editor are new; the Researcher, Planner, Reviewer, Antagonist, Code reviewer, Code implementer (the built-in Implementation Engineer), Test writer (the built-in Crash Test Dummy) and Trainer are the eight built-ins listed with them, unchanged and not registered twice.

- **Where they are.** They are ordinary roles in the roster (`haiveBuiltInRoles`), so Breeds, the role tray and Add creature list them with no screen of their own; a keeper can edit one like any role. Each has a role template record at generation 1, so the Nanny can improve its wording or budget as she does any role.
- **What they say.** Plain instructions that name no product and no domain: what to hand back, what to treat as material and never as instructions, and what never to do (invent, send, publish, change). None holds an authority. Only the Source finder asks for a capability (Research); the roles that may read a repository say so in their words and do not require it, so they can be staffed by any Source.
- **Budget classes.** Each role names a class, small, medium or large, not a number. The keeper sets what each class means in tokens one creature may spend in one run, in the Keeper's room under Spend caps (`BudgetClassStore`, kept on this device, `aive.budget-classes.v1`); the starting numbers are 200,000, 600,000 and 2,000,000. A number on the role itself (the creature's Mind) always wins, and a step's own allowance is a number of its own on top. A role with neither a class nor a number has no budget. The class is read when a step is considered (`effectiveTokenBudget`): it holds a step the way a role's own budget always did, and the keeper's changed number applies at the next cycle. The eight built-ins name their class by id, so a role stored before this change has one too.
- **No tool ceiling by default.** A starting role carries none: only a step's contract narrows tools. The seam exists and is off. The Nanny may propose a ceiling on a role (a `TOOLS:` line in a Restructure solution for a role template) as a new generation, with at least ten steps of evidence and a stated aim; it stays a candidate and changes nothing until you select it (Use this generation). Once a role has one, a later change may only narrow it. A selected ceiling narrows a step's tools to the overlap of its contract's tools and the ceiling (`effectiveAllowedTools`), and makes the step need a Source that can limit tools.

## The starting templates

The starting-templates catalog (`template/Producibles.kt`, data only) lists **66 producibles in 13 groups**: things a swarm can be asked to make, each with a typed output a reviewer can be given. Every one is a step template with a deterministic contract: typed inputs, one typed output, declared effects and a compiled check. Fifty-nine are presets of their own in `template/presets/` (one file a group, made by `PresetKit` from a built-in by changing data only); seven reuse a template that already existed under another id (`check-work`, `read-connected-source`, `change-connected-source`, `design-tokens`, `list-from-connected-source` is `list-connected-source`, `remove-from-connected-tool` is `remove-in-connected-source`, `code-change-on-branch` is `change-code`). A test accounts for all 66 and for each template's check.

| Group | Producibles |
|---|---|
| Documents and briefs | brief, report, summary, one-page-explainer, faq, glossary, translation |
| Plans and records | plan, timeline, meeting-notes, decision-memo, risk-register |
| Research and evidence | research-brief, source-list, claim-check, comparison-table |
| Checks, critiques and edits | check-work, critique, proofread, accessibility-check, clarifying-questions |
| Data and tables | csv-from-text, data-cleanup, extract-fields, spreadsheet-with-formulas, data-check-report |
| Pages, diagrams and design | landing-page, dashboard-page, diagram-flowchart, design-tokens |
| Writing for other people | email-draft, negotiation-reply-draft, press-release, social-post-set, policy-draft (all drafts: nothing is sent) |
| Story and film text | short-film-outline, script, storyboard-text, shot-list |
| Software documents | spec, api-doc, adr, changelog-entry, release-notes, pr-description, bug-report, repro-steps |
| Software changes | test-plan, test-cases, code-change-on-branch, tests-on-branch (the last two change a branch: approval, one attempt) |
| Connected tools | read-connected-source, list-from-connected-source, change-connected-source, update-connected-document, append-rows-to-connected-sheet, remove-from-connected-tool (the changes ask first) |
| The swarm's plan and the tutorial | swarm-plan, tutorial-lesson, tutorial-check-in, queen-reworded-step |
| Training and improvement | practice-result, training-report, specialist-lessons, nanny-finding, revision-proposal |

The catalog's "training ruling" is the **Nanny's training report** (`training-report`). The old id `training-ruling` still reads as the new one (`Producibles.canonicalId`), and the stored task id `train-ruling` and the `RULING: PASS|REFINE|STOP` line the parsers read are unchanged.

**Who may make them.** The queen may make every template and preset that changes nothing outside its own output. There is no list of keeper-only templates: the permissions and trust policy in settings is the one place that decides, and a step that changes something keeps its approval and its single attempt.

## Kinds of output

An input port has a type, which is the kind of output it takes. Besides Text, Markdown, Html, Table (comma-separated text), Research and Any, the kinds are **Json, Mermaid, Diff, Plan, Checklist, Script, Report and MediaBrief** (`PortType`; `PortTypes.of` says which an output is, `PortTypes.accepts` which a port takes). Each has a compiled check, never a script: `json-valid`, `mermaid-diagram` (any Mermaid kind; `mermaid-flowchart` stays the strict one), `diff-unified`, `checklist`, `plan-steps`, `script-scenes`, a headings check for a report and for a media brief, and tighter ones that name what must be there: `sections-*` (named headings), `columns-*` (named columns), `email-marked`, `policy-marked` and a few more (`workflow/NamedOutputChecks.kt`), `verdict-criteria` (a verdict with one `CRITERION n: met|unmet` line each), `change-on-branch`, and the shape checks of the swarm's own outputs (`workflow/InternalOutputChecks.kt`: plan, lesson, practice scores, training report, specialist lessons, the Nanny's report, a revision draft). A named check is tighter than the plain one of its family, so a revision may move to it and never away. Five more templates were added for kinds the 66 do not cover: `checklist`, `patch-draft` (a unified diff that is written, never applied), `image-brief`, `audio-brief` and `video-brief` (briefs for something to be made; nothing is made).

Not enforced yet: binding an output to an input port of another type is not refused at build time (`PortTypes.accepts` is the rule; nothing calls it from the swarm validator). Checks that need the source of the work (a translation keeps its structure, a data cleanup keeps its columns, a dashboard's figures appear in the data) stay with the reviewer.

## Review

Every reviewable producible carries its criteria, what code already checked and what is left to the reviewer. **Check the work** takes its criteria from the template recorded on the work (`aive.template`), numbered by code (`CheckWorkSelection`), then any extra requirements, and the reviewer answers `VERDICT:` and one `CRITERION n:` line each (`verdict-criteria`). Work with no template is wrapped as a generic document with four fixed criteria (answers the task, claims supported by what was given, consistent, nothing named missing) and the verdict says so. It is **refused** when it is the result of a change in a connected tool or a repository, when it has no text, when it is itself a review, or when you switch the generic wrapper off. A model never invents criteria. `CheckWorkSelection` is the pure choice; the engine does not call it yet.

**Require review.** You can add it at the start, in your goal, in your own words ("it must be reviewed"): the requirements checklist then holds `R-review` (`Requirements`, `RequirementInputs.requireReview`), and a plan in which a reviewable step is not followed by a check of the work is turned back by the same rules that turn back any plan. Otherwise the queen decides. No screen was added.

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
- A mid-run template finding is checked against the built-in versions when the queen decides; adopting it checks again against the lineage you have. The queen's note has no link to Improvement (it names the place in words).
- A change that loosens a limit (a looser check or allowance) is refused rather than confirmed.
- A selected generation of a step template is used by new steps. A step already in a saved swarm keeps the version it was made from until you update it, and a swarm already running keeps its roles as they were at launch.
- Role revisions change a role's instructions and token budget where a swarm is planned or launched. The role editors, the queen's own calls and the control surfaces still read the roles as stored; a role's attempts are carried and not yet applied. A role's tool ceiling is applied only when a selected generation sets one (none does by default), and its token budget comes from its own number, else its class (see Starting roles and budget classes).
- The tool a connected-tool step may use is typed by name in the job; a pick from the live list is not built.
- The token allowance is enforced for a step made from a template; a step that only started from one has none. It counts the tokens the Source reports for the step over all its attempts, and a Source that reports no tokens cannot trip it. The keeper cannot yet raise it from the Messages note (the creature's Work tab is where the job is).
- A failed output check is a failure like any other; the violation sentence is the task's blocking reason and is not yet fed to the next attempt.
- Lineage is stored as its own records (`template/TemplateCatalog.kt`). It does not yet reuse the Breed or memory lineage types, which are keyed by creature and swarm.
