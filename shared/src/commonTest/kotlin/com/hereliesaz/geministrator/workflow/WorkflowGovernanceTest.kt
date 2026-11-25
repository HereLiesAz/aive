package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.persistence.InMemorySettings
import com.hereliesaz.geministrator.domain.AcceptanceCriterion
import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.EnvironmentPlanningPolicy
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RetryPolicy
import com.hereliesaz.geministrator.domain.RetryReason
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.events.ApprovalDecisionReceived
import com.hereliesaz.geministrator.events.ApprovalRequired
import com.hereliesaz.geministrator.events.InMemoryWorkflowEventSink
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkflowGovernanceTest {
    @Test
    fun declaredFileWriteUsesHostSnapshotProposalApprovalAndOriginalCommitStep() = runBlocking {
        val role = BuiltInRoles.ImplementationEngineer
        val source = com.hereliesaz.geministrator.template.BuiltInTemplates.PrepareFilesForRepository
        val id = TaskDefinitionId("edit")
        val edit = com.hereliesaz.geministrator.template.StepInstantiation.task(
            source, source.base(), id, "Edit files", mapOf("brief" to "Update README"),
        ).copy(resources = listOf(com.hereliesaz.geministrator.domain.TaskResource.RepositoryFiles(listOf("README.md"))))
        val follow = TaskDefinition(TaskDefinitionId("follow"), "Follow", "Review result", BuiltInRoles.Worker.id,
            dependsOn = setOf(id), environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired)
        val definition = WorkflowDefinition(WorkflowDefinitionId("host"), "Host write", tasks = listOf(edit, follow),
            testDesignPolicy = com.hereliesaz.geministrator.domain.TestDesignPolicy.None)
        val registry = AgentProviderRegistry(emptyList(), inferenceSettings = InMemorySettings())
        val preparer = WorkflowDefinitionPreparer(registry, listOf(role, BuiltInRoles.Worker))
        val repo = com.hereliesaz.geministrator.domain.RepositoryRef("owner", "project")
        val prepared = preparer.prepare(definition, repo)
        val read = prepared.tasks.single { it.id.value == "edit--host-read" }
        val proposal = prepared.tasks.single { it.id.value == "edit--host-proposal" }
        val gate = prepared.tasks.single { it.id.value == "edit--host-approve" }
        val commit = prepared.tasks.single { it.id == id }
        assertEquals(com.hereliesaz.geministrator.domain.TaskExecutor.RepositoryOperation("read-file-proposal-context"), read.executor)
        assertEquals(setOf(read.id), proposal.dependsOn)
        assertEquals(role.id, proposal.roleId)
        assertEquals(emptySet(), proposal.providerCapabilitiesFor(role))
        assertEquals(null, proposal.providerRepositoryFor(repo))
        assertEquals(setOf(proposal.id), gate.dependsOn)
        assertTrue(gate.executor is com.hereliesaz.geministrator.domain.TaskExecutor.HumanApproval)
        assertEquals(setOf(proposal.id, gate.id), commit.dependsOn)
        assertEquals(com.hereliesaz.geministrator.domain.TaskExecutor.RepositoryOperation("commit-files:edit--host-proposal:auto"), commit.executor)
        assertEquals(edit.contract, commit.contract)
        assertEquals(follow, prepared.tasks.single { it.id == follow.id })
        assertEquals(prepared, preparer.prepare(prepared, repo))
        val unbounded = definition.copy(tasks = listOf(edit.copy(resources = listOf(
            com.hereliesaz.geministrator.domain.TaskResource.RepositoryFiles(listOf("docs/*.md")))), follow))
        assertEquals(unbounded, expandHostFileSteps(unbounded, repo, mapOf(role.id to role)))
        val local = repo.copy(source = com.hereliesaz.geministrator.domain.RepositorySource.Local)
        assertEquals(prepared, preparer.prepare(definition, local))
        val gitlab = repo.copy(source = com.hereliesaz.geministrator.domain.RepositorySource.GitLab)
        assertEquals(definition, expandHostFileSteps(definition, gitlab, mapOf(role.id to role)))
    }

    @Test
    fun exactRepositoryReadUsesHostAndPreservesAnswerContractAcrossRepositorySources() = runBlocking {
        val role = BuiltInRoles.Reviewer.copy(capabilitiesRequired = setOf(AgentCapability.RepositoryRead))
        val template = com.hereliesaz.geministrator.template.StepPresets.ReadRepositoryFiles
        val id = TaskDefinitionId("answer")
        val task = com.hereliesaz.geministrator.template.StepInstantiation.task(
            template, template.base(), id, "Read docs", mapOf("question" to "What does the README say?"),
        ).copy(resources = listOf(com.hereliesaz.geministrator.domain.TaskResource.RepositoryFiles(listOf("README.md"))))
        val definition = WorkflowDefinition(WorkflowDefinitionId("read"), "Host read", tasks = listOf(task),
            testDesignPolicy = com.hereliesaz.geministrator.domain.TestDesignPolicy.None)
        val registry = AgentProviderRegistry(emptyList(), inferenceSettings = InMemorySettings())
        val preparer = WorkflowDefinitionPreparer(registry, listOf(role))
        for (source in listOf(com.hereliesaz.geministrator.domain.RepositorySource.GitHub,
            com.hereliesaz.geministrator.domain.RepositorySource.GitLab,
            com.hereliesaz.geministrator.domain.RepositorySource.Local)) {
            val repo = com.hereliesaz.geministrator.domain.RepositoryRef("owner", "project", source = source)
            val prepared = preparer.prepare(definition, repo)
            assertEquals(listOf("answer--host-read", "answer"), prepared.tasks.map { it.id.value })
            val snapshot = prepared.tasks.first()
            val answer = prepared.tasks.last()
            assertEquals(com.hereliesaz.geministrator.domain.TaskExecutor.RepositoryOperation("read-file-proposal-context"), snapshot.executor)
            assertEquals(task.resources, snapshot.resources)
            assertEquals(setOf(snapshot.id), answer.dependsOn)
            assertEquals(task.contract, answer.contract)
            assertTrue(answer.hostFileRead)
            assertEquals(emptySet(), answer.providerCapabilitiesFor(role))
            assertEquals(null, answer.providerRepositoryFor(repo))
            assertEquals(prepared, preparer.prepare(prepared, repo))
        }
    }

    @Test
    fun providerRequiredEnvironmentPlanningInjectsEpaBeforeWorker() = runBlocking {
        val worker = GovernanceFakeProvider(
            id = AgentProviderId("worker"),
            providerCapabilities = AgentCapabilities(
                supported = setOf(
                    AgentCapability.RepositoryRead,
                    AgentCapability.RepositoryWrite,
                ),
                requiresEnvironmentPlanning = true,
            ),
        )
        val epa = GovernanceFakeProvider(
            id = AgentProviderId("epa"),
            providerCapabilities = AgentCapabilities(
                setOf(AgentCapability.EnvironmentPlanning),
            ),
        )
        val preparer = WorkflowDefinitionPreparer(
            providerRegistry = AgentProviderRegistry(listOf(worker, epa), inferenceSettings = InMemorySettings()),
            roles = BuiltInRoles.all,
        )
        val implementationId = TaskDefinitionId("implement")
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("wf"),
            name = "Workflow",
            testDesignPolicy = com.hereliesaz.geministrator.domain.TestDesignPolicy.None,
            tasks = listOf(
                TaskDefinition(
                    id = implementationId,
                    name = "Implement",
                    objective = "Implement feature",
                    roleId = BuiltInRoles.ImplementationEngineer.id,
                    acceptanceCriteria = listOf(AcceptanceCriterion("works")),
                    environmentPlanningPolicy = EnvironmentPlanningPolicy.WhenProviderRequires,
                ),
            ),
        )

        val prepared = preparer.prepare(definition)
        val epaTask = prepared.tasks.single { it.roleId == BuiltInRoles.EpaRepresentative.id }
        val implementation = prepared.tasks.single { it.id == implementationId }

        assertTrue(epaTask.id in implementation.dependsOn)
        assertEquals(EnvironmentPlanningPolicy.NotRequired, implementation.environmentPlanningPolicy)
    }

    @Test
    fun exhaustedRetryUsesEscalationPolicy() {
        val taskRun = TaskRun(
            id = TaskRunId("run"),
            taskDefinitionId = TaskDefinitionId("task"),
            status = TaskRunStatus.Failed,
            attempt = 2,
            assignedRoleId = BuiltInRoles.ImplementationEngineer.id,
        )

        val decision = FailurePolicyEvaluator.decide(
            taskRun = taskRun,
            retryPolicy = RetryPolicy(
                maxAttempts = 2,
                retryOn = setOf(RetryReason.ProviderFailure),
            ),
            escalationPolicy = com.hereliesaz.geministrator.domain.EscalationPolicy.Reassign(
                BuiltInRoles.RecoveryEngineer.id,
            ),
            reason = RetryReason.ProviderFailure,
        )

        val reassignment = assertIs<FailureDecision.Reassign>(decision)
        assertEquals(BuiltInRoles.RecoveryEngineer.id, reassignment.roleId)
    }

    @Test
    fun approvalDecisionPersistsAndEmitsAuditEvents() = runBlocking {
        val repository = MemoryGateRepository()
        val events = InMemoryWorkflowEventSink()
        val coordinator = ApprovalGateCoordinator(repository, events)
        val gateId = ApprovalGateId("gate-1")
        val workflowRunId = WorkflowRunId("workflow-run")
        val taskId = TaskDefinitionId("task")

        coordinator.open(
            id = gateId,
            workflowRunId = workflowRunId,
            taskDefinitionId = taskId,
            kind = ApprovalGateKind.PlanApproval,
            reason = "Review provider plan",
            requiredRoleId = BuiltInRoles.Architect.id,
            nowEpochMillis = 10L,
        )
        coordinator.decide(
            id = gateId,
            approved = true,
            decidedByRoleId = BuiltInRoles.Architect.id,
            note = "Approved",
            nowEpochMillis = 20L,
        )

        assertEquals(ApprovalGateStatus.Approved, repository.get(gateId)?.status)
        val approvalRequired = events.snapshot().filterIsInstance<ApprovalRequired>().single()
        assertEquals(gateId, approvalRequired.gateId)
        assertTrue(
            events.snapshot().any {
                it is ApprovalDecisionReceived && it.approved && it.gateId == gateId
            },
        )
    }
}

