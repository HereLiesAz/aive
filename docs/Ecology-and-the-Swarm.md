# The ecology is the swarm

`Node-Creature-Genome.md` describes the creatures as living things. This document says what that life **is**: every creature is an agent, every rule is something real agents do, and keeping the bowl healthy is the same act as running a good swarm. The facade isn't decoration over the work; it's the work, made legible.

> **Direction update:** [Collective cognition](architecture/COLLECTIVE_COGNITION.md) extends the binding rule to actual specialist communication, attention, recruitment and deliberation events independent of runs. Until those event sources exist, do not depict them as runtime truth. Cognitive links are not workflow dependencies; preserve the original ledger for execution telemetry.

## The binding rule

**Nothing happens in the creatures' world unless something happened in the swarm, and nothing happens in the swarm without the creatures showing it.** The life layer is not a simulation running beside the work; it's the work's telemetry given a body. No dice: no random strokes, no random hatch rolls, no drift for its own sake. Ambient animation (breathing, idle sway) is allowed only because it changes nothing and means nothing; every change of state, position, health, wiring, birth or death has a recorded cause in a run. (An egg's starting tint is the one apparent exception: it is set by a hash of its own task, so it looks varied and is the same every time.) A shared creature's walk between its tasks is the same kind of thing as an egg's growth: an animation between two recorded states, played only when the run records the move, never a wander of its own.

The full ledger:

| Life event | Backed by | Source |
|---|---|---|
| **Tick** | A run event: any task changing state, any agent step finishing | the run's event stream |
| **Spike** | One agent step completing (a model turn, a tool call, a commit) | provider responses, tool results |
| **Charge** | Work waiting for the agent: inputs arrived, task ready | task queue |
| **Consultation queued / transport outcome** | Actual peer mailbox enqueue and persisted transport result (not proof of reading) | Managed-session consultation activity in the creature panel and run log; metadata only. A distinct cognitive edge is still planned. |
| **Synapse forms** | A dependency edge between two agents' tasks, or one agent consuming another's artifact | workflow definition, artifact reads |
| **Synapse strengthens** | An artifact passed across it and the consumer succeeded with it | artifact handoff + consumer result |
| **Synapse prunes** | The dependency was removed, or no artifact crossed it for N runs | workflow edits, run history |
| **Kinship** | Similarity of the two agents' configurations: provider, model, instruction lineage, tools | roster |
| **Trophic support** | The agent's output was consumed and accepted downstream | review outcomes, consumer success |
| **Energy (glucose)** | Token budget remaining for this agent; burn is tokens spent | provider usage |
| **Blood, capillaries** | Connected providers; distance to a capillary is that provider's current latency and availability for this agent | provider health |
| **Crowding** | More concurrent tasks than slots, or more inputs than the agent's context window holds | concurrency policy, measured context fill |
| **Excitotoxicity** | Retrying or re-firing too fast: a tight loop | retry timestamps |
| **Runaway excitation** | Many producers triggering the agent with no reviewer gating any of them | graph shape + review assignments |
| **Integrity** | The agent's reliability: recent success, failure and rejection | task outcomes |
| **Echo** | Share of the agent's inputs, reviews and memory reads produced by its own kind | provenance |
| **Septic, poisoned tissue** | Artifacts never challenged by a different agent, and everything that consumed them | provenance graph |
| **Phagocyte bite** | A recovery or clean-up agent rejecting or retiring a failing agent's output | recovery tasks |
| **Apoptosis** | The agent retired for disuse (nothing consumed its output) or for spent budget | roster, usage |
| **Necrosis, corpse** | A task that failed terminally, left in the graph breaking its dependents, until recovered | task status |
| **Egg** | A block of work several agents are completing together (a milestone, a gate, a shared dependency) | workflow structure |
| **Hatching** | The block cleared. The child is a new agent configuration recombined from the contributors, added to the roster | block result |
| **Failed egg** | The block failed, or the recombined configuration is invalid (incompatible model and tools) | block result, config validation |
| **Solo clone** | An agent's configuration duplicated: by the person, or a planner spawning same-config agents | roster, planner |
| **Mutation** | The variation actually written into the child's configuration (model tier, instruction wording, tool set) | the child config itself |
| **Wake, sleep** | Runs active vs. idle; sleep is when memory consolidates | run status, memory layer |
| **Stroke** | A provider outage | provider health |
| **Low glucose** | Budget running out across the swarm | usage vs. budget |
| **Fever** | Higher sampling temperature, or an unstable provider | run settings, error rates |
| **Position, movement** | Graph layout: creatures move only when their wiring, cluster or provider changes | layout of the real graph |
| **Keeper: Talk** | The person messaging, approving, rejecting or editing an agent | user actions |
| **Keeper: Perfuse** | Raising budget | settings |
| **Keeper: Introduce** | Connecting a provider or model, or importing an agent | settings, Store |
| **Keeper: Quarantine** | Holding an agent's artifacts from consumers | real control |
| **Keeper: Separate** | Detaching an agent from its workflow edges | workflow edit |
| **Keeper: Sleep now** | Running memory consolidation | memory layer |

