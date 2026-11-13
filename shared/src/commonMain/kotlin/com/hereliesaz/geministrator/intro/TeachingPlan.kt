package com.hereliesaz.geministrator.intro

import com.hereliesaz.geministrator.tour.ExpectKind
import com.hereliesaz.geministrator.tour.TargetRow
import com.hereliesaz.geministrator.tour.TeachingLimits
import com.hereliesaz.geministrator.tour.Violation

/** One step of the plan the planner wrote: what the keeper learns, which real control, and the one thing observed. */
data class TeachingStep(val target: String, val kind: ExpectKind, val learns: String)

/**
 * Reads the planner's reply. A step is one line ending in `Target: <ID>. Checkpoint: <kind>.`; any other line (a title,
 * a note) is ignored. Nothing here knows an id: the floor checks them against the registry.
 */
fun parseTeachingPlan(text: String): List<TeachingStep> {
    val line = Regex("""^\s*(?:[-*]\s*)?(?:\d+[.)]\s*)?(.*?)\s*Target:\s*([A-Za-z0-9_]+)\s*\.?\s*Checkpoint:\s*([A-Za-z]+)\s*\.?\s*$""")
    return text.lines().mapNotNull { raw ->
        val m = line.matchEntire(raw.trim().trim('*', '`', '_')) ?: return@mapNotNull null
        val kind = ExpectKind.entries.firstOrNull { it.wire.equals(m.groupValues[3], ignoreCase = true) } ?: return@mapNotNull null
        TeachingStep(m.groupValues[2].uppercase(), kind, m.groupValues[1].trim().trimEnd('.', ' '))
    }
}

/**
 * The deterministic floor every plan must pass before a reviewer reads it. The rules check shape against the real
 * registry; they hold no list of what to teach and no order. T1 length, T2 real target and an observable checkpoint,
 * T3 one target per step, T4 the first step starts from something always on screen, T5 prerequisites come first,
 * T6 each step's note is short.
 */
object TeachingPlanFloor {
    /** The overlay itself observes only these two; others would wait for a follow creature that does not exist yet. */
    private val observedByTheLayer = setOf(ExpectKind.Tapped, ExpectKind.Opened)

    fun check(steps: List<TeachingStep>, rows: List<TargetRow>, scope: TutorialScope): List<Violation> = buildList {
        val (min, max) = when (scope) {
            TutorialScope.Broad -> TeachingLimits.BROAD_GOAL_MIN_STEPS to TeachingLimits.MAX_STEPS
            TutorialScope.Focused -> TeachingLimits.FOCUSED_MIN_STEPS to TeachingLimits.FOCUSED_MAX_STEPS
        }
        if (steps.size < min) add(Violation("T1", "The plan has ${steps.size} steps; this goal needs at least $min small ones."))
        if (steps.size > max) add(Violation("T1", "The plan has ${steps.size} steps; at most $max."))
        val byId = rows.associateBy { it.id }
        val seen = mutableSetOf<String>()
        steps.forEachIndexed { i, step ->
            val n = i + 1
            val row = byId[step.target]
            if (row == null) {
                add(Violation("T2", "Step $n: ${step.target} is not a listed target."))
                return@forEachIndexed
            }
            if (step.kind !in row.observable || step.kind !in observedByTheLayer) {
                add(Violation("T2", "Step $n: ${step.kind.wire} cannot be observed for ${step.target}; use tapped or opened where listed."))
            }
            if (!seen.add(step.target)) add(Violation("T3", "Step $n: ${step.target} was already taught; one target per step."))
            if (i == 0 && !row.alwaysVisible) add(Violation("T4", "Step 1 must start from a target that is always on screen."))
            val missing = row.needs.filter { it !in seen && byId[it]?.alwaysVisible != true }
            if (missing.isNotEmpty()) add(Violation("T5", "Step $n: ${missing.joinToString(", ")} must come before ${step.target}."))
            if (step.learns.split(Regex("\\s+")).count { it.isNotEmpty() } > TeachingLimits.STEP_OBJECTIVE_MAX_WORDS) {
                add(Violation("T6", "Step $n: the note is longer than ${TeachingLimits.STEP_OBJECTIVE_MAX_WORDS} words."))
            }
        }
    }
}

/** A reviewer's reading of a plan. */
sealed interface PlanVerdict {
    data object Approve : PlanVerdict
    data class Revise(val findings: List<String>) : PlanVerdict

    /** Nothing readable: the reviewer is not counted, and the floor stands alone. */
    data object Unreadable : PlanVerdict
}

private const val MAX_FINDINGS = 3

/** Reads `VERDICT: approve|revise` (first matching line) and up to three `- ` findings. */
fun parsePlanVerdict(reply: String): PlanVerdict {
    val lines = reply.lines().map { it.trim().trim('*', '`', '_') }.filter { it.isNotEmpty() }
    val verdict = lines.firstOrNull { it.startsWith("VERDICT:", ignoreCase = true) }?.substringAfter(':')?.trim()?.lowercase()
        ?: return PlanVerdict.Unreadable
    return when {
        verdict.startsWith("approve") -> PlanVerdict.Approve
        verdict.startsWith("revise") || verdict.startsWith("reject") -> {
            val findings = lines.filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim().take(200) }.take(MAX_FINDINGS)
            PlanVerdict.Revise(findings.ifEmpty { listOf("The reviewer turned the plan back without saying why; make it smaller and clearer.") })
        }
        else -> PlanVerdict.Unreadable
    }
}
