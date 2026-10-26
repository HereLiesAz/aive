package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TerrariumCircuitTest {
    private fun task(id: String, vararg deps: String) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = id,
        objective = id,
        roleId = BuiltInRoles.ImplementationEngineer.id,
        dependsOn = deps.map(::TaskDefinitionId).toSet(),
        executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
    )

    @Test
    fun aGroupFoldsIntoOneCircuitCreatureWiredLikeTheGroup() {
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("w"),
            name = "w",
            tasks = listOf(task("plan"), task("build", "plan"), task("test", "build"), task("ship", "test")),
        )
        val fold = assertIs<TerrariumFoldResult.Folded>(
            foldTerrariumCircuit(definition, setOf(TaskDefinitionId("build"), TaskDefinitionId("test"))),
        )
        val circuit = fold.parent.tasks.single { it.executor is TaskExecutor.NestedWorkflow }
        assertEquals(setOf(TaskDefinitionId("plan")), circuit.dependsOn)
        assertEquals(setOf(circuit.id), fold.parent.tasks.single { it.id.value == "ship" }.dependsOn)
        assertEquals(listOf("build", "test"), fold.circuit.tasks.map { it.id.value })
        assertEquals(emptySet(), fold.circuit.tasks.first().dependsOn)
        assertEquals(setOf(TaskDefinitionId("build")), fold.circuit.tasks.last().dependsOn)
    }

    @Test
    fun oneCreatureIsNotACircuit() {
        val definition = WorkflowDefinition(id = WorkflowDefinitionId("w"), name = "w", tasks = listOf(task("a")))
        assertIs<TerrariumFoldResult.Rejected>(foldTerrariumCircuit(definition, setOf(TaskDefinitionId("a"))))
    }
}