The test bench obeys the same rule: it doesn't roll dice for life events; it replays a stream of **synthetic workflow events** (steps, handoffs, reviews, failures, outages), and the creatures react to that stream exactly as they would to a real one.

### The bench

The bench runs every workflow offered in the Azphalt Store (64 at the time of writing, 427 tasks), launched in turn, with their real tasks, roles and dependencies. A seeded generator stands in for the providers. It makes only three kinds of draw, and each is tied to a run event: whether a finished step's output carries a flaw, whether a reviewer catches it, and which variation a new config gets. Outages come from a provider status feed fixed when the session starts.

| Run event | What the creature shows |
|---|---|
| A step finishes | Spike; budget spent (1, 1.5 or 2.5 tokens by Core); one write to shared memory |
| A step's memory write | Echo +0.06 × share of memory written by configs ≥ 80% alike, −0.04 × the rest |
| A task reads its own earlier output | Echo +0.6 |
| A task reads another agent's output | Synapse formed or strengthened; producer's trophic +4; echo +0.4 from a near-clone, −4 × (0.9 − kinship) from a stranger |
| A task reads contaminated output | Echo +3, integrity −2, stain |
| Three or more unreviewed inputs drive one task | Runaway excitation: integrity −4 |
| A retry starts within 5 ticks of a rejection | Excitotoxicity: integrity −1.5 |
| A review passes clean work | Producer's trophic +8, echo −5 × (0.9 − kinship) |
| A review rejects a flaw | Producer's integrity −5 (−8 from a phagocyte), echo −15 × (0.9 − kinship); the task reruns |
| A review misses a flaw | The output is contaminated; both sides' echo +3 when they're ≥ 95% alike |
| A task is rejected three times, or delivery finds a flaw nobody caught | Run fails: the egg withers; the worker loses 20 integrity; a corpse stays in the graph until a phagocyte recovers it |
| A run clears | Its egg hatches a recombined config from every contributor, or a clone when there was only one |
| A task waits 15 ticks with no free agent | The planner copies an existing config, or a progenitor divides |
| The output goes unused | Trophic decays 0.08 per tick; at zero, apoptosis |
| The agent's budget stays empty for 600 ticks | Retired |
| Provider outage | Calls stall; every 10 stalled ticks costs 0.5 integrity |
| Echo reaches 85 | Septic: every output is flawed and contaminated; integrity −0.3 per tick |
| No runs in flight outside working hours | Sleep: memory and stains fade faster |

Measured over 5,000 ticks from one agent:

| | Left alone | With a keeper |
|---|---|---|
| Mean echo | ~30, with septic agents recurring | ~5 |
| Runs failed | ~28% | ~12% |
| Diversity | 10–20% | 35–50% |

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

## Starting from one creature

A new keeper begins with a single creature: one provider, one model, one agent. It can only clone itself, and clone lines go septic. Diversity has to come from **outside the line**, and every outside source is a real one. In the order a new keeper meets them:

