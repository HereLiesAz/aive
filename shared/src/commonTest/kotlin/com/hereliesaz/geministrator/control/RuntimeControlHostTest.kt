package com.hereliesaz.geministrator.control

import com.hereliesaz.geministrator.ApplicationRuntime
import com.hereliesaz.geministrator.azphalt.AuthoredWorkflowStore
import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
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
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.workflow.ApprovalGate
import com.hereliesaz.geministrator.workflow.ApprovalGateKind
import com.hereliesaz.geministrator.workflow.ApprovalGateStatus
import com.hereliesaz.geministrator.workflow.WorkflowGraphValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class Authored(initial: Set<WorkflowDefinitionId> = emptySet()) : AuthoredWorkflowStore {
    private val ids = initial.toMutableSet()
    override suspend fun all(): Set<WorkflowDefinitionId> = ids.toSet()
    override suspend fun add(id: WorkflowDefinitionId) { ids += id }
    override suspend fun remove(id: WorkflowDefinitionId) { ids -= id }
}

class RuntimeControlHostTest {
    private val project = Project(ProjectId("p"), "p", createdAtEpochMillis = 0, updatedAtEpochMillis = 0)

    private fun withHost(
        authored: Authored = Authored(),
        block: suspend (RuntimeControlHost, InMemoryWorkflowPersistence, Authored) -> Unit,
    ): Unit = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = ApplicationRuntime.create(providers = emptyList(), scope = scope, persistence = persistence)
            block(RuntimeControlHost({ runtime }, authored), persistence, authored)
        } finally {
            scope.cancel()
        }
    }

    private fun agent(id: String, vararg deps: String, approval: Boolean = false) = buildJsonObject {
        put("id", id)
        put("name", id)
        put("roleId", "product-manager")
        if (deps.isNotEmpty()) put("dependsOn", buildJsonArray { deps.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        if (approval) put("requiresHumanApproval", true)
    }

    private fun gate(id: String, vararg deps: String) = buildJsonObject {
        put("id", id)
        put("name", id)
        put("kind", "approval")
        if (deps.isNotEmpty()) put("dependsOn", buildJsonArray { deps.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
    }

    private suspend fun RuntimeControlHost.makeHive(name: String = "Plan", vararg creatures: JsonObject): JsonObject =
        createHive(buildJsonObject { put("name", name); put("creatures", JsonArray(creatures.toList())) })

    private fun denied(code: ControlErrorCode, block: suspend () -> Unit) = runBlocking {
        val e = assertFailsWith<ControlException> { block() }
        assertEquals(code, e.code, e.message)
    }

    @Test
    fun createsAHiveOfAgentsAndAGateAndListsIt() = withHost { host, persistence, authored ->
        val made = host.makeHive("Ship it", agent("spec"), agent("build", "spec"), gate("sign-off", "build"))
        val id = made["id"]!!.jsonPrimitive.content
        assertEquals("ship-it", id)
        assertEquals(3, made["creatureList"]!!.jsonArray.size)
        assertTrue(WorkflowGraphValidator.validate(persistence.definitions.get(WorkflowDefinitionId(id))!!).isEmpty())
        assertTrue(WorkflowDefinitionId(id) in authored.all())
        assertEquals("ship-it", host.listHives().jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        val kinds = host.getHive(id)["creatureList"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content }
        assertEquals(listOf("agent", "agent", "approval"), kinds)
    }

    @Test
    fun onlyAgentsAndApprovalGatesCanBeAuthored() = withHost { host, _, _ ->
        // A repository change, a script or a deployment cannot be made here, however it is asked.
        for (kind in listOf("repository_operation", "script", "deployment", "github_action", "nested_workflow")) {
            denied(ControlErrorCode.InvalidArgument) {
                host.makeHive("X", buildJsonObject { put("name", "c"); put("kind", kind); put("roleId", "product-manager") })
            }
        }
        // Nor can an executor be smuggled in as a field.
        denied(ControlErrorCode.InvalidArgument) {
            host.makeHive("X", buildJsonObject { put("name", "c"); put("roleId", "product-manager"); put("executor", "RepositoryOperation") })
        }
        denied(ControlErrorCode.NotFound) { host.makeHive("X", buildJsonObject { put("name", "c"); put("roleId", "no-such-role") }) }
        denied(ControlErrorCode.InvalidArgument) { host.makeHive("X") }
    }

    @Test
    fun aRepositoryMutationStillNeedsAHumanApproval() = withHost { host, persistence, authored ->
        fun repoTask(op: String, deps: Set<String>) = TaskDefinition(
            id = TaskDefinitionId("push"), name = "push", objective = "o", roleId = null,
            executor = TaskExecutor.RepositoryOperation(op), dependsOn = deps.map(::TaskDefinitionId).toSet(),
        )
        val approval = TaskDefinition(
            id = TaskDefinitionId("ok"), name = "ok", objective = "o", roleId = null, executor = TaskExecutor.HumanApproval(),
        )
        // The validator, which the control API runs before it saves anything, refuses a mutation with no gate ahead of it.
        val bare = WorkflowDefinition(WorkflowDefinitionId("bare"), "bare", tasks = listOf(repoTask("push", emptySet())))
        assertTrue(WorkflowGraphValidator.validate(bare).isNotEmpty())
        val gated = WorkflowDefinition(WorkflowDefinitionId("gated"), "gated", tasks = listOf(approval, repoTask("push", setOf("ok"))))
        assertTrue(WorkflowGraphValidator.validate(gated).isEmpty())

        // Over the control API that gate cannot be removed, relaxed or rewired away.
        persistence.definitions.put(gated)
        authored.add(gated.id)
        denied(ControlErrorCode.PolicyDenied) { host.removeCreature("gated", "ok") }
        denied(ControlErrorCode.PolicyDenied) { host.updateCreature("gated", "push", buildJsonObject { put("dependsOn", JsonArray(emptyList())) }) }
        assertEquals(gated, persistence.definitions.get(gated.id))
    }

    @Test
    fun aHumanApprovalCanBeAddedButNotTakenAway() = withHost { host, persistence, _ ->
        val id = host.makeHive("H", agent("a", approval = true), gate("g", "a"))["id"]!!.jsonPrimitive.content
        denied(ControlErrorCode.PolicyDenied) { host.updateCreature(id, "a", buildJsonObject { put("requiresHumanApproval", false) }) }
        denied(ControlErrorCode.PolicyDenied) { host.removeCreature(id, "a") }
        denied(ControlErrorCode.PolicyDenied) { host.removeCreature(id, "g") }
        val stored = persistence.definitions.get(WorkflowDefinitionId(id))!!
        assertEquals(ApprovalPolicy.HumanApproval, stored.tasks.first { it.id.value == "a" }.approvalPolicy)
        assertTrue(stored.tasks.any { it.executor is TaskExecutor.HumanApproval })

        val other = host.makeHive("Other", agent("b"))["id"]!!.jsonPrimitive.content
        host.updateCreature(other, "b", buildJsonObject { put("requiresHumanApproval", true) })
        assertEquals(ApprovalPolicy.HumanApproval, persistence.definitions.get(WorkflowDefinitionId(other))!!.tasks.single().approvalPolicy)
    }

    @Test
    fun editsKeepTheGraphValid() = withHost { host, _, _ ->
        val id = host.makeHive("H", agent("a"), agent("b", "a"))["id"]!!.jsonPrimitive.content
        denied(ControlErrorCode.InvalidArgument) { host.updateCreature(id, "a", buildJsonObject { put("dependsOn", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("b")) }) }) }
        denied(ControlErrorCode.InvalidArgument) { host.addCreature(id, agent("c", "ghost")) }
        host.addCreature(id, agent("c", "b"))
        val after = host.removeCreature(id, "b")
        // Removing a creature takes the strands that touched it.
        assertEquals(listOf("a", "c"), after["creatureList"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(0, after["creatureList"]!!.jsonArray.last().jsonObject["dependsOn"]!!.jsonArray.size)
    }

    @Test
    fun onlyHivesMadeInTheAppCanBeChangedOrDeleted() = withHost { host, persistence, _ ->
        val installed = WorkflowDefinition(
            WorkflowDefinitionId("installed"), "Installed",
            tasks = listOf(TaskDefinition(TaskDefinitionId("t"), "t", "o", null, executor = TaskExecutor.HumanApproval())),
        )
        persistence.definitions.put(installed)
        denied(ControlErrorCode.Conflict) { host.deleteHive("installed") }
        denied(ControlErrorCode.Conflict) { host.updateHive("installed", buildJsonObject { put("name", "x") }) }
        assertNotNull(persistence.definitions.get(installed.id))
        denied(ControlErrorCode.NotFound) { host.deleteHive("nope") }
    }

    @Test
    fun deletingIsRefusedWhileARunIsLive() = withHost { host, persistence, _ ->
        val id = host.makeHive("Live", agent("a"))["id"]!!.jsonPrimitive.content
        persistence.projects.put(project)
        persistence.runs.put(run(id, "r1", WorkflowRunStatus.Running))
        denied(ControlErrorCode.Conflict) { host.deleteHive(id) }
        denied(ControlErrorCode.Conflict) { host.removeCreature(id, "a") }
        assertNotNull(persistence.definitions.get(WorkflowDefinitionId(id)))
        persistence.runs.put(run(id, "r1", WorkflowRunStatus.Completed))
        host.deleteHive(id)
        assertNull(persistence.definitions.get(WorkflowDefinitionId(id)))
        assertNotNull(persistence.runs.get(WorkflowRunId("r1")), "Past runs stay")
    }

    @Test
    fun aProviderThatIsNotLinkedCannotBeAssigned() = withHost { host, _, _ ->
        denied(ControlErrorCode.NotFound) { host.setRoleProvider("product-manager", "not-linked") }
        denied(ControlErrorCode.NotFound) { host.setRoleProvider("no-such-role", null) }
        val role = host.setRoleProvider("product-manager", null)
        assertEquals("product-manager", role["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun withoutARuntimeEverythingIsUnavailable() = runBlocking {
        val host = RuntimeControlHost({ null }, Authored())
        assertEquals(false, host.status()["runtimeReady"]!!.jsonPrimitive.content.toBoolean())
        val e = assertFailsWith<ControlException> { host.listHives() }
        assertEquals(ControlErrorCode.Unavailable, e.code)
        val noPlanner = assertFailsWith<ControlException> { host.draftHive("x") }
        assertEquals(ControlErrorCode.Unavailable, noPlanner.code)
    }

    @Test
    fun approvalsAreListedButNoControlRequestResolvesOne() = withHost { host, persistence, _ ->
        val hiveId = host.makeHive("Gate", agent("a"), gate("g", "a"))["id"]!!.jsonPrimitive.content
        persistence.projects.put(project)
        val awaiting = run(hiveId, "r1", WorkflowRunStatus.AwaitingHuman).copy(
            taskRuns = mapOf(
                TaskDefinitionId("g") to TaskRun(TaskRunId("r1-g"), TaskDefinitionId("g"), TaskRunStatus.AwaitingApproval, assignedRoleId = null),
            ),
        )
        persistence.runs.put(awaiting)
        val pending = ApprovalGate(
            id = ApprovalGateId("gate-1"), workflowRunId = awaiting.id, taskDefinitionId = TaskDefinitionId("g"),
            kind = ApprovalGateKind.PlanApproval, reason = "Approve the plan", requiresHuman = true, createdAtEpochMillis = 1,
        )
        persistence.approvalGates.put(pending)

        val listed = host.listApprovals().jsonObject
        assertEquals("gate-1", listed["gates"]!!.jsonArray.single().jsonObject["gateId"]!!.jsonPrimitive.content)
        assertEquals("g", listed["creaturesWaiting"]!!.jsonArray.single().jsonObject["creatureId"]!!.jsonPrimitive.content)

        // Hit every route the API has, every method, with the gate and run ids filled in.
        val token = "tok-0123456789ABCDEFGHIJKLMNOPQRSTUV"
        val api = ControlApi(host) { token }
        val headers = mapOf("Host" to "127.0.0.1", "Authorization" to "Bearer $token")
        for (route in ControlApi.routes) for (method in ControlMethod.entries) {
            if (ControlApi.routes.any { it.pattern == route.pattern && it.method == method }) continue
            // `/hives/draft` is also `/hives/{id}` for a hive called draft; that is a different, legitimate route.
            if (route.pattern == "/hives/draft") continue
            val path = route.pattern.replace("{id}", "r1").replace("{cid}", "g")
            val r = api.handle(method.name, ControlApi.PREFIX + path, headers, """{"decision":"approve"}""", ControlCaller.Loopback)
            assertTrue(r.status in setOf(404, 405), "$method $path answered ${r.status}")
        }
        for (path in listOf("/approvals/gate-1/approve", "/gates/gate-1/approve", "/runs/r1/gates/gate-1", "/approvals/gate-1")) for (method in listOf("POST", "PUT", "PATCH", "DELETE")) {
            val r = api.handle(method, ControlApi.PREFIX + path, headers, """{"approve":true}""", ControlCaller.Loopback)
            assertTrue(r.status in setOf(404, 405), "$method $path answered ${r.status}")
        }
        // Stopping the run is the strongest thing an agent can do to it, and it does not decide the gate.
        runCatching { host.stopRun("r1") }
        val still = persistence.approvalGates.get(ApprovalGateId("gate-1"))!!
        assertEquals(ApprovalGateStatus.Pending, still.status)
        assertNull(still.decidedAtEpochMillis)
        assertNull(still.decidedByRoleId)
        assertEquals(TaskRunStatus.AwaitingApproval, persistence.runs.get(awaiting.id)!!.taskRuns.getValue(TaskDefinitionId("g")).status)
    }

    private fun run(hiveId: String, id: String, status: WorkflowRunStatus) = WorkflowRun(
        id = WorkflowRunId(id), projectId = project.id, workflowDefinitionId = WorkflowDefinitionId(hiveId),
        objective = "o", status = status, taskRuns = emptyMap(), createdAtEpochMillis = 1, updatedAtEpochMillis = 2,
    )
}
