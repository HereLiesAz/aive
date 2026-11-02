package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.RoleExecutionSource
import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.workflow.WorkflowGraphValidator
import com.hereliesaz.geministrator.workflow.isMutation
import com.hereliesaz.geministrator.workflow.withoutPlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TerrariumAddCreatureTest {
    private val engineer = BuiltInRoles.ImplementationEngineer

    private fun task(id: String, vararg deps: String) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = id,
        objective = id,
        roleId = engineer.id,
        dependsOn = deps.map(::TaskDefinitionId).toSet(),
        executor = TaskExecutor.RoleAgent(engineer.id),
    )

    private val hive = WorkflowDefinition(
        id = WorkflowDefinitionId("w"),
        name = "w",
        tasks = listOf(task("plan"), task("build", "plan")),
    )

    @Test
    fun aRoleJoinsUnwiredAndNothingElseChanges() {
        val added = assertIs<TerrariumDependencyEditResult.Applied>(
            addTerrariumCreature(hive, TerrariumNewcomer.FromRole(engineer)),
        ).definition
        assertEquals(hive.tasks, added.tasks.dropLast(1))
        val newcomer = added.tasks.last()
        assertEquals(engineer.id, newcomer.roleId)
        assertEquals(TaskExecutor.RoleAgent(engineer.id), newcomer.executor)
        assertEquals(emptySet(), newcomer.dependsOn)
        assertEquals(TaskCondition.Always, newcomer.condition)
        assertTrue(added.tasks.none { newcomer.id in it.dependsOn })
        assertEquals(emptyList(), WorkflowGraphValidator.validate(added))
    }

    @Test
    fun aSecondOfTheSameRoleGetsItsOwnTask() {
        val once = assertIs<TerrariumDependencyEditResult.Applied>(addTerrariumCreature(hive, TerrariumNewcomer.FromRole(engineer))).definition
        val twice = assertIs<TerrariumDependencyEditResult.Applied>(addTerrariumCreature(once, TerrariumNewcomer.FromRole(engineer))).definition
        assertEquals(
            listOf(engineer.id.value, "${engineer.id.value}-2"),
            twice.tasks.drop(2).map { it.id.value },
        )
    }

    @Test
    fun aRoleThatRunsAScriptJoinsAsItsScript() {
        val scripted = RoleDefinition(
            id = RoleDefinitionId("linter"),
            name = "Linter",
            description = "Lints the code.",
            instructions = "",
            executionSource = RoleExecutionSource.Script(
                com.hereliesaz.geministrator.domain.ScriptLanguage.Python,
                "print('ok')",
                com.hereliesaz.geministrator.domain.ScriptRunner.LocalSandbox,
            ),
        )
        val added = assertIs<TerrariumDependencyEditResult.Applied>(
            addTerrariumCreature(hive, TerrariumNewcomer.FromRole(scripted)),
        ).definition
        assertIs<TaskExecutor.Script>(added.tasks.last().executor)
        assertEquals("Lints the code.", added.tasks.last().objective)
    }

    @Test
    fun aRoleSwitchedOffCannotJoinAndIsNotOffered() {
        val off = engineer.copy(enabled = false)
        assertIs<TerrariumDependencyEditResult.Rejected>(addTerrariumCreature(hive, TerrariumNewcomer.FromRole(off)))
        assertEquals(listOf(BuiltInRoles.Researcher), terrariumAddableRoles(listOf(off, BuiltInRoles.Researcher)))
    }

    @Test
    fun everyStepJoinsUnwiredAndValid() {
        TerrariumStep.entries.forEach { step ->
            val added = assertIs<TerrariumDependencyEditResult.Applied>(
                addTerrariumCreature(hive, TerrariumNewcomer.Step(step)),
            ).definition
            val newcomer = added.tasks.last()
            assertEquals(step.idStem, newcomer.id.value)
            assertEquals(null, newcomer.roleId)
            assertEquals(step.executor, newcomer.executor)
            assertEquals(emptySet(), newcomer.dependsOn)
            assertEquals(emptyList(), WorkflowGraphValidator.validate(added))
        }
    }

    @Test
    fun noStepIsARepositoryChange() {
        // Invariant 4: alone and unwired, a repository change would have no approval ahead of it.
        TerrariumStep.entries.forEach { step ->
            val operation = step.executor.withoutPlacement() as? TaskExecutor.RepositoryOperation
            assertFalse(operation?.isMutation() == true, "${step.name} must not change the repository")
        }
    }

    @Test
    fun anAddIsOnTheTideAndUndoesExactly() {
        val added = assertIs<TerrariumDependencyEditResult.Applied>(
            addTerrariumCreature(hive, TerrariumNewcomer.Step(TerrariumStep.Tests)),
        ).definition
        val tide = TerrariumTide(present = hive).record(added)
        assertTrue(tide.canUndo)
        assertEquals(hive, tide.undo().present)
        assertEquals(added, tide.undo().redo().present)
    }
}
