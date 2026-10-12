# Conveyance framework

**A UI/UX framework in which the interface teaches its own rules.**

This document describes the SDK. The [manifesto](../README.md) is the source of the philosophy and remains the source of truth when implementation language drifts away from it.

The framework exists to make three ideas practical:

- **Conveyance:** teach by example rather than by instruction.
- **Resourceful minimalism:** put present elements to work instead of surrounding useful things with scaffolding.
- **Compassionate design:** assume competence, preserve agency, and make consequences understandable before and after action.

The SDK is not a visual style guide. It does not prescribe one palette, one density, one shape language, one amount of motion, or one aesthetic temperament. A quiet banking app and an exuberant H2G2 interface can both convey well.

What Conveyance does prescribe is a way of making relationships visible enough that people can learn the product by using it.

---

## 1. The framework must convey to the developer too

The API is also an interface.

If a developer must continually repeat facts that the model already knows, those parameters are labels. Labels drift. If a developer must memorize exceptions scattered across a manual, the SDK has reproduced the same construction-zone problem it criticizes in applications.

Two working principles follow:

> **Derive what can be derived. Ask only for facts the framework cannot know.**

and

> **Put a rule beside the semantic opt-out that says what the exception is instead.**

For example:

- the developer states an Act's consequence; the motion signature is derived;
- a Gate names where it can be resolved; the Escort route is derived;
- the registry observes which element offers which Act; `Invite` is derived;
- an element with `Employment.Ambient` is not a weak working element—it explicitly says it is not operational;
- `Act.destroy(...)` requires an inverse; `Act.destroyIrreversibly(...)` names the real exception instead of weakening ordinary destruction.

The full rule/opt-out pairs live in [Rules and their opt-outs](RULES-AND-OPTOUTS.md).

---

## 2. Model actions and consequences, not buttons and callbacks

Conventional UI code often declares appearance and behavior separately. A rectangle is styled in one place; a callback changes state elsewhere; loading, success, failure, warning, and navigation are reported by still more surfaces.

Conveyance closes that gap by giving the action a semantic model.

The central noun is `Act`:

```kotlin
val publish = Act.send(
    id = "release.publish",
    subject = release,
    to = store,
    emphasis = ActEmphasis.Heroic,
)
```

An Act knows:

- its stable identity;
- what consequence occurs;
- where that consequence lands;
- what conditions block it;
- whether it can be reversed;
- how broad its scope is;
- what functional emphasis it claims relative to other Acts.

From those facts the framework can derive more:

- the consequence verb;
- motion grammar;
- physical weight;
- whether the Act is currently blocked;
- where a blocked person can be escorted;
- what a live audit should expect from the rendered surface;
- what emphasis the current screen can actually present.

An Act is not itself a visual component. The Compose binding's `Offer` is where an Act is made available to a person.

---

## 3. The semantic vocabulary

The vocabulary is intentionally small.

### Subject

A thing in the product that matters to a person: a document, photo, track, invoice, release, message, account, person, role, or workflow.

### Element

A named thing currently rendered in the interface. Elements give semantic facts physical addresses.

### Act

Something a person can do.

### Consequence

What visibly changes when an Act succeeds, and where.

The core consequence classes are:

```text
Reveal   more of what is already here becomes visible
Enter    the person goes into a Place
Create   a new Subject exists in a named destination
Destroy  a Subject ceases
Alter    a Subject changes in place
Send     a Subject leaves toward a destination
```

These classes are semantic. They are not animation names.

### Gate

A resolvable condition standing between a person and an Act. A Gate names `livesAt`, the Element where the condition can actually be addressed.

### Place

Somewhere a person can be. `Place.from(...)` has an antecedent; `Place.root(...)` names a genuine beginning.

### Employment

Why an Element is present.

`Employment.Working` is operational and is required to do at least four distinct jobs. `Employment.Ambient` explicitly says the Element is not operational.

### ActEmphasis

The functional hierarchy of Acts on a screen:

```kotlin
ActEmphasis.Heroic
ActEmphasis.Primary
ActEmphasis.Secondary
ActEmphasis.Tertiary
ActEmphasis.Supporting
```

This is semantic information about an Act's role in the interaction. It is not Employment, UI state, color, shape, size, or an animation preset.

---

## 4. One element should carry an action through its life

A common interface fragments one action into several unrelated objects:

```text
button → spinner → toast → error banner
```

That fragmentation makes the person repeatedly rediscover which object is talking about which action.

Conveyance prefers continuous identity:

```text
Ready → Blocked / Yielding → Settled / Refused
```

