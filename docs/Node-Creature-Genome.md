# Node creature genome and ecology

Companion to `Node-Creatures.md` (what a creature is). This document sets out what a creature **inherits**, how creatures **breed**, and the rules they **live and die** by. The lineage data is measured from the paintings (`tools/creature-genome/`, output `docs/swarm-terrarium/lineage.json`); the lineage map is the "Lineage map" board on the Bowl OS mockup canvas.

## Two layers, one truth

- **The work layer** is what the agents actually do. Tasks, dependencies, approvals and artifacts behave exactly as the workflow says. The game never changes an agent's output, never skips a task, and never kills a real run.
- **The life layer** is the creatures. Their health is computed from how the work is going, so a sick creature is always telling the truth about its task. A badly built workflow (too many inputs, no budget, unanswered gates) makes its creatures suffer, which teaches the person to build better without a single instruction.

Roles are no longer tied to species. **A role is a prop** (baton, wrench, magnifier, X sign, key, shield, clipboard, quill…): any creature can carry any prop and take that job. What a creature is good at comes from its genes, not its prop.

## The genome

Seven loci, each read from what is visibly painted. Allele frequencies are taken from the 66 painted creatures.

| Locus | Alleles (painted frequency) | Looks like | Does |
|---|---|---|---|
| **Pigment** | red 14, teal 11, magenta 8, cyan 7, amber 7, orange 6, blue 4, indigo 4, violet 4, green 1 | limb colour | **Temperament.** Warm (red, orange, magenta) is territorial: wins contested resources more often, picks fights, burns hot. Cool (cyan, blue, indigo, violet) is cooperative: shares, binds more easily. Teal and green are steady. Amber is *autotrophic*: it feeds itself from the sediment (see Solitary survival). |
| **Accent** | a second hue or none | tips, tail markings | **Secondary drive.** A complementary accent (blue/orange, teal/red) makes a creature a **bridge**: it binds across genera without the usual penalty. No accent means a specialist. |
| **Spectrum** | Prismatic 37, Duotone 25, Monotone 4 | how many hues | **Adaptability.** Prismatic creatures wear any prop without stress. Duotones prefer props near their temperament. Monotones excel with one prop family and sicken under the others. |
| **Core** | Dusk 29, Dark 21, Bright 16 | body shade | **Metabolism.** Dark cores are slow and frugal: low energy, long life. Bright cores are fast and hungry: high firing rate, short life. Dusk is balanced. |
| **Pattern** | Plain, Mottled, Speckled (thirds of the population by measured texture) | core texture | **Resilience.** Speckled cores carry scars well: they survive retries and attacks. Plain cores are precise and fragile: excellent first-time work, and they break on repeated failure. |
| **Skin** | Sheen 29, Opaque 25, Glass 12 | translucency | **Legibility against armour.** Glass shows its inner pulses (the person can read it best) but takes the most damage from adversaries. Opaque hides its pulses and shrugs off bites. |
| **Arbor** | Sparse, Branching, Overgrown (thirds by measured branching) | tendril count | **Valence**: how many synapses it can hold before it overloads. Sparse 2, Branching 3, Overgrown 4. This is the core of the life rules below. |

Every gene is expressed twice: in the painting (so the person can *see* it) and in behaviour (so it *matters*). No trait is decorative.

### Parts loci (mix and match)

The rigs are built from separate parts (soma, eyes, lids, tendril base/mid/tip, tail base/mid/bulb). Each part is its own locus, inherited from one parent. That's how new creatures get made from pieces: a Thalassa body with Succina tendrils and a Favilla tail is a valid child. Parts inherit independently of the seven trait loci, but a part brings its colour with it, so recombined creatures look like their parents.

## Rarity, measured

A creature's rarity is how improbable its allele combination is in the painted population (the sum of each allele's surprisal). Tiers: **Common** (bottom half, 33), **Uncommon** (16), **Rare** (10), **Very rare** (5), **Mythic** (2). The two mythics are *Viridia 067* (the only green) and *Phosphora 028* (monotone orange glass).

**Breeding difficulty** is separate from rarity: it's how isolated a creature is from its nearest kin. A common creature can still be hard to breed (*Discordia 041*, teal with a red accent, kin to nothing above 74%), and a very rare one can breed easily inside its family (*Succina 055*).

## Breeding

- **Where.** Creatures breed by co-gestating. When two or more creatures' tails feed the same egg and the block clears successfully, the egg can hatch a new creature into the person's collection, as well as the next phase of the work.
- **Who can breed.** Fertility depends on kinship (measured similarity):
  - 75–95%: fertile.
  - Above 95%: inbred. The child is born weak (low health ceiling), so the most crowded genera (Sanguina, 97–98% pairs) push the person to outcross.
  - 60–75%: the egg usually fails. Bridges (complementary accents) lift this by one band.
  - Below 60%: sterile, except through a rare *hybrid vigour* event (below).
