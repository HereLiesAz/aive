package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.workflow.ExternalExecutionRun
import com.hereliesaz.geministrator.workflow.ExternalExecutionStatus
import com.hereliesaz.geministrator.workflow.TaskExecutorContext
import com.hereliesaz.geministrator.workflow.TestRunnerExecutorIntegration
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class LocalTestRunnerClientTest {
    private val windows = System.getProperty("os.name").lowercase().contains("win")

    @Test
    fun splitsArgumentsAndQuotesWithoutAShell() {
        assertEquals(listOf("./gradlew", "test"), parseTestCommand("./gradlew test"))
        assertEquals(
            listOf("./gradlew", ":shared:desktopTest", "--tests", "com.example.*Test"),
            parseTestCommand("  ./gradlew\t:shared:desktopTest --tests 'com.example.*Test'  "),
        )
        assertEquals(listOf("npm", "run", "test:unit", "a b", ""), parseTestCommand("npm run test:unit \"a b\" ''"))
        assertEquals(listOf("pytest", "-k", "not slow"), parseTestCommand("pytest -k \"not slow\""))
        assertEquals(listOf("tool", "--flag=value"), parseTestCommand("tool --flag=value"))
    }

    @Test
    fun refusesShellSyntaxInsteadOfInterpretingIt() {
        listOf(
            "npm test && rm -rf /",
            "npm test; echo hi",
            "npm test | tee out",
            "npm test > out.txt",
            "echo \$HOME",
            "echo \"\$HOME\"",
            "echo `whoami`",
            "ls *.kt",
            "cat ~/.ssh/id_rsa",
            "(npm test)",
            "npm test &",
            "FOO=1 npm test",
            "npm test\nrm -rf /",
            "'unterminated",
            "\"unterminated",
            "",
            "   ",
            "-rf /",
            "a\\ b",
        ).forEach { command ->
            assertFailsWith<IllegalArgumentException>("accepted: $command") { parseTestCommand(command) }
        }
    }

    @Test
    fun environmentKeepsOnlyAllowlistedVariables() {
        val environment = testEnvironment(
            mapOf(
                "PATH" to "/usr/bin",
                "HOME" to "/home/keeper",
                "ANTHROPIC_API_KEY" to "secret-key",
                "GITHUB_TOKEN" to "secret-token",
                "GIT_DIR" to "/elsewhere/.git",
                "JAVA_HOME" to "/opt/jdk",
            ),
        )
        assertEquals("/usr/bin", environment["PATH"])
        assertEquals("/home/keeper", environment["HOME"])
        assertEquals("/opt/jdk", environment["JAVA_HOME"])
        assertEquals("true", environment["CI"])
        assertEquals("0", environment["GIT_TERMINAL_PROMPT"])
        assertFalse("ANTHROPIC_API_KEY" in environment)
        assertFalse("GITHUB_TOKEN" in environment)
        assertFalse("GIT_DIR" in environment)
    }

    @Test
    fun aZeroExitCompletesWithATestReport() = withFolder { folder ->
        script(folder, "pass.sh", "echo all good\nexit 0\n")
        val client = LocalTestRunnerClient()
        try {
            val context = context(folder)
            val started = runBlocking { client.start(context, "./pass.sh") }
            assertEquals(ExternalExecutionStatus.Running, started.status)

            val finished = awaitFinished(client, context.project, started.id)
            assertEquals(ExternalExecutionStatus.Completed, finished.status, finished.message)
            val report = finished.artifacts.single()
            assertEquals(ArtifactKind.TestResult, report.kind)
            assertEquals(context.taskRun.id, report.taskRunId)
            assertEquals("0", report.metadata["exitCode"])
            assertEquals("true", report.metadata["passed"])
            assertEquals("linked-folder", report.metadata["workspace"])
            assertTrue(report.textContent.orEmpty().contains("all good"))
            assertTrue(report.textContent.orEmpty().contains("Result: passed"))
        } finally {
            client.close()
        }
    }

    @Test
    fun aNonZeroExitFailsWithItsOutput() = withFolder { folder ->
        script(folder, "fail.sh", "echo 'expected 2 but was 3' >&2\nexit 3\n")
        val client = LocalTestRunnerClient()
        try {
            val context = context(folder)
            val started = runBlocking { client.start(context, "./fail.sh") }
            val finished = awaitFinished(client, context.project, started.id)

            assertEquals(ExternalExecutionStatus.Failed, finished.status)
            val report = finished.artifacts.single()
            assertEquals("3", report.metadata["exitCode"])
            assertEquals("false", report.metadata["passed"])
            assertTrue(report.textContent.orEmpty().contains("expected 2 but was 3"))
        } finally {
            client.close()
        }
    }

    @Test
    fun theIntegrationMapsTheReportToTaskState() = withFolder { folder ->
        script(folder, "fail.sh", "exit 1\n")
        val client = LocalTestRunnerClient()
        try {
            val context = context(folder, command = "./fail.sh")
            val integration = TestRunnerExecutorIntegration(client)
            assertTrue(integration.supports(context.executor, context.project))

            val dispatched = runBlocking { integration.dispatch(context) }
            assertEquals(TaskRunStatus.Running, dispatched.status)
            val runId = dispatched.externalRunId!!
            awaitFinished(client, context.project, runId)
            val reconciled = runBlocking {
                integration.reconcile(context.copy(taskRun = context.taskRun.copy(status = TaskRunStatus.Running, externalRunId = runId)))
            }
            assertEquals(TaskRunStatus.Failed, reconciled.status)
            assertEquals(ArtifactKind.TestResult, reconciled.artifacts.single().kind)
        } finally {
            client.close()
        }
    }

    @Test
    fun aRunThatOutlivesItsTimeoutIsEnded() = withFolder { folder ->
        script(folder, "hang.sh", "sleep 120\n")
        val client = LocalTestRunnerClient(timeoutMillis = 1_000)
        try {
            val context = context(folder)
            val began = System.nanoTime()
            val started = runBlocking { client.start(context, "./hang.sh") }
            val finished = awaitFinished(client, context.project, started.id)
            val seconds = (System.nanoTime() - began) / 1_000_000_000

            assertEquals(ExternalExecutionStatus.Failed, finished.status)
            assertEquals("true", finished.artifacts.single().metadata["timedOut"])
            assertTrue(seconds < 20, "timeout took ${seconds}s")
        } finally {
            client.close()
        }
    }

    @Test
    fun longOutputKeepsOnlyItsTail() = withFolder { folder ->
        script(folder, "loud.sh", "head -c 100000 /dev/zero | tr '\\000' x\necho END\n")
        val client = LocalTestRunnerClient(maxStreamChars = 1_000)
        try {
            val context = context(folder)
            val started = runBlocking { client.start(context, "./loud.sh") }
            val finished = awaitFinished(client, context.project, started.id)

            assertEquals(ExternalExecutionStatus.Completed, finished.status, finished.message)
            val report = finished.artifacts.single()
            assertEquals("100004", report.metadata["stdoutChars"])
            assertEquals("true", report.metadata["outputTruncated"])
            val text = report.textContent.orEmpty()
            assertTrue(text.contains("END"), "the tail was not kept")
            assertTrue(text.contains("last 1000 of 100004 characters"))
            assertTrue(text.length < 3_000, "report is ${text.length} characters")
        } finally {
            client.close()
        }
    }

    @Test
    fun refusesToRunWithoutAnApprovedGate() = withFolder { folder ->
        script(folder, "pass.sh", "exit 0\n")
        val client = LocalTestRunnerClient()
        try {
            assertFailsWith<IllegalArgumentException> {
                runBlocking { client.start(context(folder, approved = false), "./pass.sh") }
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun refusesAShellCommandBeforeRunningAnything() = withFolder { folder ->
        val client = LocalTestRunnerClient()
        try {
            assertFailsWith<IllegalArgumentException> {
                runBlocking { client.start(context(folder), "./pass.sh && touch pwned") }
            }
            assertFalse(File(folder, "pwned").exists())
        } finally {
            client.close()
        }
    }

    @Test
    fun aRunNobodyPollsIsEnded() = withFolder { folder ->
        script(folder, "hang.sh", "sleep 120\n")
        val client = LocalTestRunnerClient(abandonAfterMillis = 300, watchdogIntervalMillis = 100)
        try {
            val context = context(folder)
            val started = runBlocking { client.start(context, "./hang.sh") }
            Thread.sleep(2_000)
            val state = runBlocking { client.getRun(context.project, started.id) }
            assertEquals(ExternalExecutionStatus.Failed, state.status)
            assertTrue(state.message.orEmpty().contains("stopped checking"), state.message)
        } finally {
            client.close()
        }
    }

    @Test
    fun anUnknownRunFailsInsteadOfReplaying() = withFolder { folder ->
        val client = LocalTestRunnerClient()
        try {
            val state = runBlocking { client.getRun(context(folder).project, "local-test-missing") }
            assertEquals(ExternalExecutionStatus.Failed, state.status)
        } finally {
            client.close()
        }
    }

    @Test
    fun runsTheUpstreamCommitInAnIsolatedWorktree() = withFolder { folder ->
        git(folder, "init")
        git(folder, "config", "user.email", "haive-test@example.invalid")
        git(folder, "config", "user.name", "Haive Test")
        script(folder, "check.sh", "exit 0\n")
        git(folder, "add", "check.sh")
        git(folder, "commit", "-m", "Passing check")
        val commit = git(folder, "rev-parse", "HEAD").trim()
        // The linked checkout now fails, uncommitted; the run must test the upstream commit instead.
        script(folder, "check.sh", "exit 7\n")

        val client = LocalTestRunnerClient()
        try {
            val upstream = ArtifactRef(
                id = ArtifactId("code-change"),
                kind = ArtifactKind.CodeChange,
                taskRunId = TaskRunId("code-run"),
                label = "Haive: change",
                textContent = "diff",
                metadata = mapOf("branch" to "haive/x", "headCommit" to commit),
                createdAtEpochMillis = 1L,
            )
            val context = context(folder, upstreamArtifacts = listOf(upstream))
            val started = runBlocking { client.start(context, "./check.sh") }
            val finished = awaitFinished(client, context.project, started.id)

            assertEquals(ExternalExecutionStatus.Completed, finished.status, finished.message)
            val report = finished.artifacts.single()
            assertEquals("detached-worktree", report.metadata["workspace"])
            assertEquals(commit, report.metadata["headCommit"])
            assertFalse(File(report.metadata["workingDirectory"]!!).exists(), "the temporary worktree was left behind")
            assertEquals(1, git(folder, "worktree", "list").lines().count { it.isNotBlank() })
        } finally {
            client.close()
        }
    }

    private fun awaitFinished(client: LocalTestRunnerClient, project: Project, runId: String): ExternalExecutionRun =
        runBlocking {
            withTimeout(60_000) {
                var state = client.getRun(project, runId)
                while (state.status == ExternalExecutionStatus.Running) {
                    delay(100)
                    state = client.getRun(project, runId)
                }
                state
            }
        }

    private fun context(
        folder: File,
        command: String? = null,
        approved: Boolean = true,
        upstreamArtifacts: List<ArtifactRef> = emptyList(),
    ): TaskExecutorContext {
        val project = Project(
            id = ProjectId("local-project"),
            name = "Local project",
            repository = RepositoryRef(
                owner = "",
                name = folder.name,
                source = RepositorySource.Local,
                localPath = folder.absolutePath,
            ),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
        val code = TaskDefinition(
            id = TaskDefinitionId("code"),
            name = "Code",
            objective = "Change",
            roleId = null,
            executor = TaskExecutor.RepositoryOperation("status"),
        )
        val approval = TaskDefinition(
            id = TaskDefinitionId("approve"),
            name = "Approve",
            objective = "Approve running tests",
            roleId = null,
            dependsOn = setOf(code.id),
            executor = TaskExecutor.HumanApproval(),
        )
        val executor = TaskExecutor.TestRunner(command)
        val test = TaskDefinition(
            id = TaskDefinitionId("test"),
            name = "Test",
            objective = "Verify",
            roleId = null,
            dependsOn = setOf(approval.id),
            executor = executor,
        )
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("definition"),
            name = "Workflow",
            tasks = listOf(code, approval, test),
            testDesignPolicy = TestDesignPolicy.None,
        )
        val testRun = TaskRun(
            id = TaskRunId("test-run"),
            taskDefinitionId = test.id,
            status = TaskRunStatus.Ready,
            assignedRoleId = null,
            executor = executor,
        )
        val run = WorkflowRun(
            id = WorkflowRunId("run"),
            projectId = project.id,
            workflowDefinitionId = definition.id,
            objective = "Ship",
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(
                code.id to TaskRun(
                    id = TaskRunId("code-run"),
                    taskDefinitionId = code.id,
                    status = TaskRunStatus.Completed,
                    assignedRoleId = null,
                    artifacts = upstreamArtifacts,
                ),
                approval.id to TaskRun(
                    id = TaskRunId("approve-run"),
                    taskDefinitionId = approval.id,
                    status = if (approved) TaskRunStatus.Completed else TaskRunStatus.AwaitingApproval,
                    assignedRoleId = null,
                ),
                test.id to testRun,
            ),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
        return TaskExecutorContext(project, definition, run, test, testRun, executor, 2L)
    }

    private fun script(folder: File, name: String, body: String) {
        val file = File(folder, name)
        file.writeText("#!/bin/sh\n$body")
        assertTrue(file.setExecutable(true, true))
    }

    private fun withFolder(block: (File) -> Unit) {
        if (windows) return
        val folder = Files.createTempDirectory("haive-test-runner-test-").toFile()
        try {
            block(folder)
        } finally {
            folder.deleteRecursively()
        }
    }

    private fun git(folder: File, vararg args: String): String {
        val command = listOf("git", "-C", folder.absolutePath) + args
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        check(exitCode == 0) { "${command.joinToString(" ")} failed ($exitCode): $output" }
        return output
    }
}
