package com.hereliesaz.aive

import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.retrieval.JGitRepositoryReader
import com.hereliesaz.geministrator.retrieval.LocalRepositoryChunkSource
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.workflow.ExternalExecutionStatus
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidLocalGitRepositoryOperationClientTest {
    private fun withRepository(block: (File) -> Unit) {
        val parent = Files.createTempDirectory("aive-android-git").toFile()
        val root = File(parent, "repo").apply { mkdirs() }
        try {
            Git.init().setDirectory(root).call().use { git ->
                File(root, "README.md").writeText("initial\n")
                File(root, "src").mkdirs()
                File(root, "src/WorkflowDatabase.kt").writeText("class WorkflowDatabase { fun migrate() = 1 }")
                git.add().addFilepattern(".").call()
                git.commit().setMessage("one").setAuthor(AndroidLocalRepositoryStore.IDENTITY).call()
            }
            block(root)
        } finally {
            parent.deleteRecursively()
        }
    }

    private fun project(root: File) = Project(
        id = ProjectId("p"),
        name = "P",
        repository = RepositoryRef(owner = "local", name = root.name, source = RepositorySource.Local, localPath = root.path),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    @Test
    fun worktreeBranchCommitAndExportLeaveTheImportAlone() = withRepository { root ->
        root.resolve("README.md").writeText("uncommitted\n")
        val exported = mutableMapOf<String, ByteArray>()
        val client = AndroidLocalGitRepositoryOperationClient(export = { _, files -> exported += files })

        val worktree = runBlocking { client.start(project(root), "worktree-branch:feature/x") }
        assertEquals(ExternalExecutionStatus.Completed, worktree.status, worktree.artifacts.single().textContent)
        val worktreeDir = File(worktree.artifacts.single().metadata.getValue("worktreePath"))
        assertEquals("feature/x", worktree.artifacts.single().metadata["branch"])
        assertEquals("initial\n", worktreeDir.resolve("README.md").readText())
        assertEquals("uncommitted\n", root.resolve("README.md").readText())
        assertTrue(Git.open(root).use { it.repository.branch } != "feature/x")

        // Work committed in the worktree is what export writes back for that branch.
        worktreeDir.resolve("NOTES.md").writeText("done\n")
        Git.open(worktreeDir).use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage("notes").setAuthor(AndroidLocalRepositoryStore.IDENTITY).call()
        }
        val export = runBlocking { client.start(project(root), "export:feature/x") }
        assertEquals(ExternalExecutionStatus.Completed, export.status, export.artifacts.single().textContent)
        assertEquals(setOf("README.md", "NOTES.md", "src/WorkflowDatabase.kt"), exported.keys)
    }

    @Test
    fun remoteOperationsAreRefusedWithAReason() = withRepository { root ->
        val client = AndroidLocalGitRepositoryOperationClient(export = { _, _ -> })
        val run = runBlocking { client.start(project(root), "push") }
        assertEquals(ExternalExecutionStatus.Failed, run.status)
        assertTrue("credentials" in run.artifacts.single().textContent.orEmpty())
    }

    @Test
    fun retrievalReadsTheCommittedImport() = withRepository { root ->
        root.resolve("src/WorkflowDatabase.kt").writeText("class WorkflowDatabase { fun migrate() = 2 }")
        val request = AgentTaskRequest(
            taskRunId = TaskRunId("t"),
            objective = "Migrate the workflow database",
            roleInstructions = "",
            acceptanceCriteria = emptyList(),
            repository = project(root).repository,
        )
        val chunks = runBlocking { LocalRepositoryChunkSource(JGitRepositoryReader).chunks(request) }
        // READMEs always qualify, so the fixture's README rides along with the matched file.
        assertEquals(listOf("Repository file src/WorkflowDatabase.kt", "Repository file README.md"), chunks.map { it.label })
        val database = chunks.first { it.label == "Repository file src/WorkflowDatabase.kt" }
        assertTrue("= 1" in database.text, database.text)
    }
}
