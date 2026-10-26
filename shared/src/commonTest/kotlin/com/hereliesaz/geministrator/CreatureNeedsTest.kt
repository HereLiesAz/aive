package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CreatureNeedsTest {
    private val engineer = BuiltInRoles.ImplementationEngineer
    private val qa = BuiltInRoles.QaEngineer
    private val roles = listOf(engineer, qa)

    private fun task(id: String, role: RoleDefinition, vararg deps: String) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = id,
        objective = "Do $id",
        roleId = role.id,
        dependsOn = deps.map(::TaskDefinitionId).toSet(),
        executor = TaskExecutor.RoleAgent(role.id),
    )

    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("needs"),
        name = "Needs",
        tasks = listOf(
            task("plan", engineer),
            task("build", engineer, "plan"),
            task("review", qa, "build"),
        ),
    )

    private fun run(vararg tasks: Triple<String, TaskRunStatus, Pair<String, Int>>) = WorkflowRun(
        id = WorkflowRunId("run"),
        projectId = ProjectId("p"),
        workflowDefinitionId = definition.id,
        objective = "o",
        status = WorkflowRunStatus.Running,
        taskRuns = tasks.associate { (id, status, providerAndAttempt) ->
            val t = definition.tasks.first { it.id.value == id }
            t.id to TaskRun(
                id = TaskRunId("r-$id"),
                taskDefinitionId = t.id,
                status = status,
                attempt = providerAndAttempt.second,
                assignedRoleId = t.roleId,
                assignedProviderId = AgentProviderId(providerAndAttempt.first),
            )
        },
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    @Test
    fun readingANearClonesOutputIsEcho() {
        // build reads plan (same role, same provider) and is reviewed by qa (different role)
        val needs = assertNotNull(
            creatureNeeds(
                TaskDefinitionId("build"),
                definition,
                run(
                    Triple("plan", TaskRunStatus.Completed, "a" to 1),
                    Triple("build", TaskRunStatus.Completed, "a" to 1),
                    Triple("review", TaskRunStatus.Running, "b" to 1),
                ),
                roles,
            ),
        )
        assertEquals(50, needs.echo)
        assertEquals(EchoState.SelfAbsorbed, needs.echoState)
        assertEquals("plan", needs.nearClone)
        assertEquals(0, needs.selfReviews)
        assertEquals(1, needs.reviews)
        assertEquals(100, needs.outputUsed)
    }

    @Test
    fun retriesAndFailureCostIntegrity() {
        val needs = assertNotNull(
            creatureNeeds(
                TaskDefinitionId("build"),
                definition,
                run(Triple("build", TaskRunStatus.Failed, "a" to 3)),
                roles,
            ),
        )
        assertEquals(30, needs.integrity)
        assertEquals(0, needs.outputUsed)
    }

    @Test
    fun budgetIsWhatTheAgentHasLeftAcrossTheRun() {
        val budgeted = engineer.copy(tokenBudget = 1_000)
        val spent = run(
            Triple("plan", TaskRunStatus.Completed, "a" to 1),
            Triple("build", TaskRunStatus.Running, "a" to 1),
        ).let { r ->
            r.copy(
                taskRuns = r.taskRuns.mapValues { (_, t) ->
                    t.copy(usage = com.hereliesaz.geministrator.domain.TaskUsage(inputTokens = 200, outputTokens = 50))
                },
            )
        }
        val needs = assertNotNull(creatureNeeds(TaskDefinitionId("build"), definition, spent, listOf(budgeted, qa)))
        // plan and build are the same agent: 500 of 1000 spent
        assertEquals(50, needs.budget)
        assertEquals(250, needs.tokensSpent)
    }

    @Test
    fun budgetIsNotReportedAndTheLastTaskHasNoDownstream() {
        val needs = assertNotNull(creatureNeeds(TaskDefinitionId("review"), definition, run(), roles))
        assertNull(needs.budget)
        assertNull(needs.tokensSpent)
        assertNull(needs.outputUsed)
        assertEquals(0, needs.echo)
    }
}
