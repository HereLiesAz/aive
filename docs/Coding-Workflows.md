# The coding suite

Eighteen hives for working on code, shipped as plain definitions (`CodingWorkflowSuite`, `workflow/CodingWorkflowSuite.kt`). **Keeper's room › Schedules › Add the coding suite** puts them on the device as hives you authored and can edit; a hive already there is left alone, so your edits survive. They use only the built-in roles, agent steps and a person's approval: no provider, no repository operation, no executor that needs a desktop. They run on whatever you connected.

Two rules shape all of them:

1. **A hive that changes code is approved twice.** The step that changes code is approved before it runs (`ApprovalPolicy.HumanApproval`), and the hive ends at a release approval, a separate step only a person can complete. Nothing here releases by itself.
2. **A hive meant to run by itself only reads and reports.** No step in it changes code or waits on a person, so an unattended run never stalls at a gate. A fix that follows from a report is a different hive that a person starts.

`CodingWorkflowSuiteTest` holds every hive to this: each is a valid graph and expands for a run; each agent step is given to a role that holds the authority the step exercises; each hive that changes code approves its implementation step and ends at a person's approval; each scheduled hive has no approval, no implementation and no test-authoring step.

## On demand: change code, behind approvals

| Hive | What it does |
|---|---|
| Feature delivery | Requirements, design, a challenge to the plan, implementation (approved), independent verification, review, release approval. Pre- and post-code test design are on. |
| Bug fix | Reproduce, find the root cause, fix (approved), add a regression test, verify, review, approval. The objective is the bug report. |
| Safe refactor | Pin down current behaviour with tests, plan small steps, refactor (approved), prove nothing changed, review, approval. |
| Raise test coverage | Find the gaps that matter, write tests (approved), check they fail when the behaviour breaks, review, approval. |
| Update dependencies | Audit what is outdated or vulnerable, update (approved), verify build and tests, review, approval. |
| Sync the docs with the code | Find where docs and code disagree, correct the docs (approved), review, approval. |
| Prepare a release | Draft the changelog, check readiness, review the diff since the last release, approval. |
| Incident hotfix | Diagnose, apply the narrowest fix (approved), verify. If verification fails, **Plan a rollback** runs instead (a conditional step, `TaskCondition.OnFailure`). Approval follows verification. |

## On demand: read and report

| Hive | What it does |
|---|---|
| Review a change | Three independent reviews (correctness, an adversarial one, the tests) in parallel, then one summary. Changes nothing. |
| Security audit | Threat model, a hunt for weaknesses with file and line, triage, a report by severity. |
| Map a codebase | Survey, architecture, a starting guide for a new contributor. |

## On a schedule: read, report, change nothing

Each comes with a suggested schedule, off until you turn it on (see [Scheduled workflows](Scheduled-Workflows.md)). All times are UTC.

| Hive | Suggested |
|---|---|
| Nightly dependency audit | Daily, 07:00 |
| Daily CI triage | Daily, 13:00 |
| Weekly test health | Monday, 08:00 |
| Weekly security scan | Tuesday, 08:00 |
| Weekly docs drift | Wednesday, 08:00 |
| Weekly tech-debt report | Friday, 08:00 |
| Self-improvement cycle | Sunday, 10:00; its objective is followed by the swarm's evidence |

The self-improvement cycle is described in [Self-improvement](architecture/SELF_IMPROVEMENT.md): it examines the swarm from its run history, examines itself, and ends in proposals only you can adopt.

## Open decisions

- **No test or lint runner step.** The desktop `TestRunner` executor needs a local repository and an approval ahead of it, and runs only on desktop; a hive using it would not run on a phone. Verification here is an agent that runs and reports. It is wrong if you want a command's exit code to decide: add a `TestRunner` step to your copy.
- **No repository operations.** The hives do not branch, commit or open pull requests themselves; the implementing agent's provider does. Remote and local operation strings need branch names a template cannot know. It is wrong if you want a pull request opened by the hive: add an approved `RepositoryOperation` step.
- **The report goes where the hive's artifacts go.** A scheduled hive's report is its final step's text, in the run's "What it made". Sending it elsewhere (a file, an issue, a message) is not built.
- **The wording is a first draft.** Each step's objective is a few sentences. Which ones help, and which need your project's words, only use will show; edit your copy.
