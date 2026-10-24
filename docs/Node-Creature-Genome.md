# Node creatures: genome and ecology

The creatures as living things in their own right: what they inherit, what they need, how they live, compete, wire together, breed, sicken and die. They're neurons, so their biology is a neuron's biology: tissue, blood supply, trophic support, firing, synapses, pruning, excitotoxicity. It runs as rule-driven artificial life in the spirit of Conway's Game of Life: simple local rules, complex behaviour. The lineage data is measured from the paintings (`tools/creature-genome/`, output `docs/swarm-terrarium/lineage.json`); the lineage map is on the Bowl OS mockup canvas.

## The world

- **The bowl** is living tissue: extracellular fluid laid over a hexagonal lattice, threaded with **capillaries** that carry the blood supply. One creature occupies one hex. Its **neighbourhood** is the six touching hexes.
- **Time** moves in **ticks**, one per heartbeat of the existing terrarium clock (2.4 s).
- **Distance from a capillary** matters as depth would: hexes beside a vessel are rich, hexes far from one are starved.
- **Wake and sleep** alternate every 120 ticks. Waking tissue is bright, busy and costly; sleeping tissue is quiet, and sleep is when waste is cleared.

## Resources

Six things are scarce. Everything a creature does is a way of getting them.

| Resource | Where it comes from | What it's for |
|---|---|---|
| **Glucose** | the capillaries; it diffuses out and thins with distance | energy for everything, above all for firing |
| **Oxygen** | the capillaries, the same way | burning glucose; without it a creature suffocates fast |
| **Trophic factor** | released by a creature onto its partners when they make it fire (the real *use it or lose it* signal) | survival: a creature that receives none slowly self-destructs (apoptosis) |
| **Synaptic sites** | each creature's tendrils (its valence) | a creature can only be wired to so many partners; partners compete for the sites |
| **Ion balance** | restored by pumps that burn glucose | firing spends it; too much firing too fast overloads the creature (excitotoxicity) |
| **Room** | one creature per hex | crowding steals glucose and oxygen and fouls the fluid |

Each creature carries **Energy** (0–100), **Integrity** (0–100, its health), **Charge** (builds to a spike), **Trophic reserve** (0–100, drains every tick, refilled by partners) and **Age**.

## The genome

Seven trait loci, each read from what is visibly painted. Every gene shows in the body and acts in behaviour; nothing is decorative. Frequencies are from the 66 painted creatures.

| Locus | Alleles (painted frequency) | Body | Behaviour |
|---|---|---|---|
| **Pigment** | red 14, teal 11, magenta 8, cyan 7, amber 7, orange 6, blue 4, indigo 4, violet 4, green 1 | limb colour | **What kind of cell it is.** See the table below. |
| **Accent** | a second hue, or none | tips and tail markings | **Co-transmission.** A creature also releases its accent colour's signal, at half strength. A complementary accent (blue/orange, teal/red) makes it a **bridge** that can wire across genera. |
| **Spectrum** | Prismatic 37, Duotone 25, Monotone 4 | how many hues | **Tolerance.** Prismatic creatures cope with a wide range of glucose, oxygen and activity. Monotones thrive in a narrow band and suffer outside it. |
| **Core** | Dusk 29, Dark 21, Bright 16 | body shade | **Metabolism and firing rate.** Dark burns 0.5 energy a tick, fires slowly and lives 3000 ticks. Dusk burns 1 and lives 1500. Bright burns 2, fires fast and lives 750. |
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

## The rules of life

