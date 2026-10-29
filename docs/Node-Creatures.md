# What a node creature is

A node creature is the visible body of one **task**, performed by an **agent** in a **role**. It looks like a neuron because a workflow is a nervous system: units that each integrate what they receive, decide, fire, and pass something on. Behaviour comes from both sides: what the agent is actually doing (the domain) and what a neuron would do in that situation (the anatomy). The anatomy is the grammar, the agent is the meaning. When the two disagree, the agent wins and the anatomy finds another way to say it.

Everything below is grounded in the domain model (`TaskDefinition`, `TaskRun`, `TaskRunStatus`, `RoleDefinition`, `TaskExecutor`, `ArtifactRef`) so each behaviour reports something real. No motion is decoration (Conveyance §9): if a creature moves, the movement says something about its agent.

## Anatomy is function

| Neuron | Creature | Agent / workflow |
|---|---|---|
| **Soma** (cell body) | the glossy round body | the agent: its role, its model, its working context |
| **Nucleus** | the dark core seen through the soma | the role's instructions (temperament); a forked role shows a second nucleus forming |
| **Dendrites** | branching tendrils, bead tips | inputs: one branch per dependency, plus branches for repository, files and memory it reads. A bead lights when that input has arrived |
| **Dendritic integration / threshold** | the body brightening as beads light | readiness: the task fires only when every required input has arrived and its condition holds (`dependsOn`, `TaskCondition`) |
| **Axon** | the tail | the output path. It grows while the agent produces; its length is how much work is in flight |
| **Axon terminal / bouton** | the tail bulb | the output being built. When a block forms, the bulb is the **egg** |
| **Synapse** | a tether between one creature's bulb and another's dendrite | a dependency. Excitatory synapse = runs on success; inhibitory = runs on failure (`TaskCondition`) |
| **Neurotransmitter / vesicle** | a pearl travelling down a tether | an artifact handed downstream (`ArtifactRef`) |
| **Action potential** | a pulse running soma → tail tip | one agent step: a model turn, a tool call, a commit |
| **Firing rate** | pulse frequency | how actively the agent is working right now |
| **Refractory period** | a dim pause after a pulse | rate limits, provider back-off, retry delay |
| **Myelin** | a sheen along a well-used tail | prompt reuse/cache (`PromptReusePolicy`): signals travel faster |
| **Synaptic plasticity** | tethers thicken with use across runs | memory: paths the swarm has used successfully before are stronger |
| **Metabolism** | body fullness, glow | budget: tokens and cost spent; a starving creature thins |
| **Reflex arc** | a creature with no nucleus, a simple tendril loop | deterministic executors (`Script`, `TestRunner`, `Deployment`, `RepositoryOperation`, `GitHubAction`): no agent mind, just stimulus to response |
| **Sensory organ** | a tendril reaching out of the water | `ExternalService` visits and the user's own input (`HumanApproval`) |
| **Glia (microglia)** | the adversarial roles | immune cells: they inspect, attack and prune damaged work. That's why adversaries go after other creatures and their eggs |
| **Pacemaker** | the Orchestrator | sets the rhythm; its pulse is the run's heartbeat |

## Life cycle: why each state looks the way it does

Each `TaskRunStatus` is a physiological state, so a person can read the run without labels and the labels only confirm (Conveyance: corpses must be distinguishable from the living).

