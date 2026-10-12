# conveyance-auditor

The two rules no structural check can settle: *can a person predict what this control does before
touching it*, and *does the screen omit something they needed to know*. Both are answered by showing
the rendered surface to a viewer who has genuinely never seen it — this module is what arranges that
on demand, using an AI model instead of dragging a stranger into the room.

## The two-pass design

The viewer is kept naive **deliberately and structurally**. The first pass gets the screenshot and
nothing else — no element names, no act identifiers, no source. The moment it can read the code, it
stops predicting and starts reciting, and the measurement becomes worthless. Only on the second pass
does it see the truth — [`conveyance-compose`](../conveyance-compose/README.md)'s
`registry.auditFrame(...)` — used to grade what it already committed to, not to inform it.

```kotlin
val report: AuditReport = ConveyanceAuditor().audit(
    png = screenshot,
    frame = registry.auditFrame("gallery"),
)

println(report)   // "gallery [OpenAI/gpt-5]: 4 right, 0 wrong, 1 no idea; 1 omissions"
report.verdicts.filter { it.grade == Grade.Wrong }.forEach {
    println("MISLEADING ${it.element}: expected '${it.predicted}', actually '${it.actual}'")
}
report.omissions.forEach { println("OMITTED: $it") }
```

**Wrong is worse than NoIdea**, and the report keeps them separate on purpose: a person with no idea
proceeds carefully; a person who is confidently wrong proceeds, and the interface put them there.
`report.predictable` is `null`, not a fabricated `1.0`, when there's nothing to compute a fraction
over — an unaudited surface and a flawless one are never allowed to look the same.

## No API key required

[`Judges.detect()`](src/main/kotlin/com/hereliesaz/conveyance/auditor/Judge.kt) — the default —
tries, in order: a key you set explicitly (`ANTHROPIC_API_KEY`, or any of `OPENAI_API_KEY`,
`GEMINI_API_KEY`, `XAI_API_KEY`, `GROQ_API_KEY`, `MISTRAL_API_KEY`, `DEEPSEEK_API_KEY`,
`OPENROUTER_API_KEY`), then a model running locally with no key and no account at all:

```
ollama pull llama3.2-vision
```

The judge's identity is recorded on every report (`report.judge`), because a verdict from a small
local model and a verdict from a frontier one are not the same evidence and should never be filed as
though they were. **In practice, a small local model is not always strong enough to do this task
well** — grading it against a busy real screen, a 7B model has been observed to degenerate into
echoing the audit frame's own field names back as "omissions" instead of reasoning about the image. A
provider key gets you a judge actually capable of the reasoning this is asking for.

Point `Judges.detect()` anywhere else with `CONVEYANCE_JUDGE_URL` (an OpenAI-compatible endpoint),
`CONVEYANCE_JUDGE_MODEL`, and optionally `CONVEYANCE_JUDGE_KEY`.

## When there's nothing to reach

`ConveyanceAuditor(judge).audit(...)` throws `JudgeUnreachable` if the local fallback has no server
to connect to, or an `IOException` if a configured endpoint refuses the request. Both are meant to
propagate, not be swallowed — a check that silently no-ops in an environment without a key or a local
model is a check that teaches everyone to stop trusting the pipeline. Catch them explicitly and say so:

```kotlin
try {
    ConveyanceAuditor().audit(png, frame)
} catch (e: JudgeUnreachable) {
    println("AUDIT NOT RUN: ${e.message} — set a provider key, or `ollama pull llama3.2-vision`")
} catch (e: java.io.IOException) {
    println("AUDIT NOT RUN: judge could not be reached (${e.message})")
}
```

## Using it

Not on Maven Central — see [PUBLISHING.md](../docs/PUBLISHING.md) for what still blocks that and
who has to decide it. What does resolve today is JitPack, which is how every downstream repo in
this ecosystem already consumes `conveyance-core`/`conveyance-compose`:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// build.gradle.kts
dependencies {
    implementation("com.github.HereLiesAz.Conveyance:conveyance-auditor:main-SNAPSHOT")
}
```

The group is JitPack's own `com.github.<owner>.<repo>` form, not the `com.hereliesaz.conveyance`
group ID this build's POMs carry — JitPack rewrites it. `main-SNAPSHOT` tracks this repo's `main`
branch; there is no tagged release to pin to yet. Note that the `0.1.0` this build's `version`
is set to (root `build.gradle.kts`) is not a released version either, and disagrees with the
release version CI actually stamps from `version.properties`/`.version-state.json` (`0.0.12.1` at
the time of writing) — one more reason not to write a pinned number here.

Or skip the coordinate entirely and build against source, per the
[root quickstart](../docs/GETTING-STARTED.md): `includeBuild("../Conveyance")`, or
`./gradlew publishToMavenLocal` and depend on `com.hereliesaz.conveyance:conveyance-auditor`.
