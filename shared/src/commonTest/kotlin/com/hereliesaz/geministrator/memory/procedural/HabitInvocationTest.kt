package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.memory.InMemoryMemoryStore
import com.hereliesaz.geministrator.memory.MemoryBanks
import com.hereliesaz.geministrator.memory.MemoryEngineProvider
import com.hereliesaz.geministrator.memory.MemoryLayerController
import com.hereliesaz.geministrator.memory.MemoryLayerSettingsStore
import com.hereliesaz.geministrator.memory.MemoryLineage
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HabitInvocationTest {
    private fun habit(operation: String, kind: String = "GitHubAction") = FormedHabit(
        executorKind = kind, operation = operation, uses = 5, completedUses = 4, failedUses = 1,
        verifiedUses = 0, rejectedUses = 0, slots = emptyList(), firstUsedAtEpochMillis = 0L,
        lastUsedAtEpochMillis = 1L, strength = HabitStrength.Low,
    )

    private val id = TaskDefinitionId("t")

    private fun proposed(result: HabitInvocation.Result) =
        assertIs<HabitInvocation.Result.Proposed>(result).task

    @Test
    fun everyKindOfNamedOperationRebuildsItsExecutorAndAsksForAPersonsApprovalFirst() {
        val cases = mapOf(
            "github-action:ci.yml" to TaskExecutor.GitHubAction("ci.yml"),
            "deployment:production" to TaskExecutor.Deployment("production"),
            "repository:merge" to TaskExecutor.RepositoryOperation("merge"),
            "service:slack:post" to TaskExecutor.ExternalService("slack", "post"),
            "service:slack" to TaskExecutor.ExternalService("slack"),
            "nested-workflow:release" to TaskExecutor.NestedWorkflow(WorkflowDefinitionId("release")),
        )
        cases.forEach { (operation, executor) ->
            val task = proposed(HabitInvocation.propose(habit(operation), id))
            assertEquals(executor, task.executor, operation)
            assertEquals(ApprovalPolicy.HumanApproval, task.approvalPolicy, operation)
            assertEquals(id, task.id)
            assertTrue("4 of 5 earlier uses completed" in task.objective)
        }
    }

    @Test
    fun aRefAndInputsAreAcceptedOnlyAsTheShortTypedValuesAReceiptKeeps() {
        val task = proposed(
            HabitInvocation.propose(habit("github-action:ci.yml"), id, ref = "main", inputs = mapOf("build-type" to "debug", "retries" to "3")),
        )
        val executor = task.executor as TaskExecutor.GitHubAction
        assertEquals("main", executor.ref)
        assertEquals(mapOf("build-type" to "debug", "retries" to "3"), executor.inputs)

        listOf(
            mapOf("message" to "fix the bug now please"),
            mapOf("api_key" to "plain"),
            mapOf("path" to "../../etc/passwd"),
        ).forEach { bad ->
            assertIs<HabitInvocation.Result.Refused>(HabitInvocation.propose(habit("github-action:ci.yml"), id, inputs = bad))
        }
        assertIs<HabitInvocation.Result.Refused>(HabitInvocation.propose(habit("github-action:ci.yml"), id, ref = "two words"))
        assertIs<HabitInvocation.Result.Refused>(HabitInvocation.propose(habit("deployment:production"), id, ref = "main"))
    }

    @Test
    fun whatCannotBeRebuiltFaithfullyIsRefusedNotGuessed() {
        assertIs<HabitInvocation.Result.Refused>(HabitInvocation.propose(habit("github-action:ci.yml", kind = "Distributed"), id))
        assertIs<HabitInvocation.Result.Refused>(HabitInvocation.propose(habit("mystery:thing"), id))
        assertIs<HabitInvocation.Result.Refused>(HabitInvocation.propose(habit("github-action:"), id))
    }

    @Test
    fun aStandingOverrideIsNamedInTheProposalNotHiddenFromIt() {
        val withOverride = habit("github-action:ci.yml").copy(
            habitOverride = HabitOverride("keeper", "Wrong stage.", "Use the v2 job.", null, 0L, 0, null, retired = false),
        )
        val task = proposed(HabitInvocation.propose(withOverride, id))
        assertTrue("An override stands (keeper): Wrong stage. Instead: Use the v2 job." in task.objective)
    }

    @Test
    fun theControllerProposesOnlyForAHabitThatHasFormed(): Unit = runBlocking {
        val lineage = MemoryLineage()
        val controller = MemoryLayerController(
            banks = MemoryBanks.inMemory(mapOf("w1" to InMemoryMemoryStore(), "w2" to InMemoryMemoryStore(), "w3" to InMemoryMemoryStore())),
            procedureCatalog = ProcedureBankCatalog(lineage),
            lineage = lineage,
            settingsStore = MemoryLayerSettingsStore(MapSettings()),
            engineProvider = object : MemoryEngineProvider {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            nowEpochMillis = { 100L },
        )
        val operation = "github-action:ci.yml"
        assertIs<HabitInvocation.Result.Refused>(controller.proposeHabitTask("w3", operation, "t"))

        controller.updateSettings { it.copy(proceduralTraceCaptureEnabled = true, consolidationPaused = true) }
        listOf("w1", "w2", "w3").forEachIndexed { index, run ->
            controller.procedureTraceSink.record(
                ProcedureTrace(
                    workflowId = run, projectId = "project", taskRunId = "tr-$run", taskDefinitionId = "build", attempt = 1,
                    phase = ProcedureTracePhase.Reconcile, executorKind = "GitHubAction",
                    outcome = ProcedureTraceOutcome.Completed, recordedAtEpochMillis = 10L + index, operation = operation,
                ),
            )
        }
        val task = proposed(controller.proposeHabitTask("w3", operation, "t", ref = "main"))
        assertEquals(TaskExecutor.GitHubAction(operation.substringAfter(':'), ref = "main"), task.executor)
        assertIs<HabitInvocation.Result.Refused>(controller.proposeHabitTask("w3", "deployment:production", "t"))
        assertIs<HabitInvocation.Result.Refused>(controller.proposeHabitTask("w3", operation, " "))
    }
}
