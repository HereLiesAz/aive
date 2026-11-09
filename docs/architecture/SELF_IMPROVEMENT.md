# Self-improvement, and the Hall Monitor

The swarm improves itself through one loop, and the loop is part of what it improves. This note says what the loop is, how the Hall Monitor is used in it, and the rules written into the fabric so that improving the loop itself is **possible, expected, and never blockable by the loop**.

## 1. The Hall Monitor today, and what was missing

The Hall Monitor (`HallMonitorRole`) is a model-backed role with no authority. It writes a `HallMonitorReport`: findings with evidence, counter-evidence, falsification criteria, at least two remedies, a no-change option, and test designs for the orchestration and memory layers. The Antagonist reviews it cold. A passing review can open a global, resumable pause (`HallMonitorGovernanceService`), with an Orchestrator gate and a human gate, and a solution can be tried in an isolated counterfactual run (`HallMonitorSolutionTrialService`). All of that is built and tested.

None of it ran. Nothing started a Hall Monitor review, nothing gave it data (it saw only its dependency artifacts), no built-in hive used it, and the Inbox has no buttons for its review gates. This note fixes the first three. The pause and its gates stay the keeper's emergency tool for a run that is going wrong now (see §7).

## 2. The loop

`suite-self-improvement` ("Self-improvement cycle") is an ordinary hive in the coding suite. It **only reads and reports**: no approval step, no repository operation, no code-changing step. So it can run on a schedule (weekly, off until the keeper turns it on) and can never wait on a person or block another run. Its steps:

