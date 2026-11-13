package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProviderConstraints
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.RoleExecutionSource
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.persistence.InMemorySettings
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.workflow.AgentProviderRegistry
import com.hereliesaz.geministrator.workflow.ManagedSessionFailure
import com.hereliesaz.geministrator.workflow.ManagedSessionRequest
import com.hereliesaz.geministrator.workflow.ProviderBackedManagedSessionGateway
import com.hereliesaz.geministrator.workflow.ProviderSelectionRequest
import com.hereliesaz.geministrator.workflow.WorkflowEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One hive, three creatures: one pinned to a provider, one left on Auto, one pinned to an A2A
 * drifter. Routing follows the role's `preferredProviderId`; a provider failure fails the Auto
 * creature over and stops the pinned ones. Approval policy is never read or changed by any of it.
 */
class CreatureProviderRoutingTest {
    private val a2aId = "a2a:agent.example"

    private fun role(id: String, provider: String? = null, source: RoleExecutionSource = RoleExecutionSource.Agent) = RoleDefinition(
        id = RoleDefinitionId(id),
        name = id.replaceFirstChar { it.uppercase() },
        description = "d",
        instructions = "i",
        preferredProviderId = provider?.let(::AgentProviderId),
        executionSource = source,
    )

    private val pinnedRole = role("pinned", "openai")
    private val autoRole = role("auto")
    private val driftRole = role("drifter", a2aId)
    private val roles = listOf(pinnedRole, autoRole, driftRole)

