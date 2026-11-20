package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.workflow.WorkflowGraphValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SwarmQueenTest {
    private fun task(id: String, role: com.hereliesaz.geministrator.domain.RoleDefinition, vararg deps: String) = TaskDefinition(
        id = TaskDefinitionId(id), name = id, objective = id, roleId = role.id,
        dependsOn = deps.map(::TaskDefinitionId).toSet(), executor = TaskExecutor.RoleAgent(role.id),
    )

    private fun swarm(vararg tasks: TaskDefinition) = WorkflowDefinition(WorkflowDefinitionId("s"), "s", tasks = tasks.toList())

    @Test
    fun aSwarmWithoutAQueenGetsOneUnwired() {
        val before = swarm(task("plan", BuiltInRoles.Architect), task("build", BuiltInRoles.ImplementationEngineer, "plan"))
        assertFalse(hasQueen(before))
        val after = ensureQueen(before)
        assertTrue(hasQueen(after))
        assertEquals(before.tasks, after.tasks.take(2))
        val queen = after.tasks.last()
        assertEquals(BuiltInRoles.Orchestrator.id, queen.roleId)
        assertTrue(queen.dependsOn.isEmpty())
        assertTrue(WorkflowGraphValidator.validate(after).isEmpty())
    }

    @Test
    fun aSwarmThatHasHerIsReturnedAsItIs() {
        val has = swarm(task("queen", BuiltInRoles.Orchestrator), task("build", BuiltInRoles.ImplementationEngineer))
        assertSame(has, ensureQueen(has))
    }

    @Test
    fun anEmptySwarmGetsHerAndIsStillValid() {
        val after = ensureQueen(swarm())
        assertEquals(1, after.tasks.size)
        assertTrue(WorkflowGraphValidator.validate(after).isEmpty())
    }

    @Test
    fun herIdDoesNotCollideWithATaskAlreadyNamedLikeIt() {
        val after = ensureQueen(swarm(task("orchestrator", BuiltInRoles.Architect)))
        assertEquals(listOf("orchestrator", "orchestrator-2"), after.tasks.map { it.id.value })
    }

    @Test
    fun ensuringTwiceAddsHerOnce() {
        val once = ensureQueen(swarm(task("build", BuiltInRoles.ImplementationEngineer)))
        assertSame(once, ensureQueen(once))
    }

    @Test
    fun aQueenWhoRunsAsCodeRatherThanAModelIsStillTheQueen() {
        val coded = TaskDefinition(
            id = TaskDefinitionId("queen"), name = "queen", objective = "queen", roleId = BuiltInRoles.Orchestrator.id,
            executor = TaskExecutor.TestRunner(),
        )
        val has = swarm(coded, task("build", BuiltInRoles.ImplementationEngineer))
        assertTrue(hasQueen(has))
        assertSame(has, ensureQueen(has))
    }
}
