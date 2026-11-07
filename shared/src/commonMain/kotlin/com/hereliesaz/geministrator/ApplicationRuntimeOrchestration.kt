package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.activeRoles
import com.hereliesaz.geministrator.domain.normalized
import com.hereliesaz.geministrator.domain.resolveRoleCollection
import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRuntime
import com.hereliesaz.geministrator.orchestration.OrchestrationPacket
import com.hereliesaz.geministrator.orchestration.OrchestrationRole
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.orchestration.OrchestrationPlan
import com.hereliesaz.geministrator.orchestration.PlanDecision
import com.hereliesaz.geministrator.orchestration.PlanningPhase
import com.hereliesaz.geministrator.orchestration.PlanningStage
import com.hereliesaz.geministrator.orchestration.PlanningVerdict
import com.hereliesaz.geministrator.orchestration.firstStep
import com.hereliesaz.geministrator.orchestration.reportPlanningStage
import com.hereliesaz.geministrator.orchestration.PlanReviewer
import com.hereliesaz.geministrator.orchestration.planReviewNotes
import com.hereliesaz.geministrator.orchestration.planReviewerRole
import com.hereliesaz.geministrator.orchestration.reviewUntilApproved
import com.hereliesaz.geministrator.orchestration.reportLaunchProgress
import com.hereliesaz.geministrator.persistence.RepositoryWorkflowEventSink
import com.hereliesaz.geministrator.workflow.OrchestratedWorkflowFactory
import com.hereliesaz.geministrator.workflow.WorkflowDefinitionPreparer
import com.hereliesaz.geministrator.workflow.WorkflowLaunchService
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
suspend fun ApplicationRuntime.launchOrchestratedWorkflow(
    projectName: String,
    objective: String,
    orchestrationRuntime: OrchestrationAgentRuntime,
    repository: RepositoryRef? = null,
    existingProject: Project? = null,
    /** Runs the new run continues (one) or merges (several); empty starts a new root lineage. */
    continuesWorkflowRunIds: List<WorkflowRunId> = emptyList(),
    /**
     * Reads the planner's plan before a run exists and sends it back for rework until it approves (see
     * [reviewUntilApproved]). It reviews only together with [confirmPlan]: the keeper always has the final say.
     */
    planReviewer: PlanReviewer? = null,
    /** Shows the keeper the reviewed plan and the reviewer's reasons; true starts the run, false does not. */
    confirmPlan: (suspend (PlanDecision) -> Boolean)? = null,
) {
    val parents = requireKnownParentRuns(continuesWorkflowRunIds)
    val cleanProjectName = projectName.trim()
    val cleanObjective = objective.trim()
    require(cleanProjectName.isNotEmpty()) { "Project name is required" }
    require(cleanObjective.isNotEmpty()) { "Objective is required" }
    reportLaunchProgress("Reading your objective…")

    parseHallMonitorTrialLaunchObjective(cleanObjective)?.let { trial ->
        testHallMonitorSolution(
            findingId = trial.findingId,
            solutionIndex = trial.solutionIndex,
            orchestrationRuntime = orchestrationRuntime,
        )
        return
    }

    val normalizedRepository = repository?.normalized()
    val now = Clock.System.now().toEpochMilliseconds()
    val project = existingProject?.copy(
        name = cleanProjectName,
        repository = normalizedRepository ?: existingProject.repository,
        updatedAtEpochMillis = now,
    ) ?: Project(
        id = ProjectId("project-$now"),
        name = cleanProjectName,
        repository = normalizedRepository,
        createdAtEpochMillis = now,
        updatedAtEpochMillis = now,
    )

    reportLaunchProgress("Gathering available agent roles…")
    val launchRoles = activeRoles(resolveRoleCollection(persistence.roles.all()))
    // Offer the planner only roles a linked provider can staff: a step for any other role could
    // never be dispatched. With nothing staffable, keep them all so the failure names the gap.
    val plannerRoles = launchRoles
        .filter { role -> providerRegistry.canStaff(role.capabilitiesRequired, normalizedRepository) }
        .ifEmpty { launchRoles }
    val packet = OrchestrationPacket(
        objective = cleanObjective,
        currentState = "new-workflow",
        availableAgents = plannerRoles.map { role ->
            OrchestrationRole(
                id = role.id.value,
                name = role.name,
                authorities = role.authorities.map { it.name }.sorted(),
            )
        },
        instruction = "Create the minimal executable dependency-correct workflow DAG needed to satisfy the objective.",
    )
    reportLaunchProgress("Asking the planner for a workflow (${plannerRoles.size} roles available)…")
    // Only a launch that will be reviewed has a planning field: the planner's egg, the reviewer who hatches from it.
    val review = reviewRole(planReviewer, confirmPlan, launchRoles)
    val planner = BuiltInRoles.Orchestrator
    fun stage(phase: PlanningPhase, plan: OrchestrationPlan?, round: Int, reviewerRole: RoleDefinition, verdict: PlanningVerdict? = null): PlanningStage {
        val first = plan?.firstStep()
        return PlanningStage(
            phase = phase,
            planner = launchRoles.firstOrNull { it.id == planner.id } ?: planner,
            reviewer = reviewerRole,
            firstStepRole = first?.let { step -> launchRoles.firstOrNull { it.id.value == step.roleId } },
            firstStepName = first?.name,
            round = round,
            verdict = verdict,
        )
    }
    review?.let { (_, role) -> reportPlanningStage(stage(PlanningPhase.Planning, null, 0, role)) }
    val firstPlan = orchestrationRuntime.plan(packet)
    var plan = firstPlan
    review?.let { (reviewer, role) ->
        val outcome = reviewUntilApproved(
            packet = packet,
            firstPlan = firstPlan,
            planner = orchestrationRuntime,
            reviewer = reviewer,
            reviewerName = role.name,
            instructions = role.instructions,
            pinnedProviderId = role.preferredProviderId?.value,
            onStage = { phase, current, round -> reportPlanningStage(stage(phase, current, round, role)) },
        )
        plan = outcome.plan
        val verdict = when {
            outcome.problem != null -> PlanningVerdict.Unreachable
            outcome.approved -> PlanningVerdict.Approved
            else -> PlanningVerdict.NotApproved
        }
        reportPlanningStage(stage(PlanningPhase.AwaitingKeeper, plan, outcome.rounds.size, role, verdict))
        reportLaunchProgress("Waiting for your decision on the plan…")
        if (!checkNotNull(confirmPlan)(PlanDecision(cleanObjective, role.name, outcome, packet.availableAgents.associate { it.id to it.name }))) {
            reportLaunchProgress("The plan was not started")
            return
        }
        reportPlanningStage(stage(PlanningPhase.Started, plan, outcome.rounds.size, role))
    }
    reportLaunchProgress("Planner returned ${plan.steps.size} step${if (plan.steps.size == 1) "" else "s"}")
    reportLaunchProgress("Assembling workflow…")
    val definition = OrchestratedWorkflowFactory.create(
        id = WorkflowDefinitionId("workflow-$now"),
        objective = cleanObjective,
        plan = plan,
        packet = packet,
        roles = launchRoles,
    )
    val launchService = WorkflowLaunchService(
        preparer = WorkflowDefinitionPreparer(providerRegistry, launchRoles),
        persistence = persistence,
        eventSink = RepositoryWorkflowEventSink(persistence.events),
        roles = launchRoles,
    )
    reportLaunchProgress("Saving project and starting run…")
    launchService.launch(
        project = project,
        definition = definition,
        workflowRunId = WorkflowRunId("run-$now"),
        objective = cleanObjective,
        nowEpochMillis = now,
        taskRunIdFactory = { id -> TaskRunId("run-$now-${id.value}") },
        parentWorkflowRunIds = parents,
    )
    reportLaunchProgress("Loading the new run…")
    loadLatest()
}