private class MemoryGateRepository : ApprovalGateRepository {
    private val gates = mutableMapOf<ApprovalGateId, ApprovalGate>()

    override suspend fun put(gate: ApprovalGate) {
        gates[gate.id] = gate
    }

    override suspend fun get(id: ApprovalGateId): ApprovalGate? = gates[id]

    override suspend fun unresolved(workflowRunId: WorkflowRunId): List<ApprovalGate> =
        gates.values.filter {
            it.workflowRunId == workflowRunId &&
                (it.status == ApprovalGateStatus.Pending || it.status == ApprovalGateStatus.Applying)
        }
}

private class GovernanceFakeProvider(
    override val id: AgentProviderId,
    private val providerCapabilities: AgentCapabilities,
) : AgentProvider {
    override suspend fun capabilities(): AgentCapabilities = providerCapabilities

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle =
        AgentRunHandle(ProviderRunId("run"))

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = emptyFlow()

    override suspend fun sendMessage(
        runId: ProviderRunId,
        message: String,
    ): ProviderActionResult = ProviderActionResult.Accepted

    override suspend fun approvePlan(runId: ProviderRunId): ProviderActionResult =
        ProviderActionResult.Accepted

    override suspend fun cancel(runId: ProviderRunId): ProviderActionResult =
        ProviderActionResult.Accepted
}
