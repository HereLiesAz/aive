# Node creatures: genome and ecology

The creatures as living things in their own right: what they inherit, what they need, how they live, compete, wire together, breed, sicken and die. They're neurons, so their biology is a neuron's biology: tissue, blood supply, trophic support, firing, synapses, pruning, excitotoxicity. It runs as rule-driven artificial life in the spirit of Conway's Game of Life: simple local rules, complex behaviour. The lineage data is measured from the paintings (`tools/creature-genome/`, output `docs/swarm-terrarium/lineage.json`); the lineage map is on the mockup canvas.

**Status: what the app runs and what is still the design target.**

| Built (`Breeds.kt`, `BreedParents.kt`) | Design target, not built |
|---|---|
| Breeds, the stable, the starter, rarity, store breeds | Resources, Energy, Integrity, Charge, trophic reserve, Age |
| Generations at Fib(*n*) cleared runs, swung by mutation | Ticks, wake and sleep, the capillaries |
| Up to three parents, ranked by fit and by how new the pairing is | The seven-locus genome (pigment, accent, spectrum, core, pattern, skin, arbor) and cell kinds |
| Body, tendrils and tail each taken whole from one parent | Kinship, Echo, septic creatures, the clone trap |
| Pairing by breed, genus and `pairsWith`; the lottery for Rare, Very rare and Mythic | Fertility rules, mutation per locus, the Mythic conditions |
| Programmatic work counts but never parents | Survival, competition, circuits, death by apoptosis |

Everything below the table is the target unless it is in the left column or explicitly marked implemented.

### Procedural spatial expression (implemented foundation)

The 3D renderer reads the actual role configuration and the existing stable's inherited body, tendril and tail keys. The seven morphology families are anatomical starting archetypes, not agent species. Identical declared configurations and inherited part keys reproduce the same procedural anatomy independent of task identity. Existing role hatches still originate from completed runs, record their actual parents and remain disabled until adopted. A purchased or assigned breed cannot change the role's authority or permissions.

Functional pigment is derived from declared authorities and required capabilities: implementation/planning/test authoring express excitatory warmth, review/verification express inhibitory blue, research/messaging express modulatory teal. A secondary actual function becomes an accent. These are visible configuration categories, not claims of successful execution or physiological effects. Unknown functions are neutral. Existing painted breeds retain their cosmetic ancestry, but painted hue alone is not evidence of agent function. The procedural mapping does not assign pacemaker, phagocytic or progenitor functions without authoritative runtime bindings.

