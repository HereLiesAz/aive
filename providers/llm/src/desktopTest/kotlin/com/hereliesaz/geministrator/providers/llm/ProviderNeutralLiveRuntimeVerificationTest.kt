package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.ApplicationRuntime
import com.hereliesaz.geministrator.ApplicationRuntimeState
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.EscalationPolicy
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderConstraints
import com.hereliesaz.geministrator.domain.RetryPolicy
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.decideFailureEscalation
import com.hereliesaz.geministrator.persistence.RepositoryWorkflowEventSink
import com.hereliesaz.geministrator.persistence.SettingsWorkflowPersistence
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.workflow.AgentProviderRegistry
import com.hereliesaz.geministrator.workflow.TaskExecutorContext
import com.hereliesaz.geministrator.workflow.TaskExecutorExecution
import com.hereliesaz.geministrator.workflow.TaskExecutorIntegration
import com.hereliesaz.geministrator.workflow.TaskExecutorIntegrationRegistry
import com.hereliesaz.geministrator.workflow.WorkflowDefinitionPreparer
import com.hereliesaz.geministrator.workflow.WorkflowLaunchService
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in live acceptance test for the production runtime through the shared provider contract.
 *
 * Ordinary unit-test runs skip this test. Manual live verification opts in with
 * AIVE_LIVE_RUNTIME_VERIFICATION=1 and either AIVE_LIVE_PROVIDER, the first configured
 * OpenAI/Anthropic/Gemini/xAI credential, or a real local Ollama endpoint. Central acceptance
 * bootstraps Ollama automatically when no hosted-provider credential is configured.
 */
