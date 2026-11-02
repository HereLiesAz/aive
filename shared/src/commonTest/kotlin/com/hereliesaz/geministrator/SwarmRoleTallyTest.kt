package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwarmRoleTallyTest {
    private val engineer = BuiltInRoles.ImplementationEngineer
    private val qa = BuiltInRoles.QaEngineer
    private val reviewer = BuiltInRoles.CodeReviewer

    private fun task(id: String, roleId: RoleDefinitionId?, executor: TaskExecutor? = null) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = id,
        objective = "Do $id",
        roleId = roleId,
        executor = executor,
    )

    private fun hive(id: String, vararg tasks: TaskDefinition) =
        WorkflowDefinition(id = WorkflowDefinitionId(id), name = id, tasks = tasks.toList())

    @Test
    fun aHiveCountsOnceHoweverManyOfItsTasksTheRoleHolds() {
        val counts = roleHiveCounts(
            listOf(
                hive("a", task("build", engineer.id), task("build-again", engineer.id), task("check", qa.id)),
                hive("b", task("build", engineer.id)),
            ),
        )
        assertEquals(2, counts[engineer.id])
        assertEquals(1, counts[qa.id])
    }

    @Test
    fun aRoleInNoHiveIsAbsent() {
        assertNull(roleHiveCounts(listOf(hive("a", task("build", engineer.id))))[reviewer.id])
        assertTrue(roleHiveCounts(emptyList()).isEmpty())
    }

    @Test
    fun anAgentExecutorCountsForItsRole() {
        val counts = roleHiveCounts(
            listOf(hive("a", task("review", roleId = null, executor = TaskExecutor.RoleAgent(reviewer.id)))),
        )
        assertEquals(1, counts[reviewer.id])
    }

    @Test
    fun theSameHiveTwiceCountsOnce() {
        val a = hive("a", task("build", engineer.id))
        assertEquals(1, roleHiveCounts(listOf(a, a))[engineer.id])
    }

    @Test
    fun reviewersAndProducersSplitTheSwarm() {
        assertTrue(SwarmRoleFilter.All.admits(engineer))
        assertTrue(SwarmRoleFilter.All.admits(qa))
        assertTrue(SwarmRoleFilter.Producers.admits(engineer))
        assertFalse(SwarmRoleFilter.Reviewers.admits(engineer))
        assertTrue(SwarmRoleFilter.Reviewers.admits(qa))
        assertTrue(SwarmRoleFilter.Reviewers.admits(reviewer))
        assertFalse(SwarmRoleFilter.Producers.admits(reviewer))
    }
}
