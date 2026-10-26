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