Each tick, every creature is judged on its **synapses** *k* (live partners it's wired to), its **crowding** *c* (occupied hexes around it), its valence *V*, its **excitatory drive** *e* (excitatory partners that fired at it this tick) and its **inhibitory drive** *i*.

### Wiring

Two touching creatures form a synapse when both have a free site and either their kinship is at least 70% or one of them is a bridge. Across a synapse, the two:

- **signal:** a spike passes charge (excitatory), drains it (inhibitory) or strengthens the synapse (modulatory).
- **feed:** each time one makes the other fire, it receives 5 trophic factor back.
- **share:** glucose flows 10% of the difference each tick toward the poorer partner.

**Hebbian rule:** a synapse whose two ends fire within 2 ticks of each other gains strength; one that goes 60 ticks without a shared firing weakens. A synapse at zero strength is **pruned**: the tendril retracts.

### Firing

Charge builds from excitatory input (and, for pacemakers, on its own). At 100 the creature **spikes**: a flash runs down its body, it spends 3 energy and some ion balance, and it signals every partner. Firing too often (more than once every 3 ticks) costs integrity: that's **excitotoxicity**.

When most of a circuit fires in a steady rhythm, it **oscillates**, and an oscillating circuit gains its collective powers (below).

### Echo: feedback from the self

Neurons live on feedback, and feedback loops are either very constructive or very destructive. What decides which is **where the feedback comes from**.

Every creature carries **Echo** (0–100): how much of what it hears is itself.

- **Echo builds** from any input that is effectively its own voice: its own spikes looping back, and spikes from **near-clones** (kinship 95% or more). A near-clone's voice counts as self, and it counts **in full for both of them**: two near-clones wired together each take the other's feedback as their own, so each builds Echo at double the rate one would build alone.
- **Echo falls** only through **challenge**:
  - input from creatures that are genuinely different (kinship under 85%): the more different, the more it falls.
  - losing a contest, being inhibited by a non-kin rival, or being bitten.

  Comfort doesn't heal it. Being contradicted does.
- **It takes a long time** to go wrong. A lone creature hearing only itself gains about 1 Echo every 20 ticks; a near-clone pair, about 1 every 10.

What Echo does as it rises:

| Echo | State | Effect |
|---|---|---|
| 0–30 | **Grounded** | none |
| 30–60 | **Self-absorbed** | Mutation rate in its offspring rises with Echo, up to 4× at 60. The mutations are unpredictable and land as often harmful as useful. It starts ignoring weak outside input. |
| 60–85 | **Fevered** | It stops responding to challenge (Echo no longer falls from contests or inhibition, only from wiring to very different creatures). Its spikes start carrying Echo to its partners: each spike it sends adds 1 Echo to whoever receives it. |
| 85–100 | **Insane** | It turns **septic**. It poisons itself (−1 integrity a tick), the fluid in its hex and the six around it (anything there loses 1 integrity a tick), and everything wired to it (−2 integrity and +3 Echo a tick). It does nothing to fight its own sickness and actively spreads it. The poisoned fluid lingers after it dies. |

Insanity is contagious through wiring and through the fluid. Untreated, one insane creature can turn its whole circuit septic and leave a poisoned patch of tissue behind.

### Survival (the Game of Life core)

| Condition | Rule | Looks like |
|---|---|---|
| **Isolation** (*k = 0*) | No trophic factor arrives; the reserve drains. At zero, integrity falls 1 a tick (apoptosis) unless it's a pacemaker or a progenitor. | tendrils reaching out, searching |
| **Survival** (*1 ≤ k ≤ V*) | Trophic factor arrives as long as it fires and makes others fire. | steady pulses |
| **Thriving** (*k = V*, *c ≤ 4*, and *i ≥ e/4*) | Fully wired, room to breathe, enough inhibition to keep it balanced: it repairs 1 integrity a tick, and its tail grows. | full colour, smooth rhythm |
| **Overcrowding** (*k > V* or *c ≥ 5*) | Glucose and oxygen deficit: −2 integrity a tick for each synapse over *V* and each neighbour over 4. | dimming, tendrils pulled in |
| **Runaway excitation** (*e ≥ 3* and *i = 0*) | Excitotoxicity: −3 integrity a tick while it lasts. | frantic flashing, swelling |
| **Silence** (no spike in 200 ticks) | Its synapses weaken and prune; it falls toward isolation. | grey, still |
| **Starvation** (energy 0) | It goes **quiescent**: stops firing, burns 0.1 a tick, for up to 600 ticks. Fed and stimulated in time, it wakes; otherwise it dies. | curled tight, faintly lit |
| **Old age** | Past its lifespan it loses 1 integrity a tick. | fading colour, shrinking tail |
| **Death** | Integrity 0, by **apoptosis** (clean) or **necrosis** (messy). See The dead. | |

### Birth

- **Triad birth** (Conway's B3, the natural way): an empty hex touched by exactly **three** creatures that are all wired to each other and have fired together within the last 10 ticks, with at least 90 energy between them. The three grow their tails into the hex and **co-gestate an egg**: each pays 30 energy over the gestation, the egg swells for 100 ticks, then hatches. The child inherits from the two parents with the highest kinship to each other; the third parent contributes one part locus.
- **Pair birth:** two wired creatures can gestate alone, but it costs each 50 energy and the egg hatches only half the time.
- **Solo birth:** a loner (*k = 0*) with at least 80 energy and 70 integrity can grow an egg on its own tail. It costs 60 energy over 200 ticks. The child is a **clone**: an exact copy, except for whatever mutations the parent's Echo caused (see Echo). The child also inherits half its parent's Echo.
- **The clone trap.** Two creatures now, but no diversity. Parent and clone are near-clones, so wired together each hears the other as itself and builds Echo twice as fast. Anything they breed together is a near-clone of both and inherits their combined Echo, so every generation amplifies the loop. The **only** thing pulling a clone line back toward health is its mutations, which push kinship below 95% and turn self-feedback back into outside voices. A keeper who starts from one creature must breed for diversity as fast as possible.
- **Progenitor division:** a progenitor can split off a child alone once per sleep, at only 40 energy. Its children are clones like any other, but a progenitor's division resets its own Echo to zero.
- **Eggs are vulnerable.** An egg needs glucose from its parents' tails. A parent that dies during gestation detaches its tail and the egg shrinks. A starved egg is cleared as debris.

## Inheritance

| Locus | Rule |
|---|---|
| **Fertility** | Kinship 75–95% is fertile. Above 95% the child inherits both parents' Echo, summed (see the clone trap). 60–75% hatches a quarter of the time; bridges lift it to half. Below 60% is sterile, except **hybrid vigour**: a triad from three different genera that oscillates through a whole sleep without any parent dropping below 30 energy hatches anyway, and the child often founds a new genus. |
| **Pigment, Accent** | One parent's hue, or 1 time in 5 the hue midway between them on the wheel. Because pigment is cell kind, a child of an excitatory and an inhibitory parent may come out modulatory. |
| **Core** | Incomplete dominance: Dark × Bright gives Dusk. |
| **Pattern** | Speckled dominant, Plain recessive. |
| **Skin** | Glass recessive: both parents must carry it. That's why glass is rare. |
| **Arbor** | The parents' average, ±1. |
| **Spectrum** | Follows from the hues the child actually ends up with. |
| **Parts** | Each part comes whole from one parent. |
| **Mutation** | Each locus has a 2% base chance to shift one step: a neighbouring hue, the next core shade, one more or one fewer branch. A parent's Echo multiplies the chance (up to 4× at Echo 60, more beyond) and makes the shifts erratic: two steps, or a trait flipping. Mutation is how the bowl reaches colours nobody painted, and how clone lines escape themselves. |
| **Echo** | Children inherit half the parents' Echo, or the full sum for near-clone parents. |

**Mythic conditions:**
- **Viridia** (green, the progenitor) hatches only from a triad of pacemakers that has held one unbroken rhythm through a whole wake.
- **Phosphora** needs two Glass parents, a Monotone result and a mutation, all in one egg.

## Competition

- **For trophic factor.** Trophic factor is limited: a creature can release only so much each tick, shared among everyone it makes fire. When too many creatures wire into the same target, they split its supply, and the weakest-wired ones starve and prune away. This is real developmental neuron death, and it's the main way the bowl thins itself.
- **For synaptic sites.** When a creature's sites are full and a newcomer wires in, the newcomer can **displace** the weakest existing synapse. Strength wins, and synapses strengthen by firing together, so creatures that keep pace with their partner hold their place.
- **For blood.** Hexes beside a capillary are worth fighting for. Neighbours **shove** for them: energy plus 20 for excitatory cells, plus 10 for each partner bracing behind it. The loser moves one hex away and loses 2 energy. Kin (85%+) never shove each other.
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

**Apoptosis** is a clean death, from lost trophic support, old age, or slow starvation. The creature shrinks, its tendrils retract, and it breaks into small **apoptotic bodies**. Phagocytes eat them quickly, with no harm to neighbours.

**Necrosis** is a messy death, from excitotoxicity, ischaemia, or attack. The creature swells and bursts, spilling its contents. It **inflames** everything wired to it and every neighbour (−1 integrity a tick) until phagocytes clear it, and it draws phagocytes in, who may also take the weakened.

**After death:**
- **Debris:** uncleared remains become **waste** in the fluid. Sleep clears waste; too little sleep and it builds into **plaque**.
- **Recycling:** cleared remains return their glucose to the tissue around them, so a death feeds the creatures nearby.
- **Engrams:** if the dead creature was part of a learned circuit, its pattern persists in its partners' strengthened synapses, and a new creature wiring into the gap inherits a head start.
- **Fossils:** every creature that ever lived leaves a fossil trace with its genome. A trace can serve once as a parent, reviving a lost line at the cost of the trace.

## Environmental pushback

The tissue is not kind:

| Event | What happens |
|---|---|
| **Wake and sleep** | Wake is fast, costly and wasteful. Sleep is slow, cheap and cleansing. Too long awake builds waste and plaque; too long asleep and synapses weaken from disuse. |
| **Ischaemia** | A capillary narrows or blocks, a stroke. Everything downstream starves of glucose and oxygen within ticks, and necrosis spreads from the centre unless the vessel reopens or an oscillating circuit draws blood back. |
| **Hypoglycaemia** | Blood glucose falls across the bowl. Bright cores and excitatory cells fail first. |
| **Fever** | The tissue heats. Everyone fires more readily, so seizures come easily. Monotones outside their band lose integrity. |
| **Inflammation** | Phagocytes multiply after necrosis and grow aggressive, biting the merely tired. |
| **Waste build-up** | Without sleep, plaque spreads and blocks wiring. |
| **Ageing** | The whole bowl's capillaries thin slowly, and myelin wears. |
| **Carrying capacity** | Blood supply is finite, so a bowl supports only so many creatures. Past that, trophic competition and starvation enforce the limit. |

## The keeper

The person tends the tissue, never commands the creatures:

- **Perfuse:** raise glucose and oxygen in the blood.
- **Stimulate:** fire a creature by touch.
- **Quiet:** hush a seizing circuit.
- **Sleep:** call an early sleep to clear waste.
- **Move:** place a creature somewhere new.
- **Separate:** cut a circuit apart.

- **Introduce:** bring a stranger into a circuit. Competition is medicine: a challenger lowers everyone's Echo.
- **Quarantine:** isolate a septic creature before it spreads, knowing that isolation alone makes its Echo worse.

The keeper's real job is **diversity and challenge**: breed across lines as fast as possible, and keep enough competition in every circuit that nothing is only listening to itself.

Care has consequences: over-perfusion breeds overcrowding, over-stimulation causes seizures, constant separation stops circuits from ever learning, and protecting creatures from all competition breeds echo chambers.
