package com.hereliesaz.geministrator.retrieval

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalRepositoryChunkSourceTest {
    private fun git(root: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
        return output.trim()
    }

    private fun request(root: File, objective: String, upstreamHead: String? = null) = AgentTaskRequest(
        taskRunId = TaskRunId("t"),
        objective = objective,
        roleInstructions = "",
        acceptanceCriteria = emptyList(),
        repository = RepositoryRef(owner = "local", name = root.name, source = RepositorySource.Local, localPath = root.path),
        contextArtifacts = listOfNotNull(
            upstreamHead?.let {
                ArtifactRef(
                    id = ArtifactId("up"),
                    kind = ArtifactKind.CommandOutput,
                    taskRunId = TaskRunId("up"),
                    label = "Upstream",
                    textContent = "done",
                    metadata = mapOf("headCommit" to it),
                    createdAtEpochMillis = 1L,
                )
            },
        ),
    )

    @Test
    fun readsTheCommittedBaseNotTheWorkingCopy() = runBlocking {
        val root = Files.createTempDirectory("aive-local-retrieval").toFile()
        try {
            git(root, "init", "-q")
            git(root, "config", "user.email", "t@example.invalid")
            git(root, "config", "user.name", "T")
            File(root, "src").mkdirs()
            File(root, "src/WorkflowDatabase.kt").writeText("class WorkflowDatabase { fun migrate() = 1 }")
            File(root, "src/Splash.kt").writeText("class Splash")
            git(root, "add", ".")
            git(root, "commit", "-q", "-m", "one")
            val first = git(root, "rev-parse", "HEAD")
            File(root, "src/WorkflowDatabase.kt").writeText("class WorkflowDatabase { fun migrate() = 2 }")
            git(root, "commit", "-qam", "two")
            // An uncommitted edit in the user's checkout is not what the agent's worktree starts from.
            File(root, "src/WorkflowDatabase.kt").writeText("class WorkflowDatabase { fun migrate() = 3 }")

            val source = LocalRepositoryChunkSource(GitCliRepositoryReader)
            val atHead = source.chunks(request(root, "Migrate the workflow database"))
            assertEquals(listOf("Repository file src/WorkflowDatabase.kt"), atHead.map { it.label })
            assertTrue("= 2" in atHead.single().text, atHead.single().text)

            val atUpstream = source.chunks(request(root, "Migrate the workflow database", upstreamHead = first))
            assertTrue("= 1" in atUpstream.single().text, atUpstream.single().text)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun plainFoldersAreReadFromDisk() = runBlocking {
        val root = Files.createTempDirectory("aive-local-folder").toFile()
        try {
            File(root, "WorkflowDatabase.kt").writeText("class WorkflowDatabase")
            val chunks = LocalRepositoryChunkSource(GitCliRepositoryReader).chunks(request(root, "workflow database"))
            assertEquals(listOf("Repository file WorkflowDatabase.kt"), chunks.map { it.label })
        } finally {
            root.deleteRecursively()
        }
    }
}