/**
 * Asks the planner to propose a swarm for [prompt] and returns it for the keeper to edit. A draft
 * only proposes: no project, run or repository is created or read, and nothing is saved here.
 */
internal suspend fun ApplicationRuntime.draftSwarm(
    prompt: String,
    orchestrationRuntime: OrchestrationAgentRuntime,
    /** Reviews the draft before it is shown; what it said is added to the draft's notes. Null drafts unreviewed. */
    planReviewer: PlanReviewer? = null,
): SwarmDraftResult {
    val roles = activeRoles(resolveRoleCollection(persistence.roles.all()))
    // Only roles a linked provider can staff, as at launch; with none, offer them all so the failure names the gap.
    val staffable = swarmDraftRoles(roles)
        .filter { role -> providerRegistry.canStaff(role.capabilitiesRequired, null) }
        .ifEmpty { swarmDraftRoles(roles) }
    val packet = swarmDraftPacket(prompt, staffable)
    if (packet.objective.isEmpty()) return SwarmDraftResult.Problem(SwarmDraftProblem.NoPrompt)
    if (packet.availableAgents.isEmpty()) return SwarmDraftResult.Problem(SwarmDraftProblem.NoRoles)
    val notes = mutableListOf<String>()
    val plan = try {
        // Failover notes ("OpenAI is out of credit; continued with Claude.") ride back with the draft.
        kotlinx.coroutines.withContext(com.hereliesaz.geministrator.workflow.FailoverNotes { notes += it }) {
            orchestrationRuntime.plan(packet)
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        return swarmDraftFailure(failure)
    }
    var chosen = plan
    reviewRole(planReviewer, planReviewer, roles)?.let { (reviewer, role) ->
        val outcome = try {
            kotlinx.coroutines.withContext(com.hereliesaz.geministrator.workflow.FailoverNotes { notes += it }) {
                reviewUntilApproved(packet, plan, orchestrationRuntime, reviewer, role.name, role.instructions, role.preferredProviderId?.value)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        }
        notes += planReviewNotes(role.name, outcome)
        // A rework that cannot be drafted (too many creatures, say) must not cost the draft the keeper already had.
        if (swarmDraftFromPlan(prompt, outcome.plan, staffable) is SwarmDraftResult.Ready) {
            chosen = outcome.plan
        } else if (outcome.plan !== plan) {
            notes += "The reworked draft could not be used, so this is the first draft."
        }
    }
    return swarmDraftFromPlan(prompt, chosen, staffable).let { if (it is SwarmDraftResult.Ready) it.copy(notes = notes.toList()) else it }
}

/**
 * The reviewer and the role it speaks as, when a review should happen: a reviewer exists, a role may review
 * plans, and (for a launch) a keeper decision exists to show it to. Otherwise the plan goes ahead as it always did.
 */
private fun reviewRole(
    reviewer: PlanReviewer?,
    keeperDecision: Any?,
    roles: List<com.hereliesaz.geministrator.domain.RoleDefinition>,
): Pair<PlanReviewer, com.hereliesaz.geministrator.domain.RoleDefinition>? {
    if (reviewer == null || keeperDecision == null) return null
    val role = planReviewerRole(roles) ?: return null
    return reviewer to role
}