- **Inheritance.**
  - Pigment and Accent blend: the child takes one parent's hue, or occasionally the hue between them on the wheel.
  - Core is incomplete dominance: Dark × Bright gives Dusk.
  - Pattern: Speckled is dominant and Plain recessive.
  - Skin: Glass is recessive, so it needs both parents to carry it, which is why glass is rare.
  - Arbor is additive: the child's valence is the parents' average, ±1.
  - Spectrum follows from the hues the child actually ends up with.
  - Parts: each part comes from one parent, chosen per part.
- **Mutation.** Each locus has a 2% chance to shift one step: a neighbouring hue, the next core shade, one more or one fewer branch. Mutation is how the population drifts toward the colours nobody has painted yet.
- **Mythic conditions.** A mythic can't be bred on demand; it needs conditions. A **Viridia** hatches only from an egg gestated by an all-amber or all-teal cluster that has gone a full run without a single failure. A **Phosphora** needs two Glass parents, a Monotone result and a mutation, all in one egg.
- **Hybrid vigour.** Once per run, an egg from a sterile pairing (below 60%) can hatch anyway if the block it gestated was cleared on the first try with no retries. The child often founds a new genus.

## The life rules (a Game of Life on synapses)

Conway's Life runs on a grid where a cell lives or dies by its count of live neighbours. The bowl runs on the dependency graph instead: a creature's **neighbours are the creatures it shares live synapses with**, and its **valence** (from Arbor) sets where crowding begins. Rules are evaluated at every run event (a task changing state), not on a clock, so the life layer always moves in step with real work.

Let *k* be a creature's live synapses and *V* its valence.

1. **Isolation.** *k = 0* and nothing to do: the creature **encysts** (curls, dims, stops spending). It doesn't die of loneliness unless it has a job and no inputs to do it with, in which case it **starves**: its health drains each event it stays blocked. *Truth:* a task with no inputs and no context can't work.
2. **Survival.** *1 ≤ k ≤ V*: healthy. At *k = V* it **thrives**: its pulses myelinate (cache reuse makes later work cheaper). *Truth:* a task with exactly the inputs it can use does its best work.
3. **Overcrowding.** *k > V*: **hypoxia**. Each extra synapse past valence drains health and dims the creature; past *V + 2* it **seizes** (fires erratically, drops inputs). *Truth:* more inputs than a task can hold overflow its context window, and quality falls.
4. **Birth.** When a block clears and its egg was fed by **at least two** tails within the parents' combined valence, the next phase hatches. A spontaneous birth (Conway's B3) happens when **three** creatures are each bound to the other two and share a gap no task fills: the triangle can conceive a new creature there (in the work layer, the bowl suggests the missing task; it never invents one).
5. **Death.** Health reaching zero is death. In the work layer, death happens only on a terminal failure or a release; the life layer never cancels a task. A run that keeps a creature at zero health without failing shows a creature on life support: grey, barely pulsing, still working.

## Resources: what creatures need, and fight over

Every limit in the ecosystem is a real limit in the system.

| Resource | Real limit | Shortage looks like |
|---|---|---|
| **Energy** | token budget and cost ceiling | creatures thin and slow; Bright cores starve first |
| **Oxygen** | each agent's context window | hypoxia: dimming, then seizures (rule 3) |
| **Space** | concurrency slots (`ConcurrencyPolicy.maxConcurrentTasks`) | more Ready creatures than slots: they jostle for the slots |
| **Light** | the person's attention (approvals, escalations) | creatures at the glass wilt the longer a gate waits |
| **Water clarity** | the memory layer | murky water: creatures repeat old mistakes; plasticity can't strengthen paths |
| **Nutrients** | past artifacts and lessons in the sediment | autotrophs (amber) graze here; everyone grows faster in rich sediment |

**Fighting.** When Ready creatures outnumber slots, they contest them. The scheduler decides who runs (the life layer never overrides it), and the fight shows that decision: the winner pushes in, the loser is shoved back to wait. Warm pigments win more of the shoving matches *visually*, because the facade presents the scheduler's priority as temperament. The work order is unchanged. Losing costs only time, never health, unless the wait outlasts its energy.

**Predation.** Adversarial creatures (whatever prop marks them as reviewers) are the immune system. When one checks another creature's work it attacks: biting the body and cracking at the egg. A **failed** check wounds the target (Glass takes double, Speckled half), and a wounded egg hatches late. A **passed** check leaves the attacker sulking and the target unharmed. A cluster with too many adversaries turns **autoimmune** (below).

## Clusters

