# conveyance-core

Pure Kotlin. No UI toolkit. This module is the semantic model that a rendered interface agrees with.

If you want Compose components, see [`conveyance-compose`](../conveyance-compose/README.md). If you want the philosophy, read the [manifesto](../README.md). The [framework spec](../docs/CONVEYANCE-FRAMEWORK.md) explains how the SDK translates that philosophy into code.

---

## Act

Everything a person can do is an `Act`, made through one of the consequence factories:

```kotlin
val send = Act.send(
    id = "invoice.send",
    subject = SubjectId("invoice.41"),
    to = recipientAvatar,
    requires = listOf(
        Gate(
            id = "recipient.chosen",
            livesAt = recipientField,
        ) { recipient != null },
    ),
    emphasis = ActEmphasis.Primary,
)
```

An Act carries facts the framework can reason from:

- consequence;
- target;
- scope;
- Gates;
- inverse where one exists;
- functional emphasis.

The framework derives:

- `act.verb`;
- `act.signature`;
- `act.weight`;
- `act.reversible`;
- `act.state()`.

The rule is simple: if the model already knows the answer, do not make the developer type it again.

---

## Act emphasis

Every Act declares an `ActEmphasis` token:

```text
Heroic
Primary
Secondary
Tertiary
Supporting
```

This is an **Act hierarchy**. It describes how important the Act's function is relative to the other Acts on the screen. It is not Employment, element rank, or UI state.

The hierarchy is analogous to a document outline: Heroic is the title-level Act, then Primary, Secondary, Tertiary, and Supporting descend through the outline.

A screen may present at most one Heroic Act. If two or more visible Acts claim Heroic, the declarations remain intact but the presented hierarchy resolves one rung lower:

```text
Heroic     → Primary
Primary    → Secondary
Secondary  → Tertiary
Tertiary   → Supporting
Supporting → Supporting
```

Conscience reports that conflict as `HeroOfTheHill`.

The token does not prescribe appearance. A binding or product theme decides how resolved emphasis becomes shape, typography, color, motion, space, haptics, sound, or surrounding response.

There is no keystone boolean and no product-level keystone list.

---

## Gates

A `Gate` is a resolvable blocker, not just a boolean.

```kotlin
Gate(
    id = "recipient.chosen",
    livesAt = recipientField,
) { recipient != null }
```

`livesAt` is required because a blocked Act should be able to lead toward something useful.

If no action currently available to the person can resolve the condition, it is not a Gate. Model it as status, content, environmental fact, or another non-inviting thing instead.

---

## Places

A Place reached from an Element preserves its antecedent:

```kotlin
Place.from("invoice.detail", origin = invoiceRow)
```

A real beginning is explicit:

```kotlin
Place.root("home")
```

There is no universal root budget.

---

## Destruction and reversibility

Ordinary destruction requires an inverse:

```kotlin
Act.destroy(
    id = "document.delete",
    subject = document,
    target = collection,
    inverse = restore,
)
```

If the world genuinely provides no inverse, use the named semantic exception:

```kotlin
Act.destroyIrreversibly(
    id = "submission.finalise",
    subject = submission,
    target = authority,
)
```

The exception is separate so ordinary deletion stays strongly reversible by default.

---

## Employment

`Employment.Working` requires at least four distinct `Job`s.

That is a generative constraint. Do not pad the declaration. Reimagine the object until the jobs are real.

```kotlin
Employment.Working(
    Job.Invite,
    Job.Report,
    Job.Progress,
    Job.Interrupt,
)
```

For intentionally non-operational composition:

```kotlin
Employment.Ambient
```

`Ambient` is a semantic opt-out: it says the thing is not trying to be a working element.

Employment answers **why an element is here**. Act emphasis answers **what an Act does for the interaction**. Neither is derived from the other.

---

## Channels

`Channel` and `Meaning` provide a reference visual-language vocabulary for examples and analysis.

They are not a universal aesthetic constitution.

The reference mapping currently allows hue to carry stable visual identity rather than semantic rank. Products may establish another coherent grammar. The important property is learnability: repeated semantic use should not casually contradict itself.

See [Rules and their opt-outs](../docs/RULES-AND-OPTOUTS.md#visual-channels).

---

## Conscience

`Conscience` is the design-analysis layer.

It returns `Finding`s rather than throwing for judgments that require context across a surface. The linter output is intentionally compact and links directly to the relevant rule documentation.

Current live reasoning includes:

- IdleWorker detection;
- dynamic consolidation of related under-employed fragments;
- direct SDK replacement suggestions when a cluster matches a known construction;
- dead Gate resolvers;
- `HeroOfTheHill` when two or more visible Acts claim the single Heroic slot.

The important shift is relational: Conscience should prefer "these three fragments are really one missing object" over three independent warnings whenever it has enough evidence.

---

## Construction-time invariants

Some rules do not need a linter because the type system can state them completely:

| Rule | Representation |
|---|---|
| Working elements do at least four jobs | `Employment.Working` constructor |
| A resolvable Gate has an address | `Gate.livesAt` |
| Entered Places have antecedents | `Place.from(...)`; root Places cannot be entered |
| Ordinary destruction has an inverse | `Act.destroy(...)` requires `inverse` |
| Truly irreversible destruction is explicit | `Act.destroyIrreversibly(...)` |
| Chrome does not narrate obvious mechanics | `Label` constructor |

Rules whose correctness depends on relationships belong in Conscience rather than arbitrary constructor counts.

---

## Also here

- `Weight` — derives felt consequence magnitude.
- `Grammar` / `Signature` — reference consequence-motion grammar.
- `Route` — finds useful Gate-resolution paths from the semantic graph.
- `Practice` — tracks familiarity with Acts.
- `Reversal` — recovery timing and residue behavior.
- `AuditFrame` / `AuditElement` / `Census` — semantic evidence for runtime analysis.
- `ConsolidationAdvisor` — whole-surface recommendation layer for under-employed clusters.

---

## Rules and opt-outs

Every strong framework rule should either have no coherent exception or place its named semantic opt-out directly beside it.

See [Rules and their opt-outs](../docs/RULES-AND-OPTOUTS.md).
