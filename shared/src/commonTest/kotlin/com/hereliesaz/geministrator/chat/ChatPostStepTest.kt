package com.hereliesaz.geministrator.chat

import com.hereliesaz.geministrator.TerrariumDependencyEditResult
import com.hereliesaz.geministrator.TerrariumNewcomer
import com.hereliesaz.geministrator.addTerrariumCreature
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
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
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.workflow.TaskExecutorContext
import com.hereliesaz.geministrator.workflow.WorkflowGraphValidator
import com.hereliesaz.geministrator.workflow.WorkflowValidationError
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatPostStepTest {
    private val writer = TaskDefinition(TaskDefinitionId("writer"), "Writer", "Write", roleId = null, executor = TaskExecutor.TestRunner())
    private val approval = ChatPostStep.approvalTask(TaskDefinitionId("gate"))
    private val post = ChatPostStep.postTask(TaskDefinitionId("post"), approval.id).let {
        it.copy(dependsOn = it.dependsOn + writer.id)
    }

    private fun hive(vararg tasks: TaskDefinition) = WorkflowDefinition(
        id = WorkflowDefinitionId("h"), name = "H", tasks = tasks.toList(), testDesignPolicy = TestDesignPolicy.None,
    )

    @Test
    fun aPostWithNoApprovalBeforeItIsRefusedByTheValidator() {
        val bare = post.copy(dependsOn = setOf(writer.id))
        val errors = WorkflowGraphValidator.validate(hive(writer, bare))
        assertTrue(errors.any { it is WorkflowValidationError.MissingStepApproval }, errors.toString())
        assertEquals(emptyList(), WorkflowGraphValidator.validate(hive(writer, approval, post)))
    }

    @Test
    fun addCreatureMakesTheApprovalAndThePostTogetherAndValid() {
        val base = hive(writer)
        val applied = assertIs<TerrariumDependencyEditResult.Applied>(addTerrariumCreature(base, TerrariumNewcomer.PostToChat)).definition
        val added = applied.tasks.drop(1)
        assertEquals(2, added.size)
        assertTrue(added[0].executor is TaskExecutor.HumanApproval)
        assertTrue(ChatPostStep.isPost(added[1]))
        assertEquals(setOf(added[0].id), added[1].dependsOn)
        assertEquals(emptyList(), WorkflowGraphValidator.validate(applied))
    }

    private fun context(approvalStatus: TaskRunStatus, writerText: String?): TaskExecutorContext {
        val definition = hive(writer, approval, post)
        val project = Project(ProjectId("p"), "P", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)
        val writerRun = TaskRun(
            id = TaskRunId("w"), taskDefinitionId = writer.id, status = TaskRunStatus.Completed, assignedRoleId = null,
            artifacts = listOfNotNull(
                writerText?.let {
                    ArtifactRef(ArtifactId("a"), ArtifactKind.Research, TaskRunId("w"), "out", textContent = it, createdAtEpochMillis = 1L)
                },
            ),
        )
        val gateRun = TaskRun(id = TaskRunId("g"), taskDefinitionId = approval.id, status = approvalStatus, assignedRoleId = null)
        val postRun = TaskRun(id = TaskRunId("pr"), taskDefinitionId = post.id, status = TaskRunStatus.Ready, assignedRoleId = null, executor = post.executor)
        val run = WorkflowRun(
            id = WorkflowRunId("r"), projectId = project.id, workflowDefinitionId = definition.id, objective = "o",
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(writer.id to writerRun, approval.id to gateRun, post.id to postRun),
            createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L,
        )
        return TaskExecutorContext(project, definition, run, post, postRun, post.executor!!, 5L)
    }

    @Test
    fun theExecutorSendsOnlyAfterTheApprovalWasGranted(): Unit = runBlocking {
        val sent = mutableListOf<String>()
        val integration = ChatPostExecutorIntegration { text -> sent += text; 1 }
        val refused = integration.dispatch(context(TaskRunStatus.AwaitingApproval, "hello"))
        assertEquals(TaskRunStatus.Failed, refused.status)
        assertEquals(emptyList(), sent)
        val done = integration.dispatch(context(TaskRunStatus.Completed, "hello"))
        assertEquals(TaskRunStatus.Completed, done.status)
        assertEquals(listOf("hello"), sent)
        // What was posted is not kept in the artifact.
        assertTrue("hello" !in done.artifacts.single().textContent.orEmpty())
    }

    @Test
    fun theTextIsScrubbedLinkFreeAndBoundedBeforeItLeaves(): Unit = runBlocking {
        val sent = mutableListOf<String>()
        val integration = ChatPostExecutorIntegration { text -> sent += text; 1 }
        val long = "see https://evil.example/x key sk-abcdefghijklmnopqrstuvwxyz " + "w".repeat(9_000)
        integration.dispatch(context(TaskRunStatus.Completed, long))
        val text = sent.single()
        assertTrue(text.length <= ChatPostStep.MAX_CHARS)
        assertTrue("evil.example" !in text && "sk-abc" !in text, text.take(80))
    }

    @Test
    fun nothingToPostOrNoPairedChatFailsThePlainWay(): Unit = runBlocking {
        val none = ChatPostExecutorIntegration { 0 }
        assertEquals(TaskRunStatus.Failed, none.dispatch(context(TaskRunStatus.Completed, "hi")).status)
        val empty = ChatPostExecutorIntegration { 1 }
        assertEquals(TaskRunStatus.Failed, empty.dispatch(context(TaskRunStatus.Completed, null)).status)
    }

    @Test
    fun aRestartNeverSendsAgain(): Unit = runBlocking {
        var calls = 0
        val integration = ChatPostExecutorIntegration { calls += 1; 1 }
        assertEquals(TaskRunStatus.Failed, integration.reconcile(context(TaskRunStatus.Completed, "x")).status)
        assertEquals(0, calls)
        assertEquals(false, integration.retryDispatchFailures)
    }

    @Test
    fun itSupportsOnlyItsOwnService() {
        val integration = ChatPostExecutorIntegration { 0 }
        assertTrue(integration.supports(TaskExecutor.ExternalService(CHAT_POST_SERVICE, "post")))
        assertEquals(false, integration.supports(TaskExecutor.ExternalService("vercel", "deploy")))
    }
}
