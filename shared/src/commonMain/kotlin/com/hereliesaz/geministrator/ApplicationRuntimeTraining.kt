package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.HallMonitorRole
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.activeRoles
import com.hereliesaz.geministrator.domain.resolveRoleCollection
import com.hereliesaz.geministrator.persistence.RepositoryWorkflowEventSink
import com.hereliesaz.geministrator.queen.AdoptionResult
import com.hereliesaz.geministrator.queen.TRAINING_LESSONS_ID
import com.hereliesaz.geministrator.queen.TrainingRoles
import com.hereliesaz.geministrator.queen.adoptSpecialist
import com.hereliesaz.geministrator.queen.defaultTrainingLimits
import com.hereliesaz.geministrator.queen.parseLessons
import com.hereliesaz.geministrator.queen.parseTrainerLimit
import com.hereliesaz.geministrator.queen.tightenLimits
import com.hereliesaz.geministrator.queen.trainingApproval
import com.hereliesaz.geministrator.queen.trainingAdoption
import com.hereliesaz.geministrator.queen.trainingDefinition
import com.hereliesaz.geministrator.queen.trainingStepText
import com.hereliesaz.geministrator.workflow.WorkflowDefinitionPreparer
import com.hereliesaz.geministrator.workflow.WorkflowLaunchService
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Starts one round of the training swarm ([trainingDefinition]) as an ordinary run in the project of [sourceRunId] (the run the
 * keeper asked from, or the one the queen suggested it for). It is a normal persisted run: the keeper's spend caps and each
 * creature's time allowance apply, and the keeper can open it and stop it. A refine is another round, launched with [round],
 * [findings] and the earlier run as its parent, so lineage keeps the rounds together. Null when it cannot start (no such run or
 * project, a missing generic role, a blank domain, or a launch that fails).
 */
@OptIn(ExperimentalTime::class)
internal suspend fun ApplicationRuntime.launchTrainingSwarm(
    sourceRunId: String,
    domain: String,
    parentRoleId: String,
    round: Int = 1,
    findings: List<String> = emptyList(),
    trainerLimit: com.hereliesaz.geministrator.queen.TrainerLimit? = null,
    /** The keeper's hourly spend cap, when set: the swarm's own cap is never looser. */
    keeperSpendCapUsd: Double? = null,
): String? {
    val source = persistence.runs.get(WorkflowRunId(sourceRunId)) ?: return null
    val project = persistence.projects.get(source.projectId) ?: return null
    val roles = activeRoles(resolveRoleCollection(persistence.roles.all())).filter { it.enabled }
    fun role(id: RoleDefinitionId) = roles.firstOrNull { it.id == id }
    val parent = role(RoleDefinitionId(parentRoleId)) ?: return null
    val training = TrainingRoles(
        planner = role(BuiltInRoles.Planner.id) ?: return null,
        researcher = role(BuiltInRoles.Researcher.id) ?: return null,
        trainer = role(BuiltInRoles.Trainer.id) ?: return null,
        reviewer = role(BuiltInRoles.Reviewer.id) ?: return null,
        antagonist = role(BuiltInRoles.Antagonist.id) ?: return null,
        nanny = role(HallMonitorRole.id) ?: return null,
        queen = role(BuiltInRoles.Orchestrator.id) ?: return null,
    )
    val limits = tightenLimits(defaultTrainingLimits(keeperSpendCapUsd), trainerLimit)
    val now = Clock.System.now().toEpochMilliseconds()
    val definition = trainingDefinition(
        WorkflowDefinitionId("train-$now"), domain, parent, training, limits, round, findings,
    ) ?: return null
    val runId = WorkflowRunId("train-run-$now")
    val used = listOf(parent, training.planner, training.researcher, training.trainer, training.reviewer, training.antagonist, training.nanny, training.queen)
        .distinctBy { it.id }
    return try {
        WorkflowLaunchService(
            preparer = WorkflowDefinitionPreparer(providerRegistry, used),
            persistence = persistence,
            eventSink = RepositoryWorkflowEventSink(persistence.events),
            roles = used,
        ).launch(
            project = project,
            definition = definition,
            workflowRunId = runId,
            objective = "Train a specialist ${parent.name} for ${domain.replace('\n', ' ').trim().take(80)}",
            nowEpochMillis = now,
            taskRunIdFactory = { id -> TaskRunId("${runId.value}-${id.value}") },
            parentWorkflowRunIds = listOfNotNull(source.id),
        )
        loadLatest()
        runId.value
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

/** The limit the trainer stated in its step of [run], or none. */
internal fun trainerLimitOf(run: com.hereliesaz.geministrator.domain.WorkflowRun) =
    trainingStepText(run, TRAINING_LESSONS_ID)?.let(::parseTrainerLimit)

/**
 * Adopts the specialist a finished training run offered, only through the queen's gate ([adoptSpecialist]): the keeper's recorded
 * approval of exactly this specialist (the approved offer step in Messages) is required, the generic role is never edited, and the
 * variant is a new role with the generic role as its parent and the run that hatched it recorded. [bankLessons] is the seam to the
 * role's memory bank: it must store the lessons (each with its source and date) and say whether it did. Until the memory layer
 * can bank lessons per role it has no default, and without it nothing is adopted.
 */
@OptIn(ExperimentalTime::class)
internal suspend fun ApplicationRuntime.adoptTrainedSpecialist(
    trainingRunId: String,
    parentRoleId: String,
    domain: String,
    bankLessons: (suspend (RoleDefinitionId, List<String>) -> Boolean)? = null,
): AdoptionResult? {
    val run = persistence.runs.get(WorkflowRunId(trainingRunId)) ?: return null
    val lessons = trainingStepText(run, TRAINING_LESSONS_ID)?.let(::parseLessons).orEmpty()
    val adoption = trainingAdoption(parentRoleId, domain, lessons) ?: return null
    val approval = trainingApproval(run, adoption, Clock.System.now().toEpochMilliseconds())
    return adoptSpecialist(adoption, approval) { chosen ->
        val parent = persistence.roles.get(RoleDefinitionId(chosen.parentRoleId)) ?: BuiltInRoles.all.firstOrNull { it.id.value == chosen.parentRoleId }
        val id = RoleDefinitionId(chosen.roleId)
        if (bankLessons == null || parent == null || persistence.roles.get(id) != null) {
            false
        } else if (!bankLessons(id, chosen.lessons)) {
            false
        } else {
            persistence.roles.put(
                parent.copy(id = id, name = "${parent.name} · ${domain.trim().take(40)}", enabled = true, parents = listOf(parent.id), hatchedFromRunId = trainingRunId),
            )
            true
        }
    }
}
