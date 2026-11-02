package com.hereliesaz.geministrator.policy

import com.hereliesaz.geministrator.addons.HostPermission
import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleAuthority
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.events.InMemoryWorkflowEventSink
import com.hereliesaz.geministrator.events.PolicyDecisionRecorded
import com.hereliesaz.geministrator.workflow.ApprovalGate
import com.hereliesaz.geministrator.workflow.ApprovalGateCoordinator
import com.hereliesaz.geministrator.workflow.ApprovalGateKind
import com.hereliesaz.geministrator.workflow.ApprovalGateRepository
import com.hereliesaz.geministrator.workflow.ApprovalGateStatus
import com.hereliesaz.geministrator.workflow.READ_ONLY_REPOSITORY_OPERATIONS
import com.hereliesaz.geministrator.workflow.WorkflowGraphValidator
import com.hereliesaz.geministrator.workflow.WorkflowRunFactory
import com.hereliesaz.geministrator.workflow.WorkflowValidationError
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decision table: every rule must give the outcome (and message) of the inline check it replaced.
 * Each `legacy*` function below is the former check, copied verbatim, as the oracle.
 */
class PolicyDecisionPointTest {
    private val pdp: PolicyDecisionPoint = InProcessPolicyDecisionPoint
    private val runId = WorkflowRunId("run")

    // --- Gate deciders (WorkflowApprovalService, PlanRejectionService) ---------------------------

    private fun legacyGateAllowed(required: RoleDefinitionId?, decider: RoleDefinitionId?) =
        required == null || required == decider

    @Test
    fun gateDeciderMatchesFormerRequiredRoleCheck() {
        val gateId = ApprovalGateId("gate")
        val roles = listOf(null, RoleDefinitionId("orchestrator"), RoleDefinitionId("qa"))
        for (required in roles) for (decider in roles) for (kind in ApprovalGateKind.entries) {
            val decision = pdp.decide(
                PolicyRequest(
                    subject = PolicySubject.decider(decider),
                    action = PolicyAction.DecideApprovalGate(kind),
                    resource = PolicyResource.gate(gateId, required),
                ),
            )
            assertEquals(legacyGateAllowed(required, decider), decision.isAllowed, "required=$required decider=$decider")
            if (!decision.isAllowed) {
                assertEquals(
                    "Role ${decider?.value ?: "<human>"} is not authorized for gate ${gateId.value}",
                    decision.reason,
                )
                assertEquals(PolicyCodes.GATE_DECIDER_NOT_AUTHORIZED, decision.code)
            }
        }
    }

    // --- Approval policies (ProviderTaskRequestFactory, WorkflowRuntimeCoordinator) --------------

    private val approvalPolicies = listOf(
        ApprovalPolicy.None,
        ApprovalPolicy.HumanApproval,
        ApprovalPolicy.RoleApproval(RoleAuthority.ApprovePlan),
        ApprovalPolicy.RoleApproval(RoleAuthority.ApproveRelease),
    )

    @Test
    fun planApprovalMatchesFormerApprovalPolicyChecks() {
        for (policy in approvalPolicies) {
            // ProviderTaskRequestFactory: requirePlanApproval = approvalPolicy != None.
            val beforeDispatch = pdp.decide(
                PolicyRequest(PolicySubject.System, PolicyAction.ProceedPastPlan(policy), PolicyResource.task(null)),
            )
            assertEquals(policy != ApprovalPolicy.None, !beforeDispatch.isAllowed, "$policy")

            // WorkflowRuntimeCoordinator plan gate: requiresHuman = HumanApproval || None.
            val awaiting = pdp.decide(
                PolicyRequest(
                    PolicySubject.System,
                    PolicyAction.ProceedPastPlan(policy, providerRequested = true),
                    PolicyResource.task(null),
                ),
            )
            val gated = assertIs<PolicyDecision.RequireApproval>(awaiting, "$policy")
            assertEquals(policy is ApprovalPolicy.HumanApproval || policy == ApprovalPolicy.None, gated.requiresHuman)
            assertEquals((policy as? ApprovalPolicy.RoleApproval)?.authority, gated.requiredAuthority)
        }
    }

    // --- THREAT_MODEL invariant 4 (validator, readiness, distributed lease) ----------------------

    private val operations = listOf("status", "fetch", " fetch ", "push", "commit", "merge", "create-branch", "")

    private fun legacyIsMutation(operation: String) = operation.trim() !in READ_ONLY_REPOSITORY_OPERATIONS

