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
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.orchestration.OrchestrationPlan
import com.hereliesaz.geministrator.orchestration.PendingPlanLaunch
import com.hereliesaz.geministrator.orchestration.PlanReviewOutcome
import com.hereliesaz.geministrator.orchestration.PlanReviewRound
import com.hereliesaz.geministrator.orchestration.PlanVerdict
import com.hereliesaz.geministrator.orchestration.ReviewedPlanStore
import com.hereliesaz.geministrator.orchestration.StoredPlanRound
import com.hereliesaz.geministrator.orchestration.planInputsFingerprint
import com.hereliesaz.geministrator.orchestration.validateAgainst
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

/** What a launch's plan is made from, read once so a plan, its review and its fingerprint all see the same. */
private class PlanInputs(
    val projectName: String,
    val objective: String,
    val repository: RepositoryRef?,
    val existingProject: Project?,
    val parentRunIds: List<WorkflowRunId>,
    val launchRoles: List<RoleDefinition>,
    val plannerRoles: List<RoleDefinition>,
    val packet: OrchestrationPacket,
    /** The saved hive whose rough design the plan is made from; null for an objective typed into Feed it. */
    val design: WorkflowDefinition? = null,
    /** Roles a package brings with a saved hive. */
    val supplementalRoles: List<RoleDefinition> = emptyList(),
    /** The global roles a saved-hive launch records with its run; null for an orchestrated launch. */
    val rolesToPersist: List<RoleDefinition>? = null,
) {
    fun fingerprint(reviewer: RoleDefinition): String = planInputsFingerprint(
        objective = objective,
        repository = repository,
        parentRunIds = parentRunIds.map { it.value },
        plannerRoles = plannerRoles,
        reviewer = reviewer,
        design = design,
    )
}

private suspend fun ApplicationRuntime.planInputs(
    projectName: String,
    objective: String,
    repository: RepositoryRef?,
    existingProject: Project?,
    continuesWorkflowRunIds: List<WorkflowRunId>,
    /** A saved hive and its rough design, when the plan is made from one. */
    design: Pair<WorkflowDefinition, RoughDesign>? = null,
    supplementalRoles: List<RoleDefinition> = emptyList(),
): PlanInputs {
    val parents = requireKnownParentRuns(continuesWorkflowRunIds)
    val cleanProjectName = projectName.trim()
    val cleanObjective = objective.trim()
    require(cleanProjectName.isNotEmpty()) { "Project name is required" }
    require(cleanObjective.isNotEmpty()) { "Objective is required" }
    val normalizedRepository = repository?.normalized()
    reportLaunchProgress("Gathering available agent roles…")
    val globalRoles = activeRoles(resolveRoleCollection(persistence.roles.all()))
    val launchRoles = if (design == null) {
        globalRoles
    } else {
        (supplementalRoles + globalRoles).filter(RoleDefinition::enabled).distinctBy(RoleDefinition::id)
    }
    // Offer the planner only roles a linked provider can staff: a step for any other role could
    // never be dispatched. With nothing staffable, keep them all so the failure names the gap.
    val plannerRoles = launchRoles
        .filter { role -> providerRegistry.canStaff(role.capabilitiesRequired, normalizedRepository) }
        .ifEmpty { launchRoles }
    val agents = plannerRoles.map { role ->
        OrchestrationRole(
            id = role.id.value,
            name = role.name,
            authorities = role.authorities.map { it.name }.sorted(),
        )
    }
    val packet = if (design == null) {
        OrchestrationPacket(
            objective = cleanObjective,
            currentState = "new-workflow",
            availableAgents = agents,
            instruction = "Create the minimal executable dependency-correct workflow DAG needed to satisfy the objective.",
        )
    } else {
        OrchestrationPacket(
            objective = cleanObjective,
            currentState = "rough-design",
            availableAgents = agents,
            currentPlan = design.second.steps,
            instruction = ROUGH_DESIGN_INSTRUCTION,
        )
    }
    return PlanInputs(
        projectName = cleanProjectName,
        objective = cleanObjective,
        repository = normalizedRepository,
        existingProject = existingProject,
        parentRunIds = parents,
        launchRoles = launchRoles,
        plannerRoles = plannerRoles,
        packet = packet,
        design = design?.first,
        supplementalRoles = supplementalRoles,
        rolesToPersist = globalRoles.takeIf { design != null },
    )
}