1. **The keeper is the first stranger.** The person is the most different voice the bowl will ever have. Every message, approval, rejection and edit counts as challenge from far outside the creature's kin, so a creature the keeper actually talks to stays grounded. In the first days, the person is the second creature.
2. **The world talks back.** Reflex creatures (tests, scripts, builds, linters, deployments: deterministic executors with no model at all) aren't kin to anything. Wiring one in front of an agent gives it challenge it can't argue with: the test passed or it didn't. This also changes how a creature mutates: under real challenge, mutation is **adaptive** (the shifts that survived the check persist); without it, mutation is erratic.
3. **Separate the clones, within the bowl.** A clone wired to its twin doubles both their Echo, so keep them apart: move them to opposite reaches of the same bowl, near different capillaries, among different neighbours, each wired into a different circuit with different work and different reflex creatures checking it. Separation changes no genes: a creature's genome is fixed at hatching, and twins stay twins for life. What separation does is keep their Echo low, since neither hears the other and each is challenged by different neighbours, so the mutations their offspring carry are adaptive rather than erratic. Each twin then founds its own line in its own reach of the bowl, and the lines accumulate different mutations generation by generation. It's their **descendants**, several generations on, who are genetically different enough to bring together and breed something new. Distance helps less than it looks, because of the **water**: memory is shared across the whole bowl. What one twin learns and writes to memory, the other reads, wherever it is, and a memory written by your own line is your own voice coming back. So Echo leaks between twins through memory no matter how far apart they swim. Separation only works when the memories each twin draws on are written mostly by **strangers**: a twin living among different creatures reads their memories, not its sibling's. A bowl whose memory is dominated by one line is an echo chamber at every distance.
4. **Open a second vein.** Connecting another provider or model is the fastest, truest diversity: new blood carries **wild** creatures native to it. They drift in along the new capillary, and they're already strangers to everything in the bowl. Each provider has its own native strains, so the setup step of connecting providers is also how a keeper stocks the bowl.
5. **Directed mutation.** When the keeper rewrites a creature's instructions or swaps its tools (forking its role), that change comes from outside the line, so it counts as genuine variation, not Echo. The keeper can breed by hand.
6. **Trade.** The trading post (the Azphalt Store) brings in other keepers' lineages as packages, and a setup file carries a keeper's whole roster to someone else's bowl. Immigrants are the rarest stock and the widest crosses.

The lesson the bowl teaches without a word: the cheapest diversity is to listen to the person, the next cheapest is to let the world check the work, and the strongest is a second model.

## What this changes in the build

- **Provenance** is recorded for every input and every review: provider, model, role, instruction lineage. Echo, kinship and trophic support are computed from it.
- **Septic quarantine** is a real control: a septic agent's artifacts can be held from consumers until a different agent has checked them.
- **The roster** is a collection of agent configurations, each with its genome; new ones come from breeding, and the starting roster is common, varied stock.
- **Reviewer assignment** prefers difference: when a task needs checking, the swarm picks a reviewer of low kinship to the producer.
- The genome **does** shape real work, since it's the agent's configuration. The life rules still never override the person's explicit choices; they show the consequences.

### Current Needs inspector boundary

The inspector now requires recorded delivery of an exact producer artifact to a current consumer session. It compares reported model, provider and declared configuration using run role snapshots where present. Unknown inputs are excluded, and an empty comparable set is not reported. Delivery is not proof of semantic reliance, memory reads, review verdicts or contamination. Explicit review outcomes are projected separately. The complete provenance-based Echo ledger remains the target; the recorded delivery component now accumulates across retained run histories.

### Reported-model kinship evidence

The existing usage journal now carries a nullable batch-consistency flag. A new batch is consistent only when all its provider usage reports name the same nonblank model; the last model and request fingerprint remain available for existing diagnostics. Legacy batches have unknown consistency. The Needs comparison requires consistent reports for both tasks, matching the exact workflow, task, assigned provider and provider session. Mixed models, replayed habits, missing/legacy reports and stale attempts are not comparable. This is a provider-reported identity, not independent attestation or a claim that every inference was observed. The provisional weighting splits the previous provider half into provider 20 and model 30, leaving instructions 30 and declared contract 20; model changes can now break a near-clone match even on the same provider. The live and historical UI read the existing shown-run journal with a bounded refresh cadence and share that evidence between inspector and tissue projection. Echo uses delivery evidence as described below; semantic consumption, memory-read provenance and additional mutation loci remain outstanding. No changes to execution authority, provider selection, spend accounting or approval.

### Artifact delivery evidence

Successful hosted text generations with usage reports and on-device text generations record the IDs of nonblank upstream artifact text rendered into their request. The existing usage journal retains the union of positively reported IDs per batch, without content, and the inspector and tissue match the exact workflow, task, provider and current session. A cancelled or failed hosted generation does not report delivery; older reports, unsupported providers, replayed work and uninstrumented reads remain unknown. URI-only and blank artifacts are not credited. Output delivered is the percentage of reporting downstream sessions with at least one matching current producer artifact, not an acceptance verdict or a fraction of all planned consumers. Connections use the same evidence. Outgoing delivery establishes exposure to a reviewer, not feedback returned to the producer. No inferred consumption, automatic pruning or fertility is implemented here.

