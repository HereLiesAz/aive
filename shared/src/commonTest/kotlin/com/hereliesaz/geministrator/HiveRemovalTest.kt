package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.azphalt.AuthoredWorkflowStore
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun task(id: String, vararg deps: String, condition: TaskCondition = TaskCondition.Always) = TaskDefinition(
    id = TaskDefinitionId(id),
    name = id,
    objective = "Do $id",
    roleId = BuiltInRoles.ImplementationEngineer.id,
    dependsOn = deps.map(::TaskDefinitionId).toSet(),
    condition = condition,
    executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
)

private fun hive(id: String, vararg tasks: TaskDefinition) =
    WorkflowDefinition(id = WorkflowDefinitionId(id), name = id, tasks = tasks.toList())

class RemoveCreatureTest {
    // plan -> build -> ship, plan -> docs, and recover runs when build fails.
    private val definition = hive(
        "h",
        task("plan"),
        task("build", "plan"),
        task("docs", "plan"),
        task("ship", "build", "docs"),
        task("recover", "build", condition = TaskCondition.OnFailure(TaskDefinitionId("build"))),
    )

    private fun applied(result: TerrariumDependencyEditResult) =
        assertIs<TerrariumDependencyEditResult.Applied>(result).definition

    @Test
    fun removingACreatureDropsItsTaskAndEveryStrandTouchingIt() {
        val after = applied(removeTerrariumCreatures(definition, setOf(TaskDefinitionId("build"))))
        assertEquals(listOf("plan", "docs", "ship", "recover"), after.tasks.map { it.id.value })
        // Strands out of it are gone from what it fed; ids stay consistent (the validator passes).
        assertEquals(setOf(TaskDefinitionId("docs")), after.tasks.first { it.id.value == "ship" }.dependsOn)
        assertTrue(after.tasks.first { it.id.value == "recover" }.dependsOn.isEmpty())
        // The knot that named it reverts to the plain one.
        assertEquals(TaskCondition.Always, after.tasks.first { it.id.value == "recover" }.condition)
        // Strands that did not touch it are untouched.
        assertEquals(setOf(TaskDefinitionId("plan")), after.tasks.first { it.id.value == "docs" }.dependsOn)
        assertTrue(com.hereliesaz.geministrator.workflow.WorkflowGraphValidator.validate(after).isEmpty())
    }

    @Test
    fun strandsThroughTheRemovedCreatureAreNotBridged() {
        val after = applied(removeTerrariumCreatures(definition, setOf(TaskDefinitionId("build"))))
        // ship was fed by build, which was fed by plan: ship is NOT now fed by plan.
        assertTrue(TaskDefinitionId("plan") !in after.tasks.first { it.id.value == "ship" }.dependsOn)
    }

    @Test
    fun oneUndoStepRestoresExactlyTheCreatureAndItsStrands() {
        val tide = TerrariumTide(present = definition)
        val removed = applied(removeTerrariumCreatures(definition, setOf(TaskDefinitionId("build"))))
        val afterRemove = tide.record(removed)
        assertEquals(1, afterRemove.past.size)
        assertEquals(definition, afterRemove.undo().present)
        assertEquals(removed, afterRemove.undo().redo().present)
    }

    @Test
    fun removingTheLastCreatureLeavesAnEmptyHive() {
        val single = hive("one", task("only"))
        val after = applied(removeTerrariumCreatures(single, setOf(TaskDefinitionId("only"))))
        assertTrue(after.tasks.isEmpty())
        assertTrue(opensAsNewHive(after))
    }

    @Test
    fun aClusterIsRemovedInOneEdit() {
        val after = applied(removeTerrariumCreatures(definition, setOf(TaskDefinitionId("build"), TaskDefinitionId("docs"))))
        assertEquals(listOf("plan", "ship", "recover"), after.tasks.map { it.id.value })
        assertTrue(after.tasks.first { it.id.value == "ship" }.dependsOn.isEmpty())
        assertEquals(definition, TerrariumTide(present = definition).record(after).undo().present)
    }

    @Test
    fun unknownOrEmptySelectionsChangeNothing() {
        assertIs<TerrariumDependencyEditResult.Rejected>(removeTerrariumCreatures(definition, setOf(TaskDefinitionId("nope"))))
        assertEquals(TerrariumDependencyEditResult.NoChange, removeTerrariumCreatures(definition, emptySet()))
    }

    @Test
    fun removingTheApprovalAheadOfARepositoryChangeIsRefused() {
        val gated = hive(
            "g",
            TaskDefinition(
                id = TaskDefinitionId("approve"), name = "approve", objective = "Approve", roleId = null,
                executor = TaskExecutor.HumanApproval(),
            ),
            TaskDefinition(
                id = TaskDefinitionId("push"), name = "push", objective = "Push", roleId = null,
                dependsOn = setOf(TaskDefinitionId("approve")),
                executor = TaskExecutor.RepositoryOperation("push"),
            ),
        )
        assertTrue(com.hereliesaz.geministrator.workflow.WorkflowGraphValidator.validate(gated).isEmpty())
        assertIs<TerrariumDependencyEditResult.Rejected>(removeTerrariumCreatures(gated, setOf(TaskDefinitionId("approve"))))
    }