/** Makes the run from an approved [plan]: the workflow, the project, the run. */
@OptIn(ExperimentalTime::class)
private suspend fun ApplicationRuntime.startPlannedWorkflow(inputs: PlanInputs, plan: OrchestrationPlan) {
    val now = Clock.System.now().toEpochMilliseconds()
    val project = inputs.existingProject?.copy(
        name = inputs.projectName,
        repository = inputs.repository ?: inputs.existingProject.repository,
        updatedAtEpochMillis = now,
    ) ?: Project(
        id = ProjectId("project-$now"),
        name = inputs.projectName,
        repository = inputs.repository,
        createdAtEpochMillis = now,
        updatedAtEpochMillis = now,
    )
    reportLaunchProgress("Planner returned ${plan.steps.size} step${if (plan.steps.size == 1) "" else "s"}")
    reportLaunchProgress("Assembling workflow…")
    val definition = if (inputs.design != null) {
        // The saved design stays as drawn: the run is of the planned hive, under a new id.
        plannedHiveDefinition(
            design = inputs.design,
            plan = plan,
            packet = inputs.packet,
            roles = inputs.launchRoles,
            id = WorkflowDefinitionId("${inputs.design.id.value}--planned-$now"),
        )
    } else {
        OrchestratedWorkflowFactory.create(
            id = WorkflowDefinitionId("workflow-$now"),
            objective = inputs.objective,
            plan = plan,
            packet = inputs.packet,
            roles = inputs.launchRoles,
        )
    }
    val launchService = WorkflowLaunchService(
        preparer = WorkflowDefinitionPreparer(providerRegistry, inputs.launchRoles),
        persistence = persistence,
        eventSink = RepositoryWorkflowEventSink(persistence.events),
        roles = inputs.launchRoles,
        rolesToPersist = inputs.rolesToPersist ?: inputs.launchRoles,
    )
    reportLaunchProgress("Saving project and starting run…")
    launchService.launch(
        project = project,
        definition = definition,
        workflowRunId = WorkflowRunId("run-$now"),
        objective = inputs.objective,
        nowEpochMillis = now,
        taskRunIdFactory = { id -> TaskRunId("run-$now-${id.value}") },
        parentWorkflowRunIds = inputs.parentRunIds,
    )
    reportLaunchProgress("Loading the new run…")
    loadLatest()
}

/** The planning stage for the field, from the roles in hand. */
private fun planningStage(
    inputs: PlanInputs,
    phase: PlanningPhase,
    plan: OrchestrationPlan?,
    round: Int,
    reviewer: RoleDefinition,
    verdict: PlanningVerdict? = null,
): PlanningStage {
    val planner = BuiltInRoles.Orchestrator
    val first = plan?.firstStep()
    return PlanningStage(
        phase = phase,
        planner = inputs.launchRoles.firstOrNull { it.id == planner.id } ?: planner,
        reviewer = reviewer,
        firstStepRole = first?.let { step -> inputs.launchRoles.firstOrNull { it.id.value == step.roleId } },
        firstStepName = first?.name,
        round = round,
        verdict = verdict,
    )
}

private fun PlanReviewOutcome.verdict(): PlanningVerdict = when {
    problem != null -> PlanningVerdict.Unreachable
    approved -> PlanningVerdict.Approved
    else -> PlanningVerdict.NotApproved
}

/**
 * The plan for [inputs], made by [orchestrationRuntime] and, when [review] is set, read by the reviewer, reworked,
 * kept while it waits (with [planStore]) and put to the keeper through [confirmPlan]. Null when the keeper declines.
 * Without a review the planner's plan is returned as it is.
 */
