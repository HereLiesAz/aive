package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.EnvironmentPlanningPolicy
import com.hereliesaz.geministrator.domain.HallMonitorAction
import com.hereliesaz.geministrator.domain.HallMonitorEvidence
import com.hereliesaz.geministrator.domain.HallMonitorFinding
import com.hereliesaz.geministrator.domain.HallMonitorReport
import com.hereliesaz.geministrator.domain.HallMonitorScope
import com.hereliesaz.geministrator.domain.HallMonitorSolution
import com.hereliesaz.geministrator.domain.HallMonitorTestDesign
import com.hereliesaz.geministrator.domain.IntegrationPolicy
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowGlobalPause
import com.hereliesaz.geministrator.domain.WorkflowGlobalPauseKind
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRuntime
import com.hereliesaz.geministrator.orchestration.OrchestrationPacket
import com.hereliesaz.geministrator.orchestration.OrchestrationPlan
import com.hereliesaz.geministrator.orchestration.OrchestrationPlanStep
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HallMonitorSolutionTrialServiceTest {
    @Test
    fun launchesSeparateManualTrialAndLeavesSourcePaused() = runTest {
        val fixture = fixture()
        val planner = CapturingPlanner()
        val service = HallMonitorSolutionTrialService(
            persistence = fixture.persistence,
            providerRegistry = AgentProviderRegistry(emptyList()),
        )

        val trial = service.launch(
            sourceRun = fixture.sourceRun,
            findingId = "latency-plateau",
            solutionIndex = 0,
            orchestrationRuntime = planner,
            nowEpochMillis = 100L,
        )

        val sourceAfter = assertNotNull(fixture.persistence.runs.get(fixture.sourceRun.id))
        val pauseAfter = assertNotNull(sourceAfter.globalPause)
        assertEquals(WorkflowRunStatus.AwaitingHuman, sourceAfter.status)
        assertEquals(trial, pauseAfter.solutionTrials.single())

        val trialRun = assertNotNull(fixture.persistence.runs.get(trial.workflowRunId))
        val trialDefinition = assertNotNull(fixture.persistence.definitions.get(trial.workflowDefinitionId))
        assertNull(trialRun.globalPause)
        assertEquals(IntegrationPolicy.Manual, trialDefinition.integrationPolicy)
        assertEquals(TestDesignPolicy.None, trialDefinition.testDesignPolicy)
        assertTrue(trialRun.updatedAtEpochMillis > sourceAfter.updatedAtEpochMillis)
        assertEquals("Test narrower routing", trial.solutionTitle)
        assertEquals(listOf("compare latency", "compare correction count"), trial.validationTests)

        val packet = assertNotNull(planner.lastPacket)
        assertEquals("hall-monitor-solution-test", packet.currentState)
        assertEquals(trial.validationTests, packet.acceptanceCriteria)
        assertTrue(packet.instruction.contains("baseline", ignoreCase = true))
        assertTrue(packet.instruction.contains("Do not merge", ignoreCase = true))
        assertTrue(packet.artifacts.any { it.contains("Test narrower routing") })
    }

    @Test
    fun refusesSecondConcurrentTrialOfSameSolution() = runTest {
        val fixture = fixture()
        val planner = CapturingPlanner()
        val service = HallMonitorSolutionTrialService(fixture.persistence, AgentProviderRegistry(emptyList()))

        service.launch(fixture.sourceRun, "latency-plateau", 0, planner, 100L)
        val refreshedSource = assertNotNull(fixture.persistence.runs.get(fixture.sourceRun.id))

        val failure = assertFailsWith<IllegalArgumentException> {
            service.launch(refreshedSource, "latency-plateau", 0, planner, 200L)
        }
        assertTrue(failure.message.orEmpty().contains("already has an active trial"))
    }

    private suspend fun fixture(): Fixture {
        val persistence = InMemoryWorkflowPersistence()
        val project = Project(
            id = ProjectId("project"),
            name = "Project",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("source-definition"),
            name = "Source",
            tasks = listOf(
                TaskDefinition(
                    id = TaskDefinitionId("implement"),
                    name = "Implement",
                    objective = "Implement the source objective",
                    roleId = BuiltInRoles.ImplementationEngineer.id,
                    executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
                    environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
                ),
            ),
            testDesignPolicy = TestDesignPolicy.None,
        )
        val report = report()
        val reportArtifact = ArtifactRef(
            id = ArtifactId("report-artifact"),
            kind = ArtifactKind.HallMonitorReport,
            taskRunId = TaskRunId("hall-monitor-run"),
            label = "Hall Monitor report",
            textContent = Json.encodeToString(HallMonitorReport.serializer(), report),
            createdAtEpochMillis = 10L,
        )
        val sourceRun = WorkflowRun(
            id = WorkflowRunId("source-run"),
            projectId = project.id,
            workflowDefinitionId = definition.id,
            objective = "Ship the feature",
            status = WorkflowRunStatus.AwaitingHuman,
            taskRuns = mapOf(
                TaskDefinitionId("implement") to TaskRun(
                    id = TaskRunId("source-implement-run"),
                    taskDefinitionId = TaskDefinitionId("implement"),
                    status = TaskRunStatus.Blocked,
                    assignedRoleId = BuiltInRoles.ImplementationEngineer.id,
                    blockingReason = BlockingReason(HALL_MONITOR_GLOBAL_PAUSE_CODE, "Paused"),
                ),
            ),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 50L,
            globalPause = WorkflowGlobalPause(
                kind = WorkflowGlobalPauseKind.HallMonitorReview,
                reason = "Review Hall Monitor recommendations",
                reportArtifactId = reportArtifact.id,
                antagonistReviewArtifactId = ArtifactId("antagonist-review"),
                orchestratorGateId = ApprovalGateId("orchestrator-gate"),
                humanGateId = ApprovalGateId("human-gate"),
                taskSnapshots = emptyList(),
                pausedAtEpochMillis = 50L,
            ),
        )
        persistence.projects.put(project)
        persistence.definitions.put(definition)
        persistence.artifacts.put(reportArtifact)
        persistence.runs.put(sourceRun)
        return Fixture(persistence, sourceRun)
    }

    private fun report(): HallMonitorReport {
        val evidence = HallMonitorEvidence(
            claim = "Routing is slower than necessary",
            source = "runtime telemetry",
            measurementWindow = "last 20 runs",
            population = "all routed tasks",
            value = "p95 +35%",
        )
        val counterEvidence = evidence.copy(
            claim = "Complex tasks benefit from the current route",
            value = "-12% retries",
        )
        val testDesign = HallMonitorTestDesign(
            name = "Routing comparison",
            hypothesis = "Narrow routing reduces latency without more retries",
            fixture = "representative workflow corpus",
            controls = listOf("same task corpus"),
            measurements = listOf("latency", "retries"),
            expectedFailureModes = listOf("quality regression"),
        )
        return HallMonitorReport(
            reportId = "report-1",
            title = "Efficiency review",
            measuredFromEpochMillis = 1L,
            measuredToEpochMillis = 20L,
            findings = listOf(
                HallMonitorFinding(
                    id = "latency-plateau",
                    scope = HallMonitorScope.OrchestrationLayer,
                    subject = "Router",
                    observation = "Routing has plateaued",
                    evidence = listOf(evidence),
                    counterEvidence = listOf(counterEvidence),
                    falsificationCriteria = listOf("No latency improvement under controlled replay"),
                    solutions = listOf(
                        HallMonitorSolution(
                            title = "Test narrower routing",
                            action = HallMonitorAction.Reroute,
                            target = "orchestrator router",
                            rationale = "Reduce unnecessary hops",
                            tradeoffs = listOf("May lose useful deliberation"),
                            validationTests = listOf("compare latency", "compare correction count"),
                        ),
                        HallMonitorSolution(
                            title = "Benchmark current routing",
                            action = HallMonitorAction.Benchmark,
                            target = "orchestrator router",
                            rationale = "Establish a stronger baseline",
                            tradeoffs = listOf("Adds test cost"),
                            validationTests = listOf("replay fixed corpus"),
                        ),
                    ),
                ),
            ),
            orchestrationTests = listOf(testDesign),
            memoryTests = listOf(testDesign.copy(name = "Memory control")),
            summary = "Routing may be overcomplicated.",
        )
    }

    private data class Fixture(
        val persistence: InMemoryWorkflowPersistence,
        val sourceRun: WorkflowRun,
    )

    private class CapturingPlanner : OrchestrationAgentRuntime {
        var lastPacket: OrchestrationPacket? = null

        override suspend fun plan(packet: OrchestrationPacket): OrchestrationPlan = repair(packet)

        override suspend fun repair(packet: OrchestrationPacket): OrchestrationPlan {
            lastPacket = packet
            return OrchestrationPlan(
                steps = listOf(
                    OrchestrationPlanStep(
                        id = "candidate",
                        name = "Run candidate",
                        objective = "Apply the selected solution and execute its validation tests",
                        roleId = BuiltInRoles.ImplementationEngineer.id.value,
                    ),
                ),
            )
        }
    }
}
