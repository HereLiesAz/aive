package com.hereliesaz.geministrator.orchestration

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalOrchestrationUtilitiesTest {
    private val utilities = DeterministicLocalOrchestrationUtilities

    @Test
    fun memoryComposerUsesKnownEntitiesActionsAndSymbolsWithoutAnsweringTask() {
        val plan = utilities.composeMemoryQueries(
            MemoryQueryInput(
                objective = "Fix the regression after navigation changes",
                knownEntities = listOf("AR ball tracking"),
                knownActions = listOf("regression"),
                codeSymbols = listOf("BallTracker.update"),
                chronologicalContextRequired = true,
                maxQueries = 5,
            ),
        )

        assertFalse(plan.enoughEvidence)
        assertTrue(plan.queries.any { it.resolution == MemoryResolution.Entity && it.text == "AR ball tracking" })
        assertTrue(plan.queries.any { it.resolution == MemoryResolution.Action && it.text == "regression" })
        assertTrue(plan.queries.any { it.reasonCode == "EXACT_SYMBOL" })
        assertTrue(plan.queries.size <= 5)
    }

    @Test
    fun contextPackerKeepsConflictGroupAtomic() {
        val packed = utilities.packContext(
            ContextPackingInput(
                tokenBudget = 80,
                evidence = listOf(
                    ContextEvidence("timeout-30", 20, priority = 10, conflictGroup = "timeout"),
                    ContextEvidence("timeout-60", 20, priority = 10, conflictGroup = "timeout"),
                    ContextEvidence("noise", 60, priority = 1),
                ),
            ),
        )

        assertEquals(listOf("timeout-30", "timeout-60"), packed.selectedEvidenceIds)
        assertEquals(40, packed.totalEstimatedTokens)
    }

    @Test
    fun agentRouterNeverSelectsUnavailableOrUnderqualifiedCandidate() {
        val route = utilities.routeAgent(
            AgentRoutingInput(
                requiredCapabilities = setOf("repository-write", "testing"),
                requiredContextTokens = 4_000,
                candidates = listOf(
                    AgentRouteCandidate("tiny", setOf("testing"), estimatedCost = 0.0),
                    AgentRouteCandidate(
                        "offline",
                        setOf("repository-write", "testing"),
                        available = false,
                    ),
                    AgentRouteCandidate(
                        "capable",
                        setOf("repository-write", "testing"),
                        estimatedCost = 0.2,
                        contextLimitTokens = 8_000,
                    ),
                ),
            ),
        )

        assertEquals(AgentRouteDecision.Local, route.decision)
        assertEquals("capable", route.selectedAgent)
    }

    @Test
    fun toolRouterHasExplicitNoToolAndUnavailableStates() {
        assertEquals(
            ToolRouteDecision.NoTool,
            utilities.routeTool(ToolRoutingInput(capabilities = emptyList())).decision,
        )
        assertEquals(
            ToolRouteDecision.UnavailableCapability,
            utilities.routeTool(
                ToolRoutingInput(
                    operationClass = "read_repository",
                    capabilities = listOf(ToolCapability("web", setOf("web_search"))),
                ),
            ).decision,
        )
    }

    @Test
    fun handoffPreservesFailuresAndUnresolvedWork() {
        val handoff = utilities.composeHandoff(
            HandoffInput(
                objective = "Ship",
                completed = listOf("build"),
                failures = listOf("wasm failed"),
                unresolved = listOf("fix wasm"),
                artifacts = listOf("apk"),
                provenance = listOf("run-1"),
            ),
        )
        assertEquals(listOf("wasm failed"), handoff.failures)
        assertEquals(listOf("fix wasm"), handoff.unresolved)
        assertEquals(listOf("run-1"), handoff.provenance)
    }

    @Test
    fun escalationGatePrefersEscalationOverPlausibleLocalGuessing() {
        val result = utilities.evaluateEscalation(
            CapabilityAssessment(
                requiresCodebaseWideReasoning = true,
                requiresArchitecturalDecision = true,
                recommendedTier = "reasoning-large",
            ),
        )
        assertEquals(EscalationDecision.Escalate, result.decision)
        assertTrue("CODEBASE_WIDE_REASONING" in result.reasonCodes)
        assertTrue("ARCHITECTURAL_DECISION" in result.reasonCodes)
    }

    @Test
    fun completionGateRequiresExplicitPassedEvidenceForEveryCriterion() {
        val result = utilities.evaluateCompletion(
            CompletionInput(
                objective = "Ship all targets",
                taskTerminal = true,
                criteria = listOf(
                    CriterionEvidence("Android passes", listOf("android-ci"), EvidenceStatus.Passed),
                    CriterionEvidence("Wasm passes", emptyList(), EvidenceStatus.NotRun),
                ),
            ),
        )
        assertEquals(CompletionDecision.NeedsVerification, result.decision)
        assertEquals(listOf("Wasm passes"), result.unsatisfiedCriteria)
    }

    @Test
    fun executionStateSummaryUsesRuntimeTruthOnly() {
        val taskId = TaskDefinitionId("build")
        val taskRunId = TaskRunId("build-run")
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("workflow"),
            name = "Workflow",
            tasks = listOf(
                TaskDefinition(
                    id = taskId,
                    name = "Build",
                    objective = "Build it",
                    roleId = null,
                    executor = com.hereliesaz.geministrator.domain.TaskExecutor.TestRunner("gradle test"),
                ),
            ),
        )
        val artifact = ArtifactRef(
            id = ArtifactId("artifact"),
            kind = ArtifactKind.TestResult,
            taskRunId = taskRunId,
            label = "Tests",
            textContent = "passed",
            createdAtEpochMillis = 2L,
        )
        val run = WorkflowRun(
            id = WorkflowRunId("run"),
            projectId = ProjectId("project"),
            workflowDefinitionId = definition.id,
            objective = "Build",
            status = WorkflowRunStatus.Completed,
            taskRuns = mapOf(
                taskId to TaskRun(
                    id = taskRunId,
                    taskDefinitionId = taskId,
                    status = TaskRunStatus.Completed,
                    assignedRoleId = null,
                    executor = definition.tasks.single().executor,
                    artifacts = listOf(artifact),
                ),
            ),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
        )

        val summary = utilities.summarizeExecution(definition, run)
        assertEquals(listOf("Build"), summary.completedSteps)
        assertEquals(listOf("artifact"), summary.artifacts)
        assertTrue(summary.failedSteps.isEmpty())
    }

    @Test
    fun verificationPlannerGroundsChecksInCriteriaArtifactsAndPlatforms() {
        val plan = utilities.planVerification(
            VerificationPlanningInput(
                objective = "Ship",
                acceptanceCriteria = listOf("Android build succeeds", "All unit tests pass"),
                artifactKinds = setOf(ArtifactKind.CodeChange),
                targetPlatforms = listOf("Android"),
            ),
        )

        assertTrue(plan.steps.any { it.operationClass == "build" })
        assertTrue(plan.steps.any { it.operationClass == "test" })
        assertTrue(plan.steps.any { it.operationClass == "platform-check:Android" })
    }
}