    private fun task(id: String, role: RoleDefinition, approval: ApprovalPolicy = ApprovalPolicy.None) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = id,
        objective = "o",
        roleId = role.id,
        executor = TaskExecutor.RoleAgent(role.id),
        approvalPolicy = approval,
    )

    private val pinnedTask = task("pinned", pinnedRole, ApprovalPolicy.HumanApproval)
    private val autoTask = task("auto", autoRole, ApprovalPolicy.HumanApproval)
    private val driftTask = task("drifter", driftRole)

    @Test
    fun routingNamesPinnedAutoAndDrifterCreatures() {
        assertEquals(CreatureProviderRouting.Pinned("openai"), creatureProviderRouting(pinnedRole, pinnedTask))
        assertEquals(CreatureProviderRouting.Auto, creatureProviderRouting(autoRole, autoTask))
        assertEquals(CreatureProviderRouting.Pinned(a2aId), creatureProviderRouting(driftRole, driftTask))
        // A task that requires a provider by id is pinned even when its role is not.
        val required = autoTask.copy(providerConstraints = ProviderConstraints.RequireProvider(AgentProviderId("anthropic")))
        assertEquals(CreatureProviderRouting.Pinned("anthropic"), creatureProviderRouting(autoRole, required))
        // A script has no provider to choose.
        val script = role("script", source = RoleExecutionSource.GitHubAction("ci.yml"))
        assertNull(creatureProviderRouting(script, autoTask))
        assertNull(creatureProviderRouting(null, autoTask))
    }

    @Test
    fun badgesAndDriftersNames() {
        val badges = creatureProviderBadges(listOf(pinnedTask, autoTask, driftTask)) { t -> roles.first { it.id == t.roleId } }
        assertEquals(CreatureProviderBadge(CreatureProviderRouting.Auto, null, autoRole.id.value), badges["auto"])
        assertEquals("openai", (badges.getValue("pinned").routing as CreatureProviderRouting.Pinned).providerId)
        // The drifter wears its own name, not "a2a:agent.example".
        assertEquals("Drifter", badges.getValue("drifter").pinnedName)
    }

    @Test
    fun theEnginesAutoRuleIsTheSameRule() {
        val engine = WorkflowEngine(ProviderBackedManagedSessionGateway(AgentProviderRegistry(emptyList(), inferenceSettings = InMemorySettings()), CoroutineScope(SupervisorJob())), roles)
        listOf(pinnedRole to pinnedTask, autoRole to autoTask, driftRole to driftTask).forEach { (r, t) ->
            assertEquals(isProviderAuto(r, t), engine.isProviderAuto(r, t))
        }
        assertFalse(isProviderAuto(pinnedRole, pinnedTask))
        assertTrue(isProviderAuto(autoRole, autoTask))
    }

    @Test
    fun withRoleProviderTouchesOnlyThatRolesProvider() {
        val pinnedToClaude = withRoleProvider(roles, autoRole.id, "anthropic")
        assertEquals(AgentProviderId("anthropic"), pinnedToClaude.first { it.id == autoRole.id }.preferredProviderId)
        assertEquals(roles.map { it.id }, pinnedToClaude.map { it.id })
        assertEquals(pinnedRole, pinnedToClaude.first { it.id == pinnedRole.id })
        assertEquals(autoRole.copy(preferredProviderId = AgentProviderId("anthropic")), pinnedToClaude.first { it.id == autoRole.id })
        val back = withRoleProvider(pinnedToClaude, autoRole.id, null)
        assertEquals(roles, back)
    }

    private fun run(): WorkflowRun {
        val taskRuns = listOf(pinnedTask, autoTask, driftTask).associate { t ->
            t.id to TaskRun(
                id = TaskRunId("run-${t.id.value}"),
                taskDefinitionId = t.id,
                status = TaskRunStatus.Failed,
                assignedRoleId = t.roleId,
                assignedProviderId = AgentProviderId("openai"),
                providerRunId = ProviderRunId("openai-run"),
            )
        }
        return WorkflowRun(
            id = WorkflowRunId("w"),
            projectId = ProjectId("p"),
            workflowDefinitionId = WorkflowDefinitionId("d"),
            objective = "o",
            status = WorkflowRunStatus.Running,
            taskRuns = taskRuns,
            createdAtEpochMillis = 0,
            updatedAtEpochMillis = 0,
            roleSnapshot = roles,
        )
    }

    @Test
    fun aProviderFailureFailsOverTheAutoCreatureAndStopsThePinnedOnes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val openai = RoutingFakeProvider("openai")
        val claude = RoutingFakeProvider("anthropic")
        val drifter = RoutingFakeProvider(a2aId)
        try {
            val registry = AgentProviderRegistry(listOf(openai, claude, drifter), inferenceSettings = InMemorySettings())
            val engine = WorkflowEngine(ProviderBackedManagedSessionGateway(registry, scope), roles)
            val project = Project(ProjectId("p"), "P", createdAtEpochMillis = 0, updatedAtEpochMillis = 0)
            val definition = WorkflowDefinition(
                id = WorkflowDefinitionId("d"),
                name = "hive",
                tasks = listOf(pinnedTask, autoTask, driftTask),
            )
            val run = run()
            fun selection(t: TaskDefinition) = requireNotNull(engine.selectionFor(project, definition, run, t.id))

            // Routing: the pin reaches the registry as the preference; only Auto may fail over.
            assertEquals(AgentProviderId("openai"), selection(pinnedTask).preferredProviderId)
            assertEquals(null, selection(autoTask).preferredProviderId)
            assertEquals(AgentProviderId(a2aId), selection(driftTask).preferredProviderId)
            assertFalse(selection(pinnedTask).failoverAllowed)
            assertTrue(selection(autoTask).failoverAllowed)
            assertFalse(selection(driftTask).failoverAllowed)

            // Failure of openai (credit): Auto restarts on another provider; the pinned creature stops.
            val failure = "HTTP 402 Payment Required"
            val auto = engine.failOver(run, autoTask.id, openai.id, failure, selection(autoTask), 1L)
            val restarted = requireNotNull(auto.restarted).taskRuns.getValue(autoTask.id)
            assertEquals(TaskRunStatus.Retrying, restarted.status)
            assertEquals(listOf(openai.id), restarted.failedProviders)

            val pinned = engine.failOver(run, pinnedTask.id, openai.id, failure, selection(pinnedTask), 1L)
            assertNull(pinned.restarted)
            assertTrue("did not switch" in pinned.stopMessage.orEmpty(), pinned.stopMessage)

            // The A2A creature is pinned too: its drifter failing never moves it to a model provider.
            val drift = engine.failOver(run, driftTask.id, drifter.id, "Read timed out", selection(driftTask), 1L)
            assertNull(drift.restarted)
            assertTrue("did not switch" in drift.stopMessage.orEmpty(), drift.stopMessage)

            // Approval is the task's, untouched by provider routing.
            assertEquals(ApprovalPolicy.HumanApproval, pinnedTask.approvalPolicy)
            assertEquals(ApprovalPolicy.HumanApproval, autoTask.approvalPolicy)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun startFailureStaysOnAPinnedProviderAndMovesAnAutoOne() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            suspend fun start(preferred: String?, failoverAllowed: Boolean): Pair<RoutingFakeProvider, RoutingFakeProvider> {
                val openai = RoutingFakeProvider("openai", startError = "HTTP 402 Payment Required")
                val claude = RoutingFakeProvider("anthropic")
                val gateway = ProviderBackedManagedSessionGateway(
                    AgentProviderRegistry(listOf(openai, claude), inferenceSettings = InMemorySettings()),
                    scope,
                )
                runCatching {
                    gateway.createSession(
                        ManagedSessionRequest(
                            providerSelection = ProviderSelectionRequest(
                                preferredProviderId = preferred?.let(::AgentProviderId),
                                failoverAllowed = failoverAllowed,
                            ),
                            taskRequest = AgentTaskRequest(
                                taskRunId = TaskRunId("t"),
                                objective = "o",
                                roleInstructions = "i",
                                acceptanceCriteria = emptyList(),
                            ),
                        ),
                    )
                }
                return openai to claude
            }
            val (pinnedOpenai, pinnedClaude) = start("openai", failoverAllowed = false)
            assertEquals(1, pinnedOpenai.starts)
            assertEquals(0, pinnedClaude.starts)
            val (autoOpenai, autoClaude) = start("openai", failoverAllowed = true)
            assertEquals(1, autoOpenai.starts)
            assertEquals(1, autoClaude.starts)
            assertFailsWith<ManagedSessionFailure> {
                ProviderBackedManagedSessionGateway(
                    AgentProviderRegistry(listOf(RoutingFakeProvider("openai", startError = "HTTP 402")), inferenceSettings = InMemorySettings()),
                    scope,
                ).createSession(
                    ManagedSessionRequest(
                        providerSelection = ProviderSelectionRequest(preferredProviderId = AgentProviderId("openai")),
                        taskRequest = AgentTaskRequest(TaskRunId("t"), "o", "i", emptyList()),
                    ),
                )
            }
            Unit
        } finally {
            scope.cancel()
        }
    }
}

private class RoutingFakeProvider(name: String, private val startError: String? = null) : AgentProvider {
    override val id = AgentProviderId(name)
    var starts = 0
    override suspend fun capabilities() = AgentCapabilities(supported = setOf(AgentCapability.RepositoryRead))
    override suspend fun start(request: AgentTaskRequest): AgentRunHandle {
        starts++
        startError?.let { error(it) }
        return AgentRunHandle(ProviderRunId("${id.value}-run"))
    }
    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = emptyFlow()
    override suspend fun sendMessage(runId: ProviderRunId, message: String) = ProviderActionResult.Accepted
    override suspend fun approvePlan(runId: ProviderRunId) = ProviderActionResult.Accepted
    override suspend fun cancel(runId: ProviderRunId) = ProviderActionResult.Accepted
}
