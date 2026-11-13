package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.orchestration.OrchestrationPlan

/**
 * A sign that the hive is reached: closed, typed and observable in the run, never a model's opinion. The planner nominates
 * the signs it expects ([OrchestrationPlan.hiveSigns], as `done:step`, `artifact:step` or `nothing-failed`); the queen checks
 * them against what the run holds. With none nominated, the signs are that every step nothing depends on is done and nothing failed.
 */
sealed interface HiveSign {
    /** The step finished. */
    data class StepDone(val taskId: String) : HiveSign

    /** The step finished and left at least one artifact. */
    data class StepLeftArtifact(val taskId: String) : HiveSign

    /** No step failed, was escalated or was cancelled. */
    data object NothingFailed : HiveSign
}

/** One sign and whether the run shows it. */
data class HiveCheck(val sign: HiveSign, val met: Boolean)

object HiveSigns {
    const val MAX = 6
    private const val STEP_DONE = "done:"
    private const val STEP_ARTIFACT = "artifact:"
    private const val NOTHING_FAILED = "nothing-failed"

    /** Reads the planner's nominated signs: unknown words and steps that are not in [stepIds] are dropped, none repeated, at most [MAX]. */
    fun parse(raw: List<String>, stepIds: Set<String>): List<HiveSign> =
        raw.mapNotNull { text ->
            val word = text.trim()
            when {
                word.equals(NOTHING_FAILED, ignoreCase = true) -> HiveSign.NothingFailed
                word.startsWith(STEP_DONE, ignoreCase = true) -> word.substring(STEP_DONE.length).trim().takeIf { it in stepIds }?.let(HiveSign::StepDone)
                word.startsWith(STEP_ARTIFACT, ignoreCase = true) -> word.substring(STEP_ARTIFACT.length).trim().takeIf { it in stepIds }?.let(HiveSign::StepLeftArtifact)
                else -> null
            }
        }.distinct().take(MAX)

    /** The signs the planner nominated for [plan]; empty when it named none. */
    fun nominated(plan: OrchestrationPlan): List<HiveSign> = parse(plan.hiveSigns, plan.steps.map { it.id }.toSet())

    /** What counts when the planner named nothing: every step nothing depends on is done, and nothing failed. */
    fun defaultFor(definition: WorkflowDefinition): List<HiveSign> {
        val needed = definition.tasks.flatMap { task -> task.dependsOn.map { it.value } }.toSet()
        val last = definition.tasks.filter { it.id.value !in needed }.map { HiveSign.StepDone(it.id.value) }
        return (last + HiveSign.NothingFailed).take(MAX)
    }
}

private fun HiveSign.taskId(): String? = when (this) {
    is HiveSign.StepDone -> taskId
    is HiveSign.StepLeftArtifact -> taskId
    HiveSign.NothingFailed -> null
}

/**
 * Whether [run] shows each sign (pure). A sign that names a step the swarm no longer has, or one the run holds no state for,
 * cannot be seen, so it is left out (a repair may have replaced it); with none left, the default signs are checked the same way.
 */
fun checkHive(definition: WorkflowDefinition, run: WorkflowRun, signs: List<HiveSign> = emptyList()): List<HiveCheck> {
    val observable = definition.tasks.map { it.id.value }.filter { id -> run.taskRuns.keys.any { it.value == id } }.toSet()
    fun seen(sign: HiveSign): Boolean = sign.taskId()?.let { it in observable } != false
    val chosen = signs.filter { seen(it) }.ifEmpty { HiveSigns.defaultFor(definition).filter { seen(it) } }
    return chosen.map { sign ->
        val met = when (sign) {
            is HiveSign.StepDone -> run.taskRuns.values.any { it.taskDefinitionId.value == sign.taskId && it.status == TaskRunStatus.Completed }
            is HiveSign.StepLeftArtifact -> run.taskRuns.values.any {
                it.taskDefinitionId.value == sign.taskId && it.status == TaskRunStatus.Completed && it.artifacts.isNotEmpty()
            }
            HiveSign.NothingFailed -> run.taskRuns.values.none {
                it.status == TaskRunStatus.Failed || it.status == TaskRunStatus.Escalated || it.status == TaskRunStatus.Cancelled
            }
        }
        HiveCheck(sign, met)
    }
}

/**
 * The hive is reached (pure, observable): the run completed and every sign shows. A run that finishes with a sign unmet is
 * finished but not reached, and she says so (see [finishNote]).
 */
fun hiveReached(definition: WorkflowDefinition, run: WorkflowRun, signs: List<HiveSign> = emptyList()): Boolean =
    run.status == WorkflowRunStatus.Completed && checkHive(definition, run, signs).all { it.met }

/** The step to look at first when a sign is unmet: the sign's own step, or for nothing-failed the first step that failed. */
internal fun firstUnmetStep(definition: WorkflowDefinition, run: WorkflowRun, checks: List<HiveCheck>): TaskDefinition? {
    val unmet = checks.firstOrNull { !it.met } ?: return null
    val named = unmet.sign.taskId()
    if (named != null) return definition.tasks.firstOrNull { it.id.value == named }
    return definition.tasks.firstOrNull {
        run.taskRuns[it.id]?.status.let { s -> s == TaskRunStatus.Failed || s == TaskRunStatus.Escalated || s == TaskRunStatus.Cancelled }
    }
}