    @Test
    fun removeIsBlockedOnlyWhileARunIsLive() {
        val resting = restingWorkflowRun(definition)
        assertEquals(false, terrariumRemoveBlocked(resting))
        assertEquals(true, terrariumRemoveBlocked(resting.copy(status = WorkflowRunStatus.Running)))
        assertEquals(true, terrariumRemoveBlocked(resting.copy(status = WorkflowRunStatus.AwaitingHuman)))
        assertEquals(false, terrariumRemoveBlocked(resting.copy(status = WorkflowRunStatus.Completed)))
    }
}

class DeleteHiveTest {
    private val target = hive("target", task("a"))
    private val other = hive("other", task("b"))

    private fun run(def: WorkflowDefinition, status: WorkflowRunStatus) =
        restingWorkflowRun(def).copy(id = WorkflowRunId("run-${def.id.value}-$status"), status = status)

    private fun verdict(
        runs: List<com.hereliesaz.geministrator.domain.WorkflowRun> = emptyList(),
        defs: List<WorkflowDefinition> = listOf(target, other),
        packages: Set<String> = emptySet(),
    ) = hiveDeleteVerdict(target.id, defs, runs, packages)

    @Test
    fun aHiveThatNeverRanMayBeDeleted() = assertEquals(HiveDeleteVerdict.Allowed, verdict())

    @Test
    fun refusesWhileARunIsLive() {
        assertEquals(
            HiveDeleteVerdict.Refused(HiveDeleteRefusal.LiveRun),
            verdict(listOf(run(target, WorkflowRunStatus.Running))),
        )
        assertEquals(
            HiveDeleteVerdict.Refused(HiveDeleteRefusal.LiveRun),
            verdict(listOf(run(target, WorkflowRunStatus.AwaitingHuman), run(target, WorkflowRunStatus.Completed))),
        )
    }

    @Test
    fun refusesWhilePastRunsStillNeedTheHive() {
        assertEquals(
            HiveDeleteVerdict.Refused(HiveDeleteRefusal.HasRuns),
            verdict(listOf(run(target, WorkflowRunStatus.Completed))),
        )
    }

    @Test
    fun anotherHivesRunsDoNotBlockIt() =
        assertEquals(HiveDeleteVerdict.Allowed, verdict(listOf(run(other, WorkflowRunStatus.Running))))

    @Test
    fun refusesAHiveUsedAsACircuitOrFromAPackageOrUnknown() {
        val parent = hive(
            "parent",
            TaskDefinition(
                id = TaskDefinitionId("c"), name = "c", objective = "c", roleId = null,
                executor = TaskExecutor.NestedWorkflow(target.id),
            ),
        )
        assertEquals(HiveDeleteVerdict.Refused(HiveDeleteRefusal.UsedAsCircuit), verdict(defs = listOf(target, parent)))
        assertEquals(HiveDeleteVerdict.Refused(HiveDeleteRefusal.FromPackage), verdict(packages = setOf("target")))
        assertEquals(HiveDeleteVerdict.Refused(HiveDeleteRefusal.Unknown), verdict(defs = listOf(other)))
    }

    private class Authored(ids: Set<WorkflowDefinitionId>) : AuthoredWorkflowStore {
        val ids = ids.toMutableSet()
        override suspend fun all() = ids.toSet()
        override suspend fun add(id: WorkflowDefinitionId) { ids += id }
        override suspend fun remove(id: WorkflowDefinitionId) { ids -= id }
    }

    private suspend fun hostWith(
        memory: InMemoryWorkflowPersistence,
        authored: Authored,
    ): WorkflowLibraryHost {
        memory.definitions.put(target)
        memory.definitions.put(other)
        return WorkflowLibraryHost(
            persistence = memory,
            storeService = null,
            authoredStore = authored,
            launchWorkflow = { _, _, _, _ -> },
            onPackagesChanged = {},
        )
    }

    @Test
    fun deletingRemovesOnlyTheTargetHive() = runTest {
        val memory = InMemoryWorkflowPersistence()
        val authored = Authored(setOf(target.id, other.id))
        val host = hostWith(memory, authored)
        val project = Project(ProjectId("p"), "p", createdAtEpochMillis = 0, updatedAtEpochMillis = 0)
        memory.projects.put(project)
        val otherRun = run(other, WorkflowRunStatus.Completed).copy(projectId = project.id)
        memory.runs.put(otherRun)

        assertEquals(HiveDeleteVerdict.Allowed, host.deleteHive(target.id))

        assertNull(memory.definitions.get(target.id))
        assertNotNull(memory.definitions.get(other.id))
        assertEquals(setOf(other.id), authored.all())
        assertEquals(otherRun, memory.runs.get(otherRun.id))
        assertEquals(listOf(project), memory.projects.all())
    }

    @Test
    fun deletingWhileLiveDeletesNothing() = runTest {
        val memory = InMemoryWorkflowPersistence()
        val authored = Authored(setOf(target.id, other.id))
        val host = hostWith(memory, authored)
        val project = Project(ProjectId("p"), "p", createdAtEpochMillis = 0, updatedAtEpochMillis = 0)
        memory.projects.put(project)
        memory.runs.put(run(target, WorkflowRunStatus.Running).copy(projectId = project.id))

        assertEquals(HiveDeleteVerdict.Refused(HiveDeleteRefusal.LiveRun), host.deleteHive(target.id))

        assertNotNull(memory.definitions.get(target.id))
        assertEquals(setOf(target.id, other.id), authored.all())
    }
}
