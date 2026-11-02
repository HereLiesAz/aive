package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
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
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TaskRerunPlannerTest {
    private val a = TaskDefinitionId("a")
    private val b = TaskDefinitionId("b")
    private val c = TaskDefinitionId("c")
    private val d = TaskDefinitionId("d")
    private val approve = TaskDefinitionId("approve")
    private val push = TaskDefinitionId("push")
    private val role = BuiltInRoles.Architect.id

    private fun agent(id: TaskDefinitionId, vararg dependsOn: TaskDefinitionId) = TaskDefinition(
        id = id,
        name = id.value,
        objective = "Do ${id.value}",
        roleId = role,
        dependsOn = dependsOn.toSet(),
    )

    private fun definition(vararg tasks: TaskDefinition) = WorkflowDefinition(
        id = WorkflowDefinitionId("wf"),
        name = "Rerun",
        tasks = tasks.toList(),
        testDesignPolicy = TestDesignPolicy.None,
    )

    /** a -> b -> c, and d beside b (also fed by a). */
    private val chain = definition(agent(a), agent(b, a), agent(c, b), agent(d, a))

    private fun text(taskRunId: TaskRunId, body: String) = ArtifactRef(
        id = ArtifactId("${taskRunId.value}:out"),
        kind = ArtifactKind.Specification,
        taskRunId = taskRunId,
        label = "Output",
        textContent = body,
        createdAtEpochMillis = 5L,
    )

    /** A finished source run: every task Completed with a text output unless [statuses] says otherwise. */
    private fun finished(
        definition: WorkflowDefinition,
        statuses: Map<TaskDefinitionId, TaskRunStatus> = emptyMap(),
        runStatus: WorkflowRunStatus = WorkflowRunStatus.Completed,
    ): WorkflowRun {
        val created = WorkflowRunFactory.create(
            definition = definition,
            workflowRunId = WorkflowRunId("source"),
            projectId = ProjectId("project"),
            objective = "Ship it",
            nowEpochMillis = 1L,
            taskRunIdFactory = { TaskRunId("source-${it.value}") },
            roles = BuiltInRoles.all,
        )
        return created.copy(
            status = runStatus,
            taskRuns = created.taskRuns.mapValues { (id, taskRun) ->
                val status = statuses[id] ?: TaskRunStatus.Completed
                taskRun.copy(
                    status = status,
                    attempt = 2,
                    artifacts = if (status == TaskRunStatus.Completed) listOf(text(taskRun.id, "${id.value} v1")) else emptyList(),
                )
            },
        )
    }

    private fun fork(
        definition: WorkflowDefinition,
        source: WorkflowRun,
        request: TaskRerunRequest,
    ) = TaskRerunPlanner.fork(
        definition = definition,
        source = source,
        request = request,
        workflowRunId = WorkflowRunId("rerun"),
        nowEpochMillis = 10L,
        taskRunIdFactory = { TaskRunId("rerun-${it.value}") },
        editedDefinitionId = WorkflowDefinitionId("wf-edited"),
    )

    @Test
    fun rerunningAMiddleTaskRestartsItAndItsDescendantsOnlyAndKeepsUpstreamResults() {
        val source = finished(chain)
        val fork = fork(chain, source, TaskRerunRequest(b))
        val run = fork.run

        assertEquals(WorkflowRunId("rerun"), run.id)
        assertEquals(listOf(source.id), run.parentWorkflowRunIds, "a rerun continues the run it came from")
        assertEquals(source.projectId, run.projectId)
        assertSame(chain, fork.definition, "an unchanged objective reuses the definition")

        // Upstream and the untouched sibling keep their results, under the new run's own ids.
        listOf(a, d).forEach { id ->
            val kept = run.taskRuns.getValue(id)
            assertEquals(TaskRunStatus.Completed, kept.status)
            assertEquals(TaskRunId("rerun-${id.value}"), kept.id)
            val artifact = kept.artifacts.single()
            assertEquals("${id.value} v1", artifact.textContent)
            assertEquals(kept.id, artifact.taskRunId)
            assertNotEquals(source.taskRuns.getValue(id).artifacts.single().id, artifact.id)
            assertEquals(
                source.taskRuns.getValue(id).artifacts.single().id.value,
                artifact.metadata[TaskRerunPlanner.CARRIED_FROM_METADATA],
            )
        }
        // The chosen task and what follows start fresh: not started, a new attempt, no output.
        assertEquals(TaskRunStatus.Ready, run.taskRuns.getValue(b).status)
        assertEquals(TaskRunStatus.Blocked, run.taskRuns.getValue(c).status)
        listOf(b, c).forEach { id ->
            assertEquals(1, run.taskRuns.getValue(id).attempt)
            assertTrue(run.taskRuns.getValue(id).artifacts.isEmpty())
        }
        assertEquals(listOf(b, c), fork.event.rerunTaskIds)
        assertEquals(source.id, fork.event.fromWorkflowRunId)
        assertEquals(run.id, fork.event.workflowRunId)
        // The source run is history: it is not changed.
        assertEquals(finished(chain), source)
    }

    @Test
    fun anEditedObjectiveIsWhatTheRerunTaskIsAskedAndUpstreamOutputReachesIt() = runBlocking {
        val source = finished(chain)
        val fork = fork(chain, source, TaskRerunRequest(b, editedObjective = "  Do b better  "))

        assertEquals(WorkflowDefinitionId("wf-edited"), fork.definition.id)
        assertEquals(fork.definition.id, fork.run.workflowDefinitionId)
        assertEquals("Do b better", fork.definition.tasks.single { it.id == b }.objective)
        assertEquals("Do b", chain.tasks.single { it.id == b }.objective, "the source definition keeps its objective")
        assertEquals("Do b better", fork.event.editedObjective)

        val gateway = CapturingRerunGateway()
        val engine = WorkflowEngine(sessionGateway = gateway, roles = BuiltInRoles.all)
        engine.dispatchReadyTasks(project(), fork.definition, fork.run, nowEpochMillis = 11L)

        val request = gateway.requests.single().taskRequest
        assertEquals(TaskRunId("rerun-b"), request.taskRunId, "only the rerun task is dispatched")
        assertEquals("Do b better", request.objective)
        assertEquals(listOf("a v1"), request.contextArtifacts.map { it.textContent })
    }

    @Test
    fun replacingOutputKeepsTheTaskAndRerunsOnlyWhatFollowsWithTheKeepersText() = runBlocking {
        val source = finished(chain)
        val fork = fork(chain, source, TaskRerunRequest(b, replacementOutput = "b by the keeper"))
        val run = fork.run

        val edited = run.taskRuns.getValue(b)
        assertEquals(TaskRunStatus.Completed, edited.status)
        val artifact = edited.artifacts.single()
        assertEquals("b by the keeper", artifact.textContent)
        assertEquals(ArtifactKind.Specification, artifact.kind)
        assertEquals(TaskRerunPlanner.EDITED_BY_KEEPER, artifact.metadata[TaskRerunPlanner.EDITED_BY_METADATA])
        assertEquals(source.taskRuns.getValue(b).artifacts.single().id.value, artifact.metadata[TaskRerunPlanner.REPLACES_METADATA])
        assertEquals(TaskRunStatus.Ready, run.taskRuns.getValue(c).status)
        assertEquals(TaskRunStatus.Completed, run.taskRuns.getValue(a).status)
        assertEquals(TaskRunStatus.Completed, run.taskRuns.getValue(d).status)
        assertEquals(listOf(c), fork.event.rerunTaskIds)
        assertTrue(fork.event.outputReplaced)

        val gateway = CapturingRerunGateway()
        WorkflowEngine(sessionGateway = gateway, roles = BuiltInRoles.all)
            .dispatchReadyTasks(project(), fork.definition, run, nowEpochMillis = 11L)
        val request = gateway.requests.single().taskRequest
        assertEquals(TaskRunId("rerun-c"), request.taskRunId)
        assertEquals(listOf("b by the keeper"), request.contextArtifacts.map { it.textContent })
    }

    @Test
    fun unfinishedSiblingsRunAgainAndAnUnfinishedRunCannotBeRerun() {
        val failed = finished(
            chain,
            statuses = mapOf(d to TaskRunStatus.Failed),
            runStatus = WorkflowRunStatus.Failed,
        )
        val fork = fork(chain, failed, TaskRerunRequest(b))
        assertEquals(TaskRunStatus.Completed, fork.run.taskRuns.getValue(a).status)
        assertEquals(TaskRunStatus.Ready, fork.run.taskRuns.getValue(d).status, "work that had not finished runs again")
        assertEquals(listOf(b, c, d), fork.event.rerunTaskIds)

        val live = finished(chain, runStatus = WorkflowRunStatus.Running)
        assertFailsWith<IllegalArgumentException> { fork(chain, live, TaskRerunRequest(b)) }
        assertFailsWith<IllegalArgumentException> { fork(chain, failed, TaskRerunRequest(TaskDefinitionId("missing"))) }
        assertFailsWith<IllegalArgumentException> {
            fork(chain, failed, TaskRerunRequest(b, editedObjective = "x", replacementOutput = "y"))
        }
        assertFailsWith<IllegalArgumentException>("a leaf has nothing after it to rerun") {
            fork(chain, failed, TaskRerunRequest(c, replacementOutput = "c by the keeper"))
        }
    }

    @Test
    fun aRepositoryChangeThatRunsAgainNeverReusesAnEarlierApproval() {
        // approve -> b -> push: the approval sits above the task that is run again.
        val guarded = definition(
            agent(a),
            TaskDefinition(
                id = approve,
                name = "Approve",
                objective = "Approve",
                roleId = null,
                executor = TaskExecutor.HumanApproval(),
                dependsOn = setOf(a),
            ),
            agent(b, approve),
            TaskDefinition(
                id = push,
                name = "Push",
                objective = "Push",
                roleId = null,
                executor = TaskExecutor.RepositoryOperation("push"),
                dependsOn = setOf(b),
            ),
        )
        val source = finished(guarded)
        val fork = fork(guarded, source, TaskRerunRequest(b))

        assertEquals(setOf(approve, b, push), TaskRerunPlanner.rerunSet(guarded, setOf(b)))
        assertEquals(TaskRunStatus.Completed, fork.run.taskRuns.getValue(a).status)
        assertNotEquals(TaskRunStatus.Completed, fork.run.taskRuns.getValue(approve).status)
        assertNotEquals(TaskRunStatus.Completed, fork.run.taskRuns.getValue(push).status)
        // Replacing b's output would carry b past an approval its change was never given.
        assertFailsWith<IllegalArgumentException> {
            fork(guarded, source, TaskRerunRequest(b, replacementOutput = "b by the keeper"))
        }
        // A fresh approval already between the rerun task and the change is enough.
        assertEquals(setOf(approve, b, push), TaskRerunPlanner.rerunSet(guarded, setOf(approve)))
    }

    private fun project() = Project(
        id = ProjectId("project"),
        name = "Rerun",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )
}

private class CapturingRerunGateway : ManagedSessionGateway {
    private val providerId = AgentProviderId("rerun-provider")
    val requests = mutableListOf<ManagedSessionRequest>()

    override suspend fun resolveProvider(selection: ProviderSelectionRequest): AgentProviderId = providerId

    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle {
        requests += request
        return ManagedSessionHandle(
            taskRunId = request.taskRequest.taskRunId,
            providerId = providerId,
            providerRunId = ProviderRunId("provider-${request.taskRequest.taskRunId.value}"),
        )
    }

    override suspend fun status(handle: ManagedSessionHandle): ManagedSessionStatus = ManagedSessionStatus.Running

    override suspend fun message(
        handle: ManagedSessionHandle,
        message: String,
    ): ProviderActionResult = ProviderActionResult.Accepted

    override suspend fun approvePlan(handle: ManagedSessionHandle): ProviderActionResult = ProviderActionResult.Accepted

    override suspend fun artifacts(handle: ManagedSessionHandle): List<ProviderArtifact> = emptyList()
}
