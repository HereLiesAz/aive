package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.workflow.ExternalExecutionStatus
import com.hereliesaz.geministrator.workflow.RepositoryFileProposal
import com.hereliesaz.geministrator.workflow.RepositoryProposedFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

class LocalGitRepositoryOperationClientTest {
    @Test
    fun hostSnapshotReadsCommittedBytesAndNotWorkingTree() = withGitRepository { root ->
        root.resolve("README.md").writeText("dirty\n")
        root.resolve("untracked.txt").writeText("untracked")
        val client = LocalGitRepositoryOperationClient()
        val base = git(root, "rev-parse", "HEAD").trim()
        val blob = git(root, "rev-parse", "HEAD:README.md").trim()
        val branch = git(root, "branch", "--show-current").trim()
        val result = runBlocking {
            client.readFileProposalContext(project(root), listOf("README.md", "untracked.txt", "absent/new.txt"))
        }
        assertEquals(ExternalExecutionStatus.Completed, result.status)
        assertEquals(RepositoryFileProposal(base, listOf(
            RepositoryProposedFile("README.md", blob, "initial\n"),
            RepositoryProposedFile("untracked.txt", null, ""),
            RepositoryProposedFile("absent/new.txt", null, ""),
        )), RepositoryFileProposal.parse(result.artifacts.single().textContent!!))
        assertEquals("dirty\n", root.resolve("README.md").toFile().readText())
        assertEquals(base, git(root, "rev-parse", "HEAD").trim())
        assertEquals(branch, git(root, "branch", "--show-current").trim())
        assertEquals(result, runBlocking { client.getRun(project(root), result.id) })
        assertFailsWith<IllegalArgumentException> {
            runBlocking { client.readFileProposalContext(project(root), listOf("README.md/child")) }
        }
    }

    @Test
    fun hostSnapshotRejectsBinaryAndSymlinkWithoutFollowingIt() = withGitRepository { root ->
        // Insert a symbolic-link tree entry directly; no platform symlink privilege is needed.
        git(root, "update-index", "--add", "--cacheinfo", "120000," +
            git(root, "rev-parse", "HEAD:README.md").trim() + ",alias")
        root.resolve("binary").toFile().writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        git(root, "add", "binary")
        git(root, "commit", "-m", "Unsafe snapshot targets")
        val client = LocalGitRepositoryOperationClient()
        for (path in listOf("alias", "alias/child", "binary", "../escape", "*.md")) {
            assertFailsWith<IllegalArgumentException>(path) {
                runBlocking { client.readFileProposalContext(project(root), listOf(path)) }
            }
        }
    }

    @Test
    fun hostCommitCreatesNewBranchWithoutChangingCheckoutOrIndex() = withGitRepository { root ->
        val client = LocalGitRepositoryOperationClient()
        val base = git(root, "rev-parse", "HEAD").trim()
        val blob = git(root, "rev-parse", "HEAD:README.md").trim()
        root.resolve("README.md").writeText("dirty\n")
        val index = git(root, "ls-files", "--stage")
        val proposal = RepositoryFileProposal(base, listOf(
            RepositoryProposedFile("README.md", blob, "replacement\n"),
            RepositoryProposedFile("new.txt", null, "new\n"),
        ))
        val result = runBlocking { client.commitFileProposal(project(root), "aive/reviewed", proposal) }
        assertEquals(ExternalExecutionStatus.Completed, result.status)
        assertEquals("replacement\n", git(root, "show", "aive/reviewed:README.md"))
        assertEquals("new\n", git(root, "show", "aive/reviewed:new.txt"))
        assertEquals(base, git(root, "rev-parse", "HEAD").trim())
        assertEquals(index, git(root, "ls-files", "--stage"))
        assertEquals("dirty\n", root.resolve("README.md").toFile().readText())
        val head = git(root, "rev-parse", "aive/reviewed").trim()
        assertEquals(head, result.artifacts.single().metadata["headCommit"])
        assertFailsWith<IllegalArgumentException> {
            runBlocking { client.commitFileProposal(project(root), "aive/reviewed", proposal) }
        }
        assertEquals(head, git(root, "rev-parse", "aive/reviewed").trim())
        git(root, "symbolic-ref", "refs/heads/aive/alias", "refs/heads/aive/unborn")
        assertFailsWith<IllegalArgumentException> {
            runBlocking { client.commitFileProposal(project(root), "aive/alias", proposal) }
        }
        assertEquals("refs/heads/aive/unborn", git(root, "symbolic-ref", "refs/heads/aive/alias").trim())
        assertTrue("refs/heads/aive/unborn" !in git(root, "show-ref"))
        git(root, "add", "README.md")
        git(root, "commit", "-m", "Advance source branch")
        assertFailsWith<IllegalArgumentException> {
            runBlocking { client.commitFileProposal(project(root), "aive/stale", proposal) }
        }
        assertTrue("aive/stale" !in git(root, "branch", "--list"))
    }

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

