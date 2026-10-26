package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TerrariumKnotTest {
    private fun task(id: String, vararg deps: String) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = id,
        objective = "Do $id",
        roleId = BuiltInRoles.ImplementationEngineer.id,
        dependsOn = deps.map(::TaskDefinitionId).toSet(),
        executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
    )
    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("k"),
        name = "k",
        tasks = listOf(task("build"), task("recover", "build")),
    )
    private val build = TaskDefinitionId("build")
    private val recover = TaskDefinitionId("recover")

    @Test
    fun aFailureKnotBecomesTheTasksCondition() {
        val edit = assertIs<TerrariumDependencyEditResult.Applied>(setTerrariumKnot(definition, recover, build, TerrariumKnot.OnFailure))
        assertEquals(TaskCondition.OnFailure(build), edit.definition.tasks.last().condition)
        assertEquals(TerrariumKnot.OnFailure, terrariumKnot(edit.definition, recover, build))
    }

    @Test
    fun knottingAStrandThatDoesNotExistIsRefused() {
        assertIs<TerrariumDependencyEditResult.Rejected>(setTerrariumKnot(definition, build, recover, TerrariumKnot.OnFailure))
    }

    @Test
    fun theDefaultKnotIsOnSuccessAndTyingItAgainIsNoChange() {
        assertEquals(TerrariumKnot.OnSuccess, terrariumKnot(definition, recover, build))
        assertEquals(TerrariumDependencyEditResult.NoChange, setTerrariumKnot(definition, recover, build, TerrariumKnot.OnSuccess))
    }
}

class TerrariumDetachAndBudTest {
    private fun task(id: String, vararg deps: String) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = id,
        objective = "Do $id",
        roleId = BuiltInRoles.ImplementationEngineer.id,
        dependsOn = deps.map(::TaskDefinitionId).toSet(),
        executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
    )
    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("d"),
        name = "d",
        tasks = listOf(task("a"), task("b", "a"), task("c", "b").copy(condition = TaskCondition.OnFailure(TaskDefinitionId("b")))),
    )

    @Test
    fun detachCutsEveryStrandInAndOutAndItsKnots() {
        val edit = assertIs<TerrariumDependencyEditResult.Applied>(detachTerrariumCreature(definition, TaskDefinitionId("b")))
        assertEquals(emptySet(), edit.definition.tasks[1].dependsOn)
        assertEquals(emptySet(), edit.definition.tasks[2].dependsOn)
        assertEquals(TaskCondition.Always, edit.definition.tasks[2].condition)
    }

    @Test
    fun aBudIsFedLikeItsSourceAndFeedsNothing() {
        val edit = assertIs<TerrariumDependencyEditResult.Applied>(budTerrariumCreature(definition, TaskDefinitionId("b")))
        val bud = edit.definition.tasks.last()
        assertEquals("b-2", bud.id.value)
        assertEquals(setOf(TaskDefinitionId("a")), bud.dependsOn)
        assertEquals(0, edit.definition.tasks.count { bud.id in it.dependsOn })
    }
}