class ProviderNeutralLiveRuntimeVerificationTest {
    @Test
    fun launchApproveExecuteEscalateRestartResumeAndComplete() = runBlocking {
        if (System.getenv(OPT_IN_ENV) != "1") return@runBlocking

        val providerConfig = liveProviderConfig()

        val settings = MapSettings()
        val persistence = SettingsWorkflowPersistence(settings, storageKey = "aive.live.runtime.verification")
        val verifierIntegration = FailOnceVerificationIntegration()
        val executorIntegrations = TaskExecutorIntegrationRegistry(listOf(verifierIntegration))
        val firstProvider = liveProvider(providerConfig)
        val definition = verificationWorkflow(providerConfig.providerId)
        val project = Project(
            id = ProjectId("live-runtime-verification"),
            name = "Live runtime verification",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )

        WorkflowLaunchService(
            preparer = WorkflowDefinitionPreparer(
                providerRegistry = AgentProviderRegistry(listOf(firstProvider)),
                roles = BuiltInRoles.all,
            ),
            persistence = persistence,
            eventSink = RepositoryWorkflowEventSink(persistence.events),
            roles = BuiltInRoles.all,
        ).launch(
            project = project,
            definition = definition,
            workflowRunId = WorkflowRunId("live-runtime-verification-run"),
            objective = "Verify the real Aive runtime lifecycle through ${providerConfig.providerId.value}",
            nowEpochMillis = 1L,
            taskRunIdFactory = { TaskRunId("live-${it.value}") },
        )

        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstRuntime = ApplicationRuntime.create(
            providers = listOf(firstProvider),
            scope = firstScope,
            persistence = persistence,
            executorIntegrations = executorIntegrations,
        )

        val providerTaskId = TaskDefinitionId("provider-live")
        val failOnceTaskId = TaskDefinitionId("fail-once")
        val verificationTaskId = TaskDefinitionId("verification")
        val reviewTaskId = TaskDefinitionId("review")
        val releaseTaskId = TaskDefinitionId("release-approval")

        val (providerRunId, exportedProject) = try {
            val awaitingApproval = awaitLive(firstRuntime, "initial provider plan approval") { live ->
                live.presentation.run.taskRuns.getValue(providerTaskId).status == TaskRunStatus.AwaitingApproval
            }
            val taskRun = awaitingApproval.presentation.run.taskRuns.getValue(providerTaskId)
            assertEquals(WorkflowRunStatus.AwaitingHuman, awaitingApproval.presentation.run.status)
            assertEquals(null, taskRun.progress, "Provider plan/progress text must not fabricate a percentage")
            val runId = assertNotNull(taskRun.providerRunId)
            val projectExport = assertNotNull(firstRuntime.exportCurrentProjectFile())
            runId to projectExport.content
        } finally {
            firstRuntime.close()
            firstScope.cancel()
        }

        // Import the portable .ive project into a fresh durable store, then reconstruct the runtime.
        // This proves both project portability and provider-session resume without relying on process memory.
        val resumedSettings = MapSettings()
        val resumedPersistence = SettingsWorkflowPersistence(
            resumedSettings,
            storageKey = "aive.live.runtime.verification",
        )
        val importedProject = resumedPersistence.importProjectFile(exportedProject)
        assertEquals(project.id, importedProject.id)
        val resumedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val resumedRuntime = ApplicationRuntime.create(
            providers = listOf(liveProvider(providerConfig)),
            scope = resumedScope,
            persistence = resumedPersistence,
            executorIntegrations = executorIntegrations,
        )

        try {
            val resumed = awaitLive(resumedRuntime, "resumed provider plan approval") { live ->
                live.presentation.run.taskRuns.getValue(providerTaskId).status == TaskRunStatus.AwaitingApproval
            }
            assertEquals(
                providerRunId,
                resumed.presentation.run.taskRuns.getValue(providerTaskId).providerRunId,
                "Restart must reconnect the same provider run instead of allocating a replacement",
            )

            resumedRuntime.approveTask(providerTaskId)

            val escalated = awaitLive(resumedRuntime, "failure escalation") { live ->
                live.presentation.run.taskRuns.getValue(failOnceTaskId).status == TaskRunStatus.Escalated
            }
            assertEquals(TaskRunStatus.Completed, escalated.presentation.run.taskRuns.getValue(providerTaskId).status)
            assertEquals(WorkflowRunStatus.AwaitingHuman, escalated.presentation.run.status)

            resumedRuntime.decideFailureEscalation(
                taskDefinitionId = failOnceTaskId,
                approved = true,
                note = "Live runtime verification: retry the deterministic failure",
            )

            val releaseGate = awaitLive(resumedRuntime, "verification/review/release gate") { live ->
                live.presentation.run.taskRuns.getValue(releaseTaskId).status == TaskRunStatus.AwaitingApproval
            }
            assertEquals(TaskRunStatus.Completed, releaseGate.presentation.run.taskRuns.getValue(failOnceTaskId).status)
            val verificationRun = releaseGate.presentation.run.taskRuns.getValue(verificationTaskId)
            assertEquals(TaskRunStatus.Completed, verificationRun.status)
            assertTrue(verificationRun.artifacts.any { it.kind == ArtifactKind.Verification })
            val reviewRun = releaseGate.presentation.run.taskRuns.getValue(reviewTaskId)
            assertEquals(TaskRunStatus.Completed, reviewRun.status)
            assertTrue(reviewRun.artifacts.any { it.kind == ArtifactKind.Review })

            resumedRuntime.approveTask(releaseTaskId)

            val completed = awaitLive(resumedRuntime, "terminal completion") {
                it.presentation.run.status == WorkflowRunStatus.Completed
            }
            assertEquals(TaskRunStatus.Completed, completed.presentation.run.taskRuns.getValue(releaseTaskId).status)
        } finally {
            resumedRuntime.close()
            resumedScope.cancel()
        }

        // A terminal result must remain terminal after another runtime reconstruction.
        val terminalScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val terminalRuntime = ApplicationRuntime.create(
            providers = listOf(liveProvider(providerConfig)),
            scope = terminalScope,
            persistence = SettingsWorkflowPersistence(resumedSettings, storageKey = "aive.live.runtime.verification"),
            executorIntegrations = executorIntegrations,
        )
        try {
            val restored = awaitLive(terminalRuntime, "terminal state restoration") {
                it.presentation.run.status == WorkflowRunStatus.Completed
            }
            assertEquals(WorkflowRunStatus.Completed, restored.presentation.run.status)
        } finally {
            terminalRuntime.close()
            terminalScope.cancel()
        }
    }

    private data class LiveProviderConfig(
        val providerId: AgentProviderId,
        val apiKey: String,
        val baseUrl: String? = null,
        val model: String? = null,
    )