    @Test
    fun aNewAttemptIdentityRunsAgainInsteadOfReplayingTheFailure() = withGitRepository { root ->
        val client = LocalGitRepositoryOperationClient()
        val first = runBlocking { client.start(project(root), "checkout:later", "task-run") }
        assertEquals(ExternalExecutionStatus.Failed, first.status)

        git(root, "branch", "later")
        val sameAttempt = runBlocking { client.start(project(root), "checkout:later", "task-run") }
        val retry = runBlocking { client.start(project(root), "checkout:later", "task-run#attempt-2") }

        assertEquals(ExternalExecutionStatus.Failed, sameAttempt.status)
        assertEquals(ExternalExecutionStatus.Completed, retry.status, retry.message)
        assertEquals("later", git(root, "branch", "--show-current").trim())
    }

    @Test
    fun concurrentStartsOfOneRunExecuteGitOnce() = withGitRepository { root ->
        repeat(10) { round ->
            val results = runBlocking {
                // Separate clients share only the on-disk record, as two processes would.
                List(16) { LocalGitRepositoryOperationClient() }.map { client ->
                    async(Dispatchers.IO) { client.start(project(root), "create-branch:once-$round", "same-run") }
                }.awaitAll()
            }

            // Only one start ran `git switch -c`; a second run would have failed with "already exists".
            assertTrue(results.none { result -> result.artifacts.any { "already exists" in it.textContent.orEmpty() } }, "$results")
            assertEquals(1, results.count { it.status == ExternalExecutionStatus.Completed && !it.message.orEmpty().endsWith("recovered") })
        }
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

    @Test
    fun worktreeBranchLeavesTheCheckoutAlone() = withGitRepository { root ->
        root.resolve("README.md").writeText("uncommitted\n")
        val client = LocalGitRepositoryOperationClient()
        val worktree = root.resolveSibling("${root.fileName}-worktrees").resolve("feature-x")
        try {
            val execution = runBlocking { client.start(project(root), "worktree-branch:feature/x") }

            assertEquals(ExternalExecutionStatus.Completed, execution.status, execution.message)
            val artifact = execution.artifacts.single()
            assertEquals(worktree.toFile().absolutePath, artifact.metadata["worktreePath"])
            assertEquals("feature/x", artifact.metadata["branch"])
            assertEquals("initial\n", worktree.resolve("README.md").toFile().readText())
            assertEquals("uncommitted\n", root.resolve("README.md").toFile().readText())
            assertTrue(git(root, "rev-parse", "--abbrev-ref", "HEAD").trim() != "feature/x")
        } finally {
            runCatching { git(root, "worktree", "remove", "--force", worktree.toString()) }
            worktree.parent.toFile().deleteRecursively()
        }
    }

    private fun withGitRepository(block: (Path) -> Unit) {
        val root = createTempDirectory("aive-local-git-test-")
        try {
            git(root, "init")
            git(root, "config", "user.email", "aive-test@example.invalid")
            git(root, "config", "user.name", "Aive Test")
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
}