### Attributable review outcomes

The Needs inspector now reads explicit approve, revise and reject verdicts from current consumer artifacts using the existing engine verdict parser. A review is attributed only when the reviewing agent has one declared upstream producer, current-session delivery identifies that producer's artifacts without additional unidentified inputs, and the reviewer completed or was failed specifically by a blocking verdict. Conflicting or missing verdicts, stale sessions and multi-source reviews remain unknown. Hall Monitor verdicts also require an exact delivered report identifier. Counts describe stated review outcomes, not independent proof of quality, approval authority, or feedback received by the producer. Echo exposure now uses incoming artifact delivery only; outgoing verdicts are shown separately until a subsequent input actually carries that feedback. No routing, approval, spend or execution decisions read these projections.

### Light in the spatial hive

`SpatialSignals.kt`. The hive's light says what happened and is read from the run's own records; presentation time only shapes how a real event looks, it never makes one.

| Light | Read from | Rule |
|---|---|---|
| An activated memory spore's glow flips from its resting **warm** to **blue** (any kind: tag, keyword, key phrase, summary, ...) | `deliveredMemoryAddresses` (memory-read provenance), `memory-node:<id>` is the web's `node:<id>` | The mycelium is a warm-glowing data constellation at rest. A quick flip at the moment of use, a 1 s hold, then an ease back to warm; 2.5 s in all. The renderer mixes warm and blue by `blueGlow` |
| **Pulses** travel the connective tissue (or legs) from sender to receiver | A recorded artifact delivery, producer to consumer (the evidence Echo and trophic support read) | Leave on the sender's beats, at its rate, for 4 s after the delivery; 1.2 s to travel |
| The creature **pulses** at the same rate | Its own task's reported progress | 0.5 Hz rising to 3 Hz with progress; a task with no progress reports beats at 0.5, never faster; none when not working |
| The light **bursts** and its spores are released | The journal's `TaskCompleted` | Fast rise, 1.8 s fall; the spores are the web nodes whose sources include the task's own episodes (and those episodes' nodes), linked by the episode's task run, never by time or proximity |

One beat integrator per creature (`advanceBeat`) drives both its own light and the pulses it sends, so they share rate and phase while the rate climbs. The cap is 3 Hz, the most a flashing light should do; reduced motion keeps the resting state (no beat). A finish with no memory episode still bursts, with no spores. The tardigrades' collection (a clerk travelling to released spores and carrying them to the network at the pole) is choreography over these released spores and is not built. Not wired yet: the GPU (a time-varying emission and a pulse overlay in the three backends) and hardware checks. Only the specific datum that was used flips (`blueSpores`): not its neighbours, its hyphae or the rest of the network, and a used memory the web does not hold draws nothing, since the constellation is the actual memory data. The Needs memory count and the blue flip stay "not reported" until a provider opts in to `rendersPromptContextWhole`.

### Memory-read provenance

`MemoryProvenance.kt`. The memory layer's recall now says which stored nodes each prompt block actually carries (`MemoryPromptRecall.blockAddresses`, after each block's own whole-unit trimming); the gateway keeps only the nodes in blocks the context packer selected (`AgentTaskRequest.recalledMemoryAddresses`); and when the provider reports a successful generation's deliveries (the same signal as artifact delivery), the session's usage carries them (`ManagedSessionUsage.deliveredMemoryAddresses`) into the journal (`ProviderUsageRecorded.deliveredMemoryAddresses`, addresses only, never content). `reportedMemoryReads` matches the exact workflow, task, provider and session, excludes replayed work, and treats a missing report as unknown. The Needs tab shows the count of distinct nodes carried.

This is carriage in a successful request, not proof the model used the memory. Not counted: the cue cloud, habits and recalls an agent asked for mid-session (their text names no node), partner hits shown inside a unit, and resumed sessions (unknown). The assumption that a provider reporting artifact deliveries also rendered the whole prompt context is the one to check first. Not built: spores, hyphae and tardigrade clerks, which would read this evidence; Echo's memory exposure; contamination.

### Trophic support: delivery, consumption, acceptance