@OptIn(ExperimentalTime::class)
private suspend fun ApplicationRuntime.planAndDecide(
    inputs: PlanInputs,
    orchestrationRuntime: OrchestrationAgentRuntime,
    review: Pair<PlanReviewer, RoleDefinition>?,
    confirmPlan: (suspend (PlanDecision) -> Boolean)?,
    planStore: ReviewedPlanStore?,
): OrchestrationPlan? {
    reportLaunchProgress("Asking the planner for a workflow (${inputs.plannerRoles.size} roles available)…")
    // Only a launch that will be reviewed has a planning field: the planner's egg, the reviewer who hatches from it.
    review?.let { (_, role) -> reportPlanningStage(planningStage(inputs, PlanningPhase.Planning, null, 0, role)) }
    val firstPlan = orchestrationRuntime.plan(inputs.packet)
    if (review == null) return firstPlan
    val (reviewer, role) = review
    val outcome = reviewUntilApproved(
        packet = inputs.packet,
        firstPlan = firstPlan,
        planner = orchestrationRuntime,
        reviewer = reviewer,
        reviewerName = role.name,
        instructions = role.instructions,
        pinnedProviderId = role.preferredProviderId?.value,
        onStage = { phase, current, round -> reportPlanningStage(planningStage(inputs, phase, current, round, role)) },
    )
    val plan = outcome.plan
    reportPlanningStage(planningStage(inputs, PlanningPhase.AwaitingKeeper, plan, outcome.rounds.size, role, outcome.verdict()))
    reportLaunchProgress("Waiting for your decision on the plan…")
    // Kept while the keeper decides, so a restart does not cost them the plan or the reviewer's reasons.
    planStore?.setPending(
        PendingPlanLaunch(
            fingerprint = inputs.fingerprint(role),
            objective = inputs.objective,
            projectName = inputs.projectName,
            existingProjectId = inputs.existingProject?.id?.value,
            repository = inputs.repository,
            parentRunIds = inputs.parentRunIds.map { it.value },
            packet = inputs.packet,
            plan = plan,
            rounds = outcome.rounds.map { StoredPlanRound(it.plan, it.verdict is PlanVerdict.Approve, it.verdict.reasons) },
            problem = outcome.problem,
            reviewerRoleId = role.id.value,
            reviewerName = role.name,
            createdAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
            savedDefinitionId = inputs.design?.id?.value,
            supplementalRoles = inputs.supplementalRoles,
            design = inputs.design,
        ),
    )
    val start = checkNotNull(confirmPlan)(PlanDecision(inputs.objective, role.name, outcome, inputs.packet.availableAgents.associate { it.id to it.name }))
    planStore?.clearPending()
    if (!start) {
        reportLaunchProgress("The plan was not started")
        return null
    }
    reportPlanningStage(planningStage(inputs, PlanningPhase.Started, plan, outcome.rounds.size, role))
    return plan
}

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
    /** Keeps a reviewed plan that waits for the keeper across a restart (see [resumePendingPlanLaunch]). */
    planStore: ReviewedPlanStore? = null,
) {
    reportLaunchProgress("Reading your objective…")
    val cleanObjective = objective.trim()
    parseHallMonitorTrialLaunchObjective(cleanObjective)?.let { trial ->
        testHallMonitorSolution(
            findingId = trial.findingId,
            solutionIndex = trial.solutionIndex,
            orchestrationRuntime = orchestrationRuntime,
        )
        return
    }

    val inputs = planInputs(projectName, objective, repository, existingProject, continuesWorkflowRunIds)
    val review = reviewRole(planReviewer, confirmPlan, inputs.launchRoles)
    val plan = planAndDecide(inputs, orchestrationRuntime, review, confirmPlan, planStore) ?: return
    startPlannedWorkflow(inputs, plan)
}

/**
 * Launches a saved hive. One with an Orchestrator in it is a rough design (see SavedHivePlanning.kt): the Orchestrator
 * plans from it before any run exists, the reviewer reads the plan, and the keeper decides, as for [launchOrchestratedWorkflow];
 * the run is of the planned hive and the saved design is left as drawn. Every other saved hive, and any launch
 * without a planner, a reviewer and a keeper to decide (a remote interface cannot be one: invariant 8), launches as it always did.
 */
