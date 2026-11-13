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

## How a template improves: lineage

The Nanny can propose a change to a step template or to a role's own definition. It uses the same mechanism for both and the same Improvement part; nothing new is shown.

- **Generation and branch, in plain words.** Every built-in is *generation 1*. A **quick change** (a targeted edit or a transfer from another template) is a new version inside its parent's generation. A **new generation** is made on purpose, at the end of a swarm, from a larger change; it is kept beside its parent as a candidate and does not run until you select it, while the parent keeps running. Two changes made from the same parent are *siblings*: both are kept, and the one you adopted last is the one new steps use. The Improvement row says *quick change* or *new generation*.
- **Intent.** Every change says what it aims to improve (fewer retries, lower cost, faster, fewer rejected outputs, fewer keeper edits), the evidence it answers and how the next runs will judge it. A change without these is refused.
- **Evidence thresholds.** A quick change needs at least 3 steps of evidence; a new generation needs at least 10.
- **Transfer.** A change that worked in one template or role can be proposed for another. It carries a link to where it worked, and follows the same rules and the same approval.
- **Adopt and Revert.** You adopt through the Improvement part. Revert retires the branch (it stays in the lineage, never deleted) and the version it came from runs again. Revisions stay on this device: they are not in the setup file or an export.
- **What a change may never touch.** Effects, the approval floor, privacy limits, the output kind and schema, input ports, the role it uses, and for a role its identity, capabilities, authorities and way of running. Tools may only be narrowed; limits may only be tightened. This is checked on the data when you adopt, and again whenever the catalog resolves a version.
- **Wording may become more specific.** A revision may add what the Nanny has learned works. The first-run tutorial keeps using the built-in roles unless you adopted a revision.

## Steps that cannot run in Aive

Thirteen steps of the artifact set are not built, because they need a claude.ai-hosted artifact, or a capability of a hosted page, or a device feature Aive has no code for:

1. actions/quickstart, the router of that tool (the catalog and the queen's pick are the equivalent)
2. actions/publish, as a hosted page with a private address and versions (the nearest is files on a repository branch, then the existing gated repository steps)
3. actions/open
4. actions/pin-unpin
5. actions/upload-assets
6. actions/artifact-comments
7. capabilities/assets
8. capabilities/artifact, also called self
9. capabilities/comments
10. capabilities/room
11. capabilities/user
12. capabilities/dictation (no speech-recognition code)
13. capabilities/tts (no speech code)

Built on the six steps above, in some honest form, are the rest of the intents, types, reads and the connected-tool capability. Not yet built: a diagram step, a list step, a remove preset and the database presets.

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