The same offered thing can invite, work, report, fail, retry, interrupt, and carry consequence.

This is the purpose of `Offer`:

```kotlin
Offer(save) {
    // content renders this ActScope in its current state
}
```

The Act keeps its own declaration in `act.emphasis`. Compose can ask `resolvedEmphasis()` for the level the current visible Act hierarchy can actually present.

There is no need for Conveyance to invent a second styling-token framework on top of Compose.

### The durable-process exception

Sometimes an action creates a process that genuinely outlives the originating control: a build, deployment, render, sync, import, workflow, or job.

That does not justify an unrelated global spinner. It means the process has become a Subject of its own and should receive stable identity. The handoff from action to process should be visible.

---

## 5. Employment is a creative constraint

`Employment.Working` requires at least four distinct jobs.

```kotlin
Employment.Working(
    Job.Invite,
    Job.Report,
    Job.Progress,
    Job.Interrupt,
)
```

The point is not tidy screens or fewer objects for their own sake. The point is to force one-purpose fragments to be reconsidered.

If a thing only starts an action, perhaps it can also:

- report the action's state;
- become the place where success settles;
- identify the Subject;
- locate the person;
- interrupt its own work;
- group related information;
- navigate;
- absorb another nearby status object;
- carry risk or confirmation through its own behavior.

The wrong response to the four-job rule is to pad a declaration with imaginary jobs. The right response is to redesign the object.

### Ambient is not a loophole

`Employment.Ambient` is for things that are intentionally not operational: background, texture, atmosphere, illustration, breathing room, ornament, or other composition.

The distinction matters because Conscience can tell the difference between:

```text
this working object accidentally does too little
```

and

```text
this object is intentionally not trying to work
```

Employment answers why an Element is present. Act emphasis answers what an Act does for the interaction. Neither derives from the other.

---

## 6. Continuity gives the person a map

Navigation should preserve relationships where relationships exist.

```kotlin
val detail = Place.from(
    id = "invoice.detail",
    origin = invoiceRow,
    subject = invoice,
)
```

The origin tells the binding what the new Place is related to. A reference binding may render that relationship as growth, morphing, movement, shared identity, or another treatment.

The rule is not "every navigation must use one animation." The rule is that the interface should not discard a useful relationship and then ask breadcrumbs or explanatory text to reconstruct it.

### Real beginnings are roots

```kotlin
Place.root("home")
```

A root says there is no antecedent because this journey actually begins here. There is no universal one-root budget; applications, restored sessions, external entry, and deep links may produce several legitimate beginnings.

---

## 7. Gates are for resolvable blockers

A Gate is a promise that the framework knows where the person can do something about a blocked condition.

```kotlin
val recipientChosen = Gate(
    id = "recipient.chosen",
    livesAt = recipientField,
) { recipient != null }
```

When an Act is blocked, a binding can resist at the point of contact and escort toward the resolver instead of silently doing nothing or greying out the action.

A condition with no available resolution is not a Gate. It may be status, content, environmental fact, policy, or another non-inviting state.

That distinction is more useful than a universal ban on disabled-looking things. Conveyance cares that living and dead things are distinguishable and that resolvable blockers do not pretend to be dead ends.

---

## 8. Reversibility before confirmation

Ordinary destruction requires an inverse:

```kotlin
val restore = Act.create(
    id = "document.restore",
    subject = document,
    into = collection,
)

val delete = Act.destroy(
    id = "document.delete",
    subject = document,
    target = collection,
    inverse = restore,
)
```

That pressure exists because undo, staging, recovery, and residue are usually more respectful than repeatedly asking a person whether they meant what they just chose.

When reality genuinely provides no inverse, use the named exception:

```kotlin
Act.destroyIrreversibly(
    id = "submission.finalise",
    subject = submission,
    target = authority,
)
```

The API preserves the distinction rather than weakening `destroy` to accept `null` everywhere.

---

## 9. Motion is grammar when motion is speaking

The reference binding derives a `Signature` from an Act's consequence.

The useful claim is not that every product may contain only a fixed number of animations. The useful claim is:

> When motion is teaching a consequence, repeated use should remain learnable and non-contradictory.

If Send repeatedly teaches "this Subject travels there," reusing the same learned treatment later to mean "nothing moved; this is decoration" damages the grammar.

But motion can have other jobs:

- ambient life;
- stable identity;
- role personality;
- simulation;
- data visualization;
- illustration;
- atmosphere;
- playful or expressive character.

Those are not violations merely because they are not consequence grammar. They are saying something else.

