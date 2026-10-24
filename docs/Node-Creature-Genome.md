# Node creatures: genome and ecology

The creatures as living things in their own right: what they inherit, what they need, how they live, fight, bind, breed, sicken and die. This is the facade's own biology, a rule-driven artificial life in the spirit of Conway's Game of Life: simple local rules, complex behaviour. The lineage data is measured from the paintings (`tools/creature-genome/`, output `docs/swarm-terrarium/lineage.json`); the lineage map is on the Bowl OS mockup canvas.

## The world

- **The bowl** is water over a hexagonal lattice. One creature occupies one hex. Its **neighbourhood** is the six touching hexes.
- **Time** moves in **ticks**, one per heartbeat of the existing terrarium clock (2.4 s).
- **Depth** matters. The surface is bright and oxygen-rich; the bottom is dark and holds the sediment.
- **Day and night** alternate every 120 ticks. **Seasons** turn every 20 days.

## Resources

Five things are scarce. Everything a creature does is a way of getting them.

| Resource | Where it comes from | Who needs it |
|---|---|---|
| **Light** | the surface; fades with depth; zero at night | autotrophs feed on it directly |
| **Plankton** | drifts in the water, regrows in lit water, blooms over rich sediment | filter feeders eat it |
| **Oxygen** | dissolves at the surface; autotrophs make it in light; everyone burns it | everyone, every tick, at their metabolic rate |
| **Sediment** | settles from the dead and from waste | chemotrophs eat it; it feeds plankton blooms; too much chokes the bottom |
| **Room** | one creature per hex; tendrils need free hexes to reach | everyone; crowding steals oxygen |

Each creature also carries four internal values: **Energy** (0–100, spent on living, moving, fighting and breeding), **Integrity** (0–100, its health), **Charge** (builds up to a spike) and **Age**.

## The genome

Seven trait loci, each read from what is visibly painted. Every gene shows in the body and acts in behaviour; nothing is decorative. Frequencies are from the 66 painted creatures.

| Locus | Alleles (painted frequency) | Body | Behaviour |
|---|---|---|---|
| **Pigment** | red 14, teal 11, magenta 8, cyan 7, amber 7, orange 6, blue 4, indigo 4, violet 4, green 1 | limb colour | **How it eats.** Amber and green are *autotrophs*: they feed on light and make oxygen. Teal, cyan and blue are *filter feeders*: they eat plankton. Red, orange and magenta are *hunters and scavengers*: they take energy by biting the living and eating the dead. Violet and indigo are *chemotrophs*: they live off sediment in the dark. |
| **Accent** | a second hue, or none | tips and tail markings | **A second appetite.** A creature can also feed the way its accent colour feeds, at half efficiency. A complementary accent (blue/orange, teal/red) makes it a **bridge** that can bind across genera. |
| **Spectrum** | Prismatic 37, Duotone 25, Monotone 4 | how many hues | **Tolerance.** Prismatic creatures survive a wide band of light, depth and temperature. Monotones thrive in a narrow band and suffer outside it. |
| **Core** | Dusk 29, Dark 21, Bright 16 | body shade | **Metabolism.** Dark burns 0.5 energy a tick and lives 3000 ticks; Dusk 1 and 1500; Bright 2 and 750. Bright cores move, charge and spike twice as fast. |
| **Pattern** | Plain / Mottled / Speckled (thirds) | core texture | **Toughness.** Speckled takes half damage. Plain heals twice as fast. Mottled sits between. |
| **Skin** | Sheen 29, Opaque 25, Glass 12 | translucency | **Visibility against armour.** Glass is hard to see in lit water (hunters miss it half the time) but takes double damage when hit. Opaque is easy to see and takes 25% less damage. |
| **Arbor** | Sparse / Branching / Overgrown (thirds) | tendril count | **Valence V**: how many bonds it can hold (2 / 3 / 4) and how far it reaches. |

**Parts loci.** The rigs are built from separate parts (soma, eyes, lids, tendril base/mid/tip, tail base/mid/bulb). Each part is its own locus, inherited whole from one parent. A child can be a Thalassa body with Succina tendrils and a Favilla tail. Parts carry their colours, so children visibly resemble both parents. The tail sets **fecundity** (longer tail, larger clutch); tendril tips set **bite** (barbed or spiked tips bite harder, ball tips bind more gently).

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

Each tick, every creature is judged on its **bonds** *k* (live creatures it holds a tendril bond with), its **crowding** *c* (occupied hexes around it) and its valence *V*.

### Bonding

Two touching creatures bind when both have free valence and either their kinship is at least 70% or one of them is a bridge. A bond is a live tendril, and across it the two:

- **share:** energy and oxygen flow 10% of the difference each tick toward the poorer partner.
- **relay:** a spike from one adds charge to the other.
- **hold:** bonded creatures move together.

A bond that carries no spike for 60 ticks withers and breaks.