`Trophic.kt`. Delivery (an artifact reached a consumer's request) is what Echo counts; it is not what feeds. One producer-to-consumer path in a settled run is judged by what the consumer did with it:

| Outcome | Evidence | Effect on the producer's role |
|---|---|---|
| Unknown | No delivery report for the consumer's session, or the consumer has not finished | none |
| Ignored | Consumer finished, its session reported its inputs, the artifact was not among them | starvation evidence |
| Delivered | Reached the consumer, which then failed | none |
| Consumed | A reviewer completed with it but stated no attributable verdict | none (acceptance unproven) |
| Accepted | A non-reviewer completed with it (+4), or a reviewer stated Approve (+8, replaces the +4) | fed |
| Rejected | A reviewer stated Revise or Reject | starvation evidence |

A task with no planned dependents is exempt (its output goes to the keeper). A role is **barren** in a run only when every one of its downstream paths is known and none was accepted; any unknown path blocks starvation. The reserve (0 to 100, in hundredths) starts at 50 when a role is first observed, gains the rates above, and loses 10 per barren run (provisional: the documented 0.08 a tick has no tick to run on in a settled run). Fed from 50, hungry from 20, starving above 0, starved at 0. Replaying a run changes nothing; a role with no downstream evidence is unreported. The Needs tab shows the reserve and its state.

**Pruning** retires a role by disabling it (never deleting; lineage stays). Only a hatched, enabled agent role whose reserve is 0 is eligible: founders keep the starting roster's coverage, and a role a saved hive still names, or any role while a run is live, is skipped. It runs after a run settles, only when the durable switch `trophic.prune` is on; it is off by default and has no control yet. What would show the rates wrong: a role that starves while its output is plainly used downstream. Not done: a reviewer's verdict on a consumer's work does not reach back to the consumer's own producers, and the integrity and echo effects of rejection are separate.

### Cumulative delivery-based Echo

Needs and tissue now share a deterministic per-agent balance reconstructed from the existing persisted run snapshots and usage journals, rather than the percentage of current near-clone paths. For each comparable delivered producer/current consumer session path, count once, using the documented bench rates: own output +0.6, a near-clone +0.4, stranger below 85% kinship −4 × (0.9 − kinship), with kinship normalized to 0–1. Intermediate similarities are neutral. Integer hundredths preserve fractional changes and clamp after each path to 0–100. Within a run, order by first matching report timestamp and task IDs; across runs fold by settlement timestamp and run ID. The shown run starts from terminal runs settled by its creation, excluding overlapping runs that settled later until a subsequent run. Reopening or refreshing a run recomputes rather than increments. Missing snapshots or model evidence remain unknown; a recorded challenge can establish a known zero. Current-attempt evidence only; batch delivery does not prove semantic reliance. A solo hatch inherits its parent's recorded balance at that run's settlement when its exact lineage is subsequently observed. Actual memory reads, per-call counts and returned review challenge effects remain outstanding; subsequent sections describe hybrid inheritance and mutation. No additional persisted store, authority or execution changes.

The first functional mutation locus now alters actual offspring role instructions at birth; see [Functional instruction-locus mutation](Node-Creature-Genome.md#functional-instruction-locus-mutation). The outcome is an unadopted configuration, not a demonstrated improvement or grant of authority.

Tool-ceiling and model-source variation also operate on the persisted disabled child; see [Tool and model-source mutation](Node-Creature-Genome.md#tool-and-model-source-mutation). There is no per-role same-provider model selector, and no inferred model tier.

Functional fertility and hybrid Echo inheritance now use current-session parent kinship, actual catalog genus evidence at birth, and the recorded child lineage; see [Kinship-based functional fertility and Echo inheritance](Node-Creature-Genome.md#kinship-based-functional-fertility-and-echo-inheritance). Unknown evidence fails closed for fertility or leaves inheritance unreported. The legacy visual stable pace remains separate.

Birth now also preserves the parent's actual budget limit and validates the child's provider/contract compatibility before saving; see [Birth-time functional compatibility and budget inheritance](Node-Creature-Genome.md#birth-time-functional-compatibility-and-budget-inheritance). No new policy engine or authority is introduced.

Energy capacity is now an inherited effective token allowance: a solo parent's cap or the median of the selected hybrid parents' caps. A keeper override always wins; the existing spend engine consumes the effective cap.
