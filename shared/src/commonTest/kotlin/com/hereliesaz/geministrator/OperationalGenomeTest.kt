package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RoleDefinitionId
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
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Operational traits are observed from the journal and ranked in thirds; synaptic capacity comes from the token allowance. */
class OperationalGenomeTest {
    private val engineer = BuiltInRoles.ImplementationEngineer
    private val qa = BuiltInRoles.QaEngineer
    private val writer = engineer.copy(id = RoleDefinitionId("writer"), name = "Technical Writer")

    /** A copy with exactly this allowance: no starter class or birth cap to widen it. */
    private fun sized(id: String, budget: Long?) =
        engineer.copy(id = RoleDefinitionId(id), tokenBudget = budget, budgetClass = null, geneticTokenCap = null)

    @Test
    fun thirdsRankAgainstTheRosterAndNeedTwoDistinctValues() {
        assertEquals(emptyMap(), thirds(mapOf("a" to 1.0, "b" to 1.0)))
        assertEquals(mapOf("a" to 0, "b" to 2), thirds(mapOf("a" to 1.0, "b" to 5.0)))
        val three = thirds(mapOf("cheap" to 1.0, "mid" to 2.0, "dear" to 3.0, "tied" to 2.0))
        assertEquals(mapOf("cheap" to 0, "mid" to 1, "dear" to 2, "tied" to 1), three)
    }

    @Test
    fun traitsAreObservedAndAnUnobservedOneIsNull() {
        val profiles = mapOf(
            engineer.id to RoleProfile(pricedTokens = 1_000, costUsd = 0.001, outputTokens = 500, timedMillis = 10_000,
                retriedTasks = 3, recoveredTasks = 3, succeededOn = setOf("a|m1", "a|m2", "b|m3")),
            qa.id to RoleProfile(pricedTokens = 1_000, costUsd = 0.05, outputTokens = 500, timedMillis = 100_000,
                retriedTasks = 3, recoveredTasks = 0, succeededOn = setOf("a|m1")),
            writer.id to RoleProfile(),
        )
        val traits = operationalTraits(profiles)
        val cheap = assertNotNull(traits[engineer.id])
        assertEquals(ModelTier.Dark, cheap.tier)
        assertEquals(SignalEfficiency.Fast, cheap.efficiency)
        assertEquals(Tolerance.Wide, cheap.tolerance)
        assertEquals(Resilience.Tough, cheap.resilience)
        val dear = assertNotNull(traits[qa.id])
        assertEquals(ModelTier.Bright, dear.tier)
        assertEquals(SignalEfficiency.Slow, dear.efficiency)
        assertEquals(Tolerance.Narrow, dear.tolerance)
        assertEquals(Resilience.Fragile, dear.resilience)
        assertEquals(OperationalTraits(null, null, null, null), traits[writer.id])
        // A single role has nothing to rank against: tier and efficiency stay unreported.
        assertNull(operationalTraits(mapOf(engineer.id to profiles.getValue(engineer.id)))[engineer.id]?.tier)
    }

    @Test
    fun resilienceBandsAreThirdsOfRecoveredRetries() {
        fun of(retried: Int, recovered: Int) = operationalTraits(mapOf(engineer.id to
            RoleProfile(retriedTasks = retried, recoveredTasks = recovered)))[engineer.id]?.resilience
        assertEquals(Resilience.Tough, of(3, 2))
        assertEquals(Resilience.Steady, of(3, 1))
        assertEquals(Resilience.Fragile, of(3, 0))
        assertNull(of(0, 0))
    }

    private val definition = WorkflowDefinition(WorkflowDefinitionId("op"), "Op", tasks = listOf(
        TaskDefinition(id = TaskDefinitionId("t"), name = "t", objective = "o", roleId = engineer.id,
            executor = TaskExecutor.RoleAgent(engineer.id))))

    private fun run(id: String, attempt: Int = 1, status: TaskRunStatus = TaskRunStatus.Completed) = WorkflowRun(
        id = WorkflowRunId(id), projectId = ProjectId("p"), workflowDefinitionId = definition.id, objective = "o",
        status = WorkflowRunStatus.Completed,
        taskRuns = mapOf(TaskDefinitionId("t") to TaskRun(id = TaskRunId("r-$id"), taskDefinitionId = TaskDefinitionId("t"),
            status = status, attempt = attempt, assignedRoleId = engineer.id, assignedProviderId = AgentProviderId("a"),
            providerRunId = ProviderRunId("s-$id"))),
        createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L, roleSnapshot = listOf(engineer), definitionSnapshot = definition,
    )

    private fun report(id: String, input: Long? = 600, output: Long? = 400, cost: Double? = 0.01, latency: Long? = 8_000,
        costUnknown: Boolean = false, avoided: Long = 0) =
        ProviderUsageRecorded(WorkflowRunId(id), TaskDefinitionId("t"), AgentProviderId("a"), ProviderRunId("s-$id"),
            inputTokens = input, outputTokens = output, costUsd = cost, latencyMillis = latency, costUnknown = costUnknown,
            tokensAvoided = avoided, model = "m1", modelConsistent = true, occurredAtEpochMillis = 2L)

