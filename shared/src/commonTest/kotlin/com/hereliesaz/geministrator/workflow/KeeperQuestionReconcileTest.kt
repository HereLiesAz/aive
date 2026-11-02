package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.agentui.AgentUi
import com.hereliesaz.geministrator.agentui.awaitsKeeperAnswer
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The engine carries an agent's question to the keeper onto its task (docs/Agent-UI.md). */
class KeeperQuestionReconcileTest {
    private val implementation = TaskDefinitionId("implementation")
    private val project = Project(
        id = ProjectId("project"),
        name = "The Aive",
        repository = RepositoryRef("HereLiesAz", "aive", "main"),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )
    private val definition = StarterWorkflowFactory.create(
        id = WorkflowDefinitionId("starter"),
        objective = "Ship the objective",
    ).copy(testDesignPolicy = TestDesignPolicy.None)
    private val question = "Which plan?\n```a2ui\n{\"surfaceUpdate\": {\"surfaceId\": \"s\", \"components\": []}}\n```"

    @Test
    fun aQuestionWaitsUntilAnsweredAndAReplayedAnswerStaysAnswered(): Unit = runBlocking {
        val gateway = QuestionGateway()
        val engine = WorkflowEngine(sessionGateway = gateway, roles = BuiltInRoles.all)
        val run = WorkflowRunFactory.create(
            definition = definition,
            workflowRunId = WorkflowRunId("run"),
            projectId = project.id,
            objective = "Ship the objective",
            nowEpochMillis = 1L,
            taskRunIdFactory = { id -> TaskRunId("run-${id.value}") },
        )
        val dispatched = engine.dispatchReadyTasks(project, definition, run, nowEpochMillis = 2L)
        suspend fun reconcile(current: WorkflowRun, now: Long) =
            engine.reconcile(definition, current, dispatched.handles, now) { _, _, index -> ArtifactId("a$index") }

        gateway.state = KeeperQuestionState(pending = question)
        val asked = reconcile(dispatched.run, 3L)
        val askedTask = asked.taskRuns.getValue(implementation)
        assertEquals(TaskRunStatus.Running, askedTask.status)
        assertEquals(question, askedTask.keeperQuestion)
        assertTrue(askedTask.awaitsKeeperAnswer())

        // A gateway that does not know (after a restart) keeps the question the task holds.
        gateway.state = null
        assertEquals(question, reconcile(asked, 4L).taskRuns.getValue(implementation).keeperQuestion)

        // Answered: the wait ends and the answer is remembered.
        gateway.state = KeeperQuestionState(pending = null, answeredKey = AgentUi.questionKey(question))
        val answered = reconcile(asked, 5L).taskRuns.getValue(implementation)
        assertNull(answered.keeperQuestion)
        assertFalse(answered.awaitsKeeperAnswer())
        assertEquals(AgentUi.questionKey(question), answered.answeredKeeperQuestion)

        // A provider replaying its history after a restart asks the same question again: still answered.
        gateway.state = KeeperQuestionState(pending = question)
        val replayed = reconcile(asked.copy(taskRuns = asked.taskRuns + (implementation to answered)), 6L)
        assertNull(replayed.taskRuns.getValue(implementation).keeperQuestion)

        // A new question is a new wait; a finished task waits on no one.
        gateway.state = KeeperQuestionState(pending = "$question\nAnd one more.")
        assertEquals(
            "$question\nAnd one more.",
            reconcile(asked.copy(taskRuns = asked.taskRuns + (implementation to answered)), 7L)
                .taskRuns.getValue(implementation).keeperQuestion,
        )
        gateway.status = ManagedSessionStatus.Completed
        assertNull(reconcile(asked, 8L).taskRuns.getValue(implementation).keeperQuestion)
    }
}

private class QuestionGateway : ManagedSessionGateway {
    private val providerId = AgentProviderId("question-provider")
    var state: KeeperQuestionState? = null
    var status: ManagedSessionStatus = ManagedSessionStatus.Running

    override suspend fun resolveProvider(selection: ProviderSelectionRequest): AgentProviderId = providerId

    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle =
        ManagedSessionHandle(request.taskRequest.taskRunId, providerId, ProviderRunId("question-run"))

    override suspend fun status(handle: ManagedSessionHandle): ManagedSessionStatus = status

    override suspend fun keeperQuestion(handle: ManagedSessionHandle): KeeperQuestionState? = state

    override suspend fun message(handle: ManagedSessionHandle, message: String): ProviderActionResult =
        ProviderActionResult.Accepted

    override suspend fun approvePlan(handle: ManagedSessionHandle): ProviderActionResult = ProviderActionResult.Accepted

    override suspend fun artifacts(handle: ManagedSessionHandle): List<ProviderArtifact> = emptyList()
}