The remaining original loci (operational tolerance, actual model tier, measured resilience, myelin/signal efficiency and enforced synaptic capacity), additional agent-config mutation loci, full model/provenance-based kinship/Echo and comprehensive runtime compatibility validation remain design targets; implemented fertility is described below. No such values are synthesized from a random seed, artwork, role label or capability count. The current config fingerprint is for deterministic visual expression, not a similarity score or a security identifier.

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
- **Generations.** Generation *n* needs Fib(*n*) cleared workflows (1, 1, 2, 3, 5…). When they clear, the run's contributors hatch the next generation: body from one parent, tendrils from another, tail from a third (or, with two parents, from either). A pure child joins its breed's count; a mixed one is one of a kind.
- **Parents.** Up to three, one for each part, chosen from the six creatures that did the most of the run's work (`BreedParents.kt`). Every set of parents on offer is ranked by how well its breeds fit (the same breed 1.0, the same genus 0.8, listed in each other's pairing 0.6, otherwise 0.1) and by how seldom this stable has hatched that pair before. Both are z-scored across the sets and weighted 0.3 to 0.7, the ranking of arXiv:2603.01092 ("The Alien Space of Science") applied to breeds: a combination nobody here has made wins, but never an unfitting one (a set whose mean fit is under 0.3 is chosen only if no other set is left). Nothing in it is random and none of it is shown. The pace swings in the keeper's favour when every pair in the set is a good pair (0.6 or more). Echo, kinship and the fertility rules below are the design this builds toward; the app runs the rules in this section.
- **Mutation.** Each breed has a positive mutation score. A hatch swings the next generation's workflow count by the parents' higher score, earlier or later, never below 1.
- **Pairing.** Each breed lists the breeds it pairs with; its own kind and genus always do. A good pair swings the count in the keeper's favour three times in four; a poor pair, against.
- **Lottery.** Each hatch draws once, rarest first: Mythic 1 in 1000, Very rare 1 in 100, Rare 1 in 20.
- **Sources.** Some breeds are sold and never hatch (their parts are never passed on); some hatch and are never sold.
- **Hidden.** The keeper sees only names, rarity, how many they own, and prices. Mutation, pairing, pace and odds are never shown.
- **On screen** (`StableScreens.kt`, mockups B1-B4). *Your first creature*: a hint card, the starters in a three-column grid (creature, name, rarity), the picked one repeated larger, and **Keep** at the bottom right. *Your stable*: a **Generation N** card with "k of n workflows" over n segments, k filled, and a card of rows, one per creature owned (name, rarity or starter, how many, ∞ for the starter). From 820dp wide it is a card grid instead (three columns above 1080dp, two below): each card an art panel in the creature's own hue with **Gen N** on a hatched one, then its rarity, name and count, under a filter row of All, each rarity owned and Hatched here, with how many show. *Hatched* replaces the stable until **Seen**: the parents with × between, the child large, **Generation N · Added to your stable.**, and a stranger with its rarity when the lottery brought one. *Its creature*, in a creature's Mind tab: every creature owned as a row with what is left ("starter · ∞", "2 left"), the current one selected; one whose every copy already lives in another agent is dimmed, says where ("in QA Engineer") and cannot be chosen, with a line saying so.
- **Buying.** The Store's **Breeds** tab lists the breeds for sale, dearest first: each row is the creature, its name, its rarity ("only here" for one that never hatches) and its price on the button, under the note "Each is one creature, added to your stable." Android buys through Google Play (`BreedPurchases`); a platform without a way to pay shows the catalog price on a button that does nothing.

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

- **Hatching:** when the run clears, its egg hatches one new config, recombined from the contributors. The child inherits from the two contributors most alike to each other, unless they are near-clones (see the clone trap). Each other contributor gives one **functional locus**, drawn from the run and the contributor so a retry hatches the same child: instructions, required capabilities, surfaces or preferred source. A locus only adds; none touches authorities or the tool ceiling, an Antagonist never gives one, and a contributor whose completed work needs an authority the child lacks passes. Only a contributor that actually changed the child is recorded as a parent (at most 8). If the child with its extra parents cannot execute its parents' work, the hatch retries as the pair alone, so an extra parent never costs the pair its birth. A run that fails withers its egg.
- **Solo birth:** a run cleared by one contributor hatches a **clone**: an exact copy, except for whatever mutations its Echo caused. The clone inherits its parent's Echo.
- **Planner staffing:** a task that waits 15 ticks with no free creature makes the planner copy an existing config. That's a clone too, and it's how a lone creature fills a swarm with itself.
- **The clone trap.** Parent and clone are near-clones, so wired together each hears the other as itself. Breeding them would only produce a near-clone of both, with their Echo summed, so every generation would amplify the loop. No creature's genes ever change after it hatches. **Inbreeding is avoided by cloning.** A pair at 95% kinship or more is inbred. When the only viable pair is inbred, one parent clones instead of pretending to breed (a viable pair below 95% still breeds, even beside a twin), and the clone inherits that one parent's Echo, not a sum. Its mutation chance is still drawn from the pair's **summed** Echo, because near-clone parents pass on the sum and mutation reads Echo: 2% a locus at the base, up to 4x at Echo 60. Those mutations land as often harmful as useful, and selection (trophic starvation) weeds out the harmful ones. The **only** way out is through **offspring**: a child whose mutations make it less than 95% alike to the others is genuinely different, so its feedback counts as an outside voice. A keeper who starts from one creature must breed for diversity as fast as possible, and import strangers.
- **Progenitor division:** when the planner needs a creature and a progenitor exists, the progenitor divides instead. Its child starts with no Echo.
- **Cost:** each birth spends 40 of the swarm's budget. With no budget or room (60 creatures), a cleared run hatches nothing.

## Inheritance

| Locus | Rule |
|---|---|
| **Fertility** | A recombination is a config, and it has to validate. Kinship 75% and up always does; at 95% and up the pair are near-clones and do not breed: one of them clones (see the clone trap). 60–75% validates only with a bridge or at least two more contributors. Below 60% is invalid unless three or more genera contributed (**hybrid vigour**); the child often founds a new genus. |
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