### Motion should survive interruption truthfully

A canned animation that insists on finishing an obsolete state can lie.

If a working Act fails halfway through, or a destination moves, or a person reverses direction, motion should be able to retarget toward the new truth rather than finish performing the old one first.

This is one reason physics- and spring-oriented motion systems are useful: their value is not that they look "alive" by themselves, but that they can remain responsive to changing reality.

---

## 10. Visual channels are learned language, not assigned uniforms

Conveyance has a reference `Channel` vocabulary for examples and analysis:

```text
Position
Size
Shape
Hue
Chroma
Elevation
Opacity
TypeScale
Density
Motion
Haptics
Sound
```

The reference mapping gives these channels meanings, but the mapping is not a universal aesthetic constitution.

A channel acquires meaning through repeated use inside a product.

Hue may carry identity. Shape may carry state. Size may carry momentary importance. Another product may make a different coherent choice.

The important distinction is between:

```text
consistent because the product has taught this relationship
```

and

```text
consistent because a framework demanded visual order for its own sake
```

Conveyance wants the first and has no use for the second.

### Visual tension is allowed to work

Irregularity, clash, density, asymmetry, frame-breaking motion, and maximalism are not inherently failures.

A better question than "is this consistent?" is:

> **Is the inconsistency doing useful work?**

A sharp object among rounded ones may communicate risk, fracture, interruption, or simply product personality. If the contrast teaches something or belongs to an intentional design language, forcing it back into uniformity would reduce conveyance rather than improve it.

---

## 11. Act emphasis and engineering the hero moment

`ActEmphasis` replaces the old binary keystone idea with a functional outline:

```text
Heroic     title-level Act
Primary    leading Act
Secondary  next level
Tertiary   next level
Supporting lower-level Act
```

The HTML analogy is useful: Heroic is the page title, then Primary, Secondary, and Tertiary descend like headings.

This hierarchy describes **Acts**, not the Employment of the Elements that happen to render them and not the current state of those Elements.

### Hero of the hill

A screen can present **at most one** Heroic Act.

With zero or one visible Heroic declaration, every Act keeps its declared level.

If two or more visible Acts claim Heroic, there is no unique title. The visible Act outline resolves one rung lower:

```text
Heroic     → Primary
Primary    → Secondary
Secondary  → Tertiary
Tertiary   → Supporting
Supporting → Supporting
```

The declarations themselves are not changed. `act.emphasis` remains what the developer said; Compose derives the presented level with `resolvedEmphasis()`.

Conscience reports the conflict as `HeroOfTheHill` and asks the developer to decide which Act actually owns the Heroic slot.

This is not a general scarcity budget. It is a structural property of the top level: a title is only a title when it is singular.

### Engineering the hero moment

The framework does **not** decide that Heroic means "big yellow button." Compose already has theming and token machinery. The product decides how the resolved functional hierarchy becomes its visual and sensory language.

A Heroic Act may be allowed to coordinate more channels at once:

- stronger transformation;
- more spatial response;
- a more expressive type treatment;
- surrounding elements moving or receding;
- stronger haptic articulation;
- sound;
- richer identity transition;
- a longer or more memorable consequence path.

Those are possibilities, not requirements.

---

## 12. Conscience is a design critic with runtime evidence

Conscience is the verification layer.

A useful linter for Conveyance cannot stop at checking individual declarations because many design failures are relationships between individually valid objects.

The Compose registry already knows, live:

- which Elements are composed;
- their geometry;
- which Acts they offer;
- which jobs they perform;
- which Gates resolve there;
- consequence verbs and targets;
- reversibility;
- declared Act emphasis;
- Ambient declarations.

That gives Conscience enough evidence to reason about the surface as a system.

### Compact findings

A lint finding should help without turning the build log into the manual:

```text
[Warning] IdleWorker at invoice
Found: invoice.send is doing 2 jobs
Try: Reimagine it until it honestly does four jobs. Enrich interface objects.
Examples, ideas, and opt-out: https://github.com/HereLiesAz/Conveyance/blob/main/docs/RULES-AND-OPTOUTS.md#employment
```

The linked documentation contains the reasoning, examples, and semantic opt-out.

### Prefer relational findings

Suppose a surface contains:

```text
save.button   → Invite + Interrupt
save.spinner  → Progress
save.success  → Confirm
```

Three IdleWorker warnings are technically correct and practically unhelpful.

The better diagnosis is:

```text
these are three fragments of one action lifecycle
```

and, because the SDK already has a construction for that lifecycle:

```text
replace them with one Offer
```

`ConsolidationAdvisor` is the recommendation layer for this kind of finding.

### Behavioral mapping

The recommendation engine does not map raw job sets directly to component names.

Its current architecture is:

```text
observed facts and jobs
        ↓
BehavioralRole
        ↓
SDK construction
```

The current behavioral vocabulary includes:

```text
ActionSource
ProgressReporter
CompletionReporter
Interruptible
StatusReporter
IdentityCarrier
GateResolver
Locator
Navigator
GroupContainer
```

For example:

```text
ActionSource
+ ProgressReporter
+ CompletionReporter
+ Interruptible
→ Offer
```

The SDK-owned recipes currently describe `Offer`, `Form`, `Collection`, and `Places` through these roles. Raw-job matching remains as a compatibility fallback for custom recipes that have not adopted behavioral roles yet.

The next useful evidence is relationship-specific: for example, whether a progress reporter and completion reporter are actually reporting the **same Act**. That should be added only when the registry can state it, not guessed from names or proximity.

The capability catalog belongs to the SDK so it stays synchronized with the constructions Conveyance actually offers.

---

## 13. Compose binding

The Compose module provides the physical machinery that turns semantic relationships into rendered behavior.

Important primitives include:

### `Offer`

Offers an Act and keeps its lifecycle in one identity.

### `Collection`

Represents a collection of Subjects together with creation and recoverable removal behavior.

### `Form`

Coordinates related field/gate behavior as one form rather than a stack of unrelated controls and validation messages.

### `Places`

Hosts `Place` continuity and Return.

### Element registry

Maps semantic Element addresses to current geometry, derives live evidence for routing and audits, and resolves visible Act emphasis against the screen's Heroic claims.

### Stage / motion

Renders consequence travel and transformation using semantic signatures rather than application-supplied animation instructions.

These composables are not a component catalog in the ordinary sense. They are examples of recurring **behavioral structures** that the binding knows enough about to convey on the application's behalf.

---

## 14. What Conveyance deliberately does not prescribe

Conveyance does not require:

- monochrome interfaces;
- color to mean rank;
- one primary Element per surface;
- one root Place per product;
- only consequence motion and no decorative/personality motion;
- one visual aesthetic;
- quietness, cleanliness, or orderly composition;
- a particular Material component hierarchy;
- uniform corner radii;
- minimal visual density for its own sake.

It **does** define one Heroic Act per visible screen as the top of the Act hierarchy. That is a functional outline rule, not a visual-style rule and not an Employment rule.

Other restrictions may be useful inside a particular product's design system. They are not general consequences of the manifesto.

A framework rule earns its place when it creates generative pressure toward a more self-explanatory interface—not when it merely makes the design easier to classify.

---

## 15. Rule design standard

Before adding a new hard rule to Conveyance, ask:

1. **What invention does this pressure provoke?**
2. **Can the framework derive the answer instead of asking for another declaration?**
3. **What legitimate case does not participate in this rule?**
4. **Can that exception say what it is instead of merely suppressing enforcement?**
5. **Can Conscience distinguish deliberate exception from accidental violation?**
6. **Is this really a universal framework principle, or is it one product's taste?**

A strong rule with a truthful exception is better than permissive mush.

A strict-looking rule that only preserves order is worse than no rule at all.

---

## Appendix A — Core types

```text
Act
ActEmphasis
ActState
AuditElement
AuditFrame
BehavioralRole
Channel
Consequence
DeclaredElement
ElementId
Employment
Finding
Gate
Job
Label
Meaning
Outcome
Place
Product
Refusal
Scope
SubjectId
Surface
Verb
Weight
```

## Appendix B — Act emphasis

```text
Heroic      title-level Act; at most one visible per screen
Primary     leading Act
Secondary   next level
Tertiary    next level
Supporting  lower-level Act; demotion floor
```

## Appendix C — Employment jobs

```text
Invite
Report
Locate
Identify
Group
Separate
Progress
Confirm
Warn
Navigate
Interrupt
```

## Appendix D — Reference consequences

```text
Reveal
Enter
Create
Destroy
Alter
Send
```

## Appendix E — Documentation relationship

- [README / Manifesto](../README.md) — philosophy; authored source of truth.
- [Framework](CONVEYANCE-FRAMEWORK.md) — semantic model and SDK architecture.
- [Rules and opt-outs](RULES-AND-OPTOUTS.md) — compact rule-by-rule reference used by Conscience links.
- [Getting started](GETTING-STARTED.md) — practical first implementation.
