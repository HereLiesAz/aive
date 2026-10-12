# Conveyance rules and their opt-outs

Conveyance is deliberately demanding where pressure produces a better interface. A rule should make a designer reconsider what an object is, what an action teaches, or how several fragments could become one richer thing.

But a legitimate exception is not a reason to weaken a useful rule.

The rule and its exception belong together:

> **Strong generative rule → named semantic opt-out.**
>
> The opt-out must say what the thing **is instead**. `ignore = true`, suppression annotations, and vague "custom" switches are not Conveyance vocabulary.

When Conscience reports one of these rules, the build log stays short and links here for the reasoning, examples, ideas, and opt-out.

---

## Employment

### Rule — a working element does at least four jobs

`Employment.Working` requires at least four distinct `Job`s.

The number is not a cleanliness quota. It is there to make a one-purpose object uncomfortable enough that the designer asks a more useful question: **what else could this thing already be doing?**

A control that begins work can also report that work, carry its result, interrupt it, identify the subject, locate the person, become the destination of a consequence, or absorb another fragment of chrome that currently exists only to explain it.

```kotlin
Employment.Working(
    Job.Invite,
    Job.Report,
    Job.Progress,
    Job.Interrupt,
)
```

The declaration is not the only source of jobs. Anything the live semantic graph can prove should be credited automatically rather than making the developer repeat it. Today the Compose registry derives, among others:

- `Invite` when an Element actually offers an Act;
- `Identify` when it carries a travelling identity token;
- Gate-related `Invite` / `Locate` when it is a Gate resolver;
- `Receive` when an Act's consequence actually targets that Element.

`Job.Receive` is important because "this is where the consequence lands" is real work. An Element is defined by what it does, not by what somebody remembered to write in its Employment declaration.

The framework does **not** automatically credit jobs such as `Progress`, `Confirm`, or `Interrupt` merely because an Act exists. Those only count when the rendered object actually does them. Otherwise the four-job constraint could satisfy itself without provoking any design work.

Conscience reasons across the whole surface before complaining about isolated IdleWorkers. If three nearby two-job objects collectively describe one richer object, the useful finding is not three tickets. It is a consolidation suggestion. If that pattern matches something the SDK already offers, Conscience can name the replacement directly—for example `Offer`, `Form`, `Collection`, or `Places`.

### Opt-out — `Employment.Ambient`

Use `Employment.Ambient` when the thing is intentionally **not operational**: ground, texture, atmosphere, breathing room, ornament, illustration, or another presence whose purpose would be distorted by inventing fake jobs.

```kotlin
Employment.Ambient
```

`Ambient` does not mean "ignore the four-job rule." It says: **this is not a working element.**

---

## Trusting chrome

### Rule — chrome does not narrate obvious mechanics

`Label` rejects instructional filler such as `tap`, `click`, `press`, `swipe`, `drag`, `select`, `choose`, `please`, `simply`, and `just`.

The point is not banning words. The point is trusting the person. If an affordance only works after the interface says "tap here", the sentence is usually repairing a design failure in prose.

Labels may be strange, funny, terse, verbose, conversational, profane, poetic, or product-specific. Conveyance is not a copy editor.

### Opt-out — it is content, not `Label`

Documentation, user-authored text, narrative copy, necessary warnings, help, prose, and other material whose purpose is genuinely to be read are content. Do not model them as chrome merely because they appear on a screen.

The opt-out says what the text **is instead**: content.

---

## Continuity of place

### Rule — an entered place has an antecedent

A navigated place is made with `Place.from(...)`. Its origin is the element it grows out of and returns toward.

```kotlin
Place.from("invoice.detail", origin = invoiceRow)
```

This is not a demand for one visual transition style. It is a demand that navigation preserve a relationship the person can learn instead of teleporting them and rebuilding the lost map with breadcrumbs.

### Opt-out — `Place.root(...)`

Use `Place.root(...)` when there genuinely is no visual antecedent: an application entry point, restored entry, external/deep-link entry, or another true beginning.

```kotlin
Place.root("home")
```

There is no universal root quota. A product can have several genuine beginnings. `Root` says **this journey begins here**; it is not continuity with enforcement switched off.

---

## Gates

### Rule — a resolvable blocker knows where resolution lives

A `Gate` carries `livesAt`. When an Act is blocked, the interface can escort the person toward something they can actually do instead of greying out the control and abandoning them.

```kotlin
Gate("recipient.chosen", livesAt = recipientField) { recipient != null }
```

Conscience reports a Gate whose declared resolver is absent because the model promised a route that the rendered surface did not provide.

### Opt-out — do not model an unresolvable fact as a Gate

If there is nothing the person can currently do to satisfy the condition, it is not a resolvable Gate. Represent it as status, content, environmental fact, or another non-inviting thing instead.

