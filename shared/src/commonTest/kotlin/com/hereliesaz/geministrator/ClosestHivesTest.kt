package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClosestHivesTest {
    private fun hive(id: String, name: String, vararg steps: Pair<String, String>, description: String? = null) = WorkflowDefinition(
        id = WorkflowDefinitionId(id),
        name = name,
        description = description,
        tasks = steps.map { (step, objective) ->
            TaskDefinition(
                id = TaskDefinitionId("$id-$step"), name = step, objective = objective,
                roleId = BuiltInRoles.QaEngineer.id, executor = TaskExecutor.RoleAgent(BuiltInRoles.QaEngineer.id),
            )
        },
    )

    private val release = hive("release", "Ship a release", "Build" to "Build the app for every platform", "Notes" to "Write the release notes")
    private val audit = hive("audit", "Audit the memory layer", "Plan" to "Plan an audit of the memory layer", "Review" to "Review the audit plan")
    private val empty = WorkflowDefinition(id = WorkflowDefinitionId("none"), name = "Audit everything", tasks = emptyList())

    @Test
    fun theHiveThatSpeaksOfMostOfTheDescriptionIsFirstAndNamesTheWordsThatMatched() {
        val found = ClosestHives.of("Audit the memory layer and plan improvements", listOf(release, audit, empty))

        assertEquals(listOf("audit"), found.map { it.definition.id.value })
        assertEquals(listOf("audit", "memory", "layer", "plan"), found.single().matched)
    }

    @Test
    fun somethingUnrelatedFindsNothingAndSoDoesABlankOrAHiveWithNoCreatures() {
        assertEquals(emptyList(), ClosestHives.of("Paint a mural", listOf(release, audit)))
        assertEquals(emptyList(), ClosestHives.of("   ", listOf(release, audit)))
        assertEquals(emptyList(), ClosestHives.of("Audit everything", listOf(empty)))
    }

    @Test
    fun pluralsAndEndingsAreFoldedAndOneSharedWordIsNotEnoughForALongDescription() {
        assertTrue(ClosestHives.of("releases building notes", listOf(release)).isNotEmpty(), "release, build and note all match")
        assertEquals(emptyList(), ClosestHives.of("write a long poem about the sea shore at dawn", listOf(release)), "one word (write) is not a match")
    }

    @Test
    fun theOrderIsStableAndLimited() {
        val a = hive("a", "Alpha memory audit", "Plan" to "memory audit")
        val b = hive("b", "Beta memory audit", "Plan" to "memory audit")
        val found = ClosestHives.of("memory audit", listOf(b, a, audit), limit = 2)
        assertEquals(2, found.size)
        assertEquals(found, ClosestHives.of("memory audit", listOf(audit, a, b), limit = 2), "the input order does not matter")
    }
}