    @Test
    fun repositoryOperationMatchesInvariantFour() {
        for (operation in operations) {
            for (evidence in ApprovalEvidence.entries) {
                for (provenance in listOf(PolicyProvenance.Local, PolicyProvenance.RemoteLease)) {
                    val decision = pdp.decide(
                        PolicyRequest(
                            subject = PolicySubject.System,
                            action = PolicyAction.RunRepositoryOperation(operation),
                            resource = PolicyResource.task(TaskDefinitionId("op")),
                            context = PolicyContext(approval = evidence, provenance = provenance),
                        ),
                    )
                    val case = "op='$operation' evidence=$evidence provenance=$provenance"
                    val expected = when {
                        !legacyIsMutation(operation) -> PolicyEffect.Allow
                        // DistributedComputeWorker: remote workers never run a mutation.
                        provenance == PolicyProvenance.RemoteLease -> PolicyEffect.Deny
                        // WorkflowRunFactory: a granted approval ancestor releases it.
                        evidence == ApprovalEvidence.Granted -> PolicyEffect.Allow
                        // WorkflowGraphValidator: a declared unconditional ancestor is valid.
                        evidence == ApprovalEvidence.Declared -> PolicyEffect.RequireApproval
                        else -> PolicyEffect.Deny
                    }
                    assertEquals(expected, decision.effect, case)
                }
            }
        }
    }

    @Test
    fun repositoryDenialsKeepTheirFormerMessages() {
        val remote = pdp.decide(
            PolicyRequest(
                PolicySubject.RemotePeer(),
                PolicyAction.RunRepositoryOperation("push"),
                PolicyResource.task(null),
                PolicyContext(approval = ApprovalEvidence.Granted, provenance = PolicyProvenance.RemoteLease),
            ),
        )
        assertEquals(
            "repository operation 'push' changes the repository; remote workers run only read-only operations",
            remote.reason,
        )
        val local = pdp.decide(
            PolicyRequest(PolicySubject.System, PolicyAction.RunRepositoryOperation("push"), PolicyResource.task(null)),
        )
        assertEquals("No human approval was granted for this repository change; it will not run.", local.reason)
    }

    private val build = TaskDefinitionId("build")
    private val approve = TaskDefinitionId("approve")
    private val op = TaskDefinitionId("op")

    private fun task(id: TaskDefinitionId, executor: TaskExecutor, dependsOn: Set<TaskDefinitionId> = emptySet(), condition: TaskCondition = TaskCondition.Always) =
        TaskDefinition(id = id, name = id.value, objective = id.value, roleId = null, executor = executor, dependsOn = dependsOn, condition = condition)

    private fun definition(operation: String, approvalCondition: TaskCondition? = TaskCondition.Always) = WorkflowDefinition(
        id = WorkflowDefinitionId("wf"),
        name = "wf",
        testDesignPolicy = TestDesignPolicy.None,
        tasks = listOfNotNull(
            task(build, TaskExecutor.RoleAgent(RoleDefinitionId("engineer"))),
            approvalCondition?.let { task(approve, TaskExecutor.HumanApproval(), setOf(build), it) },
            task(op, TaskExecutor.RepositoryOperation(operation), if (approvalCondition != null) setOf(approve, build) else setOf(build)),
        ),
    )

    @Test
    fun validatorStillEnforcesInvariantFourThroughThePolicyPoint() {
        for (operation in listOf("status", "fetch", "push", "commit")) {
            val mutation = legacyIsMutation(operation)
            val missing = WorkflowValidationError.MissingRepositoryMutationApproval(op)
            assertEquals(mutation, missing in WorkflowGraphValidator.validate(definition(operation, null)), "no approval, $operation")
            assertEquals(
                mutation,
                missing in WorkflowGraphValidator.validate(definition(operation, TaskCondition.OnFailure(build))),
                "conditional approval, $operation",
            )
            assertTrue(missing !in WorkflowGraphValidator.validate(definition(operation)), "approved, $operation")
        }
    }

    // --- Add-on host permissions (HaiveAddonMediator) -------------------------------------------

    @Test
    fun hostPermissionsMatchFormerGrantCheck() {
        val granted = setOf(HostPermission.AppRead, HostPermission.RepositoryRead, HostPermission.UiScreen)
        val subject = PolicySubject.Addon("addon.example", granted)
        for (permission in HostPermission.entries) {
            val decision = pdp.decide(
                PolicyRequest(subject, PolicyAction.UseHostCapability(permission), PolicyResource.hostApi(permission)),
            )
            assertEquals(granted.contains(permission), decision.isAllowed, "$permission")
            if (!decision.isAllowed) assertEquals("Add-on addon.example lacks permission $permission", decision.reason)
        }
        val notAnAddon = pdp.decide(
            PolicyRequest(PolicySubject.Human, PolicyAction.UseHostCapability(HostPermission.AppRead), PolicyResource.hostApi(HostPermission.AppRead)),
        )
        assertTrue(notAnAddon.isDenied)
    }

    // --- RoleAuthority checks (MoA, Skeleton-of-Thought, resource-aware resolver) ----------------

