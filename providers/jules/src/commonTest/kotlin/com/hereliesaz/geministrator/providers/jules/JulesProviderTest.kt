package com.hereliesaz.geministrator.providers.jules

import com.hereliesaz.geministrator.domain.AcceptanceCriterion
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.PromptContext
import com.hereliesaz.geministrator.providers.PromptContextBlock
import com.hereliesaz.geministrator.providers.ProviderActionResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JulesProviderTest {

    @Test
    fun startResolvesSourceAndKeepsStableContextBeforeTask() {
        runBlocking {
            val api = FakeJulesApi()
            val provider = JulesProvider(api)

            val handle = provider.start(
                AgentTaskRequest(
                    taskRunId = TaskRunId("task-run-1"),
                    objective = "Implement the approved behavior",
                    roleInstructions = "Implement only approved scope.",
                    acceptanceCriteria = listOf(AcceptanceCriterion("Returns success")),
                    repository = RepositoryRef("HereLiesAz", "Geministrator", "main"),
                    requirePlanApproval = true,
                    promptContext = PromptContext(
                        stablePrefix = listOf(
                            PromptContextBlock("Specification", "Approved specification"),
                            PromptContextBlock("Verification contract", "Approved pre-code tests"),
                        ),
                        dynamicContext = listOf(
                            PromptContextBlock("Attempt", "Attempt 2 after a failed verification"),
                        ),
                    ),
                ),
            )

            assertEquals(ProviderRunId("sessions/session-1"), handle.providerRunId)
            val request = api.createdRequest ?: error("No session request captured")
            assertEquals("sources/geministrator", request.sourceContext?.source)
            assertEquals("main", request.sourceContext?.githubRepoContext?.startingBranch)
            assertTrue(request.requirePlanApproval)
            assertEquals("AUTO_CREATE_PR", request.automationMode)

            val roleIndex = request.prompt.indexOf("ROLE INSTRUCTIONS")
            val specIndex = request.prompt.indexOf("SPECIFICATION")
            val taskIndex = request.prompt.indexOf("TASK\nImplement the approved behavior")
            val dynamicIndex = request.prompt.indexOf("ATTEMPT")
            assertTrue(roleIndex in 0 until specIndex)
            assertTrue(specIndex < taskIndex)
            assertTrue(taskIndex < dynamicIndex)
        }
    }

    @Test
    fun observeMapsPlanMessagesArtifactsAndPullRequest() {
        runBlocking {
            val api = FakeJulesApi().apply {
                activities = listOf(
                    JulesActivity(
                        name = "sessions/session-1/activities/a1",
                        id = "a1",
                        planGenerated = JulesPlanGenerated(
                            JulesPlan(
                                id = "plan-1",
                                steps = listOf(
                                    JulesPlanStep("s1", 0, "Inspect", "Read the relevant code"),
                                    JulesPlanStep("s2", 1, "Implement", "Make the approved change"),
                                ),
                            ),
                        ),
                    ),
                    JulesActivity(
                        name = "sessions/session-1/activities/a2",
                        id = "a2",
                        planApproved = JulesPlanApproved("plan-1"),
                        agentMessaged = JulesAgentMessaged("Working on it"),
                        progressUpdated = JulesProgressUpdated("Implementation", "Editing files"),
                        artifacts = listOf(
                            JulesArtifact(
                                changeSet = JulesChangeSet(
                                    source = "sources/geministrator",
                                    gitPatch = JulesGitPatch(
                                        baseCommitId = "abc123",
                                        unidiffPatch = "diff --git a/A.kt b/A.kt",
                                        suggestedCommitMessage = "feat: change A",
                                    ),
                                ),
                            ),
                            JulesArtifact(
                                bashOutput = JulesBashOutput(
                                    command = "./gradlew test",
                                    output = "BUILD SUCCESSFUL",
                                    exitCode = 0,
                                ),
                            ),
                        ),
                    ),
                    JulesActivity(
                        name = "sessions/session-1/activities/a3",
                        id = "a3",
                        sessionCompleted = JulesSessionCompleted(),
                    ),
                )
                session = session.copy(
                    state = "COMPLETED",
                    outputs = listOf(
                        JulesSessionOutput(
                            pullRequest = JulesPullRequest(
                                url = "https://github.com/HereLiesAz/Geministrator/pull/99",
                                title = "Approved change",
                                description = "Implements the task",
                            ),
                        ),
                    ),
                )
            }

            val events = JulesProvider(api)
                .observe(ProviderRunId("sessions/session-1"))
                .toList()

            assertTrue(events.any { it is AgentEvent.PlanGenerated && "Inspect" in it.summary })
            assertTrue(events.any { it is AgentEvent.PlanApproved })
            assertTrue(events.any { it is AgentEvent.Message && it.content == "Working on it" })
            assertTrue(events.any { it is AgentEvent.Progress && "Editing files" in it.message })
            assertTrue(
                events.filterIsInstance<AgentEvent.Progress>().all { it.fraction == null },
                "Jules textual progress must not invent a numeric completion fraction",
            )
            assertTrue(
                events.any {
                    it is AgentEvent.ArtifactProduced &&
                        it.artifact.kind == ArtifactKind.CodeChange &&
                        it.artifact.textContent?.startsWith("diff --git") == true
                },
            )
            assertTrue(
                events.any {
                    it is AgentEvent.ArtifactProduced &&
                        it.artifact.kind == ArtifactKind.CommandOutput &&
                        it.artifact.metadata["exitCode"] == "0"
                },
            )
            assertTrue(
                events.any {
                    it is AgentEvent.ArtifactProduced &&
                        it.artifact.kind == ArtifactKind.PullRequest &&
                        it.artifact.uri?.endsWith("/99") == true
                },
            )
            assertIs<AgentEvent.Completed>(events.last())
        }
    }

    @Test
    fun approvePlanTransportFailurePropagatesInsteadOfBecomingSemanticRejection() = runBlocking {
        val api = FakeJulesApi().apply {
            approveFailure = IllegalStateException("temporary Jules API failure")
        }
        val provider = JulesProvider(api)

        val failure = assertFailsWith<IllegalStateException> {
            provider.approvePlan(ProviderRunId("sessions/session-1"))
        }

        assertEquals("temporary Jules API failure", failure.message)
    }

    @Test
    fun cancelPreservesEvidenceByDefault() {
        runBlocking {
            val api = FakeJulesApi()
            val result = JulesProvider(api).cancel(ProviderRunId("sessions/session-1"))

            assertIs<ProviderActionResult.Rejected>(result)
            assertTrue(!api.deleted)
        }
    }
}

private class FakeJulesApi : JulesApi {
    var createdRequest: JulesCreateSessionRequest? = null
    var activities: List<JulesActivity> = emptyList()
    var deleted: Boolean = false
    var approveFailure: Throwable? = null
    var session: JulesSession = JulesSession(
        name = "sessions/session-1",
        id = "session-1",
        state = "IN_PROGRESS",
    )

    override suspend fun listSources(): List<JulesSource> = listOf(
        JulesSource(
            name = "sources/geministrator",
            id = "geministrator",
            githubRepo = JulesGithubRepo(
                owner = "HereLiesAz",
                repo = "Geministrator",
                defaultBranch = JulesGithubBranch("main"),
            ),
        ),
    )

    override suspend fun getSession(sessionName: String): JulesSession = session

    override suspend fun createSession(request: JulesCreateSessionRequest): JulesSession {
        createdRequest = request
        return session
    }

    override suspend fun listActivities(sessionName: String): List<JulesActivity> = activities

    override suspend fun sendMessage(sessionName: String, message: String) = Unit

    override suspend fun approvePlan(sessionName: String) {
        approveFailure?.let { throw it }
    }

    override suspend fun deleteSession(sessionName: String) {
        deleted = true
    }
}
