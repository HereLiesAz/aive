package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.llm.TextGenerationApi
import com.hereliesaz.geministrator.providers.llm.TextGenerationResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class LocalWorkspaceAgentProviderTest {
    @Test
    fun mutationRunsInIsolatedWorktreeAndPreservesSourceCheckout() = withGitRepository { root ->
        val api = QueueTextApi(
            TextGenerationResult(
                text = """
                    Update the README in one focused change.
                    FILES:
                    README.md
                """.trimIndent(),
                inputTokens = 10,
                outputTokens = 5,
            ),
            TextGenerationResult(
                text = """
                    diff --git a/README.md b/README.md
                    --- a/README.md
                    +++ b/README.md
                    @@ -1 +1 @@
                    -initial
                    +changed
                """.trimIndent(),
                inputTokens = 20,
                outputTokens = 8,
            ),
        )
        val provider = LocalWorkspaceAgentProvider(
            id = AgentProviderId("local-workspace-test"),
            displayName = "Local workspace test",
            api = api,
        )

        val handle = runBlocking { provider.start(request(root, TaskRunId("task-1"))) }
        val events = runBlocking { provider.observe(handle.providerRunId).toList() }

        assertIs<AgentEvent.Completed>(events.last())
        val artifacts = events.filterIsInstance<AgentEvent.ArtifactProduced>().map { it.artifact }
        val codeChange = artifacts.single { it.kind == ArtifactKind.CodeChange }
        val branch = codeChange.metadata.getValue("branch")

        assertEquals("initial\n", root.resolve("README.md").readText())
        assertEquals("changed", git(root, "show", "$branch:README.md").trim())
        assertTrue(git(root, "status", "--porcelain").isBlank())
        assertTrue(git(root, "branch", "--list", branch).contains(branch))
        assertTrue(codeChange.metadata.getValue("headCommit").isNotBlank())
        assertTrue(events.any { event ->
            event is AgentEvent.UsageReported && event.inputTokens == 30L && event.outputTokens == 13L
        })
    }

    @Test
    fun planApprovalGatesWorkspaceMutation() = withGitRepository { root ->
        val api = QueueTextApi(
            TextGenerationResult(
                text = """
                    Change the README after approval.
                    FILES:
                    README.md
                """.trimIndent(),
            ),
            TextGenerationResult(
                text = """
                    diff --git a/README.md b/README.md
                    --- a/README.md
                    +++ b/README.md
                    @@ -1 +1 @@
                    -initial
                    +approved
                """.trimIndent(),
            ),
        )
        val provider = LocalWorkspaceAgentProvider(
            id = AgentProviderId("local-workspace-approval-test"),
            displayName = "Local workspace approval test",
            api = api,
        )
        val handle = runBlocking {
            provider.start(
                request(
                    root = root,
                    taskRunId = TaskRunId("approval-task"),
                    requirePlanApproval = true,
                ),
            )
        }

        val firstEvent = runBlocking { provider.observe(handle.providerRunId).first() }
        assertIs<AgentEvent.PlanGenerated>(firstEvent)
        assertEquals("initial\n", root.resolve("README.md").readText())
        assertEquals(1, api.callCount)

        assertEquals(
            ProviderActionResult.Accepted,
            runBlocking { provider.approvePlan(handle.providerRunId) },
        )
        val events = runBlocking { provider.observe(handle.providerRunId).toList() }

        assertIs<AgentEvent.Completed>(events.last())
        assertEquals(2, api.callCount)
        val codeChange = events
            .filterIsInstance<AgentEvent.ArtifactProduced>()
            .map { it.artifact }
            .single { it.kind == ArtifactKind.CodeChange }
        val branch = codeChange.metadata.getValue("branch")
        assertEquals("approved", git(root, "show", "$branch:README.md").trim())
        assertEquals("initial\n", root.resolve("README.md").readText())
    }

    private fun request(
        root: Path,
        taskRunId: TaskRunId,
        requirePlanApproval: Boolean = false,
    ): AgentTaskRequest = AgentTaskRequest(
        taskRunId = taskRunId,
        objective = "Update README",
        roleInstructions = "Implement the requested repository change.",
        acceptanceCriteria = emptyList(),
        repository = RepositoryRef(
            owner = "",
            name = root.fileName.toString(),
            source = RepositorySource.Local,
            localPath = root.toAbsolutePath().toString(),
        ),
        requirePlanApproval = requirePlanApproval,
    )

    private fun withGitRepository(block: (Path) -> Unit) {
        val root = createTempDirectory("haive-local-workspace-test-")
        try {
            git(root, "init")
            git(root, "config", "user.email", "haive-test@example.invalid")
            git(root, "config", "user.name", "Haive Test")
            root.resolve("README.md").writeText("initial\n")
            git(root, "add", "README.md")
            git(root, "commit", "-m", "Initial commit")
            block(root)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path ->
                    Files.deleteIfExists(path)
                }
            }
        }
    }

    private fun git(root: Path, vararg args: String): String {
        val command = buildList {
            add("git")
            add("-C")
            add(root.toAbsolutePath().toString())
            addAll(args)
        }
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        check(exitCode == 0) { "${command.joinToString(" ")} failed ($exitCode): $output" }
        return output
    }

    private class QueueTextApi(vararg responses: TextGenerationResult) : TextGenerationApi {
        private val queued = responses.toList()
        var callCount: Int = 0
            private set

        override suspend fun generate(prompt: String): TextGenerationResult {
            check(callCount < queued.size) { "Unexpected generation call ${callCount + 1}" }
            return queued[callCount++]
        }
    }
}