    private fun liveProviderConfig(): LiveProviderConfig {
        val requested = System.getenv("AIVE_LIVE_PROVIDER")?.trim()?.lowercase().orEmpty()
        if (requested == "ollama") return ollamaProviderConfig()

        val candidates = listOf(
            "openai" to "OPENAI_API_KEY",
            "anthropic" to "ANTHROPIC_API_KEY",
            "gemini" to "GEMINI_API_KEY",
            "xai" to "XAI_API_KEY",
        )
        val selected = if (requested.isNotEmpty()) {
            candidates.firstOrNull { it.first == requested }
                ?: error(
                    "AIVE_LIVE_PROVIDER must be one of: " +
                        (candidates.map { it.first } + "ollama").joinToString(),
                )
        } else {
            candidates.firstOrNull { (_, envName) -> !System.getenv(envName).isNullOrBlank() }
                ?: return ollamaProviderConfig()
        }
        val apiKey = System.getenv(selected.second)?.trim().orEmpty()
        check(apiKey.isNotEmpty()) {
            "${selected.second} is required for AIVE_LIVE_PROVIDER=${selected.first}"
        }
        return LiveProviderConfig(
            providerId = AgentProviderId(selected.first),
            apiKey = apiKey,
        )
    }

    private fun ollamaProviderConfig(): LiveProviderConfig = LiveProviderConfig(
        providerId = AgentProviderId("ollama"),
        apiKey = "ollama",
        baseUrl = System.getenv("AIVE_LIVE_OLLAMA_BASE_URL")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: "http://127.0.0.1:11434/v1",
        model = System.getenv("AIVE_LIVE_OLLAMA_MODEL")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: "smollm2:135m-instruct-q2_K",
    )

    private fun liveProvider(config: LiveProviderConfig): AgentProvider {
        val keyProvider = LlmApiKeyProvider { config.apiKey }
        return when (config.providerId.value) {
            "openai" -> OpenAiProvider(keyProvider)
            "anthropic" -> AnthropicProvider(keyProvider)
            "gemini" -> GeminiProvider(keyProvider)
            "xai" -> XaiProvider(keyProvider)
            "ollama" -> TextLlmProvider(
                id = config.providerId,
                displayName = "Ollama / SmolLM2",
                api = OpenAiCompatibleChatApi(
                    apiKeyProvider = keyProvider,
                    model = checkNotNull(config.model) { "Ollama model is not configured" },
                    baseUrl = checkNotNull(config.baseUrl) { "Ollama base URL is not configured" },
                ),
            )
            else -> error("Unsupported live provider ${config.providerId.value}")
        }
    }

    private fun verificationWorkflow(providerId: AgentProviderId): WorkflowDefinition {
        val providerTask = TaskDefinitionId("provider-live")
        val failOnce = TaskDefinitionId("fail-once")
        val verification = TaskDefinitionId("verification")
        val review = TaskDefinitionId("review")
        val release = TaskDefinitionId("release-approval")
        return WorkflowDefinition(
            id = WorkflowDefinitionId("live-runtime-verification-definition"),
            name = "Live runtime verification",
            testDesignPolicy = TestDesignPolicy.None,
            tasks = listOf(
                TaskDefinition(
                    id = providerTask,
                    name = "Live provider execution",
                    objective = "Create a short two-step plan for this runtime smoke test. After approval, complete the task. Do not access or modify a repository.",
                    roleId = BuiltInRoles.Orchestrator.id,
                    executor = TaskExecutor.RoleAgent(BuiltInRoles.Orchestrator.id),
                    approvalPolicy = ApprovalPolicy.HumanApproval,
                    retryPolicy = RetryPolicy(maxAttempts = 1),
                    escalationPolicy = EscalationPolicy.FailWorkflow,
                    providerConstraints = ProviderConstraints.RequireProvider(providerId),
                ),
                TaskDefinition(
                    id = failOnce,
                    name = "Exercise failure escalation",
                    objective = "Fail once, require a human escalation decision, then succeed on retry.",
                    roleId = null,
                    executor = TaskExecutor.ExternalService("runtime-verifier", "fail-once"),
                    dependsOn = setOf(providerTask),
                    retryPolicy = RetryPolicy(maxAttempts = 1),
                    escalationPolicy = EscalationPolicy.RequireHumanDecision,
                ),
                TaskDefinition(
                    id = verification,
                    name = "Verify recovered execution",
                    objective = "Emit durable verification evidence after the approved retry.",
                    roleId = null,
                    executor = TaskExecutor.ExternalService("runtime-verifier", "verify"),
                    dependsOn = setOf(failOnce),
                    requiredArtifacts = setOf(ArtifactKind.Verification),
                ),
                TaskDefinition(
                    id = review,
                    name = "Review verified evidence",
                    objective = "Produce explicit review evidence before terminal approval.",
                    roleId = null,
                    executor = TaskExecutor.ExternalService("runtime-verifier", "review"),
                    dependsOn = setOf(verification),
                    requiredArtifacts = setOf(ArtifactKind.Review),
                ),
                TaskDefinition(
                    id = release,
                    name = "Terminal approval",
                    objective = "Approve the verified and reviewed terminal outcome.",
                    roleId = null,
                    executor = TaskExecutor.HumanApproval("Approve live runtime verification"),
                    dependsOn = setOf(review),
                ),
            ),
        )
    }

