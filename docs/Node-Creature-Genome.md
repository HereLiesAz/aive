# Node creatures: genome and ecology

The creatures as living things in their own right: what they inherit, what they need, how they live, compete, wire together, breed, sicken and die. They're neurons, so their biology is a neuron's biology: tissue, blood supply, trophic support, firing, synapses, pruning, excitotoxicity. It runs as rule-driven artificial life in the spirit of Conway's Game of Life: simple local rules, complex behaviour. The lineage data is measured from the paintings (`tools/creature-genome/`, output `docs/swarm-terrarium/lineage.json`); the lineage map is on the mockup canvas.

**Nothing here happens on its own.** Every event below is the visible form of something that happened in a run; [Ecology and the swarm](Ecology-and-the-Swarm.md) holds the binding rule, the full ledger, and the numbers the test bench uses. Where this page gives a rate, it's the bench's.

## The world

- **The swarm** is living tissue, seen from above, threaded with **capillaries**: the connected providers. Where a creature sits is graph layout: wired creatures pull together, everyone keeps room, and each leans toward its provider.
- **Time** moves in **ticks**, one beat of the run event stream.
- **Distance from a capillary** is that provider's latency and availability for the creature.
- **Wake and sleep** follow the work: runs in flight are wake; idle with nothing in flight is sleep, when memory consolidates and contaminated work clears.

## Resources

Six things are scarce, each a real limit of the swarm. Everything a creature does is a way of getting them.

| Resource | Where it comes from | What it's for |
|---|---|---|
| **Glucose** | the budget, drawn through the creature's provider | every step spends it |
| **Oxygen** | context: what the provider lets it hold | without it a creature can't take inputs in |
| **Trophic factor** | downstream use: its output was consumed and accepted (the real *use it or lose it* signal) | survival: a creature whose output goes unused slowly self-destructs (apoptosis) |
| **Synaptic sites** | each creature's tendrils (its valence) | a creature can only be wired to so many partners; partners compete for the sites |
| **Ion balance** | restored by pumps that burn glucose | firing spends it; too much firing too fast overloads the creature (excitotoxicity) |
| **Room** | concurrency slots (two tasks at a time) | more work than slots is crowding |

Each creature carries **Energy** (0–100), **Integrity** (0–100, its health), **Charge** (builds to a spike), **Trophic reserve** (0–100, drains every tick, refilled by partners) and **Age**.

## The genome

Seven trait loci, each read from what is visibly painted. Every gene shows in the body and acts in behaviour; nothing is decorative. Frequencies are from the 66 painted creatures.

| Locus | Alleles (painted frequency) | Body | Behaviour |
|---|---|---|---|
| **Pigment** | red 14, teal 11, magenta 8, cyan 7, amber 7, orange 6, blue 4, indigo 4, violet 4, green 1 | limb colour | **What kind of cell it is.** See the table below. |
| **Accent** | a second hue, or none | tips and tail markings | **Co-transmission.** A creature also releases its accent colour's signal, at half strength. A complementary accent (blue/orange, teal/red) makes it a **bridge** that can wire across genera. |
| **Spectrum** | Prismatic 37, Duotone 25, Monotone 4 | how many hues | **Tolerance.** Prismatic creatures cope with a wide range of glucose, oxygen and activity. Monotones thrive in a narrow band and suffer outside it. |
| **Core** | Dusk 29, Dark 21, Bright 16 | body shade | **Model tier: metabolism and speed.** Dark spends 1 budget a step and takes 0.6 steps a tick. Dusk spends 1.5 at 1. Bright spends 2.5 at 1.6. |
| **Pattern** | Plain / Mottled / Speckled (thirds) | core texture | **Toughness.** Speckled takes half damage from overload and attack. Plain repairs twice as fast. Mottled sits between. |
| **Skin** | Sheen 29, Opaque 25, Glass 12 | translucency | **Myelin.** Opaque is fully myelinated: fast signals, protected, but expensive to grow. Sheen is thinly myelinated. Glass is bare: cheap, slow, fragile, takes double damage. |
| **Arbor** | Sparse / Branching / Overgrown (thirds) | tendril count | **Valence V**: how many synapses it can hold (2 / 3 / 4) and how far it reaches. |