| Status | What the agent is doing | What the creature does, and why |
|---|---|---|
| `Created` | nothing yet; unborn | Not yet hatched: it sits inside its parent's egg. You can see it curled there. |
| `Blocked` | waiting on an input or a resolvable condition | Dendrites reach toward the missing input; that branch's bead stays dark. Reaching **is** the escort to the gate: the dark branch points at what it's waiting for. |
| `Ready` | all inputs in, threshold reached | Every bead lit, body brightening: about to fire. |
| `Planning` | drafting its approach | Slow, deep pulses in the soma with no output yet; the nucleus works. |
| `AwaitingApproval` | plan done, needs a person | It swims to the glass and taps (the inbox). The plan is held in its bulb, visible. |
| `Running` | working | Pulses run down the tail; the tail grows; the bulb swells. |
| `Verifying` | checking its own output | Pulses run backwards, bulb to soma. Adversaries arrive. |
| `Retrying` | trying again after a failure | Refractory dim, then a fresh spike; a scar shows the earlier attempt (`attempt`). |
| `Escalated` | stuck; needs a decision | Calls out: it taps the glass harder and its dendrites reach toward the Recovery Engineer if one exists. |
| `Completed` | done | Releases its pearl down the tethers, detaches its tail from any egg, and settles, calm and dimmer. The work goes on downstream. |
| `Failed` | ended badly | Sags, dims and trembles; its pearl never forms. Clearly a corpse, never mistaken for waiting. The tide can still bring it back. |
| `Cancelled` | stopped on purpose | Curls up and sinks to the sediment, intact: stopped, not broken. |

## Programmatic work: the neuron

Work done programmatically rather than by an agent wears one body: **the neuron**, a faceted soma, dendrites and a myelinated axon (`files/rigs/neuron.rig.json`, sliced from `docs/swarm-terrarium/characters/000_neuron/neuron.png` by `tools/puppet-rig/slice/rig_painting.py`). That covers:

- the deterministic executors: Python, JavaScript, Test Runner, Deployment, Repository Operation, Nested Workflow;
- roles that run a script or an action instead of an agent (`RoleExecutionSource` other than `Agent`);
- memory stages running without a model (the memory swarm).

The neuron only connects. It takes its place in the swarm, wired to what feeds it and what it feeds, so the swarm doubles as a standing flowchart of the workflow, every step visible. Its work counts toward a run like any other, but it has no genes: it is never a parent of a hatch, never sways a generation's mutation, and is never in the stable or the Store. A programmatic role has no creature to choose.

## Birth, breeding, mutation, death

- **Birth.** A phase is born from the egg the previous phase gestated. When the Orchestrator plans new tasks during a run, they hatch from its bulb, which is why the Orchestrator's tail is the longest.
- **Breeding.** Combining two workflows mates two bowls: their creatures meet at shared synapses. That's the Combine action, made physical.
- **Budding.** A duplicate creature of the same role buds off the parent: the same species, its own task, running in parallel.
- **Mutation.** Forking a role is a mutation: the nucleus divides and the creature's colour shifts slightly, so it's visibly kin but its own line.
- **Death.** Release (removal) is death with residue: the body dissolves into the water, and the tide can reverse it. Run failure is a different kind of death: the corpse stays in place, so you can see where it fell.
- **Generations.** Each finished run settles as sediment. Creatures in later runs are descendants: plasticity carries forward what earlier runs learned.

## What every creature offers (its Acts)

The creature is the Offer for everything about its task (Conveyance §4: one element carries the action through its life). No separate buttons float elsewhere.

| Act | Consequence | Where it lives on the body |
|---|---|---|
| Open / care | Enter its care panel | tap the soma |
| Message the agent | Send words to it (exists today: `onMessageAgent`) | press the soma; your words travel down a dendrite |
| Approve / send back | Alter: open or shut its valve | its bulb, at the glass |
| Retry | Create a new attempt | pull a fallen creature back up |
| Cancel | Destroy, reversible by the tide | pinch the tail shut |
| Connect / disconnect | Alter a synapse | draw from the bulb; slice the tether |
| Bud, fork, release | Create / Alter / Destroy | hold the body |

Every Act also exists in the care panel and the semantics tree, so nothing depends on gesture alone.

## Consequence for the mockups

The creatures are the existing paintings and rigs; nothing here redesigns them. What changes is what their parts do: dendrite beads light per input, tails grow with output, bulbs become eggs, pulses show work, and the status table above sets their posture. The interface around them stays as full as it needs to be: each creature is labelled with its task and state, the bowl carries its run status, and the app's navigation stays. The anatomy explains the behaviour; it doesn't replace the information.
