package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AcceptanceCriterion
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import no.synth.kmpzip.io.ByteArrayOutputStream
import no.synth.kmpzip.zip.ZipEntry
import no.synth.kmpzip.zip.ZipOutputStream
import kotlin.test.Test
import com.hereliesaz.geministrator.providers.PromptContextBlock
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenCodeActionsAgentProviderTest {
    private val repository = RepositoryRef(owner = "az", name = "app", defaultBranch = "main")

    private fun request(requirePlanApproval: Boolean = false) = AgentTaskRequest(
        taskRunId = TaskRunId("task-1"),
        objective = "Fix the add function",
        roleInstructions = "Implement only the task.",
        acceptanceCriteria = listOf(AcceptanceCriterion("add returns the sum")),
        repository = repository,
        requirePlanApproval = requirePlanApproval,
    )

    private fun provider(client: FakeRunnerClient) = OpenCodeActionsAgentProvider(
        client = client,
        workflowTemplate = { "template-v2" },
        pollIntervalMillis = 1,
    )

    @Test
    fun workflowUpdateWaitsForApprovalThenStreamsStepsAndReturnsBranch() = runBlocking<Unit> {
        val client = FakeRunnerClient(installed = "template-v1")
        val provider = provider(client)
        // No plan gate was requested, but committing the workflow file still needs approval.
        val runId = provider.start(request()).providerRunId

        val observed = async { provider.observe(runId).toList() }
        repeat(20) { yield() }
        assertEquals(null, client.written)
        assertEquals(null, client.dispatchedTask)

        provider.approvePlan(runId)
        val events = observed.await()

        val plan = events.filterIsInstance<AgentEvent.PlanGenerated>().single()
        assertContains(plan.summary, "update ${OpenCodeAgentWorkflow.PATH} by committing it directly to main")
        assertEquals("template-v2", client.written)
        assertContains(client.dispatchedTask.orEmpty(), "Fix the add function")
        assertContains(client.dispatchedTask.orEmpty(), OpenCodeActionsAgentProvider.runName(runId))
        val progress = events.filterIsInstance<AgentEvent.Progress>().map { it.message }
        assertTrue("Updating ${OpenCodeAgentWorkflow.PATH}" in progress)
        // Each step appears once even though the check run is re-read on every poll.
        assertEquals(1, progress.count { it == "bash: ls" })
        assertEquals(1, progress.count { it == "edit: calc.py" })
        val change = events.filterIsInstance<AgentEvent.ArtifactProduced>().single().artifact
        assertEquals(ArtifactKind.CodeChange, change.kind)
        assertEquals("aive/opencode-x", change.metadata["branch"])
        assertContains(change.textContent.orEmpty(), "+    return a + b")
        assertIs<AgentEvent.Completed>(events.last())
    }

    @Test
    fun waitsForPlanApprovalBeforeDispatch() = runBlocking<Unit> {
        val client = FakeRunnerClient(installed = "template-v2")
        val provider = provider(client)
        val runId = provider.start(request(requirePlanApproval = true)).providerRunId

        val observed = async { provider.observe(runId).toList() }
        repeat(20) { yield() }
        assertEquals(null, client.dispatchedTask)

        provider.approvePlan(runId)
        val events = observed.await()

        assertIs<AgentEvent.PlanGenerated>(events.first())
        assertTrue(events.any { it is AgentEvent.PlanApproved })
        assertEquals(null, client.written)
        assertIs<AgentEvent.Completed>(events.last())
    }

    @Test
    fun anApprovedEnginePlanThatDisclosesTheWorkflowUpdateDispatchesWithoutASecondGate() = runBlocking<Unit> {
        val client = FakeRunnerClient(installed = "template-v1")
        val provider = provider(client)

        val draft = provider.draftPlan(request(requirePlanApproval = true))
        assertContains(draft, "update ${OpenCodeAgentWorkflow.PATH} by committing it directly to main")
        assertEquals(null, client.written, "drafting must not touch the repository")

        val approved = request().let {
            it.copy(
                promptContext = it.promptContext.copy(
                    dynamicContext = it.promptContext.dynamicContext + PromptContextBlock(APPROVED_PLAN_CONTEXT_LABEL, draft),
                ),
            )
        }
        val events = provider.observe(provider.start(approved).providerRunId).toList()

        assertTrue(events.none { it is AgentEvent.PlanGenerated }, "the engine's approval already covered the workflow update")
        assertEquals("template-v2", client.written)
        assertIs<AgentEvent.Completed>(events.last())
    }

    @Test
    fun reconnectFollowsAnAlreadyDispatchedRunWithoutRedispatching() = runBlocking<Unit> {
        val client = FakeRunnerClient(installed = "template-v2")
        val provider = provider(client)
        val runId = ProviderRunId("opencode:task-1:abc")
        client.existingRunName = OpenCodeActionsAgentProvider.runName(runId)

        provider.reconnect(runId, request(), planGenerated = false, planApproved = false)
        val first = provider.observe(runId).first { it is AgentEvent.Progress }

        assertEquals(null, client.dispatchedTask)
        assertIs<AgentEvent.Progress>(first)
    }

    @Test
    fun approvedWorkflowInstallSurvivesProviderRestartAndReconnectsByRunName() = runBlocking<Unit> {
        val client = FakeRunnerClient(installed = null)
        val firstProvider = provider(client)
        val request = request(requirePlanApproval = false)
        val runId = ProviderRunId("opencode:task-1:restart")

        // Simulate the durable provider run that existed before process death.
        firstProvider.reconnect(
            runId = runId,
            request = request,
            planGenerated = true,
            planApproved = false,
            planPreview = null,
        )
        val firstObserve = async { firstProvider.observe(runId).toList() }
        repeat(20) { yield() }

        assertEquals(null, client.written)
        assertEquals(null, client.dispatchedTask)

        firstProvider.approvePlan(runId)
        repeat(100) {
            if (client.dispatchCount == 1) return@repeat
            yield()
        }
        assertEquals("template-v2", client.written)
        assertContains(client.dispatchedTask.orEmpty(), OpenCodeActionsAgentProvider.runName(runId))
        assertEquals(1, client.dispatchCount)
        firstObserve.cancelAndJoin()

        // Recreate the provider as a process restart would. The durable run ID + request + approved
        // plan state are restored by ApplicationRuntime; the GitHub run itself is rediscovered by
        // the deterministic run-name rather than dispatched again.
        client.existingRunName = OpenCodeActionsAgentProvider.runName(runId)
        client.polls = 0
        val restartedProvider = provider(client)
        restartedProvider.reconnect(
            runId = runId,
            request = request,
            planGenerated = true,
            planApproved = true,
            planPreview = "Approving this will install ${OpenCodeAgentWorkflow.PATH} by committing it directly to main.",
        )
        val resumedEvents = restartedProvider.observe(runId).toList()

        assertEquals(1, client.dispatchCount, "Restart must follow the existing workflow_dispatch run")
        assertTrue(resumedEvents.none { it is AgentEvent.PlanGenerated })
        assertTrue(resumedEvents.none { it is AgentEvent.PlanApproved })
        assertIs<AgentEvent.Completed>(resumedEvents.last())
    }

    @Test
    fun bundledWorkflowRunsOpenCodeAndStreamsToACheckRun() = runBlocking<Unit> {
        val template = OpenCodeAgentWorkflow.template()

        assertContains(template, "workflow_dispatch")
        assertContains(template, OpenCodeAgentWorkflow.TASK_INPUT)
        assertContains(template, "opencode\", [\"run\", \"--format\", \"json\"")
        assertContains(template, "checks: write")
    }

    @Test
    fun parsesSequencedStepLog() {
        assertEquals(
            listOf(1 to "Starting", 2 to "read: a.txt"),
            OpenCodeActionsAgentProvider.parseSteps("1\tStarting\n2\tread: a.txt\nnoise\n3\t  "),
        )
    }

    private class FakeRunnerClient(var installed: String?) : OpenCodeRunnerClient {
        var written: String? = null
        var dispatchedTask: String? = null
        var existingRunName: String? = null
        var dispatchCount: Int = 0
        var polls: Int = 0

        override suspend fun defaultBranch(repository: RepositoryRef) = "main"

        override suspend fun workflowFile(repository: RepositoryRef, branch: String) = installed

        override suspend fun writeWorkflowFile(repository: RepositoryRef, branch: String, content: String, message: String) {
            written = content
            installed = content
        }

        override suspend fun dispatch(repository: RepositoryRef, branch: String, taskJson: String): String {
            dispatchCount += 1
            dispatchedTask = taskJson
            existingRunName = Regex("\\\"checkName\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .find(taskJson)?.groupValues?.get(1)
            return "77"
        }

        override suspend fun findRun(repository: RepositoryRef, runName: String) =
            existingRunName?.takeIf { it == runName }?.let { OpenCodeWorkflowRunRef("77", it, "sha") }

        override suspend fun getRun(repository: RepositoryRef, runId: String): GitHubWorkflowRun {
            polls += 1
            val done = polls >= 3
            return GitHubWorkflowRun(
                id = runId,
                status = if (done) GitHubWorkflowRunStatus.Completed else GitHubWorkflowRunStatus.Running,
                artifacts = if (done) listOf(GitHubWorkflowArtifact("9", "aive-result", null)) else emptyList(),
                headSha = "sha",
            )
        }

        override suspend fun checkRunText(repository: RepositoryRef, headSha: String, checkName: String) =
            if (polls < 2) "1\tbash: ls" else "1\tbash: ls\n2\tedit: calc.py"

        override suspend fun downloadArtifact(repository: RepositoryRef, artifactId: String): ByteArray = zipOf(
            "aive-result.json",
            """{"status":"completed","branch":"aive/opencode-x","patch":"+    return a + b","summary":"Fixed","inputTokens":10,"outputTokens":2}""",
        )

        override suspend fun cancelRun(repository: RepositoryRef, runId: String) = Unit
    }
}

private fun zipOf(name: String, content: String): ByteArray {
    val output = ByteArrayOutputStream()
    val zip = ZipOutputStream(output)
    try {
        zip.putNextEntry(ZipEntry(name))
        val data = content.encodeToByteArray()
        zip.write(data, 0, data.size)
        zip.closeEntry()
    } finally {
        zip.close()
    }
    return output.toByteArray()
}