### Spikes

Feeding builds charge. At 100 charge a creature **spikes**: it flashes, empties its charge, and passes 30 charge to each bonded partner. Spikes are how clusters coordinate. When most of a cluster spikes in a steady rhythm it **oscillates**, and an oscillating cluster earns its collective powers (below).

### Survival (the Game of Life core)

| Condition | Rule | Looks like |
|---|---|---|
| **Isolation** (*k = 0*) | No sharing. If it can't feed itself where it is, it loses 1 extra energy a tick. | drifting, searching, tendrils reaching |
| **Survival** (*1 ≤ k ≤ V*) | Each bond cuts its metabolism by 10%. | calm, steady pulses |
| **Thriving** (*k = V*, and *c ≤ 4*) | It also heals 1 integrity a tick. | full colour, tail lengthening |
| **Overcrowding** (*k > V*, or *c ≥ 5*) | Oxygen deficit: it loses 2 integrity a tick for each bond over *V* and each neighbour over 4. | dimming, gasping, tendrils pulled in |
| **Starvation** (energy 0) | It **encysts**: curls into a dormant cyst burning 0.1 a tick, for up to 600 ticks. Fed in time, it wakes; otherwise it dies. | a tight, dull ball sinking slowly |
| **Old age** | Past its lifespan it loses 1 integrity a tick. | colour fading, tail shortening |
| **Death** | Integrity 0. | it stops, sags and sinks |

### Birth