- **For trophic factor.** Trophic factor is limited: a creature can release only so much each tick, shared among everyone it makes fire. When too many creatures wire into the same target, they split its supply, and the weakest-wired ones starve and prune away. In a settled run this is: a consumer releases one read's worth (+4), or one approval's (+8) if it is a reviewer, however many producers fed it, shared among the producers whose output it accepted, rounded down to a whole hundredth; a sole producer is paid in full. This is real developmental neuron death, and it's the main way the swarm thins itself.
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

### Functional genetics: observed operational traits and synaptic capacity

`OperationalGenome.kt`. The genome's operational loci are read from what each role actually did, never from artwork (the body is the stable's; the role owns the function). Each trait is null without evidence, and the evidence is the retained settled runs and their journals (a live task's transient retry is not counted; replaying a run is a no-op).

| Locus | Trait | Observed from | Bands |
|---|---|---|---|
| Core | Model tier | Cost per token, over the sessions that were fully priced | Dark, Dusk, Bright: thirds of the roster, cheapest first |
| Spectrum | Tolerance | Distinct source and model pairs a task of the role completed on | Narrow (1), Moderate (2), Wide (3 or more) |
| Pattern | Resilience | Share of tasks that needed a retry and still completed | Fragile (under a third), Steady, Tough (two thirds or more) |
| Skin | Signal efficiency | Output tokens per second the source delivered | Slow, Even, Fast: thirds of the roster |
| Arbor | Synaptic capacity | The effective token allowance: unlimited 4; finite allowances in thirds across the roster (2, 3, 4); one finite allowance alone holds 3 | 2, 3, 4 |

Thirds rank against the roster, so no absolute threshold is invented; fewer than two distinct values rank nothing and the trait stays unreported. **Capacity is enforced as a cost, never a refusal** (the "defaults are not rules" lesson): a creature's synapses are its distinct partner tasks, upstream and downstream, and each beyond its capacity costs 2 integrity in the Needs tab. The Needs tab shows the traits and "synapses / capacity". Provisional: the bands, and capacity from the allowance (the keeper's choice over a declared field). Not built: the documented effects of each trait (a model tier's budget per step and speed, toughness against damage, myelin's growth cost and fragility), tolerance as a cost band, and creatures displacing the weakest synapse. The trait words are shown as written, not translated.

### Echo beyond reads: memory, returned criticism, fever, sepsis

`EchoEffects.kt`, steps of `EchoHistory.advance`, in hundredths, each read from the run's own records and failing closed.

- **Memory it wrote itself (+0.6).** A task whose successful requests carried a memory node written by its own role alone (`memoryAuthorsOf`: from the memory layer's episodes, each of which records its role; a node with any role-less source episode, a keeper's own memory say, is unknown and left out) takes the rate of reading its own output. One step per task however many such nodes, so a long memory does not swamp a run (provisional). A node written by another role is not scored: the authors' provider and model are not recorded, so near-clone cannot be told from stranger, and that exposure stays unreported.
- **Criticism that came back.** A review that turned work back (Revise or Reject, stated, attributable) lowers the producer's Echo by 15 x (90 - kinship) hundredths, nothing for a near-clone reviewer, only if the producer's current session actually received the reviewer's artifact. A verdict nobody returned to the producer is not criticism it heard.
- **Fevered creatures ignore it.** At 60 or more a creature no longer responds to returned criticism; only reading very different creatures (kinship under 85) still lowers it. (Contests and inhibition are not modelled yet.)
- **Septic contagion.** A reader of a producer whose role stands at 85 or more takes +3 Echo on top of the read, and 2 integrity in the Needs tab (septic inputs, an unchallenged producer: the proxy the app can show, not a finding that the output is wrong).
- **Not built:** the stain that lingers until consolidation clears it, quarantine, recovery by consolidation, and the memory layer's own writes (+0.06 / -0.04 per step). `memoryAuthorsOf` is not yet fed to the live history: wiring it needs each settled run's memory bank (`webSourceForRun`), so only the pure projection and its tests exist for memory exposure.

### The reviewer pollinates

