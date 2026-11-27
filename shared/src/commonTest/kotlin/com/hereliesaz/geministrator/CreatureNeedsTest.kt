package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
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
                providerRunId = ProviderRunId("session-$id"),
            )
        },
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    /** Explicit evidence per exact session, not inferred from its role. */
    private fun reports(run: WorkflowRun) = run.taskRuns.values.map { task ->
        ProviderUsageRecorded(run.id,task.taskDefinitionId,requireNotNull(task.assignedProviderId),
            requireNotNull(task.providerRunId),model="model-a",modelConsistent=true,occurredAtEpochMillis=2L)
    }

    @Test
    fun readingANearClonesOutputIsEcho() {
        // build reads plan (same role, same provider) and is reviewed by qa (different role)
        val r=run(
            Triple("plan", TaskRunStatus.Completed, "a" to 1),
            Triple("build", TaskRunStatus.Completed, "a" to 1),
            Triple("review", TaskRunStatus.Running, "b" to 1),
        )
        val needs=assertNotNull(creatureNeeds(TaskDefinitionId("build"),definition,r,roles,reports(r)))
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
        assertEquals(250L, needs.tokensSpent)
    }

    @Test
    fun budgetIsNotReportedAndTheLastTaskHasNoDownstream() {
        val needs = assertNotNull(creatureNeeds(TaskDefinitionId("review"), definition, run(), roles))
        assertNull(needs.budget)
        assertNull(needs.tokensSpent)
        assertNull(needs.outputUsed)
        assertEquals(0, needs.echo)
    }

    @Test
    fun clonedRoleIdsAndCosmeticNamesDoNotAlterConfigurationKinship() {
        val clone=engineer.copy(id=com.hereliesaz.geministrator.domain.RoleDefinitionId("new-id"),
            name="Different name",parents=listOf(engineer.id),enabled=false)
        assertEquals(100,configurationKinship(engineer,"provider-a",clone,"provider-a","model-a","model-a"))
        assertEquals(80,configurationKinship(engineer,"provider-a",clone,"provider-b","model-a","model-a"))
        assertEquals(70,configurationKinship(engineer,"provider-a",clone,"provider-a","model-a","model-b"))
        assertEquals(70,configurationKinship(engineer,"provider-a",clone.copy(instructions="Different instructions"),"provider-a","model-a","model-a"))
        assertEquals(80,configurationKinship(engineer,"provider-a",clone.copy(toolCeiling=listOf("read")),"provider-a","model-a","model-a"))
        assertNull(configurationKinship(engineer,null,clone,null,"model-a","model-a"))
        assertNull(configurationKinship(engineer,"provider-a",clone,"provider-a",null,"model-a"))
    }

    @Test
    fun echoUsesRunSnapshotRatherThanLaterRosterAndIgnoresFuturePaths() {
        val clone=engineer.copy(id=com.hereliesaz.geministrator.domain.RoleDefinitionId("clone"),name="Clone")
        val d=definition.copy(tasks=listOf(task("plan",clone),task("build",engineer,"plan"),task("review",qa,"build")))
        val plan=d.tasks[0]
        val build=d.tasks[1]
        val review=d.tasks[2]
        val r=run(
            Triple("plan",TaskRunStatus.Completed,"a" to 1),
            Triple("build",TaskRunStatus.Completed,"a" to 1),
            Triple("review",TaskRunStatus.Ready,"b" to 1),
        ).let { original -> original.copy(roleSnapshot=listOf(clone,engineer,qa),
            taskRuns=original.taskRuns + (plan.id to original.taskRuns.getValue(plan.id).copy(assignedRoleId=clone.id))) }
        val needs=assertNotNull(creatureNeeds(build.id,d,r,listOf(engineer.copy(instructions="Edited after launch"),qa),reports(r)))
        assertEquals("plan",needs.nearClone)
        assertEquals(100,needs.echo)
        assertEquals(1,needs.echoComparablePaths)
        assertEquals(0,needs.reviews)
        val unknown=r.copy(taskRuns=r.taskRuns+(plan.id to r.taskRuns.getValue(plan.id).copy(assignedProviderId=null)))
        val missing=assertNotNull(creatureNeeds(build.id,d,unknown,roles,reports(r)))
        assertNull(missing.nearClone)
        assertEquals(0,missing.echoComparablePaths)
    }

    @Test
    fun programmaticDependencyIsNotACloneEvenWithSameRoleAndProvider() {
        val d=definition.copy(tasks=definition.tasks.map {
            if(it.id.value=="plan") it.copy(executor=TaskExecutor.TestRunner("./gradlew test")) else it
        })
        val r=run(Triple("plan",TaskRunStatus.Completed,"a" to 1),Triple("build",TaskRunStatus.Running,"a" to 1))
        val needs=assertNotNull(creatureNeeds(TaskDefinitionId("build"),d,r,roles))
        assertNull(needs.nearClone)
        assertEquals(1,needs.echoComparablePaths)
        assertEquals(0,needs.echo)
    }

    @Test
    fun onlyUnambiguousCurrentSessionModelReportsEstablishKinship() {
        val r=run(Triple("plan",TaskRunStatus.Completed,"a" to 1),Triple("build",TaskRunStatus.Running,"a" to 1))
        val plan=TaskDefinitionId("plan")
        val build=TaskDefinitionId("build")
        val events=reports(r)
        val source=events.single { it.taskDefinitionId==plan }
        val target=events.single { it.taskDefinitionId==build }
        assertEquals(mapOf(plan to "model-a",build to "model-a"),reportedSessionModels(r,events))
        fun needs(reports: List<ProviderUsageRecorded>)=assertNotNull(creatureNeeds(build,definition,r,roles,reports))
        assertEquals(100,needs(events).echo)
        assertEquals(0,needs(emptyList()).echoComparablePaths)
        assertNull(needs(emptyList()).nearClone)
        val different=listOf(source.copy(model="model-b"),target)
        assertEquals(1,needs(different).echoComparablePaths)
        assertEquals(0,needs(different).echo)
        assertNull(needs(different).nearClone)
        for(unreliable in listOf(
            source.copy(modelConsistent=null),source.copy(modelConsistent=false),
            source.copy(model=null),source.copy(tokensAvoided=20),
            source.copy(workflowRunId=WorkflowRunId("other-run")),
            source.copy(providerRunId=ProviderRunId("prior-attempt")),
            source.copy(providerId=AgentProviderId("different-provider")),
        )) assertEquals(0,needs(listOf(unreliable,target)).echoComparablePaths)
        assertEquals(0,needs(events+source.copy(model="model-b")).echoComparablePaths)
    }
}