| Step | Role | What it does |
|---|---|---|
| `observe` | Hall Monitor | Reads the run's evidence (§3) and writes the `HallMonitorReport`. |
| `challenge-report` | Antagonist | Reviews the report cold; unsupported claims are struck. |
| `examine-loop` | Architect | Examines **the loop itself** (its steps, its evidence, the Hall Monitor's instructions), independent of the report, so the Hall Monitor never grades its own overlay. |
| `challenge-loop` | Adversarial Reviewer | Attacks that examination, above all for any change that would make the loop harder to change. |
| `propose` | Product Manager | Turns what survived into proposals (§4). Must include at least one proposal about the loop, or `META-NO-CHANGE` with its evidence. |
| `refine` | Adversarial Reviewer | Last attack on the proposals; only survivors are listed. |

The evidence is put in the run's objective at start (`SwarmEvidence`, deterministic, computed from the run history on the device; the objective of such a run is marked external content, and the earlier proposals it quotes are model text, each quoted as a one-line `⟦agent-written: …⟧` fence the loop reads as data): per role, workflow and provider, the runs, steps, completions, failures, escalations, retries, tokens, cost, and the change since the last window, plus what was adopted since the last cycle and what it did to the numbers, plus a section **"The loop, as it is now"**: the loop's own steps and the Hall Monitor's instructions, so the loop always sees itself.

## 3. One ledger for every mechanism that proposes

Three things notice what could be better, and they share one ledger and one screen (`ImprovementLedger`, Keeper's room › Improvement). Each keeps making its own proposals; each is mirrored in (`ProposalMirrors`), labelled with its `ImprovementSource`, and decided in the same place:

| Source | Notices | Proposes | Adoption |
|---|---|---|---|
| **The loop** | A model reading the swarm's evidence, and the loop itself | Any kind (role instruction, hive step, schedule, evidence, loop definition) | Role instruction and hive-step objective applied by the app; other kinds noted |
| **Run history** | Deterministic heuristics: the same failure twice, a fix after failures, the keeper's repeated corrections | Lines added to a role's instructions (`RoleInstructionProposalLedger`) | The mechanism's own `approve`, which refuses a role that changed since the draft |
| **Habits** | An operation done often enough to be a habit | A step for a hive (`HabitInvocation`) | You choose the hive; it is added behind its own approval step |

A decision made on a creature's Mind tab is followed here, and one made here is made there, so the two never disagree. The loop **reads the other two as evidence**: the evidence lists, per mechanism, what is waiting, adopted, dismissed and reverted, and what you dismissed recently, so it does not propose again what you already turned down. Adopted run-history proposals get the same before-and-after fail rate as the loop's own. The substrate (evidence, ledger, mirrors, applier) is code and deliberately thin; every judgment is data the loop may propose to change.

## 3a. Proposals, and who decides

The last step's text holds proposal lines (`IMPROVEMENT | kind | target | summary | new text | expected effect, measurable | how to undo`). The app parses them into a ledger (`ImprovementLedger`), nothing else. **A proposal changes nothing.** It reaches a role, a hive or a schedule only when the keeper approves that exact change in **Keeper's room › Improvement** (invariants 4 and 8 hold: no agent, no scheduled run and no remote interface adopts a proposal). Agent text is untrusted (invariant 3 and the role-instruction rule): it is bounded, quoted, shown with its evidence, and applied as text only.

Adoption records the text before and after. **Revert** restores it. Every improvement is reversible, which is what makes allowing all of them safe.

## 4. The amendment clause

The loop's own definition, its evidence, its role instructions and its schedule are improvable through exactly the same path as everything else. The fabric keeps that true in six ways, each with a test (`SelfImprovementClauseTest`):

1. **Self-inclusion.** Every cycle examines the loop (`examine-loop`) and its evidence includes the loop as it is. `LoopIntegrity.violations` fails a loop hive that lacks the examination, the challenge of it, or the `META` requirement in `propose`.
2. **Obligation, not just permission.** A cycle must end with at least one proposal about the loop, or `META-NO-CHANGE` with evidence. A `META-NO-CHANGE` is a finding the next cycle must re-examine. `LoopObligation` says when the loop has not examined itself for too long and the Improvement screen says so.
3. **No lock exists.** There is no "protected", "frozen" or "core" flag on a hive, role or schedule, and none may be added for the loop. The loop's hive is an authored copy, never a package or built-in (`installCodingSuite` saves it as authored; the test checks it is editable and deletable-by-keeper like any hive).
4. **Same path, same standard.** A proposal about the loop is judged exactly like any other: the ledger and the applier take no account of the target (`ImprovementApplier` has no branch for it; the test applies a proposal against each of the loop's own parts). The loop's own steps can challenge a proposal about the loop but cannot veto it; only the keeper decides.
5. **Never blocked by the loop.** The loop has no approval step and no gate, so it cannot hold itself or anything else up; a failed cycle is only a failed cycle.
6. **Reversible.** Adoption stores before and after; Revert restores the before.

What the clause does not do: it does not force the keeper to adopt anything, and it does not stop a keeper deleting or rewriting the loop. It stops the **system** from exempting itself, locking itself, or quietly dropping itself from review.

## 5. Why it should be hard to improve on

- It is skeptical by construction: the Hall Monitor's evidence contract (counter-evidence, falsification criteria, a no-change option) and two adversarial passes, one aimed at the loop.
- It is measured: each proposal states how its effect will be measured, and the next cycle's evidence reports the before and after numbers of what was adopted.
- It is small: six steps, read-only, one ledger, one screen.
- Where it can still be improved is the point: the measures, the evidence, the steps and the instructions are all text the loop may propose to change.

## 6. Open decisions (wrong if…)

- **Evidence is counts, not quality.** It sees failure, retry, escalation, cost and token numbers, not whether the work was good. Wrong if cheap, failing-free work is bad work; the Antagonist and the keeper judge that, and a quality measure is a proposal the loop should make.
- **The weekly window.** Seven days of runs on one device may be too few to see a trend. Wrong on a quiet device.
- **Applying is limited.** The app applies role-instruction and hive-step-objective changes (and a schedule's cadence is changed by hand). Other kinds are recorded as noted, for the keeper to do.
- **Role changes are global.** A role is one catalog shared by every hive (`PROCEDURAL_MEMORY.md` §10): an adopted instruction change applies everywhere.
- **A model can still write a bad proposal that sounds good.** The gates are two attacks and the keeper. Wrong if the keeper approves without reading; the card shows the exact text and the undo.

## 7. Using the Hall Monitor beyond the loop

- **Watching (the loop).** The Hall Monitor is the loop's observer, weekly and read-only.
- **Stopping a run that is going wrong now.** The existing review pause (`HallMonitorGovernanceService`): the Hall Monitor and Antagonist report, the run pauses, an Orchestrator and the keeper decide, and `HallMonitorSolutionTrialService` tries a remedy in an isolated run. It remains built, tested and not wired to a button; wiring it is the next piece (Open decision: wrong if a run needs stopping and no button exists; Pause and Stop are the keeper's tools until then).
- **Improving the Hall Monitor.** Its own instructions are a role like any other: the role-instruction proposals of its own failures reach its Mind tab, and the loop may propose changes to them (it is in the loop's own evidence).