    private fun role(authorities: Set<RoleAuthority>, capabilities: Set<AgentCapability> = emptySet()) = RoleDefinition(
        id = RoleDefinitionId("r"),
        name = "R",
        description = "",
        instructions = "",
        authorities = authorities,
        capabilitiesRequired = capabilities,
    )

    @Test
    fun roleAuthorityChecksMatchFormerRequires() {
        val cases = listOf(
            role(emptySet()),
            role(setOf(RoleAuthority.Verify)),
            role(setOf(RoleAuthority.Implement)),
            role(setOf(RoleAuthority.Implement, RoleAuthority.Verify), setOf(AgentCapability.RepositoryWrite)),
            role(setOf(RoleAuthority.ReviewCode), setOf(AgentCapability.RepositoryWrite)),
        )
        for (role in cases) {
            val verify = pdp.decideRole(role, PolicyAction.ExerciseAuthority(RoleAuthority.Verify))
            assertEquals(RoleAuthority.Verify in role.authorities, verify.isAllowed, "verify $role")

            // Former order: implementation authority first, then repository-write capability.
            val reasonOnly = pdp.decideRole(role, PolicyAction.ReasonOnly)
            val expectedCode = when {
                RoleAuthority.Implement in role.authorities -> PolicyCodes.HOLDS_IMPLEMENT_AUTHORITY
                AgentCapability.RepositoryWrite in role.capabilitiesRequired -> PolicyCodes.REQUIRES_REPOSITORY_WRITE
                else -> PolicyCodes.ALLOWED
            }
            assertEquals(expectedCode, reasonOnly.code, "reason-only $role")
        }
    }

    // --- Hooks: only ever add a gate ------------------------------------------------------------

    @Test
    fun hookActionsGateUntrustedContextAndAllowLocal() {
        val tool = PolicyAction.CallTool("mcp.fs.write")
        val delegate = PolicyAction.Delegate("node-2")
        for (action in listOf(tool, delegate)) {
            val local = pdp.decide(PolicyRequest(PolicySubject.System, action, PolicyResource(type = "tool")))
            assertTrue(local.isAllowed, "$action local")
            val tainted = pdp.decide(
                PolicyRequest(PolicySubject.System, action, PolicyResource(type = "tool"), PolicyContext(provenance = PolicyProvenance.Untrusted)),
            )
            assertIs<PolicyDecision.RequireApproval>(tainted, "$action tainted")
        }
        val readOnlyTool = pdp.decide(
            PolicyRequest(
                PolicySubject.System,
                PolicyAction.CallTool("mcp.fs.read", mutating = false),
                PolicyResource(type = "tool"),
                PolicyContext(provenance = PolicyProvenance.Untrusted),
            ),
        )
        assertTrue(readOnlyTool.isAllowed)
    }

    // --- Decision events ------------------------------------------------------------------------

    @Test
    fun allowAndRunlessDecisionsRecordNothing() {
        val request = PolicyRequest(PolicySubject.System, PolicyAction.RunRepositoryOperation("push"), PolicyResource.task(op))
        assertNull(request.auditEvent(pdp.decide(request), 1L), "no run in context")
        val withRun = request.copy(context = PolicyContext(workflowRunId = runId, approval = ApprovalEvidence.Granted))
        assertNull(withRun.auditEvent(pdp.decide(withRun), 1L), "allow")
    }

    @Test
    fun refreshReadinessReportsAnUngrantedMutationAsADenyEvent() = runBlocking {
        // The approval settled without being granted (its branch was skipped), so the push must be
        // cancelled and the denial audited.
        val gated = definition("push")
        var run: WorkflowRun = WorkflowRunFactory.create(
            definition = gated,
            workflowRunId = runId,
            projectId = ProjectId("project"),
            objective = "test",
            nowEpochMillis = 1L,
            taskRunIdFactory = { TaskRunId(it.value) },
        )
        run = run.copy(
            taskRuns = run.taskRuns +
                (build to run.taskRuns.getValue(build).copy(status = TaskRunStatus.Completed)) +
                (
                    approve to run.taskRuns.getValue(approve).copy(
                        status = TaskRunStatus.Cancelled,
                        blockingReason = BlockingReason(code = "CONDITION_NOT_MET", message = "skipped"),
                    )
                ),
        )
        val sink = InMemoryWorkflowEventSink()
        val decisions = mutableListOf<Pair<PolicyRequest, PolicyDecision>>()
        val refreshed = WorkflowRunFactory.refreshReadiness(gated, run, 5L) { request, decision ->
            decisions += request to decision
        }
        decisions.forEach { (request, decision) -> sink.recordPolicyDecision(request, decision, 5L) }

        val opRun = refreshed.taskRuns.getValue(op)
        assertEquals(TaskRunStatus.Cancelled, opRun.status)
        assertEquals(WorkflowRunFactory.APPROVAL_NOT_GRANTED, opRun.blockingReason?.code)
        assertEquals("No human approval was granted for this repository change; it will not run.", opRun.blockingReason?.message)

        val event = sink.snapshot().filterIsInstance<PolicyDecisionRecorded>().single()
        assertEquals(runId, event.workflowRunId)
        assertEquals(op, event.taskDefinitionId)
        assertEquals(PolicyEffect.Deny, event.effect)
        assertEquals(PolicyCodes.REPOSITORY_MUTATION_WITHOUT_APPROVAL, event.code)
        assertEquals("repository.operation", event.action)
        assertEquals("task", event.resourceType)
        assertEquals(op.value, event.resourceId)
    }