The exception is not a Gate with a fake address. It says: **there is no available act to escort to.**

---

## Destruction

### Rule — destruction is reversible whenever reality permits it

Use `Act.destroy(...)`; its inverse is mandatory.

```kotlin
Act.destroy(
    id = "document.delete",
    subject = document,
    target = collection,
    inverse = restore,
)
```

The pressure is intentional. Before introducing confirmation friction, look for undo, recovery, staging, a Ghost, delayed commitment, or another construction that lets the person act without being treated as a likely mistake.

> **"Ghost", in this spec, means one thing only: the reversible residue a destroyed subject leaves
> behind, in the place it was.** That is what `Ghosts`/`Residue`
> (`conveyance-compose/src/commonMain/kotlin/com/hereliesaz/conveyance/compose/Ghosts.kt`, held by
> the collection surface in `Collection.kt`) implement, and it is the mechanism that makes a
> confirmation dialog unnecessary.
>
> Do not confuse it with `ConveyWeight.Ghost` in the downstream
> [`convey`](https://github.com/HereLiesAz/convey) library (a separate Kotlin Multiplatform design
> system built on this Manifesto). There, `Ghost` is the lowest tier of a four-step *visual
> hierarchy* vocabulary (Hero / Primary / Secondary / Ghost) — a decorative, inert element that
> carries no emphasis. It has nothing to do with destruction, reversal, or residue. The name
> collision is coincidental and lives in a downstream implementation this spec does not own;
> `convey` names its own destruction-residue mechanism `ConveyReversal` precisely because
> `ConveyWeight.Ghost` had already taken the word. Anyone extending this spec should keep reading
> "Ghost" here as destruction-residue, and qualify the word explicitly whenever both ecosystems are
> in scope.

### Opt-out — `Act.destroyIrreversibly(...)`

When the actual consequence has no meaningful inverse, say so explicitly.

```kotlin
Act.destroyIrreversibly(
    id = "submission.finalise",
    subject = submission,
    target = destination,
)
```

Examples include legal submissions, physical effects, or external operations the product cannot restore. The separate factory keeps the exception visible to weight, audits, and bindings without weakening normal destruction.

---

## Act emphasis

### Rule — the Act hierarchy has one title

Every Act declares a functional emphasis level:

```kotlin
ActEmphasis.Heroic
ActEmphasis.Primary
ActEmphasis.Secondary
ActEmphasis.Tertiary
ActEmphasis.Supporting
```

This is an **Act hierarchy**, not Employment and not UI state. It says how much functional prominence an Act deserves relative to the other Acts on the current screen.

The closest analogy is an HTML document outline:

```text
Heroic      ≈ title
Primary     ≈ h1
Secondary   ≈ h2
Tertiary    ≈ h3
Supporting  ≈ lower-order action
```

A screen may present **at most one Heroic Act**.

If zero or one visible Act claims Heroic, every Act keeps its declared level.

If two or more visible Acts claim Heroic, the screen resolves the entire hierarchy one rung lower:

```text
Heroic      → Primary
Primary     → Secondary
Secondary   → Tertiary
Tertiary    → Supporting
Supporting  → Supporting
```

That demotion is not punishment and it does not mutate the Acts. It is a pure consequence of there being no unique title. Two things cannot both occupy the single highest place in the outline, so neither is rendered as the hero moment and every lower relationship shifts with them.

```kotlin
val publish = Act.send(
    id = "release.publish",
    subject = release,
    to = store,
    emphasis = ActEmphasis.Heroic,
)
```

In Compose:

```kotlin
act.emphasis             // declared functional level
scope.resolvedEmphasis() // level after current-screen hierarchy is resolved
```

A theme may map those levels onto whatever combination of typography, shape, motion, space, colour, haptics, sound, or surrounding response belongs to that product. Conveyance defines the hierarchy, not the costume.

### Opt-out — choose the level that actually describes the Act

There is no `allowTwoHeroes` switch because that would make Heroic stop meaning "the hero moment."

If two Acts genuinely have equal importance on the same screen, declare them both `Primary`. If one is the hero, declare one `Heroic` and the other at the appropriate lower level.

Conscience reports competing Heroic declarations and points here; the runtime derives the demoted presentation until the declarations are resolved.

---

## Consequence motion

### Rule — motion that communicates consequence must tell the truth

A Conveyance consequence has a grammar-derived `Signature`. When motion is being used to teach Reveal, Enter, Create, Destroy, Alter, Send, Refuse, Yield, or Return, repeated use should remain learnable enough that the person can predict what is happening.

The stronger rule is not "only these animations may exist." It is:

> **Do not teach one consequence with a motion and then reuse that learned motion to mean something contradictory.**

Motion should also remain truthful when reality changes halfway through it. An interrupted or retargeted act should respond to the new state rather than finishing an obsolete canned animation first.

### Opt-out — motion that is saying something else