`ReviewerPollen.kt`. A reviewer (the reviewer kinds except the Antagonist) is a pollinator: it visits the work it reviews and carries the producers' genes. Its nth attributable review in a workflow is born to **the reviewer and every distinct producer it has reviewed through review n**, counted across the workflow's settled runs. So review 1's child has the reviewer and producer 1; review 2's has the reviewer, producers 1 and 2; and so on, up to eight parents (the reviewer and the seven most recently reviewed distinct producers). A producer reviewed again moves to its newest position and is not repeated.

- **A visit is an attributable review**, whatever its verdict: a single-source review with a stated verdict whose session received the producer's artifact (the same evidence as review outcomes). A multi-source or unverdicted review is not a visit; replayed and stale sessions are excluded.
- **Some offspring are reviewers: one out of every five.** In every block of five of a reviewer's children in a workflow (reviews 1 to 5, 6 to 10, ...), exactly one follows the reviewer (name, authorities, keeper instructions), so the reviewer lineage can improve too, and the other four follow one of the producers. A stable hash picks which of the five it is, so the position varies and the share does not. **The Antagonist does not improve genetically:** it neither pollinates, nor is pollen, nor leads a child. The other parents add instructions, capabilities and surfaces; tool ceilings intersect, and a disjoint pair or a surface alias bound two ways means no child. The token allowance is the median of the parents' caps. A child never gains an authority its lead lacks, and is born unadopted (disabled), named "<lead> · pollen n", with ID `pollen-<workflow>-<reviewer>-<n>` (saved once).
- **Provisional:** the block of five (the keeper's figure; the slot within a block is arbitrary), the cap of eight, and one child per review. Not applied: kinship fertility, mutation and the birth-time provider check of ordinary hatches (a pollen child's parents may come from earlier runs); the keeper adopts it from the lineage as before. Echo inheritance for a child with more than two parents is unreported. Wrong if the roster fills with children nobody adopts: the first lever is one child per run instead of per review.
- Not built: the reviewer's body (the tall-legged, translucent creature) and the pollen-carrying animation.

### Configuration kinship and Echo exposure (partial implementation)

The original Needs inspector compared actual assigned providers and declared role configurations, preferring a run's role snapshot to later roster edits. Identity and cosmetic breed do not participate. Its original provisional overlap score weighted provider identity 50, exact role and keeper instructions 30, and the declared capability/authority/tool ceiling/surface contract 20. The documented 95 threshold applies to this proxy. Different role ids do not disguise a clone; unknown assignments do not count as either clones or independent sources. That original meter counted comparable active dependency/reviewer paths, not confirmed reads. Completed programmatic inputs count as distinct sources. It reports missing evidence as not reported, and no longer presents the graph proxy as a diagnosis of sepsis. That initial comparison did not implement actual model identity, per-input and memory-read provenance, review verdicts, cumulative Echo, adaptation or fertility. Later implementation stages follow. The weights are provisional, not measured correlations or calibrated probabilities.

### Reported-model kinship evidence

The existing usage journal now carries a nullable batch-consistency flag. A new batch is consistent only when all its provider usage reports name the same nonblank model; the last model and request fingerprint remain available for existing diagnostics. Legacy batches have unknown consistency. The Needs comparison requires consistent reports for both tasks, matching the exact workflow, task, assigned provider and provider session. Mixed models, replayed habits, missing/legacy reports and stale attempts are not comparable. This is a provider-reported identity, not independent attestation or a claim that every inference was observed. The provisional weighting splits the previous provider half into provider 20 and model 30, leaving instructions 30 and declared contract 20; model changes can now break a near-clone match even on the same provider. The live and historical UI read the existing shown-run journal with a bounded refresh cadence and share that evidence between inspector and tissue projection. Echo now accumulates the recorded artifact-delivery component across retained run histories, using the documented own-output, near-clone and stranger rates; a solo hatch inherits its recorded parent's balance at birth. See [the ecology ledger](Ecology-and-the-Swarm.md#cumulative-delivery-based-echo) for evidence and temporal limits. Semantic consumption, memory-read provenance and returned review challenge effects remain outstanding; later sections describe functional mutation and hybrid inheritance. No changes to execution authority, provider selection, spend accounting or approval.

### Functional instruction-locus mutation

