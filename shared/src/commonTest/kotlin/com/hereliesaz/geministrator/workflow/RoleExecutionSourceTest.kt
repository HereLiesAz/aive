package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.RoleExecutionSource
import com.hereliesaz.geministrator.domain.ScriptLanguage
import com.hereliesaz.geministrator.domain.ScriptRunner
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RoleExecutionSourceTest {
    @Test
    fun githubActionRoleBecomesSystemExecutorWithoutLosingRoleIdentity() {
        val role = role(
            RoleExecutionSource.GitHubAction(
                workflow = "role.yml",
                ref = "main",
            ),
        )
        val run = createRun(role)

        val taskRun = run.taskRuns.getValue(TaskDefinitionId("work"))
        val executor = assertIs<TaskExecutor.GitHubAction>(taskRun.executor)
        assertEquals("role.yml", executor.workflow)
        assertEquals(role.id, taskRun.assignedRoleId)
    }

    @Test
    fun localJavaScriptRoleBecomesScriptExecutor() {
        val role = role(
            RoleExecutionSource.Script(
                language = ScriptLanguage.JavaScript,
                source = "return { status: 'completed', output: aive.taskObjective };",
                runner = ScriptRunner.LocalSandbox,
            ),
        )
        val executor = assertIs<TaskExecutor.Script>(
            createRun(role).taskRuns.getValue(TaskDefinitionId("work")).executor,
        )
        assertEquals(ScriptLanguage.JavaScript, executor.language)
        assertIs<ScriptRunner.LocalSandbox>(executor.runner)
    }

    @Test
    fun pythonRoleBecomesGitHubBackedScriptExecutor() {
        val role = role(
            RoleExecutionSource.Script(
                language = ScriptLanguage.Python,
                source = "result = {'status': 'completed'}",
                runner = ScriptRunner.GitHubActions(workflow = "script-runner.yml"),
            ),
        )
        val executor = assertIs<TaskExecutor.Script>(
            createRun(role).taskRuns.getValue(TaskDefinitionId("work")).executor,
        )
        assertEquals(ScriptLanguage.Python, executor.language)
        assertEquals(
            "script-runner.yml",
            assertIs<ScriptRunner.GitHubActions>(executor.runner).workflow,
        )
    }

    @Test
    fun defaultRoleStillUsesAgentExecutor() {
        val role = role(RoleExecutionSource.Agent)
        val executor = createRun(role).taskRuns.getValue(TaskDefinitionId("work")).executor
        assertEquals(TaskExecutor.RoleAgent(role.id), executor)
    }

    private fun role(source: RoleExecutionSource) = RoleDefinition(
        id = RoleDefinitionId("worker"),
        name = "Worker",
        description = "Does work",
        instructions = "Complete the task.",
        executionSource = source,
    )

    private fun createRun(role: RoleDefinition) = WorkflowRunFactory.create(
        definition = WorkflowDefinition(
            id = WorkflowDefinitionId("definition"),
            name = "Definition",
            tasks = listOf(
                TaskDefinition(
                    id = TaskDefinitionId("work"),
                    name = "Work",
                    objective = "Do the work",
                    roleId = role.id,
                ),
            ),
        ),
        workflowRunId = WorkflowRunId("run"),
        projectId = ProjectId("project"),
        objective = "Objective",
        nowEpochMillis = 1L,
        taskRunIdFactory = { TaskRunId("run-${it.value}") },
        roles = listOf(role),
    )
}