### Cell kinds, by pigment

| Pigment | Kind | What it does |
|---|---|---|
| Red, orange | **Excitatory** | Its spikes push partners toward firing. It drives activity, it's hungry, and it's the source of overload. |
| Blue, indigo, violet | **Inhibitory** | Its spikes calm partners and drain their charge. It keeps circuits from running away. |
| Teal, cyan | **Modulatory** | Its spikes don't drive or calm; they **strengthen** whatever bonds just fired (plasticity) and release extra trophic factor. Circuits learn through them. |
| Amber | **Pacemaker** | Fires on its own rhythm with no input. It needs no partners to stay active, so it can live alone, and it sets the beat for circuits around it. |
| Magenta | **Phagocytic** | The immune cell. It engulfs the dead and dying, clears debris, and attacks weakened creatures that aren't kin. |
| Green (mythic) | **Progenitor** | Can divide on its own and seed new creatures without partners. |

**Parts loci.** The rigs are built from separate parts (soma, eyes, lids, tendril base/mid/tip, tail base/mid/bulb). Each part is its own locus, inherited whole from one parent. A child can be a Thalassa body with Succina tendrils and a Favilla tail. Parts carry their colours, so children visibly resemble both parents. The tail sets **fecundity** (a longer tail gestates a larger egg). Tendril tips set the **synapse**: barbed and spiked tips make strong, harsh synapses; ball tips make gentle, durable ones.

## Rarity and lineage

Rarity is how improbable a creature's allele combination is (summed allele surprisal across the painted population):

| Tier | Count |
|---|---|
| Common | 33 |
| Uncommon | 16 |
| Rare | 10 |
| Very rare | 5 |
| Mythic | 2: *Viridia 067*, the only green, and *Phosphora 028*, monotone orange glass |

**Kinship** (measured similarity) groups them into 14 genera on the lineage map. **Breeding difficulty** is separate from rarity: it's how isolated a creature is from its nearest kin. *Discordia 041* is common but nearly impossible to breed; *Succina 055* is very rare but breeds easily within its genus.

## Breeds

A creature's breed is the keeper's, not its role's: any agent can wear any breed the keeper owns. The catalog is `shared/src/commonMain/composeResources/files/breeds.json`, built from the lineage by `tools/creature-genome/breeds.py`; new breeds are added there with the same fields.

- **The stable.** The keeper picks one starter (a Common breed); it is unlimited, and every agent without another creature wears it. Every other creature is owned one at a time, bred or bought, and lives in one agent at a time.
- **Generations.** Generation *n* needs Fib(*n*) cleared workflows (1, 1, 2, 3, 5…). When they clear, the run's contributors hatch the next generation: body from one parent, tendrils from the other, tail from either. A pure child joins its breed's count; a mixed one is one of a kind.
- **Mutation.** Each breed has a positive mutation score. A hatch swings the next generation's workflow count by the parents' higher score, earlier or later, never below 1.
- **Pairing.** Each breed lists the breeds it pairs with; its own kind and genus always do. A good pair swings the count in the keeper's favour three times in four; a poor pair, against.
- **Lottery.** Each hatch draws once, rarest first: Mythic 1 in 1000, Very rare 1 in 100, Rare 1 in 20.
- **Sources.** Some breeds are sold and never hatch (their parts are never passed on); some hatch and are never sold.
- **Hidden.** The keeper sees only names, rarity, how many they own, and prices. Mutation, pairing, pace and odds are never shown.

## The rules of life