- **Triad birth** (Conway's B3, the natural way): an empty hex touched by exactly **three** creatures that are all bonded to each other, with at least 90 energy between them. The three grow their tails into the hex and **co-gestate an egg**: each pays 30 energy over the gestation, the egg swells for 100 ticks, then hatches. The child inherits from the two parents with the highest kinship to each other; the third parent contributes one part locus.
- **Pair birth:** two bonded creatures can gestate alone, but it costs each 50 energy and the egg hatches only half the time.
- **Eggs are vulnerable.** Hunters can eat them; a parent that dies during gestation detaches its tail and the egg shrinks.

## Inheritance

| Locus | Rule |
|---|---|
| **Fertility** | Kinship 75–95% is fertile. Above 95% the child is born **weak** (maximum integrity 60). 60–75% hatches a quarter of the time; bridges lift it to half. Below 60% is sterile, except **hybrid vigour**: a triad whose three parents come from three different genera, gestating through a full night without losing energy below 30, hatches anyway, and the child often founds a new genus. |
| **Pigment, Accent** | One parent's hue, or 1 time in 5 the hue midway between them on the wheel. |
| **Core** | Incomplete dominance: Dark × Bright gives Dusk. |
| **Pattern** | Speckled dominant, Plain recessive. |
| **Skin** | Glass recessive: both parents must carry it. That's why glass is rare. |
| **Arbor** | The parents' average, ±1. |
| **Spectrum** | Follows from the hues the child actually ends up with. |
| **Parts** | Each part comes whole from one parent. |
| **Mutation** | Each locus has a 2% chance to shift one step: a neighbouring hue, the next core shade, one more or one fewer branch. Mutation is how the bowl reaches colours nobody painted. |

**Mythic conditions:**
- **Viridia** (green) hatches only from a triad of autotrophs that has oscillated unbroken through a whole day.
- **Phosphora** needs two Glass parents, a Monotone result and a mutation, all in one egg.

## Fighting

- **Hunting.** Hunters (red, orange, magenta) bite a touching creature that isn't kin (kinship under 85%). Damage = 8 × the hunter's core speed × barb factor, scaled by the target's pattern and skin. The hunter gains half the damage as energy. Glass in daylight is missed half the time.
- **Contests.** When food or light is scarce in a patch, neighbours **shove** for the best hexes. The winner is decided by energy plus pigment aggression (warm hues +20) plus 10 for each bonded ally pushing behind it. The loser is displaced one hex and loses 2 energy. Kin never fight.
- **Famine turns everyone.** When plankton falls below a fifth of its normal level, filter feeders begin biting each other, and hunters will bite kin.

## Clusters

A cluster is a set of creatures joined by bonds.

### Why bind

| Benefit | How it works |
|---|---|
| **Sharing** | Energy and oxygen even out across bonds, so one lucky feeder keeps the hungry alive. |
| **Cheaper living** | Every bond cuts metabolism 10%. |
| **Division of labour** | A mixed cluster feeds itself. Autotrophs pump oxygen and energy into the cluster by day; chemotrophs feed it by night from the sediment; filter feeders graze; hunters guard and clear corpses. |
| **Collective powers** (oscillating clusters only) | **Current:** synchronised beating draws fresh oxygen and plankton through the cluster. **Herding:** filter feeders together corral plankton into the cluster. **Mobbing:** a hunter biting a member is bitten back by every member touching it. **Brooding:** eggs inside an oscillating cluster hatch 30% faster and can't be eaten. |
| **Resilience** | A cluster absorbs damage and starvation that would kill a creature alone. |

### Size

A cluster's health depends on its size *n* and on the conditions around it:

| Size | State | Why |
|---|---|---|
| 1 | lone | see Solitary survival |
| 2–3 | efficient pair or triad | cheapest per creature; triads can breed |
| 4–12 | **the sweet spot** | sharing and collective powers without crowding, if feeding modes are mixed |
| 13–30 | needs a **pacemaker** | a Bright core with Overgrown arbor that spikes first and sets the rhythm. Without one the cluster can't oscillate and splinters into factions. |
| 30+ | collapses | resources arrive at the edges, but the interior grows with volume. The centre goes **hypoxic** and starves, and the cluster splits along its weakest bonds (fission). |

The size limits shift with conditions:

| Condition | Effect on the limits |
|---|---|
| Lit surface water | up by half |
| The bottom | halved |
| Night | the surface advantage vanishes; autotroph-heavy clusters shrink their oxygen, and mixed clusters with chemotrophs weather the night best |
| One genus only (monoculture) | efficient while things are good, but blight takes them all |

### Unhealthy clusters

| Condition | Looks like | Cause |
|---|---|---|
| **Seizure** | every member flashing in lockstep, faster and faster, then collapsing | a pacemaker too strong, or too many Bright cores: spikes cascade, energy crashes |
| **Hypoxic core** | grey, gasping centre, bright edges | cluster too big or too dense for its water |
| **Necrosis** | a dead member still bonded; grey spreading along its tendrils | a corpse not shed; decay poisons bonded neighbours (−1 integrity a tick each) |
| **Tumour** | one creature budding copies of itself into every gap until the cluster can't breathe | a mutation that skips the triad rule (asexual budding); its clones crowd everything |
| **Atrophy** | tendrils that never light, slowly withering | members too far apart in rhythm; bonds starve of spikes and break |
| **Cannibalism** | members biting members | famine inside a cluster with hunters in it |
| **Parasitism** | one plump creature in a thinning cluster | a *cheater* mutation that takes through bonds but never gives back |
| **Blight** | the same colour of creature sickening across the cluster | disease spreads along bonds between kin of 90%+; diverse clusters stop it, monocultures don't |

## Solitary survival

A creature can live alone if it can feed where it is:

| Kind | How it survives alone |
|---|---|
| **Autotrophs** | Live alone near the surface by day and on stored energy by night. |
| **Chemotrophs** | Live alone on the sediment, in the dark, indefinitely. |
| **Dark cores** | Burn little and can drift a long way between meals. |
| **Nomads** (Glass, Sparse) | Slip past hunters and roam. |
| **Scavengers** (hunters) | Shadow other hunters to eat their leftovers. |

Anything else alone **encysts** when food runs out and waits to be found. A loner never gets the cluster's powers, and a loner can't breed.

## The dead

- **The corpse** stays where it died for 120 ticks: sagging, grey, still. Hunters and scavengers can eat it for energy.
- **Decay:** uneaten, it sinks and becomes **sediment**. Sediment feeds chemotrophs and sets off plankton blooms, so a death feeds the next generation.
- **Corpses in clusters** poison their bonded neighbours (necrosis) until the cluster sheds them or something eats them.
- **Fossils:** every creature that ever lived leaves a fossil in the sediment with its genome. A fossil can serve once as a parent, reviving a lost line at the cost of the fossil.
- **Silt:** too much sediment chokes the bottom. It smothers chemotrophs and starves the water of oxygen. Sediment needs eaters.

## Environmental pushback

The bowl is not kind:

| Event | What happens |
|---|---|
| **Night** | No light, autotrophs stop making oxygen, oxygen falls everywhere. |
| **Seasons** | Plankton booms in summer and crashes in winter; winter favours Dark cores and chemotrophs. |
| **Bloom and crash** | A plankton bloom over rich sediment feeds everyone, then dies at night and strips the water of oxygen. Overpopulation follows feast. |
| **Temperature drift** | The water warms and cools; Monotones outside their band lose integrity. |
| **Currents** | Turbulence pushes creatures and breaks weak bonds. |
| **Silting** | The bottom fills unless something eats the sediment. |
| **Blight** | Arises in crowded monocultures and spreads along kin bonds. |
| **Carrying capacity** | Light, plankton and oxygen are finite, so a bowl supports only so many creatures. Past that, everything above is enforced by starvation and suffocation. |

## The keeper

The person tends the bowl, never commands the creatures:

- **Feed:** sprinkle plankton, which can start a bloom.
- **Light:** lengthen the day.
- **Stir:** a current that breaks bonds and oxygenates.
- **Clean:** lift silt.
- **Move:** place a creature somewhere new.
- **Separate:** split a crowded cluster.

Care has consequences: overfeeding causes blooms and crashes, and constant stirring stops clusters from ever forming.