A completed run can hatch a disabled variant whose actual role instructions include one bounded work-method preference. Contributors come from the run snapshot, not later roster edits. The run ID deterministically draws once for the instruction locus: the documented 2% base rises linearly from Echo 30 to 8% at Echo 60, using the highest recorded parent balance at settlement. Higher rates remain capped at 8% until specified; unknown balances use the base rate. A second deterministic draw selects a nonduplicate preference. The persisted instructions reach the actual provider prompt, subject to existing redaction. Mutation adds no authority, capabilities, tools, surfaces, budget or keeper instructions; it introduces no provider/model switch. The child remains disabled until adopted. Existing hatches and parents are not rewritten. Mutation quality is unmeasured; subsequent sections describe the bounded tool and model-source loci and fertility. Actual model tiers are not represented.

### Tool and model-source mutation

Independent draws at the same Echo-adjusted rate can remove one member of a nonempty tool ceiling with at least two distinct members, never the last member (an empty ceiling is unrestricted). A solo offspring can also pin a different provider whose model differs from the parent's preferred or most-used observed source. Both sources must have consistent, successful current-session model evidence from the parent and still expose those configured model IDs. This changes actual provider selection after adoption; it does not invent a model tier or guarantee the provider will never change its configuration. Same-provider model overrides are not supported. Unsupported providers and unknown identities cannot supply a candidate. The existing provider eligibility, redaction and approval boundaries still apply. No additional tool authority is introduced.

### Kinship-based functional fertility and Echo inheritance

A cleared run with multiple actual agent contributors now hatches only a viable pair. Every completed session of each comparable parent must identify the same provider and reported model; unknown or mixed evidence is not fertility. The pair with highest kinship wins, with completed-work rank and role ID breaking ties. A score of at least 75 is viable; 60–74 requires a bridge (at least 75 to each parent) or four comparable contributors; lower scores require three distinct catalog genera among comparable contributors. Genus evidence is the keeper's current assigned catalog breed at hatch, not an inferred functional classification; missing or hybrid breed IDs without a catalog genus cannot supply that exception. The two parents' identical instructions are deduplicated; explicit tool ceilings intersect, and disjoint ceilings cannot produce a child because an empty ceiling would widen authority. The resulting child remains disabled. Runtime provider, contract and approval validation still applies after adoption; this does not certify model/tool compatibility for every future task. The existing visual stable's independent generation counter is unchanged.

Echo replay now records settlement balances and pairwise kinship from the birth run, and applies them only when the actual persisted child lineage appears. A solo child inherits its parent's known balance. Near-clone parents sum their known balances, clamped at 100; other hybrids inherit one quarter of the mean of all completed agent contributors. Missing contributor balances or kinship make inheritance unknown, never zero. Reopening a run cannot reapply inheritance. Historical genera are not reconstructed from later stable assignments. The design's additional donated body loci remain part of the existing visual stable; functional extra-contributor genes are not yet recombined.

### Birth-time functional compatibility and budget inheritance

A solo offspring inherits its parent's effective token cap at birth. A hybrid inherits the median of the two selected parents' effective caps (the midpoint, rounded down to whole tokens). If only one parent has a finite cap, inherit that value; if both are unlimited, inherit no cap. The result is stored separately from the keeper's explicit per-role override, with no budget mutation draw. An explicit override always wins; clearing it restores the inherited default. Existing global class settings and spend stops remain adjustable, and historical inherited values do not change when the class settings change. Conflicting surface aliases prevent a pairing rather than silently selecting a different binding. Before a disabled child is saved, the runtime checks its actual completed parent task contexts, required authority, role capabilities, provider constraints, repository scope and tool-filter requirement through the existing resolved-provider selection path. Auto children must have a compatible connected normal source for each context; explicit pins are checked without substitution. On-device context sizes and habit replay cannot establish general role compatibility. No task is dispatched and no routing quota reserved by this check. Unknown snapshots fail closed. This is a conservative preflight, not proof of future arbitrary task viability; the selected pair may fail rather than falling back to another pair. Provider capabilities and budgets can change later, so ordinary execution validation remains authoritative.

The per-role budget editor preserves the inherited cap, class, lineage and tool ceiling when saving edits, rather than reconstructing a founder from an edited hatch. No new global spend setting or approval bypass is introduced.