    private suspend fun awaitLive(
        runtime: ApplicationRuntime,
        label: String,
        timeoutMillis: Long = 300_000L,
        predicate: (ApplicationRuntimeState.Live) -> Boolean,
    ): ApplicationRuntimeState.Live {
        val result = withTimeoutOrNull(timeoutMillis) {
            while (true) {
                when (val state = runtime.state.value) {
                    is ApplicationRuntimeState.Live -> if (predicate(state)) return@withTimeoutOrNull state
                    is ApplicationRuntimeState.Disconnected ->
                        error("$label failed because runtime disconnected: ${state.message}")
                    is ApplicationRuntimeState.ResumeFailed ->
                        error("$label failed during resume: ${state.message}")
                    else -> Unit
                }
                delay(250L)
            }
            error("unreachable")
        }
        return result ?: error(
            "$label timed out after ${timeoutMillis}ms; last runtime state=${runtime.state.value}",
        )
    }

    private class FailOnceVerificationIntegration : TaskExecutorIntegration {
        private val dispatches = mutableMapOf<TaskDefinitionId, Int>()

        override fun supports(executor: TaskExecutor): Boolean =
            executor is TaskExecutor.ExternalService && executor.service == "runtime-verifier"

        override suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution {
            val count = (dispatches[context.task.id] ?: 0) + 1
            dispatches[context.task.id] = count
            return when (context.task.id.value) {
                "fail-once" -> if (count == 1) {
                    TaskExecutorExecution(
                        status = TaskRunStatus.Failed,
                        progressMessage = "Intentional first-attempt failure for escalation verification",
                    )
                } else {
                    TaskExecutorExecution(
                        status = TaskRunStatus.Completed,
                        artifacts = listOf(
                            ArtifactRef(
                                id = ArtifactId("${context.taskRun.id.value}:recovered:${context.taskRun.attempt}"),
                                kind = ArtifactKind.CommandOutput,
                                taskRunId = context.taskRun.id,
                                label = "Recovered execution",
                                textContent = "Approved escalation retry completed.",
                                mediaType = "text/plain",
                                createdAtEpochMillis = context.nowEpochMillis,
                            ),
                        ),
                        progress = 1f,
                        progressMessage = "Recovered after approved escalation",
                    )
                }
                "verification" -> TaskExecutorExecution(
                    status = TaskRunStatus.Completed,
                    artifacts = listOf(
                        ArtifactRef(
                            id = ArtifactId("${context.taskRun.id.value}:verification:${context.taskRun.attempt}"),
                            kind = ArtifactKind.Verification,
                            taskRunId = context.taskRun.id,
                            label = "Live runtime verification",
                            textContent = "Provider approval, execution, escalation recovery, restart/resume, artifact collection, and terminal persistence all completed.",
                            mediaType = "text/plain",
                            createdAtEpochMillis = context.nowEpochMillis,
                        ),
                    ),
                    progress = 1f,
                    progressMessage = "Runtime lifecycle verified",
                )
                "review" -> TaskExecutorExecution(
                    status = TaskRunStatus.Completed,
                    artifacts = listOf(
                        ArtifactRef(
                            id = ArtifactId("${context.taskRun.id.value}:review:${context.taskRun.attempt}"),
                            kind = ArtifactKind.Review,
                            taskRunId = context.taskRun.id,
                            label = "Live runtime review",
                            textContent = "Reviewed the durable verification evidence and found the acceptance path complete.",
                            mediaType = "text/plain",
                            createdAtEpochMillis = context.nowEpochMillis,
                        ),
                    ),
                    progress = 1f,
                    progressMessage = "Verification evidence reviewed",
                )
                else -> error("Unexpected runtime-verifier task ${context.task.id.value}")
            }
        }

        override suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution =
            TaskExecutorExecution(
                status = context.taskRun.status,
                externalRunId = context.taskRun.externalRunId,
                artifacts = context.taskRun.artifacts,
                progress = context.taskRun.progress,
                progressMessage = context.taskRun.progressMessage,
            )
    }

    private companion object {
        const val OPT_IN_ENV = "AIVE_LIVE_RUNTIME_VERIFICATION"
    }
}