@OptIn(ExperimentalTime::class)
internal suspend fun ApplicationRuntime.launchPlannedSavedWorkflow(
    definition: WorkflowDefinition,
    projectName: String,
    objective: String,
    repository: RepositoryRef?,
    existingProject: Project?,
    supplementalRoles: List<RoleDefinition>,
    continuesWorkflowRunIds: List<WorkflowRunId>,
    orchestrationRuntime: OrchestrationAgentRuntime,
    planReviewer: PlanReviewer,
    confirmPlan: suspend (PlanDecision) -> Boolean,
    planStore: ReviewedPlanStore?,
): Boolean {
    val rough = savedHiveRoughDesign(definition) ?: return false
    val inputs = planInputs(
        projectName, objective, repository, existingProject, continuesWorkflowRunIds,
        design = definition to rough,
        supplementalRoles = supplementalRoles,
    )
    val review = reviewRole(planReviewer, confirmPlan, inputs.launchRoles) ?: return false
    val plan = planAndDecide(inputs, orchestrationRuntime, review, confirmPlan, planStore) ?: return true
    startPlannedWorkflow(inputs, plan)
    return true
}

/**
 * Offers again the reviewed plan a restart interrupted, if there is one and nothing it was made from has
 * changed: the same objective, repository, runs it continues, planner roles and reviewer (and, for a saved hive,
 * its rough design). The keeper is resuming the decision they were looking at; if anything differs, or the plan
 * has gone stale, it is dropped and they start afresh. Returns true when a decision was offered (and answered).
 * Starting it is still the keeper's Start in the app, through [confirmPlan].
 */
@OptIn(ExperimentalTime::class)
suspend fun ApplicationRuntime.resumePendingPlanLaunch(
    planStore: ReviewedPlanStore,
    confirmPlan: suspend (PlanDecision) -> Boolean,
): Boolean {
    val pending = planStore.pending(Clock.System.now().toEpochMilliseconds()) ?: return false
    val inputs = try {
        val saved = pending.savedDefinitionId?.let { id ->
            // A hive in the library is read again, so an edit to it is seen; one a package brought is not saved, so the plan's own copy is used.
            val definition = persistence.definitions.get(WorkflowDefinitionId(id)) ?: pending.design ?: error("The saved hive is gone")
            definition to (savedHiveRoughDesign(definition) ?: error("The saved hive is no longer a rough design"))
        }
        planInputs(
            projectName = pending.projectName,
            objective = pending.objective,
            repository = pending.repository,
            existingProject = pending.existingProjectId?.let { persistence.projects.get(ProjectId(it)) ?: error("The project is gone") },
            continuesWorkflowRunIds = pending.parentRunIds.map(::WorkflowRunId),
            design = saved,
            supplementalRoles = pending.supplementalRoles,
        )
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        planStore.clearPending()
        return false
    }
    val role = planReviewerRole(inputs.launchRoles)
    val stillValid = role != null && role.id.value == pending.reviewerRoleId &&
        inputs.fingerprint(role) == pending.fingerprint &&
        runCatching { pending.plan.validateAgainst(inputs.packet) }.isSuccess
    if (!stillValid || role == null) {
        planStore.clearPending()
        return false
    }
    val outcome = PlanReviewOutcome(
        plan = pending.plan,
        rounds = pending.rounds.map { PlanReviewRound(it.plan, if (it.approved) PlanVerdict.Approve(it.reasons) else PlanVerdict.Reject(it.reasons)) },
        problem = pending.problem,
    )
    reportPlanningStage(planningStage(inputs, PlanningPhase.AwaitingKeeper, pending.plan, outcome.rounds.size, role, outcome.verdict()))
    reportLaunchProgress("Waiting for your decision on the plan…")
    val start = confirmPlan(PlanDecision(inputs.objective, pending.reviewerName, outcome, inputs.packet.availableAgents.associate { it.id to it.name }))
    planStore.clearPending()
    if (!start) {
        reportLaunchProgress("The plan was not started")
        return true
    }
    reportPlanningStage(planningStage(inputs, PlanningPhase.Started, pending.plan, outcome.rounds.size, role))
    startPlannedWorkflow(inputs, pending.plan)
    return true
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
