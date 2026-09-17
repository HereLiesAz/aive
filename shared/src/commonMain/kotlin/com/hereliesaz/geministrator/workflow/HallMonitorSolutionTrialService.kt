package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.decodeHallMonitorReportPayload
import com.hereliesaz.geministrator.domain.AcceptanceCriterion
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.EnvironmentPlanningPolicy
import com.hereliesaz.geministrator.domain.HallMonitorRole
import com.hereliesaz.geministrator.domain.HallMonitorSolutionTrial
import com.hereliesaz.geministrator.domain.IntegrationPolicy
import com.hereliesaz.geministrator.domain.RoleAuthority
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowGlobalPauseKind
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.activeRoles
import com.hereliesaz.geministrator.domain.isTerminal
import com.hereliesaz.geministrator.domain.resolveRoleCollection
import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRuntime
import com.hereliesaz.geministrator.orchestration.OrchestrationPacket
import com.hereliesaz.geministrator.orchestration.OrchestrationPlanStep
import com.hereliesaz.geministrator.orchestration.OrchestrationRole
import com.hereliesaz.geministrator.persistence.RepositoryWorkflowEventSink
import com.hereliesaz.geministrator.persistence.WorkflowPersistence

/**
 * Creates an executable counterfactual for one Hall Monitor recommendation while leaving the
 * original run globally paused and unchanged.
 *
 * The generated workflow is always Manual integration: a successful experiment is evidence, not
 * permission to merge, deploy, or mutate the paused workflow. The trial is a normal persisted run,
 * so the existing terrarium, run history, artifacts, and inspectors can show its real execution.
 */
