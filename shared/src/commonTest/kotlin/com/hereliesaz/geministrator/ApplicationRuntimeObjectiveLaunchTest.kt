package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRuntime
import com.hereliesaz.geministrator.orchestration.OrchestrationPacket
import com.hereliesaz.geministrator.orchestration.OrchestrationPlan
import com.hereliesaz.geministrator.orchestration.OrchestrationPlanStep
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The app's launch path when a planner is linked: project + objective → planned DAG → persisted run. */
class ApplicationRuntimeObjectiveLaunchTest {
    @Test
    fun objectiveIsPlannedMaterializedAndPersisted() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val planner = RecordingPlanner(
            OrchestrationPlan(
                listOf(
                    OrchestrationPlanStep("requirements", "Define requirements", "Pin down scope", BuiltInRoles.ProductManager.id.value),
                    OrchestrationPlanStep(
                        "summary",
                        "Write summary",
                        "Summarize the result",
                        BuiltInRoles.ProductManager.id.value,
                        dependsOn = listOf("requirements"),
                    ),
                ),
            ),
        )
        try {
            val runtime = ApplicationRuntime.create(providers = emptyList(), scope = scope, persistence = persistence)

            runtime.launchOrchestratedWorkflow(
                projectName = " Objective launch ",
                objective = " Summarize a smoke test ",
                orchestrationRuntime = planner,
            )

            assertEquals("Summarize a smoke test", planner.packets.single().objective, "The planner sees the cleaned objective")
            val live = assertIs<ApplicationRuntimeState.Live>(runtime.state.value)
            val project = persistence.projects.get(live.presentation.run.projectId) ?: error("Project was not persisted")
            assertEquals("Objective launch", project.name)
            assertEquals("Summarize a smoke test", live.presentation.run.objective)
            val taskIds = live.presentation.definition.tasks.map { it.id }
            assertTrue(TaskDefinitionId("requirements") in taskIds && TaskDefinitionId("summary") in taskIds, "Planned steps become tasks: $taskIds")
            assertEquals(
                listOf(TaskDefinitionId("requirements")),
                live.presentation.definition.tasks.single { it.id == TaskDefinitionId("summary") }.dependsOn.toList(),
            )
            assertEquals(live.presentation.definition.id, persistence.definitions.all().single().id, "The definition is persisted")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun plannerIsOfferedOnlyRolesALinkedProviderCanStaff() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val planner = RecordingPlanner(
            OrchestrationPlan(
                listOf(OrchestrationPlanStep("requirements", "Define requirements", "Pin down scope", BuiltInRoles.ProductManager.id.value)),
            ),
        )
        try {
            val runtime = ApplicationRuntime.create(
                providers = listOf(PlanOnlyProvider()),
                scope = scope,
                persistence = InMemoryWorkflowPersistence(),
            )

            runtime.launchOrchestratedWorkflow("Staffing", "Summarize a smoke test", planner)

            val offered = planner.packets.single().availableAgents.map { it.id }.toSet()
            assertTrue(BuiltInRoles.ProductManager.id.value in offered, "Offered: $offered")
            assertTrue(
                BuiltInRoles.ImplementationEngineer.id.value !in offered,
                "A role needing repository access is not offered when no provider has it: $offered",
            )
        } finally {
            scope.cancel()
        }
    }

    /** A hosted text model: plans, nothing else. */
    private class PlanOnlyProvider : AgentProvider {
        override val id = AgentProviderId("plan-only")

        override suspend fun capabilities() = AgentCapabilities(
            supported = setOf(AgentCapability.PlanGeneration, AgentCapability.PlanApproval),
        )

        override suspend fun start(request: AgentTaskRequest) = AgentRunHandle(ProviderRunId("plan-only-${request.taskRunId.value}"))

        override fun observe(runId: ProviderRunId): Flow<AgentEvent> = emptyFlow()

        override suspend fun sendMessage(runId: ProviderRunId, message: String) = ProviderActionResult.Accepted

        override suspend fun approvePlan(runId: ProviderRunId) = ProviderActionResult.Accepted

        override suspend fun cancel(runId: ProviderRunId) = ProviderActionResult.Accepted
    }

    private class RecordingPlanner(private val plan: OrchestrationPlan) : OrchestrationAgentRuntime {
        val packets = mutableListOf<OrchestrationPacket>()

        override suspend fun plan(packet: OrchestrationPacket): OrchestrationPlan = plan.also { packets += packet }

        override suspend fun repair(packet: OrchestrationPacket): OrchestrationPlan = plan(packet)
    }
}