    @Test
    fun historyCountsOnlyThisSessionsPricedTimedWorkAndIsIdempotent() {
        val first = OperationalHistory().advance(run("one"), listOf(report("one")))
        val p = first.profiles.getValue(engineer.id)
        assertEquals(1_000L, p.pricedTokens)
        assertEquals(0.01, p.costUsd, 1e-9)
        assertEquals(400L, p.outputTokens)
        assertEquals(8_000L, p.timedMillis)
        assertEquals(setOf("a|m1"), p.succeededOn)
        assertEquals(first, first.advance(run("one"), listOf(report("one"))))
        // Unpriced, unknown-cost, replayed and stale sessions add no cost or speed.
        val none = OperationalHistory().advance(run("two"), listOf(report("two", cost = null), report("two", costUnknown = true),
            report("two", avoided = 5), report("other")))
        assertEquals(0L, none.profiles.getValue(engineer.id).pricedTokens)
        // Speed needs no price: the unpriced and the unknown-cost sessions are timed, the replayed and stale are not.
        assertEquals(16_000L, none.profiles.getValue(engineer.id).timedMillis)
    }

    @Test
    fun aRetryCountsOnlyOnceTheTaskIsSettledAndRecoveryOnlyIfItCompleted() {
        val recovered = OperationalHistory().advance(run("a", attempt = 3), emptyList()).profiles.getValue(engineer.id)
        assertEquals(1, recovered.retriedTasks)
        assertEquals(1, recovered.recoveredTasks)
        val failed = OperationalHistory().advance(run("b", attempt = 2, status = TaskRunStatus.Failed), emptyList())
            .profiles.getValue(engineer.id)
        assertEquals(1, failed.retriedTasks)
        assertEquals(0, failed.recoveredTasks)
        val live = OperationalHistory().advance(run("c", attempt = 2, status = TaskRunStatus.Running), emptyList())
            .profiles.getValue(engineer.id)
        assertEquals(0, live.retriedTasks)
    }

    @Test
    fun capacityComesFromTheTokenAllowanceInThirds() {
        val small = sized("small", 1_000)
        val medium = sized("medium", 50_000)
        val large = sized("large", 900_000)
        val unlimited = sized("unlimited", null)
        val caps = synapseCapacities(listOf(small, medium, large, unlimited))
        assertEquals(2, caps[small.id])
        assertEquals(3, caps[medium.id])
        assertEquals(4, caps[large.id])
        assertEquals(4, caps[unlimited.id])
        // One finite allowance has nothing to rank against: it holds the middle.
        assertEquals(3, synapseCapacities(listOf(small, unlimited))[small.id])
    }

    @Test
    fun wiringACreaturePastItsCapacityCostsIntegrityAndNeverBlocks() {
        val small = sized("small", 1_000)
        val large = sized("large", 900_000)
        fun task(id: String, role: com.hereliesaz.geministrator.domain.RoleDefinition, vararg deps: String) = TaskDefinition(
            id = TaskDefinitionId(id), name = id, objective = "o", roleId = role.id,
            dependsOn = deps.map(::TaskDefinitionId).toSet(), executor = TaskExecutor.RoleAgent(role.id))
        // hub is read by three tasks and reads one: four partners against a capacity of 2.
        val d = WorkflowDefinition(WorkflowDefinitionId("hub"), "Hub", tasks = listOf(
            task("src", large), task("hub", small, "src"), task("a", large, "hub"), task("b", large, "hub"), task("c", large, "hub")))
        fun runOf(role: com.hereliesaz.geministrator.domain.RoleDefinition) = WorkflowRun(
            id = WorkflowRunId("hub-run"), projectId = ProjectId("p"), workflowDefinitionId = d.id, objective = "o",
            status = WorkflowRunStatus.Running,
            taskRuns = d.tasks.associate { t ->
                t.id to TaskRun(id = TaskRunId("r-${t.id.value}"), taskDefinitionId = t.id, status = TaskRunStatus.Running,
                    assignedRoleId = t.roleId)
            },
            createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L, roleSnapshot = listOf(small, large), definitionSnapshot = d)
        val needs = assertNotNull(creatureNeeds(TaskDefinitionId("hub"), d, runOf(small), listOf(small, large)))
        assertEquals(4, needs.synapses)
        assertEquals(2, needs.synapseCapacity)
        assertEquals(2, needs.synapseExcess)
        assertEquals(96, needs.integrity)
        val roomy = assertNotNull(creatureNeeds(TaskDefinitionId("a"), d, runOf(large), listOf(small, large)))
        assertEquals(1, roomy.synapses)
        assertEquals(4, roomy.synapseCapacity)
        assertEquals(0, roomy.synapseExcess)
        assertEquals(100, roomy.integrity)
    }
}