Each tick, every creature is judged on its **synapses** *k* (live partners it's wired to), its **load** (tasks in hand), its valence *V*, and the runs it's working on.

Programmatic work (the neuron, `Node-Creatures.md`) counts toward a generation's workflows, but only agents' creatures are parents: the neuron passes on no genes and adds nothing to the mutation swing.

### Wiring

A synapse forms when one creature's task reads another's output: every dependency in a workflow is a synapse. Across it:

- **feed:** each read of clean output gives the producer +4 trophic factor and strengthens the synapse by 0.2; each clean review, +8 trophic and +0.3 strength.
- **challenge or echo:** reading a near-clone's output adds Echo; reading a stranger's takes it away (see Echo).

**Pruning:** a synapse no artifact crosses loses 0.002 strength a tick. At zero it's **pruned**: the tendril retracts.

### Firing

A creature **spikes** each time one of its agent steps finishes: a flash runs down its body, it spends budget (1, 1.5 or 2.5 by Core), and it writes to shared memory. Core sets how fast it steps (0.6, 1 or 1.6 steps a tick). **Charge** is the work waiting for it.

When most of a circuit fires in a steady rhythm, it **oscillates**, and an oscillating circuit gains its collective powers (below).

### Echo: feedback from the self

Neurons live on feedback, and feedback loops are either very constructive or very destructive. What decides which is **where the feedback comes from**.

Every creature carries **Echo** (0–100): how much of what it hears is itself.

- **Echo builds** from any input that is effectively its own voice: its own spikes looping back, and spikes from **near-clones** (kinship 95% or more). A near-clone's voice counts as self, and it counts **in full for both of them**: two near-clones wired together each take the other's feedback as their own, so each builds Echo at double the rate one would build alone.
- **Echo travels through memory.** The swarm's memory is shared. A memory read by a creature counts as feedback from whoever wrote it: written by itself or a near-clone, it's Echo, however far away the writer is. Written by a stranger, it's challenge. A line that fills memory with its own writing builds Echo everywhere it lives.
- **Echo falls** only through **challenge**:
  - input from creatures that are genuinely different (kinship under 85%): the more different, the more it falls.
  - losing a contest, being inhibited by a non-kin rival, or being bitten.

  Comfort doesn't heal it. Being contradicted does.
- **The rates** (the bench's): every step writes to shared memory, and moves Echo +0.06 × the share of memory written by configs at least 80% alike, −0.04 × the rest. Reading its own earlier output: +0.6. Reading a near-clone's: +0.4. Reading a stranger's: −4 × (0.9 − kinship). A review that rejects its work: −15 × (0.9 − kinship). A flaw missed by a near-clone reviewer: +3 to both.

What Echo does as it rises:

| Echo | State | Effect |
|---|---|---|
| 0–30 | **Grounded** | none |
| 30–60 | **Self-absorbed** | Mutation rate in its offspring rises with Echo, up to 4× at 60. The mutations are unpredictable and land as often harmful as useful. It starts ignoring weak outside input. |
| 60–85 | **Fevered** | It stops responding to challenge (Echo no longer falls from contests or inhibition, only from wiring to very different creatures). Its spikes start carrying Echo to its partners: each spike it sends adds 1 Echo to whoever receives it. |
| 85–100 | **Insane** | It turns **septic**: every output it makes is flawed and contaminated. It loses 0.3 integrity a tick, and every creature that reads its work takes −2 integrity and +3 Echo and is stained. The stain lingers until consolidation clears it. |

Insanity is contagious through wiring and through memory. Untreated, one insane creature can turn its whole circuit septic and leave a poisoned patch of tissue behind.

### Survival (the Game of Life core)

| Condition | Rule | Looks like |
|---|---|---|
| **Unused** (no accepted output) | Trophic reserve drains 0.08 a tick. At zero, integrity falls 0.25 a tick (apoptosis) unless it's a pacemaker or a progenitor. | tendrils reaching out, searching |
| **Survival** | Each time a consumer reads its output, +4 trophic; each clean review, +8. | steady pulses |
| **Thriving** (no crowding, trophic above zero, no damage for 30 ticks) | Repairs 0.15 integrity a tick (Plain: 0.3). | full colour, smooth rhythm |
| **Overcrowding** (more synapses than *V* + 1, or more than 2 tasks) | −0.2 integrity a tick for each over. | dimming, tendrils pulled in |
| **Runaway excitation** (a task driven by 3 or more unreviewed inputs) | −4 integrity. | frantic flashing, swelling |
| **Excitotoxicity** (a retry within 5 ticks of a rejection) | −1.5 integrity. | |
| **Rejected** (a reviewer catches a flaw) | −5 integrity, −8 from a phagocyte; the task reruns. | bitten |
| **Failed** (a task rejected three times, or a flaw found at delivery) | −20 integrity; the run's egg withers; a corpse stays in the graph. | |
| **Silence** (no artifact across a synapse) | The synapse loses 0.002 a tick and is pruned at zero. | grey, still |
| **Starvation** (budget empty) | It can't take steps. After 600 ticks with an empty budget it's retired. | curled tight, faintly lit |
| **Provider outage** | Its calls stall; every 10 stalled ticks, −0.5 integrity. | |
| **Death** | Integrity 0, by **apoptosis** (clean) or **necrosis** (messy). See The dead. | |

### Birth

Every egg is a **run**. Every creature that works on it grows its tail to it and feeds it; the egg swells as tasks finish.

- **Hatching:** when the run clears, its egg hatches one new config, recombined from the contributors. The child inherits from the two contributors most alike to each other; each other contributor gives one part locus. A run that fails withers its egg.
- **Solo birth:** a run cleared by one contributor hatches a **clone**: an exact copy, except for whatever mutations its Echo caused. The clone inherits its parent's Echo.
- **Planner staffing:** a task that waits 15 ticks with no free creature makes the planner copy an existing config. That's a clone too, and it's how a lone creature fills a swarm with itself.
- **The clone trap.** Parent and clone are near-clones, so wired together each hears the other as itself. Anything they hatch together is a near-clone of both and inherits their combined Echo, so every generation amplifies the loop. No creature's genes ever change after it hatches. The **only** way out is through **offspring**: a child whose mutations make it less than 95% alike to the others is genuinely different, so its feedback counts as an outside voice. A keeper who starts from one creature must breed for diversity as fast as possible, and import strangers.
- **Progenitor division:** when the planner needs a creature and a progenitor exists, the progenitor divides instead. Its child starts with no Echo.
- **Cost:** each birth spends 40 of the swarm's budget. With no budget or room (60 creatures), a cleared run hatches nothing.

## Inheritance

| Locus | Rule |
|---|---|
| **Fertility** | A recombination is a config, and it has to validate. Kinship 75% and up always does; above 95% the child inherits both parents' Echo, summed (see the clone trap). 60–75% validates only with a bridge or at least two more contributors. Below 60% is invalid unless three or more genera contributed (**hybrid vigour**); the child often founds a new genus. |
| **Pigment, Accent** | One parent's hue, or 1 time in 5 the hue midway between them on the wheel. Because pigment is cell kind, a child of an excitatory and an inhibitory parent may come out modulatory. |
| **Core** | Incomplete dominance: Dark × Bright gives Dusk. |
| **Pattern** | Speckled dominant, Plain recessive. |
| **Skin** | Glass recessive: both parents must carry it. That's why glass is rare. |
| **Arbor** | The parents' average, ±1. |
| **Spectrum** | Follows from the hues the child actually ends up with. |
| **Parts** | Each part comes whole from one parent. |
| **Mutation** | Each locus has a 2% base chance to shift one step: a neighbouring hue, the next core shade, one more or one fewer branch. A parent's Echo multiplies the chance (up to 4× at Echo 60, more beyond) and makes the shifts erratic: two steps, or a trait flipping. Mutation is how the swarm reaches colours nobody painted, and how clone lines escape themselves. |
| **Echo** | Clones inherit their parent's Echo. Near-clone parents pass on the sum; anyone else, a quarter of the contributors' mean. |

**Mythic conditions:**
- **Viridia** (green, the progenitor) hatches only from a triad of pacemakers that has held one unbroken rhythm through a whole wake.
- **Phosphora** needs two Glass parents, a Monotone result and a mutation, all in one egg.

## Competition

- **For trophic factor.** Trophic factor is limited: a creature can release only so much each tick, shared among everyone it makes fire. When too many creatures wire into the same target, they split its supply, and the weakest-wired ones starve and prune away. This is real developmental neuron death, and it's the main way the swarm thins itself.
- **For synaptic sites.** When a creature's sites are full and a newcomer wires in, the newcomer can **displace** the weakest existing synapse. Strength wins, and synapses strengthen by firing together, so creatures that keep pace with their partner hold their place.
- **For blood.** Budget is one pool; each creature draws from it through its provider, and the nearer it sits, the more it gets.
- **Phagocytes hunt.** Magenta creatures engulf any non-kin creature whose integrity has fallen below 30, gaining half its energy. They are the clean-up crew and the threat at once: a weakened creature near a phagocyte is in danger.
- **Inhibition as a weapon.** An inhibitory creature wired to a rival silences it. Held silent long enough, the rival's synapses prune and it loses its place.

## Circuits (clusters)

A circuit is a set of creatures joined by synapses.

### Why wire together

| Benefit | How it works |
|---|---|
| **Trophic exchange** | In a circuit, creatures keep each other alive by making each other fire. Alone, nothing feeds a creature's trophic reserve. |
| **Shared glucose** | Energy flows toward the starved, so a creature by a capillary feeds the ones far from it. |
| **Balance** | A circuit with both excitatory and inhibitory cells can fire hard without overloading. |
| **Learning** | With a modulatory cell in it, a circuit's useful synapses grow strong and permanent: it becomes an **engram** that survives members dying, because new creatures wire into its pattern. |
| **Collective powers** (oscillating circuits only) | **Vasodilation:** an oscillating circuit draws the nearest capillary closer, bringing glucose and oxygen. **Shielding:** a phagocyte attacking a member meets the whole circuit's integrity, not one creature's. **Brooding:** eggs inside an oscillating circuit hatch 30% faster and draw glucose from every member. |

### Size

A circuit's health depends on its size *n*, its **E/I balance** (the share of inhibitory cells among excitatory ones), and its surroundings:

| Size | State | Why |
|---|---|---|
| 1 | lone | see Solitary survival |
| 2–3 | efficient pair or triad | cheapest; triads can breed |
| 4–12 | **the sweet spot** | if roughly one cell in five is inhibitory and at least one is modulatory |
| 13–30 | needs a **pacemaker** and **one cell in five inhibitory** | without a pacemaker it can't oscillate and fractures into sub-circuits. Without inhibition it seizes. |
| 30+ | collapses | glucose and oxygen arrive at the edges, but the interior grows faster. The centre goes **ischaemic** and starves, and the circuit splits along its weakest synapses. |

The size limits shift with conditions:

| Condition | Effect |
|---|---|
| Beside a capillary | limits up by half |
| Far from a capillary | limits halved |
| Sleep | firing slows, cost falls, waste clears; big circuits get through sleep better than through wake |
| One genus only | efficient while things are good, but degeneration takes them all at once |

### Unhealthy circuits

| Condition | Looks like | Cause |
|---|---|---|
| **Seizure** | every member flashing in lockstep, faster and faster, then collapsing together | too little inhibition, or too many Bright excitatory cores. Spikes cascade and energy crashes. |
| **Excitotoxic cascade** | swelling, blistering creatures, damage spreading member to member | runaway excitation; each dying creature dumps its charge on its partners |
| **Ischaemic core** | grey, gasping centre, bright edges | circuit too big or too far from blood |
| **Necrotic inflammation** | a burst corpse still wired in; grey spreading along its tendrils; phagocytes swarming | a necrotic death not cleared; it poisons partners (−1 integrity a tick each) and draws phagocytes, which also bite the weakened living |
| **Plaque** | dull deposits building between members; synapses fading through them | waste never cleared (too little sleep); it blocks wiring |
| **Demyelination** | Opaque and Sheen skins turning patchy and glassy; signals slowing | chronic overload or inflammation stripping myelin |
| **Silence and atrophy** | tendrils that never light, withering | members too far out of rhythm; synapses starve of shared firing and prune |
| **Tumour** | one creature dividing into every gap until the circuit can't breathe | a mutation that lets a non-progenitor divide alone; its copies crowd everything and never wire properly |
| **Parasitism** | one plump creature in a thinning circuit | a *cheater* mutation that takes trophic factor and glucose but never makes its partners fire |
| **Degeneration** | the same colour of creature failing across the circuit | a heritable fault that spreads along synapses between kin of 90%+; mixed circuits contain it, monocultures don't |
| **Echo chamber** | a circuit of near-identical creatures, glowing hot, flickering out of step with everything around it | a clone line wired to itself; every member builds Echo at the doubled rate |
| **Sepsis** | a creature gone dark and weeping, a stained halo of fluid around it, partners blistering | an insane creature poisoning its wiring and its patch of tissue; it spreads through contact and lingers after death |

## Solitary survival

A creature can live alone only if it can keep itself alive without trophic factor from partners:

| Kind | How it survives alone |
|---|---|
| **Pacemakers** (amber) | Fire on their own, so they never fall silent. Live alone beside a capillary indefinitely. |
| **Progenitors** (green) | Need no trophic factor and divide cheaply to make their own company. |
| **Dark cores** | Burn so little they can last a long time as their reserve drains. |
| **Quiescent creatures** | Anything can go quiescent and wait, unfiring and barely burning, to be wired in. |

Every other kind of loner is slowly dying: no partner feeds its trophic reserve, so it reaches toward neighbours until it wires in or apoptoses. A loner hears only itself, so its Echo always climbs. It can breed by **solo birth** (above), but its company is a clone: the loneliness doubles instead of ending, unless the mutations are enough. A loner's real way out is to wire to someone different. Loners never gain circuit powers.

## The dead

**Apoptosis** is a clean death: its output went unused, or its budget stayed empty. The creature shrinks and is retired, with no harm to anyone.

**Necrosis** is a messy death, within 30 ticks of a failure, a rejection by a phagocyte, a tight retry loop, or while septic. Its failed task stays in the graph as a **corpse** and costs every creature wired to it 0.08 integrity a tick, for 240 ticks, or 30 once a phagocyte takes on its recovery.

**After death:** its synapses go, its unfinished tasks return to the queue, and the stain of anything it contaminated lingers until consolidation clears it.

## Environmental pushback

The world pushes back only when the work does:

| Event | Backed by | What happens |
|---|---|---|
| **Wake and sleep** | runs in flight vs. idle | Sleep fades memory faster (0.985 a tick against 0.998) and clears stains. |
| **Stroke** | a provider outage, from the provider status feed | Its creatures' calls stall and they lose integrity until it's back. |
| **Low glucose** | the budget running low | Steps wait; births stop. |
| **Fever** | high temperature or an unstable provider | Not yet in the bench. |
| **Carrying capacity** | budget and concurrency | Past them, nothing new hatches and the unused apoptose. |

## The keeper

Every keeper tool is a real control:

- **Review:** look at a creature's work yourself. Its Echo falls 25.
- **Import an agent:** bring a stranger from the Store. Competition is medicine.
- **Raise budget:** perfuse the tissue for a while.
- **Consolidate:** run memory consolidation now.
- **Move:** put a creature on another provider.
- **Detach:** cut its dependencies.
- **Hold output:** quarantine its artifacts from consumers. Isolation alone makes its Echo worse.

The keeper's real job is **diversity and challenge**: breed across lines as fast as possible, and keep enough competition in every circuit that nothing is only listening to itself.