    @Test
    fun grantedMutationRecordsNoEvent() {
        val gated = definition("push")
        var run: WorkflowRun = WorkflowRunFactory.create(
            definition = gated,
            workflowRunId = runId,
            projectId = ProjectId("project"),
            objective = "test",
            nowEpochMillis = 1L,
            taskRunIdFactory = { TaskRunId(it.value) },
        )
        run = run.copy(
            taskRuns = run.taskRuns +
                (build to run.taskRuns.getValue(build).copy(status = TaskRunStatus.Completed)) +
                (approve to run.taskRuns.getValue(approve).copy(status = TaskRunStatus.Completed)),
        )
        val decisions = mutableListOf<PolicyDecision>()
        val refreshed = WorkflowRunFactory.refreshReadiness(gated, run, 5L) { _, decision -> decisions += decision }
        assertEquals(TaskRunStatus.Ready, refreshed.taskRuns.getValue(op).status)
        assertTrue(decisions.isEmpty())
    }

    @Test
    fun unauthorizedGateDeciderIsRefusedAndRecorded() = runBlocking {
        val repository = PolicyTestGateRepository()
        val sink = InMemoryWorkflowEventSink()
        val coordinator = ApprovalGateCoordinator(repository, sink)
        val gate = coordinator.open(
            id = ApprovalGateId("gate"),
            workflowRunId = runId,
            taskDefinitionId = op,
            kind = ApprovalGateKind.PlanApproval,
            reason = "Plan approval required for 'op'",
            requiredRoleId = RoleDefinitionId("orchestrator"),
            nowEpochMillis = 1L,
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            coordinator.requireDecider(gate, decidedByRoleId = null, nowEpochMillis = 2L)
        }
        assertEquals("Role <human> is not authorized for gate gate", failure.message)
        val event = sink.snapshot().filterIsInstance<PolicyDecisionRecorded>().single()
        assertEquals(PolicyEffect.Deny, event.effect)
        assertEquals(PolicyCodes.GATE_DECIDER_NOT_AUTHORIZED, event.code)
        assertEquals("approval_gate.decide", event.action)
        assertEquals("human", event.subjectType)
        assertEquals("gate", event.resourceId)
        assertEquals(op, event.taskDefinitionId)
        assertEquals(2L, event.occurredAtEpochMillis)

        // The named role may decide, and an allowed decision adds nothing to the timeline.
        coordinator.requireDecider(gate, decidedByRoleId = RoleDefinitionId("orchestrator"), nowEpochMillis = 3L)
        assertEquals(1, sink.snapshot().filterIsInstance<PolicyDecisionRecorded>().size)
    }

    @Test
    fun planGateRequirementIsRecordedAsRequireApproval() = runBlocking {
        val sink = InMemoryWorkflowEventSink()
        val request = PolicyRequest(
            subject = PolicySubject.System,
            action = PolicyAction.ProceedPastPlan(ApprovalPolicy.HumanApproval, providerRequested = true),
            resource = PolicyResource.task(op),
            context = PolicyContext(workflowRunId = runId, taskDefinitionId = op),
        )
        val decision = pdp.decideAndRecord(request, sink, 7L)
        assertIs<PolicyDecision.RequireApproval>(decision)
        val event = sink.snapshot().filterIsInstance<PolicyDecisionRecorded>().single()
        assertEquals(PolicyEffect.RequireApproval, event.effect)
        assertEquals(PolicyCodes.PLAN_APPROVAL_REQUIRED, event.code)
        assertEquals("plan.proceed", event.action)
    }
}

private class PolicyTestGateRepository : ApprovalGateRepository {
    private val values = mutableMapOf<ApprovalGateId, ApprovalGate>()
    override suspend fun put(gate: ApprovalGate) { values[gate.id] = gate }
    override suspend fun get(id: ApprovalGateId): ApprovalGate? = values[id]
    override suspend fun unresolved(workflowRunId: WorkflowRunId): List<ApprovalGate> =
        values.values.filter { it.workflowRunId == workflowRunId && it.status == ApprovalGateStatus.Pending }
}
