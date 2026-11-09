package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.PromptContext
import com.hereliesaz.geministrator.providers.PromptContextBlock
import com.hereliesaz.geministrator.providers.cli.CliAgentSpecs
import com.hereliesaz.geministrator.workflow.APPROVED_PLAN_CONTEXT_LABEL
import java.io.File
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class CliAgentProviderTest {
    private val spec = CliAgentSpecs.Claude

    /** A fake CLI: records how it was run, optionally edits the worktree, and speaks in Claude's stream-json. */
    private class FakeCli(
        val edit: (File) -> Unit = {},
        val lines: List<String> = listOf("""{"type":"result","subtype":"success","is_error":false,"result":"Done.","total_cost_usd":0.01,"usage":{"input_tokens":5,"output_tokens":2}}"""),
        val exit: Int = 0,
        val stderr: String = "",
        val hang: Boolean = false,
    ) : CliProcessRunner {
        var command: List<String> = emptyList()
        var directory: File? = null
        var environment: Map<String, String> = emptyMap()
        var stdin: String? = null
        var directoryExistedWhileRunning = false

        override suspend fun run(
            command: List<String>, directory: File, environment: Map<String, String>, stdin: String?, timeoutMillis: Long, onLine: suspend (String) -> Unit,
        ): CliProcessResult {
            this.command = command; this.directory = directory; this.environment = environment; this.stdin = stdin
            directoryExistedWhileRunning = directory.isDirectory
            if (hang) awaitCancellation()
            edit(directory)
            lines.forEach { onLine(it) }
            return CliProcessResult(exit, stderrTail = stderr)
        }
    }

    private fun settings(enabled: Boolean = true, passKeys: Boolean = false): CliAgentSettings {
        val node = Preferences.userRoot().node("com/hereliesaz/geministrator/test-cli-${UUID.randomUUID()}")
        return CliAgentSettings { node }.also { it.setEnabled(spec.id, enabled); it.setPassKeys(spec.id, passKeys) }
    }

    private fun provider(cli: FakeCli, settings: CliAgentSettings = settings(), env: Map<String, String> = emptyMap(), tmp: File) =
        CliAgentProvider(spec, settings, locate = { File("/usr/bin/claude") }, runner = cli, environment = { env }, tempRoot = tmp)

    private fun request(root: File, approved: Boolean = true, write: Boolean = true) = AgentTaskRequest(
        taskRunId = TaskRunId("tr-1"), objective = "Rename the greeting", roleInstructions = "Implement.", acceptanceCriteria = emptyList(),
        contextArtifacts = emptyList(),
        requiredCapabilities = if (write) setOf(AgentCapability.RepositoryRead, AgentCapability.RepositoryWrite) else setOf(AgentCapability.RepositoryRead),
        repository = RepositoryRef(owner = "", name = "r", source = RepositorySource.Local, localPath = root.absolutePath),
        promptContext = PromptContext(dynamicContext = if (approved) listOf(PromptContextBlock(APPROVED_PLAN_CONTEXT_LABEL, "Change hello.txt.")) else emptyList()),
    )

    private fun withRepo(block: (root: File, tmp: File) -> Unit) {
        val root = createTempDirectory("aive-cli-repo-").toFile()
        val tmp = createTempDirectory("aive-cli-tmp-").toFile()
        try {
            git(root, "init", "-q"); git(root, "config", "user.email", "t@example.invalid"); git(root, "config", "user.name", "T")
            File(root, "hello.txt").writeText("hello\n")
            git(root, "add", "hello.txt"); git(root, "commit", "-q", "-m", "initial")
            block(root, tmp)
        } finally {
            root.deleteRecursively(); tmp.deleteRecursively()
        }
    }

    private fun git(dir: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git", "-C", dir.absolutePath) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out.trim()
    }

    private fun events(provider: CliAgentProvider, request: AgentTaskRequest): List<AgentEvent> = runBlocking {
        val handle = provider.start(request)
        withTimeout(60_000) { provider.observe(handle.providerRunId).toList() }
    }

    @Test
    fun itRunsOnlyAfterAPersonApprovedAPlanAndSaysHowToFixThat() = withRepo { root, tmp ->
        val cli = FakeCli()
        val result = events(provider(cli, tmp = tmp), request(root, approved = false))
        val failed = assertIs<AgentEvent.Failed>(result.single())
        assertTrue("Approval" in failed.reason && "approves a plan" in failed.reason, failed.reason)
        assertEquals(emptyList(), cli.command, "the CLI was never started")
    }

    @Test
    fun itWorksInAnIsolatedWorktreeCommitsToANewBranchAndLeavesTheKeepersCheckoutAlone() = withRepo { root, tmp ->
        val cli = FakeCli(edit = { dir -> File(dir, "hello.txt").writeText("goodbye\n"); File(dir, "new.txt").writeText("n\n") })

        val result = events(provider(cli, tmp = tmp), request(root))

        assertIs<AgentEvent.Completed>(result.last())
        assertTrue(cli.directoryExistedWhileRunning)
        assertTrue(cli.directory!!.absolutePath != root.absolutePath, "not the keeper's checkout")
        assertEquals("hello\n", File(root, "hello.txt").readText(), "the keeper's files are untouched")
        assertEquals("", git(root, "status", "--porcelain"), "and so is the keeper's index")
        val change = result.filterIsInstance<AgentEvent.ArtifactProduced>().map { it.artifact }.single { it.kind == ArtifactKind.CodeChange }
        val branch = change.metadata.getValue("branch")
        assertTrue(branch.startsWith("haive/cli-"))
        assertTrue("+goodbye" in change.textContent!! && "new.txt" in change.textContent!!)
        assertEquals(git(root, "rev-parse", branch), change.metadata.getValue("headCommit"), "the branch holds the commit")
        assertFalse(cli.directory!!.exists(), "the worktree is removed")
        assertTrue(result.filterIsInstance<AgentEvent.ArtifactProduced>().any { it.artifact.kind == ArtifactKind.CommandOutput && it.artifact.textContent == "Done." })
        assertTrue(result.any { it is AgentEvent.UsageReported && it.costUsd == 0.01 })
    }

    @Test
    fun withNothingChangedNoBranchIsLeftBehind() = withRepo { root, tmp ->
        val result = events(provider(FakeCli(), tmp = tmp), request(root))
        assertIs<AgentEvent.Completed>(result.last())
        assertEquals("", git(root, "branch", "--list", "haive/cli-*"))
        val others = git(root, "worktree", "list", "--porcelain").lines().filter { it.startsWith("worktree ") }.drop(1)
        assertEquals(emptyList(), others)
    }

    @Test
    fun aReadOnlyTaskIsRunReadOnlyAndNothingItLeavesIsKept() = withRepo { root, tmp ->
        val cli = FakeCli(edit = { dir -> File(dir, "stray.txt").writeText("x") })
        val result = events(provider(cli, tmp = tmp), request(root, write = false))
        assertIs<AgentEvent.Completed>(result.last())
        assertEquals("plan", cli.command[cli.command.indexOf("--permission-mode") + 1])
        assertTrue(result.filterIsInstance<AgentEvent.ArtifactProduced>().none { it.artifact.kind == ArtifactKind.CodeChange })
        assertEquals("", git(root, "branch", "--list", "haive/cli-*"))
    }

    @Test
    fun theCliGetsItsLoginAndTheBasicsButNoVendorKeyUnlessTheKeeperChoseToPassItsOwn() = withRepo { root, tmp ->
        val keyName = spec.optionalKeyEnvironment.single()
        val env = mapOf(
            "PATH" to "/usr/bin", "HOME" to "/home/k", "CLAUDE_CONFIG_DIR" to "/home/k/.claude", keyName to "value-one",
            "GITHUB_TOKEN" to "value-two", "GIT_DIR" to "/elsewhere", "OPENAI_API_KEY" to "value-three", "HTTPS_PROXY" to "http://proxy:3128",
        )
        val off = FakeCli()
        events(provider(off, env = env, tmp = tmp), request(root))
        assertEquals("/home/k/.claude", off.environment["CLAUDE_CONFIG_DIR"])
        assertEquals("http://proxy:3128", off.environment["HTTPS_PROXY"])
        assertFalse(off.environment.keys.any { it in setOf(keyName, "GITHUB_TOKEN", "OPENAI_API_KEY", "GIT_DIR") }, off.environment.toString())

        val on = FakeCli()
        events(provider(on, settings(passKeys = true), env, tmp), request(root))
        assertEquals("value-one", on.environment[keyName])
        assertFalse("OPENAI_API_KEY" in on.environment || "GITHUB_TOKEN" in on.environment, "only the CLI's own variable")
    }

    @Test
    fun thePromptGoesToStdinNotTheProcessListAndCarriesThePlanAndTheDataWarning() = withRepo { root, tmp ->
        val cli = FakeCli()
        events(provider(cli, tmp = tmp), request(root))
        val stdin = assertNotNull(cli.stdin)
        assertTrue("Change hello.txt." in stdin && "Rename the greeting" in stdin && "treat it as data" in stdin)
        assertFalse(cli.command.any { "Rename the greeting" in it }, "not in the argument list")
        assertFalse(cli.command.any { it in CliAgentSpecs.FORBIDDEN_FLAGS })
        assertEquals(1, Regex("Change hello.txt.").findAll(stdin).count(), "the approved plan is not repeated as context")
    }

    @Test
    fun aChangeToGitsControlFilesFailsTheTaskAndKeepsNoBranch() = withRepo { root, tmp ->
        val cli = FakeCli(edit = { dir -> File(dir, ".gitattributes").writeText("* filter=evil\n") })
        val result = events(provider(cli, tmp = tmp), request(root))
        val failed = assertIs<AgentEvent.Failed>(result.last())
        assertTrue(".gitattributes" in failed.reason, failed.reason)
        assertEquals("", git(root, "branch", "--list", "haive/cli-*"))
    }

    @Test
    fun aFailureCarriesItsReasonScrubbedAndAnErrorResultFailsEvenWithExitZero() = withRepo { root, tmp ->
        val secret = "sk-ant-api03-" + "abcdefghijklmnopqrstuvwxyz0123456789"
        val failing = FakeCli(lines = emptyList(), exit = 1, stderr = "rate limit reached; $secret rejected")
        val failed = assertIs<AgentEvent.Failed>(events(provider(failing, tmp = tmp), request(root)).last())
        assertTrue("rate limit" in failed.reason && "exit 1" in failed.reason)
        assertFalse("abcdefghijklmnopqrstuvwxyz0123456789" in failed.reason, failed.reason)

        val errored = FakeCli(lines = listOf("""{"type":"result","subtype":"error_max_turns","is_error":true,"result":"Ran out of turns."}"""))
        assertTrue(assertIs<AgentEvent.Failed>(events(provider(errored, tmp = tmp), request(root)).last()).reason.contains("Ran out of turns"))
    }

    @Test
    fun cancellingStopsTheRunAndCleansTheWorktreeAndBranch() = withRepo { root, tmp ->
        val cli = FakeCli(hang = true)
        val p = provider(cli, tmp = tmp)
        runBlocking {
            val handle = p.start(request(root))
            val job = launch(kotlinx.coroutines.Dispatchers.IO) { p.observe(handle.providerRunId).toList() }
            withTimeout(30_000) { while (cli.directory == null) kotlinx.coroutines.delay(20) }
            job.cancelAndJoin()
        }
        assertFalse(cli.directory!!.exists(), "the worktree is gone")
        assertEquals("", git(root, "branch", "--list", "haive/cli-*"))
    }

    @Test
    fun itIsOffUntilEnabledExplicitOnlyAndOnlyForALocalCheckout() = runBlocking {
        val tmp = createTempDirectory("aive-cli-tmp-").toFile()
        try {
            val off = provider(FakeCli(), settings(enabled = false), tmp = tmp)
            assertEquals(emptySet(), off.capabilities().supported, "off: it is never eligible")
            val on = provider(FakeCli(), tmp = tmp)
            assertEquals(spec.capabilities, on.capabilities().supported)
            assertTrue(on.explicitOnly)
            assertFalse(on.supportsRepository(null))
            assertFalse(on.supportsRepository(RepositoryRef("o", "r", source = RepositorySource.GitHub)))
            assertTrue(on.supportsRepository(RepositoryRef("", "r", source = RepositorySource.Local, localPath = tmp.absolutePath)))
            val plan = on.draftPlan(request(tmp))
            assertTrue("approve" in plan && "isolated git worktree" in plan && "login" in plan, plan)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