class HallMonitorSolutionTrialService(
    private val persistence: WorkflowPersistence,
    private val providerRegistry: AgentProviderRegistry,
) {
    suspend fun launch(
        sourceRun: WorkflowRun,
        findingId: String,
        solutionIndex: Int,
        orchestrationRuntime: OrchestrationAgentRuntime,
        nowEpochMillis: Long,
    ): HallMonitorSolutionTrial {
        val pause = requireNotNull(sourceRun.globalPause) {
            "Workflow ${sourceRun.id.value} is not globally paused for Hall Monitor review"
        }
        require(pause.kind == WorkflowGlobalPauseKind.HallMonitorReview) {
            "Workflow ${sourceRun.id.value} is paused for ${pause.kind}, not Hall Monitor review"
        }
        require(findingId.isNotBlank()) { "Hall Monitor finding id is required" }
        require(solutionIndex >= 0) { "Hall Monitor solution index must not be negative" }

        val alreadyRunning = pause.solutionTrials.firstOrNull { trial ->
            trial.findingId == findingId &&
                trial.solutionIndex == solutionIndex &&
                persistence.runs.get(trial.workflowRunId)?.status?.isTerminal() == false
        }
        require(alreadyRunning == null) {
            "Hall Monitor solution '$findingId/$solutionIndex' already has an active trial ${alreadyRunning?.workflowRunId?.value}"
        }

        val reportArtifact = requireNotNull(persistence.artifacts.get(pause.reportArtifactId)) {
            "Paused Hall Monitor report ${pause.reportArtifactId.value} is missing"
        }
        val report = decodeHallMonitorReportPayload(reportArtifact.textContent.orEmpty())
        val finding = requireNotNull(report.findings.firstOrNull { it.id == findingId }) {
            "Hall Monitor report ${report.reportId} has no finding '$findingId'"
        }
        val solution = requireNotNull(finding.solutions.getOrNull(solutionIndex)) {
            "Hall Monitor finding '$findingId' has no solution at index $solutionIndex"
        }

        val project = requireNotNull(persistence.projects.get(sourceRun.projectId)) {
            "Project ${sourceRun.projectId.value} for paused workflow was not found"
        }
        val sourceDefinition = requireNotNull(persistence.definitions.get(sourceRun.workflowDefinitionId)) {
            "Workflow definition ${sourceRun.workflowDefinitionId.value} was not found"
        }
        val launchRoles = activeRoles(resolveRoleCollection(persistence.roles.all()))
            .filterNot { it.id == HallMonitorRole.id }
        require(launchRoles.isNotEmpty()) { "No active roles are available to run a Hall Monitor solution trial" }
        val launchRoleIds = launchRoles.mapTo(linkedSetOf()) { it.id }
        val comparisonRole = launchRoles.firstOrNull {
            it.id == BuiltInRoles.QaEngineer.id && RoleAuthority.Verify in it.authorities
        } ?: launchRoles.firstOrNull {
            RoleAuthority.Verify in it.authorities
        } ?: launchRoles.firstOrNull {
            RoleAuthority.ReviewCode in it.authorities
        } ?: error(
            "Hall Monitor solution testing requires an active independent verification or review role",
        )

        val roleTaskIds = sourceDefinition.tasks.mapNotNullTo(linkedSetOf()) { task ->
            val roleId = task.roleId ?: (task.executor as? TaskExecutor.RoleAgent)?.roleId
            task.id.takeIf { roleId != null && roleId != HallMonitorRole.id }
        }
        val currentPlan = sourceDefinition.tasks.mapNotNull { task ->
            val roleId = task.roleId ?: (task.executor as? TaskExecutor.RoleAgent)?.roleId
                ?: return@mapNotNull null
            if (roleId == HallMonitorRole.id || roleId !in launchRoleIds) return@mapNotNull null
            OrchestrationPlanStep(
                id = task.id.value,
                name = task.name,
                objective = task.objective,
                roleId = roleId.value,
                dependsOn = task.dependsOn.filter { it in roleTaskIds }.map { it.value },
                requiresHumanApproval = false,
            )
        }

        val trialId = "hall-monitor-solution-trial:${sourceRun.id.value}:$nowEpochMillis"
        val trialDefinitionId = WorkflowDefinitionId("hall-monitor-trial-$nowEpochMillis")
        val trialRunId = WorkflowRunId("hall-monitor-trial-run-$nowEpochMillis")
        val experimentObjective = "Test Hall Monitor solution '${solution.title}' against the paused workflow objective: ${sourceRun.objective}"
        val packet = OrchestrationPacket(
            objective = experimentObjective,
            currentState = "hall-monitor-solution-test",
            acceptanceCriteria = solution.validationTests,
            availableAgents = launchRoles.map { role ->
                OrchestrationRole(
                    id = role.id.value,
                    name = role.name,
                    authorities = role.authorities.map { it.name }.sorted(),
                )
            },
            artifacts = listOf(
                "Hall Monitor report: ${report.summary}",
                "Finding ${finding.id}: ${finding.observation}",
                "Evidence: ${finding.evidence.joinToString { it.claim }}",
                "Counter-evidence: ${finding.counterEvidence.joinToString { it.claim }}",
                "Falsification criteria: ${finding.falsificationCriteria.joinToString()}",
                "Selected solution: ${solution.title} / ${solution.action} / ${solution.target} / ${solution.rationale}",
                "Tradeoffs: ${solution.tradeoffs.joinToString()}",
                "Validation tests: ${solution.validationTests.joinToString()}",
            ),
            previousSteps = sourceDefinition.tasks.map { task ->
                "${task.id.value}: ${task.name} — ${task.objective}"
            },
            currentPlan = currentPlan,
            instruction = """
                Build an isolated counterfactual experiment for exactly the selected Hall Monitor solution.
                The source workflow is globally paused and MUST remain untouched.

                Use the current plan as the baseline. Preserve a control/baseline path whenever technically
                feasible. Build a modified candidate that changes only what is necessary to exercise the
                selected solution. Run the solution's declared validation tests against the candidate and,
                where feasible, the baseline under comparable conditions. The runtime will append an
                independent comparison task, so expose the measurements and artifacts it needs.

                This is an experiment, not approval. Do not merge, release, deploy to production, change the
                paused run, or silently adopt the recommendation. Repository-writing work must stay isolated
                from the source branch/workspace. Prefer verification and measurement over implementation
                claims. Return the smallest executable dependency-correct workflow that can let the user run
                the modified version and inspect the evidence for themselves.
            """.trimIndent(),
        )
        val plan = orchestrationRuntime.repair(packet)
        val planned = OrchestratedWorkflowFactory.create(
            id = trialDefinitionId,
            objective = experimentObjective,
            plan = plan,
            packet = packet,
            roles = launchRoles,
        )
        val plannedTaskIds = planned.tasks.mapTo(linkedSetOf()) { it.id }
        val dependedOn = planned.tasks.flatMapTo(linkedSetOf()) { it.dependsOn }
        val exitTaskIds = plannedTaskIds - dependedOn
        require(exitTaskIds.isNotEmpty()) { "Hall Monitor solution trial planner produced no terminal experiment task" }
        val comparisonTaskId = uniqueComparisonTaskId(plannedTaskIds)
        val comparisonCriteria = buildList {
            solution.validationTests.forEach { add(AcceptanceCriterion(it)) }
            finding.falsificationCriteria.forEach {
                add(AcceptanceCriterion("State whether this falsification criterion was met: $it"))
            }
            add(
                AcceptanceCriterion(
                    "Compare observed candidate results with the baseline/control where available, including regressions and tradeoffs; do not approve or integrate the recommendation.",
                ),
            )
        }
        val comparisonTask = TaskDefinition(
            id = comparisonTaskId,
            name = "Compare Hall Monitor trial results",
            objective = buildString {
                append("Independently evaluate the isolated trial for '")
                append(solution.title)
                append("'. Use produced artifacts and measurements to compare the modified candidate against the baseline/control where available. Report every declared validation test, observed regression, tradeoff, and falsification criterion. This is evidence for the user; do not merge, deploy, release, or adopt the recommendation.")
            },
            roleId = comparisonRole.id,
            executor = TaskExecutor.RoleAgent(comparisonRole.id),
            dependsOn = exitTaskIds,
            acceptanceCriteria = comparisonCriteria,
            environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
        )
        val trialDefinition = planned.copy(
            name = "TEST · ${solution.title}".take(80),
            description = "Isolated Hall Monitor counterfactual for ${sourceRun.id.value}; finding ${finding.id}, solution $solutionIndex.",
            tasks = planned.tasks + comparisonTask,
            integrationPolicy = IntegrationPolicy.Manual,
            // The selected solution carries its own validation contract; avoid injecting unrelated
            // generic pre/post implementation test topology into the counterfactual.
            testDesignPolicy = TestDesignPolicy.None,
        )

        val launchAt = nowEpochMillis + 1L
        val launchService = WorkflowLaunchService(
            preparer = WorkflowDefinitionPreparer(providerRegistry, launchRoles),
            persistence = persistence,
            eventSink = RepositoryWorkflowEventSink(persistence.events),
            roles = launchRoles,
        )
        launchService.launch(
            project = project,
            definition = trialDefinition,
            workflowRunId = trialRunId,
            objective = experimentObjective,
            nowEpochMillis = launchAt,
            taskRunIdFactory = { id -> TaskRunId("${trialRunId.value}-${id.value}") },
        )

        val trial = HallMonitorSolutionTrial(
            id = trialId,
            findingId = finding.id,
            solutionIndex = solutionIndex,
            solutionTitle = solution.title,
            action = solution.action,
            target = solution.target,
            validationTests = solution.validationTests,
            workflowDefinitionId = trialDefinitionId,
            workflowRunId = trialRunId,
            createdAtEpochMillis = launchAt,
        )
        val persistedSource = requireNotNull(persistence.runs.get(sourceRun.id)) {
            "Paused source workflow ${sourceRun.id.value} disappeared while starting trial"
        }
        val livePause = requireNotNull(persistedSource.globalPause) {
            "Paused source workflow ${sourceRun.id.value} resumed while starting trial"
        }
        persistence.runs.put(
            persistedSource.copy(
                globalPause = livePause.copy(solutionTrials = livePause.solutionTrials + trial),
                updatedAtEpochMillis = nowEpochMillis,
            ),
        )
        return trial
    }

    private fun uniqueComparisonTaskId(existing: Set<TaskDefinitionId>): TaskDefinitionId {
        var suffix = 0
        while (true) {
            val value = if (suffix == 0) "hall-monitor-trial-compare" else "hall-monitor-trial-compare-$suffix"
            val candidate = TaskDefinitionId(value)
            if (candidate !in existing) return candidate
            suffix += 1
        }
    }
}
