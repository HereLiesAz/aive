package com.hereliesaz.geministrator.providers.jules

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
import com.hereliesaz.geministrator.events.RepositoryWorkflowEventSink
import com.hereliesaz.geministrator.persistence.SettingsWorkflowPersistence
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
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in live acceptance test for the production runtime/Jules integration.
 *
 * Ordinary unit-test runs skip this test. CI's dedicated live-runtime-verification job opts in with
 * AIVE_LIVE_RUNTIME_VERIFICATION=1 and supplies the repository's real JULES_API_KEY.
 */
class JulesLiveRuntimeVerificationTest {
    @Test
    fun launchApproveExecuteEscalateRestartResumeAndComplete() = runBlocking {
        if (System.getenv(OPT_IN_ENV) != "1") return@runBlocking

        val apiKey = System.getenv("JULES_API_KEY")?.trim().orEmpty()
        check(apiKey.isNotEmpty()) { "JULES_API_KEY is required when $OPT_IN_ENV=1" }

        val settings = MapSettings()
        val persistence = SettingsWorkflowPersistence(settings, storageKey = "aive.live.runtime.verification")
        val verifierIntegration = FailOnceVerificationIntegration()
        val executorIntegrations = TaskExecutorIntegrationRegistry(listOf(verifierIntegration))
        val firstProvider = liveJulesProvider(apiKey)
        val definition = verificationWorkflow()
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
            objective = "Verify the real Aive runtime lifecycle against Jules",
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

        val julesTaskId = TaskDefinitionId("jules-live")
        val failOnceTaskId = TaskDefinitionId("fail-once")
        val verificationTaskId = TaskDefinitionId("verification")
        val reviewTaskId = TaskDefinitionId("review")
        val releaseTaskId = TaskDefinitionId("release-approval")

        val providerRunId = try {
            val awaitingApproval = awaitLive(firstRuntime) { live ->
                live.presentation.run.taskRuns.getValue(julesTaskId).status == TaskRunStatus.AwaitingApproval
            }
            val taskRun = awaitingApproval.presentation.run.taskRuns.getValue(julesTaskId)
            assertEquals(WorkflowRunStatus.AwaitingHuman, awaitingApproval.presentation.run.status)
            assertNotNull(taskRun.providerRunId)
            taskRun.providerRunId
        } finally {
            firstRuntime.close()
            firstScope.cancel()
        }

        // Reconstruct both persistence and runtime around the same durable Settings backend.
        val resumedPersistence = SettingsWorkflowPersistence(settings, storageKey = "aive.live.runtime.verification")
        val resumedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val resumedRuntime = ApplicationRuntime.create(
            providers = listOf(liveJulesProvider(apiKey)),
            scope = resumedScope,
            persistence = resumedPersistence,
            executorIntegrations = executorIntegrations,
        )

        try {
            val resumed = awaitLive(resumedRuntime) { live ->
                live.presentation.run.taskRuns.getValue(julesTaskId).status == TaskRunStatus.AwaitingApproval
            }
            assertEquals(
                providerRunId,
                resumed.presentation.run.taskRuns.getValue(julesTaskId).providerRunId,
                "Restart must reconnect the same Jules session instead of redispatching work",
            )

            resumedRuntime.approveTask(julesTaskId)

            val escalated = awaitLive(resumedRuntime, timeoutMillis = 300_000L) { live ->
                live.presentation.run.taskRuns.getValue(failOnceTaskId).status == TaskRunStatus.Escalated
            }
            assertEquals(TaskRunStatus.Completed, escalated.presentation.run.taskRuns.getValue(julesTaskId).status)
            assertEquals(WorkflowRunStatus.AwaitingHuman, escalated.presentation.run.status)

            resumedRuntime.decideFailureEscalation(
                taskDefinitionId = failOnceTaskId,
                approved = true,
                note = "Live runtime verification: retry the deterministic failure",
            )

            val releaseGate = awaitLive(resumedRuntime) { live ->
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

            val completed = awaitLive(resumedRuntime) {
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
            providers = listOf(liveJulesProvider(apiKey)),
            scope = terminalScope,
            persistence = SettingsWorkflowPersistence(settings, storageKey = "aive.live.runtime.verification"),
            executorIntegrations = executorIntegrations,
        )
        try {
            val restored = awaitLive(terminalRuntime) {
                it.presentation.run.status == WorkflowRunStatus.Completed
            }
            assertEquals(WorkflowRunStatus.Completed, restored.presentation.run.status)
        } finally {
            terminalRuntime.close()
            terminalScope.cancel()
        }
    }

    private fun liveJulesProvider(apiKey: String): JulesProvider = JulesProvider(
        api = JulesRestApi(JulesApiKeyProvider { apiKey }),
        config = JulesProviderConfig(
            pollIntervalMillis = 250L,
            autoCreatePullRequests = false,
        ),
    )

    private fun verificationWorkflow(): WorkflowDefinition {
        val jules = TaskDefinitionId("jules-live")
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
                    id = jules,
                    name = "Live Jules execution",
                    objective = "Create a short two-step plan for this runtime smoke test. After approval, complete the task. Do not access or modify a repository.",
                    roleId = BuiltInRoles.ImplementationEngineer.id,
                    executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
                    approvalPolicy = ApprovalPolicy.HumanApproval,
                    retryPolicy = RetryPolicy(maxAttempts = 1),
                    escalationPolicy = EscalationPolicy.FailWorkflow,
                    providerConstraints = ProviderConstraints.RequireProvider(AgentProviderId("jules")),
                ),
                TaskDefinition(
                    id = failOnce,
                    name = "Exercise failure escalation",
                    objective = "Fail once, require a human escalation decision, then succeed on retry.",
                    roleId = null,
                    executor = TaskExecutor.ExternalService("runtime-verifier", "fail-once"),
                    dependsOn = setOf(jules),
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
        timeoutMillis: Long = 120_000L,
        predicate: (ApplicationRuntimeState.Live) -> Boolean,
    ): ApplicationRuntimeState.Live = withTimeout(timeoutMillis) {
        while (true) {
            val live = runtime.state.value as? ApplicationRuntimeState.Live
            if (live != null && predicate(live)) return@withTimeout live
            delay(250L)
        }
        error("unreachable")
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
                            textContent = "Jules approval, execution, escalation recovery, restart/resume, artifact collection, and terminal persistence all completed.",
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
