package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.workflow.WorkflowRunFactory

/**
 * Birth (docs/Node-Creatures.md): where each creature is in being born, read only from the run.
 *
 * A creature is born the moment the engine releases its task (Ready or later). Until then only the
 * next generation is drawn, as an egg gestated by the creatures that feed it: a producer's downstream
 * egg has one gestator, a producer and its reviewer give the egg of whatever they feed two, and every
 * parallel feeder of one join gestates that join's egg together. Nothing here is invented: an egg
 * hatches exactly when `WorkflowRunFactory.refreshReadiness` releases its task.
 */
internal sealed interface CreatureBirth {
    /** The task is not born, and nothing that feeds it has begun: nothing is drawn. */
    data object Hidden : CreatureBirth

    /** The next generation: an egg that grows as its [gestators] work. */
    data class Egg(
        /** 0..1: the mean of the gestators' [EggGestator.contribution]. */
        val growth: Float,
        val gestators: List<EggGestator>,
    ) : CreatureBirth

    /** The egg's gestation failed, or the run ended before it hatched. */
    data object Withered : CreatureBirth
}

/** One creature feeding an egg, and how far along its share of the gestation is (0..1). */
internal data class EggGestator(val taskId: String, val contribution: Float)

/**
 * What each creature has contributed to an egg, by its task's status: a presentation mapping of
 * recorded states, so an egg moves only when the run records a change.
 */
internal fun gestationContribution(status: TaskRunStatus?): Float = when (status) {
    TaskRunStatus.Completed -> 1f
    TaskRunStatus.Verifying -> 0.8f
    TaskRunStatus.Running -> 0.5f
    TaskRunStatus.Planning, TaskRunStatus.Retrying,
    TaskRunStatus.AwaitingApproval, TaskRunStatus.Escalated -> 0.25f
    else -> 0f
}

/**
 * Every task that is not simply born, by task id. A task absent from the map is born and drawn as
 * itself, so an empty map (a hive being edited, or one that has never run) shows everything.
 */
internal fun projectBirths(
    definition: WorkflowDefinition,
    run: WorkflowRun,
    /** Build holds every creature in view for editing. */
    building: Boolean = false,
): Map<String, CreatureBirth> {
    val started = run.status != WorkflowRunStatus.Created ||
        run.taskRuns.values.any { it.status != TaskRunStatus.Created }
    if (building || !started) return emptyMap()

    val runEnded = run.status == WorkflowRunStatus.Failed || run.status == WorkflowRunStatus.Cancelled
    val tasksById = definition.tasks.associateBy { it.id.value }
    val births = linkedMapOf<String, CreatureBirth>()
    for (task in definition.tasks) {
        val feeders = task.feeders().filter { it in tasksById }
        if (feeders.isEmpty()) continue
        val taskRun = run.taskRuns[task.id]
        if (taskRun != null && taskRun.isBorn()) continue

        val gestatorRuns = feeders.map { it to run.taskRuns[tasksById.getValue(it).id] }
        val begun = gestatorRuns.filter { (_, fed) -> fed != null && fed.isBorn() }
        if (begun.isEmpty()) {
            births[task.id.value] = CreatureBirth.Hidden
            continue
        }
        // A branch not taken is skipped, not failed: it never shows.
        if (taskRun?.isSkippedBranch() == true) {
            births[task.id.value] = CreatureBirth.Hidden
            continue
        }
        val gestatorFailed = gestatorRuns.any { (_, fed) ->
            fed?.status == TaskRunStatus.Failed || (fed?.status == TaskRunStatus.Cancelled && !fed.isSkippedBranch())
        }
        births[task.id.value] = if (runEnded || gestatorFailed || taskRun?.status == TaskRunStatus.Cancelled) {
            CreatureBirth.Withered
        } else {
            val gestators = gestatorRuns.map { (id, fed) -> EggGestator(id, gestationContribution(fed?.status)) }
            CreatureBirth.Egg(growth = gestators.map { it.contribution }.average().toFloat(), gestators = gestators)
        }
    }
    return births
}

/** The tasks that gestate this one: what it depends on, and the task its condition waits on. */
private fun TaskDefinition.feeders(): List<String> {
    val conditionTask = when (val c = condition) {
        TaskCondition.Always -> null
        is TaskCondition.OnAnyOutcome -> c.ofTask
        is TaskCondition.OnFailure -> c.ofTask
    }
    return (dependsOn + listOfNotNull(conditionTask)).filter { it != id }.map { it.value }.distinct().sorted()
}

private fun TaskRun.isSkippedBranch(): Boolean =
    status == TaskRunStatus.Cancelled && blockingReason?.code == WorkflowRunFactory.CONDITION_NOT_MET

/**
 * Born once the engine has released it. Not born: not yet created, still waiting on what it depends
 * on (the engine always says so; a Blocked task that says nothing is read the same), or cancelled
 * before it ever started. A task held for another reason (a spent budget, a spend cap) was released
 * first, so it is born.
 */
private fun TaskRun.isBorn(): Boolean = when (status) {
    TaskRunStatus.Created -> false
    // Blocked with no recorded reason is waiting too; only a different reason (a spent budget, a spend cap) means released.
    TaskRunStatus.Blocked -> blockingReason?.code.let { it != null && it != WorkflowRunFactory.WAITING_FOR_DEPENDENCIES }
    TaskRunStatus.Cancelled -> hasStarted()
    else -> true
}

/** Any trace that a provider or executor took the task on: a cancelled task with none never hatched. */
private fun TaskRun.hasStarted(): Boolean =
    assignedProviderId != null || providerRunId != null || usage != null ||
        artifacts.isNotEmpty() || progress != null || attempt > 1
