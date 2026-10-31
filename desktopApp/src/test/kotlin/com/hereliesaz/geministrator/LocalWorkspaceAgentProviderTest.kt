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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

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

        assertCompleted(events)
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

        assertCompleted(events)
        assertEquals(2, api.callCount)
        val codeChange = events
            .filterIsInstance<AgentEvent.ArtifactProduced>()
            .map { it.artifact }
            .single { it.kind == ArtifactKind.CodeChange }
        val branch = codeChange.metadata.getValue("branch")
        assertEquals("approved", git(root, "show", "$branch:README.md").trim())
        assertEquals("initial\n", root.resolve("README.md").readText())
    }

    @Test
    fun patchesThatReachGitControlFilesThroughHeadersGitParsesAreRejected() {
        val renameOntoAttributes = """
            diff --git a/README.md b/README.md
            similarity index 100%
            rename from README.md
            rename to .gitattributes
        """.trimIndent()
        val quotedOctalAttributes = """
            diff --git "a/\056gitattributes" "b/\056gitattributes"
            new file mode 100644
            --- /dev/null
            +++ "b/\056gitattributes"
            @@ -0,0 +1 @@
            +* filter=evil
        """.trimIndent()
        val copyOntoModules = """
            diff --git a/README.md b/README.md
            copy from README.md
            copy to sub/.gitmodules
        """.trimIndent()
        val traditionalAfterGitHeader = """
            diff --git a/README.md b/README.md
            --- a/README.md
            +++ b/README.md
            @@ -1 +1 @@
            -initial
            +changed
            --- a/other
            +++ b/.GITATTRIBUTES
            @@ -0,0 +1 @@
            +x
        """.trimIndent()
        val unparseable = "diff --git a/README.md b/README.md\nthis is not a patch\n"

        listOf(renameOntoAttributes, quotedOctalAttributes, copyOntoModules, traditionalAfterGitHeader, unparseable)
            .forEachIndexed { index, patch ->
                withGitRepository { root ->
                    val events = runPatch(root, TaskRunId("hostile-$index"), patch)
                    val failure = events.filterIsInstance<AgentEvent.Failed>().singleOrNull()
                    assertTrue(failure != null, "patch $index was not rejected: $events")
                    assertTrue(events.none { it is AgentEvent.ArtifactProduced }, "patch $index produced a change")
                    assertEquals("", git(root, "branch", "--list", "haive/*").trim(), "patch $index left its branch")
                }
            }
    }

    @Test
    fun gitWhitespaceWarningsCannotDisguiseAControlFileInThePathListing() = withGitRepository { root ->
        // A tracked .gitattributes whose line has whitespace errors: git warns about them on stderr
        // while listing the patch's paths, and a warning must never be read as part of a path.
        root.resolve(".gitattributes").writeText("*\t\tfilter=old  \n")
        git(root, "add", ".gitattributes")
        git(root, "commit", "-m", "Attributes")
        val patch = """
            diff --git a/.gitattributes b/.gitattributes
            --- a/.gitattributes
            +++ b/.gitattributes
            @@ -1 +1 @@
            -*\t\tfilter=old  
            +*\t\tfilter=evil  
        """.trimIndent().replace("\\t", "\t") + "\n"

        val events = runPatch(root, TaskRunId("whitespace-warning"), patch)

        assertTrue(events.filterIsInstance<AgentEvent.Failed>().isNotEmpty(), "the patch was not rejected: $events")
        assertTrue(events.none { it is AgentEvent.ArtifactProduced })
        assertEquals("", git(root, "branch", "--list", "haive/*").trim())
    }

    @Test
    fun repositoryHooksNeverRunDuringWorktreeCreationOrCommit() = withGitRepository { root ->
        val marker = root.resolveSibling("${root.fileName}-hook-ran")
        try {
            val hooks = root.resolve(".git/hooks")
            Files.createDirectories(hooks)
            listOf("post-checkout", "pre-commit", "commit-msg", "post-commit").forEach { name ->
                hooks.resolve(name).apply {
                    writeText("#!/bin/sh\necho $name >> '${marker.toAbsolutePath()}'\n")
                    toFile().setExecutable(true)
                }
            }

            val events = runPatch(root, TaskRunId("hooks"), README_PATCH)

            assertCompleted(events)
            assertFalse(Files.exists(marker), "a repository hook ran: ${if (Files.exists(marker)) marker.readText() else ""}")
        } finally {
            Files.deleteIfExists(marker)
        }
    }

    @Test
    fun cancellationRemovesTheWorktreeBranchAndTemporaryDirectories() = withGitRepository { root ->
        val generating = CompletableDeferred<Unit>()
        val api = object : TextGenerationApi {
            var calls = 0
            override suspend fun generate(prompt: String): TextGenerationResult {
                if (calls++ == 0) return TextGenerationResult(text = "Plan\nFILES:\nREADME.md")
                generating.complete(Unit)
                awaitCancellation()
            }
        }
        val provider = LocalWorkspaceAgentProvider(AgentProviderId("cancel-test"), "Cancel test", api)
        val tempRoot = Path.of(System.getProperty("java.io.tmpdir"))
        fun haiveTempDirs() = Files.list(tempRoot).use { paths ->
            paths.filter { it.fileName.toString().startsWith("haive-") && it.fileName.toString().contains("cancel") ||
                it.fileName.toString().startsWith("haive-empty-hooks-") }.toList().toSet()
        }
        val before = haiveTempDirs()

        runBlocking {
            val handle = provider.start(request(root, TaskRunId("cancel-me")))
            val observation = launch(Dispatchers.Default) { provider.observe(handle.providerRunId).toList() }
            withTimeout(60_000) { generating.await() }
            assertTrue(git(root, "branch", "--list", "haive/*").isNotBlank(), "worktree branch was not created")
            observation.cancelAndJoin()
        }

        assertEquals("", git(root, "branch", "--list", "haive/*").trim())
        assertEquals(1, git(root, "worktree", "list", "--porcelain").lines().count { it.startsWith("worktree ") })
        assertEquals(before, haiveTempDirs())
    }

    @Test
    fun gitEnvironmentIsStrippedOfInheritedRepositoryOverrides() {
        val environment = mutableMapOf(
            "PATH" to "/usr/bin",
            "GIT_DIR" to "/elsewhere/.git",
            "GIT_INDEX_FILE" to "/elsewhere/index",
            "git_work_tree" to "/elsewhere",
            "SSH_ASKPASS" to "/usr/bin/askpass",
        )

        sanitizeGitEnvironment(environment)

        assertEquals(
            mapOf("PATH" to "/usr/bin", "GIT_TERMINAL_PROMPT" to "0", "GCM_INTERACTIVE" to "Never"),
            environment,
        )
    }

    private fun runPatch(root: Path, taskRunId: TaskRunId, patch: String): List<AgentEvent> {
        val api = QueueTextApi(
            TextGenerationResult(text = "Change the README.\nFILES:\nREADME.md"),
            TextGenerationResult(text = patch),
        )
        val provider = LocalWorkspaceAgentProvider(AgentProviderId("patch-test"), "Patch test", api)
        val handle = runBlocking { provider.start(request(root, taskRunId)) }
        return runBlocking { provider.observe(handle.providerRunId).toList() }
    }

    private fun assertCompleted(events: List<AgentEvent>) {
        val failure = events.filterIsInstance<AgentEvent.Failed>().lastOrNull()
        assertTrue(failure == null, failure?.reason ?: "Workspace provider emitted a failure")
        assertIs<AgentEvent.Completed>(events.last())
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

    private companion object {
        val README_PATCH = """
            diff --git a/README.md b/README.md
            --- a/README.md
            +++ b/README.md
            @@ -1 +1 @@
            -initial
            +changed
        """.trimIndent()
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
