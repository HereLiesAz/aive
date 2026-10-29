package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BreedsTest {
    private val catalog = BreedCatalog(
        listOf(
            Breed("a", "Thalassa 1", "Thalassa", "Common", starter = true, mutation = 2, pairsWith = listOf("b")),
            Breed("b", "Thalassa 2", "Thalassa", "Common"),
            Breed("s", "Glacia 3", "Glacia", "Rare", source = "store"),
            Breed("m", "Viridia 4", "Viridia", "Mythic", source = "breed", lottery = "mythic"),
        ),
    )

    private fun run(id: String, vararg roles: String, status: WorkflowRunStatus = WorkflowRunStatus.Completed) = WorkflowRun(
        id = WorkflowRunId(id),
        projectId = ProjectId("p"),
        workflowDefinitionId = WorkflowDefinitionId("w"),
        objective = "o",
        status = status,
        taskRuns = roles.mapIndexed { i, role ->
            TaskDefinitionId("t$i") to TaskRun(TaskRunId("r$i"), TaskDefinitionId("t$i"), TaskRunStatus.Completed, assignedRoleId = RoleDefinitionId(role))
        }.toMap(),
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )

    @Test
    fun programmaticWorkOnlyConnects() {
        // A script's task run, though its role is "b": the neuron did it, so it is no parent and no count.
        val scripted = run("r-script", "a", "b").let { r ->
            val (key, task) = r.taskRuns.entries.last()
            r.copy(taskRuns = r.taskRuns + (key to task.copy(executor = com.hereliesaz.geministrator.domain.TaskExecutor.TestRunner())))
        }
        val stable = Stable(starter = "a")
        assertEquals(listOf("a"), stable.contributorsOf(scripted))
        val onlyScripts = scripted.copy(taskRuns = scripted.taskRuns.mapValues { (_, t) -> t.copy(executor = com.hereliesaz.geministrator.domain.TaskExecutor.TestRunner()) })
        assertEquals(emptyList(), stable.contributorsOf(onlyScripts))
    }

    @Test
    fun programmaticWorkCountsButIsNeverAParent() {
        val testRunner = com.hereliesaz.geministrator.domain.TaskExecutor.TestRunner()
        // Role "b"'s task was a test run: the neuron did it, so "b" is no parent.
        val mixed = run("r-mixed", "a", "b").let { r ->
            val (key, task) = r.taskRuns.entries.last()
            r.copy(taskRuns = r.taskRuns + (key to task.copy(executor = testRunner)))
        }
        val stable = Stable(starter = "a")
        assertEquals(listOf("a"), stable.contributorsOf(mixed))
        // A run of programmatic work alone still counts toward the next generation.
        val scriptsOnly = mixed.copy(id = WorkflowRunId("r-scripts"), taskRuns = mixed.taskRuns.mapValues { (_, t) -> t.copy(executor = testRunner) })
        assertEquals(emptyList(), stable.contributorsOf(scriptsOnly))
        val counted = Stable(starter = "a", need = 3).onRunCleared(scriptsOnly, stable.contributorsOf(scriptsOnly), catalog)
        assertEquals(1, counted.progress)
    }

    @Test
    fun fibonacciPace() = assertEquals(listOf(1, 1, 2, 3, 5, 8), (1..6).map(::fibonacci))

    @Test
    fun theStarterIsUnlimitedAndOthersAreCounted() {
        val stable = Stable(starter = "a").add("b")
        assertNull(stable.available("a"))
        val one = stable.assign(RoleDefinitionId("x"), "b")
        assertEquals("b", one.creatureOf(RoleDefinitionId("x")))
        assertEquals(0, one.available("b"))
        // The only one is already living in x.
        val two = one.assign(RoleDefinitionId("y"), "b")
        assertEquals("a", two.creatureOf(RoleDefinitionId("y")))
    }

    @Test
    fun aRunCountsOnceAndHatchesWhenThePaceIsMet() {
        val stable = Stable(starter = "a")
        val first = stable.onRunCleared(run("r1", "x"), listOf("a"), catalog, Random(1))
        assertEquals(2, first.generation)
        assertNotNull(first.lastHatching)
        assertEquals(first, first.onRunCleared(run("r1", "x"), listOf("a"), catalog, Random(1)))
        assertTrue(first.need >= 1)
    }

    @Test
    fun aFailedRunCountsForNothing() {
        val stable = Stable(starter = "a")
        assertEquals(stable, stable.onRunCleared(run("r1", "x", status = WorkflowRunStatus.Failed), listOf("a"), catalog))
    }

    @Test
    fun aMixedPairHatchesAOneOfAKind() {
        val stable = Stable(starter = "a").add("b")
        val next = stable.onRunCleared(run("r1", "x", "y"), listOf("a", "b"), catalog, Random(3))
        val child = assertNotNull(next.lastHatching?.child)
        val bred = next.bred.firstOrNull { it.id == child }
        if (bred != null) assertEquals(setOf("a", "b"), setOf(bred.body, bred.tendrils, bred.tail) + setOf("a", "b"))
    }

    @Test
    fun storeOnlyBreedsNeverPassThemselvesOn() {
        val stable = Stable(starter = "a").add("s")
        repeat(20) { seed ->
            val next = stable.onRunCleared(run("r$seed", "x", "y"), listOf("s", "a"), catalog, Random(seed))
            next.bred.forEach { assertTrue("s" !in listOf(it.body, it.tendrils, it.tail)) }
            assertTrue((next.owned["s"] ?: 0) <= 1)
        }
    }

    @Test
    fun theSwingIsTheParentsMutationAndNeverBelowOne() {
        val needs = (0 until 200).map { seed ->
            Stable(starter = "a", generation = 4, progress = 2, need = 3)
                .onRunCleared(run("r$seed", "x"), listOf("a"), catalog, Random(seed)).need
        }.toSet()
        // Generation 5 is Fib(5) = 5, swung by mutation 2 either way.
        assertEquals(setOf(3, 7), needs)
    }

    @Test
    fun theShippedCatalogLoads() = runBlocking {
        val shipped = BreedCatalog.load()
        assertTrue(shipped.starters.isNotEmpty())
        assertTrue(shipped.breeds.all { it.mutation > 0 })
        assertEquals(2, shipped.breeds.count { it.lottery == "mythic" })
    }
}