Ambient drift, stable identity motion, role personality, illustration, simulation, data animation, decorative life, and other movement whose job is **not to describe a consequence** are outside consequence grammar.

Name that different purpose in the product or binding. Do not lie by assigning a consequence verb merely to obtain an animation.

---

## Visual channels

### Rule — semantic visual language should be learnable

Hue, chroma, shape, size, elevation, opacity, typography, density, motion, haptics, and sound do not have universal meanings built into nature or Compose.

Meaning comes from the grammar a product establishes through repeated use.

Conveyance ships a reference `Channel` mapping because examples and audits need a vocabulary. It is not a tailoring specification. A product may replace that mapping with another coherent grammar.

H2G2-style identity hues are a canonical example: hue answers **who/what is this?**, while another channel can carry state, importance, or consequence.

The useful question is not "are all shapes consistent?" but **"is this inconsistency doing work?"** Visual tension, clash, irregular composition, and deliberate maximalism are valid when the contrast communicates rather than merely accumulates.

### Opt-out — the channel is carrying another named purpose

A visual property may be carrying identity, content, atmosphere, brand language, illustration, personality, simulation, or another product-defined grammar.

The opt-out is not "randomness is allowed." It says: **this channel is intentionally saying something else.**

Accessibility requirements still apply regardless of the chosen grammar.

---

## One Element

### Rule — invitation, progress, result, and failure keep one identity

An Act should not become a button plus unrelated spinner plus unrelated toast plus unrelated error banner. The person should be able to follow one thing through engagement and consequence.

The rule is about continuity of identity, not forcing every durable process to remain physically inside the original pixels forever.

### Opt-out — the action genuinely creates a durable process

A build, import, render, deployment, workflow, sync, or other long-running operation may become a subject of its own.

When that happens, give the process its **own stable identity** and make the handoff visible. The exception is not "show a global spinner"; it says **this action created a new thing whose state now belongs to that thing.**

---

## Dynamic Conscience recommendations

Conscience should prefer relational findings over isolated findings whenever it has enough evidence.

Given:

```text
save.button   → Invite + Interrupt
save.spinner  → Progress
save.success  → Confirm
```

three separate IdleWorker warnings are less useful than recognizing one fragmented action lifecycle—but only when the framework can establish that all three fragments really belong to Save.

The runtime registry already knows element jobs, offered Acts, Gates, geometry, consequence targets, reversibility, declared Act emphasis, and now **Act lifecycle membership** for named Elements composed inside an `Offer` scope. `ConsolidationAdvisor` turns that evidence into behavioral roles and compares the result with an SDK-owned recipe catalog.

The current architecture is:

```text
observed jobs + semantic graph facts + proven composition relations
        ↓
BehavioralRole + known relations
        ↓
available Conveyance construction
```

Current roles include `ActionSource`, `ProgressReporter`, `CompletionReporter`, `Interruptible`, `StatusReporter`, `IdentityCarrier`, `GateResolver`, `Destination`, `Locator`, `Navigator`, and `GroupContainer`.

For an `Offer` recommendation, the relation matters as much as the roles:

```text
ActionSource(save)
+ ProgressReporter(lifecycleAct = save)
+ CompletionReporter(lifecycleAct = save)
+ Interruptible(save)
→ one proven action lifecycle
→ Offer
```

The superficially similar case does **not** qualify:

```text
ActionSource(save)
+ nearby ProgressReporter(unknown lifecycle)
+ nearby CompletionReporter(unknown lifecycle)
↛ Offer
```

Likewise, two distinct offered Acts are never collapsed into one `Offer` lifecycle merely because their combined jobs happen to fill the recipe.

Lifecycle membership is inferred from composition where Compose can actually observe it: a named Element rendered inside an `Offer`'s `ActScope` carries that Act's lifecycle identity into `AuditFrame`. The developer does not write `forAct = save` simply to restate a relationship the composition already proves.

There is still an intentional boundary. A detached reporter outside that scope may in reality observe Save through application state, but Conveyance does not claim that relation unless it has evidence. The linter should become smarter by learning more truth, not by becoming more confident at guessing.

Conveyance also derives `Destination` from actual consequence target addresses, not from names such as `result`, `output`, or `container`.

The catalog belongs to the SDK, not to a pile of linter folklore, so recommendations evolve with the components Conveyance actually ships.

---

## The meta-rule

Every hard Conveyance requirement should satisfy one of two conditions:

1. **No legitimate exception exists because violating it makes the model internally incoherent**, or
2. **A named semantic opt-out is documented directly beside it.**

When a new rule is proposed, design its exception at the same time. If the only escape hatch is "ignore Conveyance here", the vocabulary is unfinished.

When an opt-out is proposed, it should answer **what the thing is instead**. If it merely disables enforcement, it is a suppression switch and should be treated with suspicion.
