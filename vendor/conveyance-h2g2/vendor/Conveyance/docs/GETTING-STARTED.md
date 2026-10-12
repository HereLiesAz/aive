# Getting started

This page gets one real Conveyance interaction on screen without making you learn the entire framework first.

For philosophy, read the [manifesto](../README.md). For the semantic model and architecture, read the [framework spec](CONVEYANCE-FRAMEWORK.md). For individual rules, examples, ideas, and semantic opt-outs, see [Rules and their opt-outs](RULES-AND-OPTOUTS.md).

---

## 1. Add the SDK

Until published coordinates are available, the simplest route is to build against source:

```kotlin
// settings.gradle.kts
includeBuild("../Conveyance")
```

Or publish locally:

```bash
git clone https://github.com/HereLiesAz/Conveyance
cd Conveyance
./gradlew publishToMavenLocal
```

Then depend on the core model and Compose binding from your application.

---

## 2. Declare an Act

An Act is a semantic action, not a button.

```kotlin
val recipientField = ElementId("recipient.field")
val recipientAvatar = ElementId("recipient.avatar")

val send = Act.send(
    id = "invoice.send",
    subject = SubjectId("invoice.41"),
    to = recipientAvatar,
    requires = listOf(
        Gate(
            id = "recipient.chosen",
            livesAt = recipientField,
        ) { chosenRecipient != null },
    ),
    emphasis = ActEmphasis.Primary,
)
```

The Act already tells Conveyance:

- what kind of consequence this is;
- where it lands;
- what blocks it;
- whether it can be reversed;
- how broad its consequence is;
- how much functional emphasis the product intends for this Act.

From that the framework can derive state, verb, weight, motion grammar, routing evidence, and audit evidence.

---

## 3. Offer the Act

Wrap the surface in `ConveyanceHost`, then render the Act with `Offer`.

```kotlin
ConveyanceHost {
    Column {
        Offer(send) {
            Box(
                Modifier
                    .tell(owesTell, weight)
                    .clickable { engage() },
            ) {
                Text("Send")
            }
        }

        Offer(
            act = Act.alter(
                id = "recipient.choose",
                subject = SubjectId("recipient"),
                property = "recipient",
                target = recipientField,
            ) {
                chosenRecipient = Recipient("Mara")
                Outcome.Done
            },
            element = recipientField,
        ) {
            Box(Modifier.clickable { engage() }) {
                Text("Mara")
            }
        }
    }
}
```

Tap **Send** before choosing anyone. The Act is blocked by a Gate that already names where resolution lives. The binding can resist at the point of contact and escort toward the resolver instead of doing nothing.

Choose **Mara**, then Send again. The same offered element passes through the Act lifecycle rather than outsourcing progress and result to unrelated UI.

---

## 4. Use Act emphasis as a functional hierarchy

Every Act declares one emphasis level:

```kotlin
ActEmphasis.Heroic
ActEmphasis.Primary
ActEmphasis.Secondary
ActEmphasis.Tertiary
ActEmphasis.Supporting
```

Think of the visible Acts on a screen like an HTML outline:

```text
Heroic     ≈ page title
Primary    ≈ h1
Secondary  ≈ h2
Tertiary   ≈ h3
Supporting ≈ lower-level action
```

This hierarchy belongs to Acts: what they do for the interaction. It is not Employment and it is not UI state.

An Act keeps the level it declares:

```kotlin
act.emphasis
```

Compose resolves that declaration against the other visible Acts:

```kotlin
Offer(publish) {
    val treatment = MyTheme.actTreatment(resolvedEmphasis())
    PublishControl(treatment)
}
```

A screen may present at most one Heroic Act. If two or more visible Acts claim Heroic, there is no unique title. The entire visible Act outline resolves one level lower:

```text
Heroic     → Primary
Primary    → Secondary
Secondary  → Tertiary
Tertiary   → Supporting
Supporting → Supporting
```

Conscience reports this as `HeroOfTheHill` so the developer can choose the Act that actually owns the hill.

Conveyance does not define `Heroic = huge yellow button` or any other universal appearance. Compose already has theming and token machinery. Your theme decides how resolved Act emphasis becomes shape, typography, color, motion, space, haptics, sound, or surrounding response.

---

## 5. Put working elements to work

A working element must do at least four distinct jobs:

```kotlin
Employment.Working(
    Job.Invite,
    Job.Report,
    Job.Progress,
    Job.Interrupt,
)
```

Do not pad the set to satisfy the constructor. Reimagine the object until the jobs are real.

If an element is intentionally non-operational, say so:

```kotlin
Employment.Ambient
```

That is a semantic opt-out, not an enforcement bypass.

---

## 6. Let Conscience look at the whole surface

The live Compose registry can produce an `AuditFrame` containing element geometry, observed jobs, offered Acts, Gates, consequences, reversibility, and declared Act emphasis.

Conscience uses that evidence relationally.

If one element only does two jobs, it may produce:

```text
[Warning] IdleWorker at invoice
Found: invoice.send is doing 2 jobs
Try: Reimagine it until it honestly does four jobs. Enrich interface objects.
Examples, ideas, and opt-out: https://github.com/HereLiesAz/Conveyance/blob/main/docs/RULES-AND-OPTOUTS.md#employment
```

If several nearby under-employed elements collectively describe one richer object, `ConsolidationAdvisor` can recommend combining them instead of issuing repetitive individual warnings.

When the pattern matches a component the SDK already ships, the recommendation can name the replacement directly—for example `Offer`, `Form`, `Collection`, or `Places`.

If two or more visible Acts claim Heroic, Conscience reports `HeroOfTheHill`. The Acts themselves are not mutated; Compose simply resolves the screen's functional hierarchy one rung lower until one Act owns the Heroic slot.

---

## 7. Core constructions to know

### `Offer`

One Act lifecycle, one continuous identity.

### `Form`

A group of related fields and completion behavior treated as a coherent form rather than independent validation fragments.

### `Collection`

A repeating set of identified Subjects with creation and recovery behavior.

### `Places`

Continuity between a Place and the Element it came from, plus Return.

### `Gate`

A resolvable blocker that knows where resolution lives.

### `Employment.Ambient`

The explicit non-working opt-out from the four-job rule.

---

## 8. Reversible destruction

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

If reality genuinely provides no inverse, use the named exception:

```kotlin
Act.destroyIrreversibly(
    id = "submission.finalise",
    subject = submission,
    target = authority,
)
```

Do not weaken ordinary destruction just because an exception exists.

---

## 9. Continue from here

- [Manifesto](../README.md) — the authored philosophy.
- [Framework spec](CONVEYANCE-FRAMEWORK.md) — the semantic model and architecture.
- [Rules and opt-outs](RULES-AND-OPTOUTS.md) — the reference linked by linter findings.
- [Core README](../conveyance-core/README.md) — model-focused module guide.
- [Compose README](../conveyance-compose/README.md) — binding-focused module guide.
- [`convey`](https://github.com/HereLiesAz/convey) — a separate implementation of the manifesto.
- [`convey-web`](https://github.com/HereLiesAz/convey-web) — a separate web implementation.

The important habit is simple: when you find yourself adding an explanation, a detached status object, or another one-purpose control, ask whether the interface could demonstrate the relationship instead.
