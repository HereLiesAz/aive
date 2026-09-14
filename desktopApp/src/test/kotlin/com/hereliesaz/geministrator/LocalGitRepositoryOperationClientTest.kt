package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.workflow.ExternalExecutionStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class LocalGitRepositoryOperationClientTest {
    @Test
    fun statusReportsHeadBranchAndCleanWorkingTree() = withGitRepository { root ->
        val client = LocalGitRepositoryOperationClient()
        val execution = runBlocking { client.start(project(root), "status") }

        assertEquals(ExternalExecutionStatus.Completed, execution.status)
        assertEquals(1f, execution.progress)
        val artifact = execution.artifacts.single()
        assertEquals(RepositorySource.Local.name, artifact.metadata["repositorySource"])
        assertEquals("status", artifact.metadata["operation"])
        assertEquals("0", artifact.metadata["dirtyFileCount"])
        assertTrue(artifact.metadata["branch"].orEmpty().isNotBlank())
        assertTrue(artifact.metadata["headCommit"].orEmpty().length >= 7)
    }

    @Test
    fun statusReportsDirtyFilesAfterWorkingTreeChange() = withGitRepository { root ->
        root.resolve("README.md").writeText("changed\n")
        val client = LocalGitRepositoryOperationClient()
        val execution = runBlocking { client.start(project(root), "status") }

        assertEquals(ExternalExecutionStatus.Completed, execution.status)
        assertEquals("1", execution.artifacts.single().metadata["dirtyFileCount"])
        assertTrue(execution.message.orEmpty().contains("1 changed file"))
    }

    @Test
    fun createBranchChangesReportedBranch() = withGitRepository { root ->
        val client = LocalGitRepositoryOperationClient()
        val execution = runBlocking { client.start(project(root), "create-branch:feature/local-link") }

        assertEquals(ExternalExecutionStatus.Completed, execution.status)
        assertEquals("feature/local-link", execution.artifacts.single().metadata["branch"])
        assertEquals("feature/local-link", git(root, "branch", "--show-current").trim())
    }

    @Test
    fun rejectsRefThatCouldBeParsedAsGitOption() = withGitRepository { root ->
        val client = LocalGitRepositoryOperationClient()

        assertFailsWith<IllegalArgumentException> {
            runBlocking { client.start(project(root), "checkout:-force") }
        }
    }

    @Test
    fun completedRunCanBeReadBackDuringProcessLifetime() = withGitRepository { root ->
        val client = LocalGitRepositoryOperationClient()
        val started = runBlocking { client.start(project(root), "status") }
        val reconciled = runBlocking { client.getRun(project(root), started.id) }

        assertEquals(started, reconciled)
        assertNotNull(reconciled.artifacts.single().metadata["headCommit"])
    }

    private fun project(root: Path): Project = Project(
        id = ProjectId("local-project"),
        name = "Local project",
        repository = RepositoryRef(
            owner = "",
            name = root.fileName.toString(),
            source = RepositorySource.Local,
            localPath = root.toAbsolutePath().toString(),
        ),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private fun withGitRepository(block: (Path) -> Unit) {
        val root = createTempDirectory("haive-local-git-test-")
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
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
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
}