A cluster is a connected group of creatures (one weakly connected component of the dependency graph).

**Why bind.** Solitude is possible; clusters are better at almost everything:
- **Shared water:** memory written by one member clears the water for all, so the cluster learns as one.
- **Myelin:** repeated paths between members get cheaper (prompt reuse), so energy goes further.
- **Pooled gestation:** several tails feed one egg, so blocks clear faster and eggs hatch healthier.
- **Mutual repair:** a Speckled member next to a wounded one lends resilience (a retry with the failure as context).
- **Defence:** a cluster can absorb an adversary's checks that would cripple a lone creature.

**Size.** Cluster health depends on its size *n* against the bowl's concurrency slots *C*, and on whether it has a pacemaker (a creature carrying the orchestrator's prop):
- *n < C*: **underpopulated.** Slots sit idle, so energy is wasted on keeping the water warm. Healthy but sluggish.
- *C ≤ n ≤ 2C*: **the sweet spot.** Every slot busy, short queues.
- *2C < n ≤ 4C*: healthy **only with a pacemaker** setting the rhythm. Without one, the cluster is crowded and quarrelsome.
- *n > 4C*: **overgrown.** Coordination cost (synapse count grows faster than members) outruns the benefit. Even a pacemaker can't hold it, and the cluster wants to split: the bowl suggests a nested workflow.
- **Density matters too.** A cluster whose synapses exceed half of all possible pairs crowds even at a good size: everyone is overcrowded (rule 3).

**Unhealthy clusters**, and the real failure each one shows:

| Condition | Looks like | Real cause |
|---|---|---|
| **Seizure** | the whole cluster firing in sync, flashing | retry storms: many tasks retrying at once |
| **Necrosis** | a dead creature and greying spreading down its tethers | a failure cascading to dependents |
| **Tumour** | one creature budding again and again, a lump of copies | runaway task creation (a plan that keeps spawning tasks) |
| **Atrophy** | tethers that never pulse, withering | conditions that can never be met; dead branches of the graph |
| **Autoimmune** | adversaries attacking healthy eggs, nothing hatching | reviewers rejecting work in a loop |
| **Famine** | everyone thin and slow | budget exhausted |
| **Hypoxia** | everyone dim, seizing at the edges | context overflow from too many inputs |
| **Eclipse** | creatures pressed to the glass, wilting | approvals waiting on the person |

## Solitary survival

A lone creature (a one-task workflow, or one left after a release) survives if it can feed itself:
- **Autotrophs** (amber pigment) graze the sediment, the memory and past artifacts, and live alone indefinitely.
- **Dark cores** spend little and can wait out long droughts.
- Anything else alone **encysts** when idle: curled, dim, costing nothing. It wakes the moment it's fed an objective or bound to another creature.

A solitary creature never gains the cluster benefits, so a person learns that solitude is a choice with a cost, not a failure.

## The dead

- **Failed** creatures stay where they fell, as corpses (`Node-Creatures.md`), until the run ends. You can see where the failure is.
- **Decay.** When the run settles, corpses sink into the sediment. Their artifacts and failure notes become **nutrients**: lessons in memory that make the next run's water clearer. A failure is never wasted.
- **Scavengers.** A creature carrying the recovery prop can eat a corpse during the run, reclaiming its partial artifacts and attempt history into a retry.
- **Fossils.** Every creature that ever lived leaves a fossil in its bowl's sediment, with its genome. A fossil can be used **once** as a parent, so a lost line can be revived, at the cost of the fossil.
- **The tide** still reverses a death within the session (a release, an accidental cut).

## Environmental pushback

The environment pushes back with real system conditions, and the bowl shows them as weather.

| Weather | Real condition | Effect |
|---|---|---|
| **Drought** | a provider outage | no energy reaches anyone using that provider; they encyst |
| **Cold snap** | rate limiting | everyone's refractory period lengthens |
| **Famine** | budget ceiling reached | energy stops; Bright cores fall first |
| **Turbulence** | repository churn, merge conflicts | tethers tangle; inputs arrive late |
| **Toxins** | failing tests in the repo | water clouds; every creature takes small damage until the tests are fixed |
| **Night** | the person is away | gates wait in the dark; nobody wilts while the person sleeps, but nothing at the glass moves |

## What this asks of the build

- **Health** is computed from real signals: context fill, token spend against budget, retries, failures, time waiting at gates, slot contention. It never feeds back into the work.
- **Genome storage:** each creature in a person's collection carries its seven alleles, its parts and its lineage (parents, generation), persisted with the setup file.
- **Starting collection:** commons only. Rarer creatures arrive by breeding, mutation and the mythic conditions.
- **Measurement:** the scripts in `tools/creature-genome/` re-measure the paintings, so a new painting joins the lineage automatically.
