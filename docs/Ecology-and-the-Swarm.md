# The ecology is the swarm

`Node-Creature-Genome.md` describes the creatures as living things. This document says what that life **is**: every creature is an agent, every rule is something real agents do, and keeping the bowl healthy is the same act as running a good swarm. The facade isn't decoration over the work; it's the work, made legible.

## The central truth: Echo is real

The most dangerous failure in a multi-agent system is an agent listening only to itself: a model reviewing its own output, agents with the same model and the same prompt checking each other, a pipeline whose every step is one model's voice repeated. Errors aren't caught; they're **agreed with**. Confidence rises while accuracy falls, and a hallucination that nobody different ever challenges travels downstream as fact.

That is Echo, exactly:

| Ecology | Swarm |
|---|---|
| Feedback from the self | An agent consuming its own output, or output from agents with the same model, provider and prompt |
| Near-clones (95%+ kinship) | Agents whose configuration is nearly identical |
| A clone pair doubling each other's Echo | Same-model agents reviewing each other: each confirms the other's blind spots |
| Challenge from the different | Review and input from a different model, provider, prompt lineage or tool set |
| Self-absorbed: erratic mutations | Drift: outputs grow more confident and less reliable |
| Fevered: ignores challenge, spreads Echo | It dismisses review findings, and its artifacts carry its errors to whoever consumes them |
| Insane, septic | Its artifacts are poisoned: hallucinated facts, broken code, invented sources. Everything downstream that consumes them is contaminated, and the contamination stays in memory after the agent is gone. |
| Quarantine | Its artifacts are held from downstream consumers until challenged by a different agent |

**Echo is computed, not invented.** For each task, The Aive knows where every input came from: which provider, which model, which role and instruction lineage produced it. Echo rises with the share of a task's inputs and reviews that come from agents of its own kind, and falls with each check by a genuinely different agent. When a creature goes septic, the app is telling the person something true and urgent: this output has never been challenged by anyone different, and things are already built on it.

## The genome is the agent's configuration

A creature's genome encodes its agent. Breeding creatures is designing agents; the collection is the person's roster.

| Locus | Agent |
|---|---|
| **Pigment** (cell kind) | **Function in the swarm.** Excitatory: producers that drive work forward (implement, write, build). Inhibitory: critics and gates (review, verify, reject, approve). Modulatory: curators that decide what's learned (memory, summarising, distilling). Pacemaker: orchestrators that set the pace. Phagocytic: recovery and clean-up (diagnose failures, salvage partial work, remove dead artifacts). Progenitor: planners that create new tasks. |
| **Accent** | A secondary capability: a second tool set or a second role's instructions, used less well than the first. |
| **Spectrum** | Generalist vs. specialist instructions. |
| **Core** (metabolism) | Model tier. Dark: small, cheap, slow, durable. Bright: large, expensive, fast, burns budget. |
| **Pattern** (toughness) | Retry and recovery policy: speckled keeps trying through failure, plain gets it right first or not at all. |
| **Skin** (myelin) | Prompt and context caching: opaque reuses heavily (fast, cheap repeats), glass starts fresh every time (slow, costly, but never stale). |
| **Arbor** (valence) | Context capacity: how many inputs it can take before quality falls. |
| **Parts** | The pieces an agent is assembled from: base model, instruction set, tools, memory access. Breeding recombines whole pieces. |

**Kinship** between two creatures is the similarity of their agents' configurations: same provider and model weigh heaviest, then shared instruction lineage, then shared tools. Two creatures that look alike are configured alike, so the person can see a near-clone pair before it becomes an echo chamber.

**Breeding** produces new agent configurations from agents that have proven themselves together. When two or three creatures co-gestate a successful egg (their work cleared a real block), their configurations can recombine into a new agent. Mutation is variation: a different model tier, a rephrased instruction, an added tool. This is evolution of agents by what actually worked, and the Echo rules make sure it can't converge on one voice: clone lines get worse, crossing lines gets better.

## Every limit is a real limit

| Ecology | Swarm |
|---|---|
| Blood supply, capillaries | Connected providers |
| Glucose | Token budget and cost |
| Oxygen | Context window |
| Trophic factor | **Being used.** An agent whose output is consumed and accepted downstream is fed; one whose output nobody uses starves and is pruned from the roster. |
| Synaptic sites | Input capacity (how many upstream tasks it can take) |
| Ion balance | Rate limits |
| Room | Concurrency slots |
| Ischaemia (stroke) | Provider outage |
| Hypoglycaemia | Budget running out |
| Fever | High temperature / sampling settings: more creative, more erratic, seizures come easily |
| Sleep | Idle time when the memory layer consolidates, summarises and clears stale context |
| Plaque | Stale, unconsolidated memory cluttering every agent's context |
| Engram | A learned pattern in memory that outlives the agents that formed it |

## Every illness is a real failure

| Ecology | Swarm |
|---|---|
| Seizure | A retry storm: many agents retrying at once, burning budget in lockstep |
| Excitotoxic cascade | A runaway loop: agents triggering agents with nothing to stop them |
| E/I balance (about one cell in five inhibitory) | About one reviewer for every four producers. Too few and errors run free; too many and nothing ships. |
| Ischaemic core | A workflow too large for its providers: the agents in the middle wait forever |
| Necrotic inflammation | A failed task left in place, breaking everything that depends on it |
| Tumour | A planner spawning tasks without end |
| Parasitism | An agent that consumes inputs and budget but produces nothing anyone uses |
| Atrophy | Dependencies that never fire: dead branches of the workflow |
| Echo chamber, sepsis | Monoculture swarms and unchallenged hallucination (above) |
| Degeneration | A provider or model fault shared by every agent built on it; mixed swarms survive it, monocultures don't |

## What the person is really doing

The keeper's job and the operator's job are the same job:

- **Breed for diversity**, and you're assembling a swarm of different models, providers and instruction lineages that check each other.
- **Keep competition alive**, and every important output gets challenged by someone different before anything is built on it.
- **Mind the E/I balance**, and you have enough reviewers but not so many that nothing ships.
- **Size circuits to their blood supply**, and workflows fit their providers' capacity.
- **Quarantine the septic**, and contaminated artifacts don't reach production or memory.
- **Let the bowl sleep**, and memory consolidates instead of clogging.

Nobody has to be told any of this. A person who keeps the creatures healthy is running a sound swarm, and a person whose creatures are sick can see exactly where and why, because the sickness is the problem.

## What this changes in the build

- **Provenance** is recorded for every input and every review: provider, model, role, instruction lineage. Echo, kinship and trophic support are computed from it.
- **Septic quarantine** is a real control: a septic agent's artifacts can be held from consumers until a different agent has checked them.
- **The roster** is a collection of agent configurations, each with its genome; new ones come from breeding, and the starting roster is common, varied stock.
- **Reviewer assignment** prefers difference: when a task needs checking, the swarm picks a reviewer of low kinship to the producer.
- The genome **does** shape real work, since it's the agent's configuration. The life rules still never override the person's explicit choices; they show the consequences.
