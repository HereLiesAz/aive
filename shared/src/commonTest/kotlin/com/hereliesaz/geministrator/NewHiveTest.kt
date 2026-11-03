package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A new hive (mockup H6): opened empty it is new, its name is saved as typed, the tray adds creatures. */
class NewHiveTest {
    private val empty = WorkflowDefinition(id = WorkflowDefinitionId("hive-1"), name = "New hive", tasks = emptyList())

    @Test
    fun anEmptyHiveOpensAsNew() {
        assertTrue(opensAsNewHive(empty))
        val engineer = BuiltInRoles.ImplementationEngineer
        val populated = empty.copy(
            tasks = listOf(TaskDefinition(TaskDefinitionId("t"), "t", "t", engineer.id, executor = TaskExecutor.RoleAgent(engineer.id))),
        )
        assertFalse(opensAsNewHive(populated))
    }

    @Test
    fun renamingTrimsAndABlankNameKeepsTheOld() {
        assertEquals("Release train", renamedHive(empty, "  Release train ").name)
        assertEquals(empty.id, renamedHive(empty, "Release train").id)
        assertSame(empty, renamedHive(empty, "   "))
    }

    @Test
    fun theTrayBringsARoleInUnwired() {
        val edit = addTerrariumCreature(empty, TerrariumNewcomer.FromRole(BuiltInRoles.Architect))
        val applied = assertIs<TerrariumDependencyEditResult.Applied>(edit)
        val joined = applied.definition.tasks.single()
        assertEquals(BuiltInRoles.Architect.id, joined.roleId)
        assertTrue(joined.dependsOn.isEmpty())
        // The name the keeper gave survives the edit.
        assertEquals(empty.name, applied.definition.name)
    }
}
