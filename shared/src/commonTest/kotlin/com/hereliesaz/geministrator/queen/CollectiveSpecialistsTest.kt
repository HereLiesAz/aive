package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollectiveSpecialistsTest {
    private fun model(source: String, modelId: String = "model-$source"): QueenModel = object : QueenModel {
        override val uses = listOf(ModelUse(source, modelId, false))
        override suspend fun answer(prompt: String): String = error("Selection must not run inference")
    }

    @Test
    fun selectsRelevantDistinctRolesAndDifferentSourcesDespitePinnedFacilitator() {
        val first = RoleDefinition(RoleDefinitionId("database"), "Database indexing", "database indexing", "")
        val second = RoleDefinition(RoleDefinitionId("database-review"), "Database indexing", "database indexing", "")
        val disabled = first.copy(id = RoleDefinitionId("disabled"), enabled = false)
        val missing = first.copy(id = RoleDefinitionId("missing"), preferredProviderId = AgentProviderId("unlinked"))
        val models = mapOf("a" to model("a"), "b" to model("b"))
        val factory: (String?) -> QueenModel? = { pin ->
            if (pin == null) object : QueenModel {
                override val uses = models.values.flatMap { it.uses }
                override suspend fun answer(prompt: String): String = error("Must not call")
            } else models[pin]
        }
        val result = selectCollectiveSpecialists("database indexing",
            listOf(BuiltInRoles.Orchestrator, disabled, missing, second, first),
            models.getValue("a").uses, factory, emptyMap())
        assertEquals(listOf(first.id, second.id), result.map { it.first.id })
        assertEquals(setOf("a", "b"), result.flatMap { it.second.uses }.map { it.sourceId }.toSet())
    }

    @Test
    fun prefersDifferentConfiguredModelOverAnotherSourceWithSameModel() {
        val first = RoleDefinition(RoleDefinitionId("a"), "Indexing", "indexing", "")
        val second = first.copy(id = RoleDefinitionId("b"))
        val models = linkedMapOf("a" to model("a", "shared"), "b" to model("b", "shared"),
            "c" to model("c", "different"))
        val factory: (String?) -> QueenModel? = { pin ->
            if (pin == null) object : QueenModel {
                override val uses = models.values.flatMap { it.uses }
                override suspend fun answer(prompt: String): String = error("Must not call")
            } else models[pin]
        }
        val result = selectCollectiveSpecialists("indexing", listOf(first, second),
            models.getValue("a").uses, factory, emptyMap())
        assertEquals(listOf("a", "c"), result.map { it.second.uses.single().sourceId })
        val pinned = second.copy(preferredProviderId = AgentProviderId("b"))
        val pinnedResult = selectCollectiveSpecialists("indexing", listOf(first, pinned),
            models.getValue("a").uses, factory, emptyMap())
        assertEquals(listOf("a", "b"), pinnedResult.map { it.second.uses.single().sourceId })
    }

    @Test
    fun relevantExactInstanceGetsOneSlotWithoutDisplacingIndependentExploration() {
        val a = RoleDefinition(RoleDefinitionId("a"), "Indexing", "indexing", "")
        val b = a.copy(id = RoleDefinitionId("b"))
        val experienced = a.copy(id = RoleDefinitionId("z"))
        val pinned = experienced.copy(preferredProviderId = AgentProviderId("other"))
        val first = model("a")
        val other = model("other")
        val pointer = com.hereliesaz.geministrator.memory.CollectiveExpertiseMatch(
            experienced.id.value, specialistInstanceId(experienced, other.uses), 2)
        val fallback = mapOf(a.id.value to first, b.id.value to first, experienced.id.value to other)
        val roster = listOf(a, b, experienced)
        val chosen = selectCollectiveSpecialists("indexing", roster, first.uses, null, fallback, listOf(pointer))
        assertEquals(listOf(experienced.id, a.id), chosen.take(2).map { it.first.id })
        assertEquals("other", chosen.first().second.uses.single().sourceId)
        assertEquals(setOf(a.id, b.id, experienced.id), chosen.map { it.first.id }.toSet())
        val revised = experienced.copy(description = "indexing revised")
        val changed = selectCollectiveSpecialists("indexing", listOf(a, b, revised), first.uses, null, fallback, listOf(pointer))
        assertEquals(listOf(a.id, b.id), changed.take(2).map { it.first.id })
        val repinned = selectCollectiveSpecialists("indexing", listOf(a, b, pinned), first.uses, null, fallback, listOf(pointer))
        assertEquals(listOf(a.id, b.id), repinned.take(2).map { it.first.id })
    }

    @Test
    fun returningExactInstanceCanUseItsPreviousSourceButCannotOverridePin() {
        val role = RoleDefinition(RoleDefinitionId("a"), "Indexing", "indexing", "")
        val a = model("a")
        val b = model("b")
        val factory: (String?) -> QueenModel? = { pin ->
            when (pin) {
                "a" -> a
                "b" -> b
                else -> object : QueenModel {
                    override val uses = a.uses + b.uses
                    override suspend fun answer(prompt: String): String = error("No calls during selection")
                }
            }
        }
        val previous = specialistInstanceId(role, b.uses)
        val result = selectCollectiveSpecialists("indexing", listOf(role), a.uses, factory, emptyMap(),
            returningInstances = setOf(previous))
        assertEquals(b.uses, result.single().second.uses)
        val pinned = role.copy(preferredProviderId = AgentProviderId("a"))
        val changed = selectCollectiveSpecialists("indexing", listOf(pinned), a.uses, factory, emptyMap(),
            returningInstances = setOf(previous))
        assertEquals(a.uses, changed.single().second.uses)
    }

    @Test
    fun explicitPinNeverFallsBackToAnotherSource() {
        val role = BuiltInRoles.Architect.copy(preferredProviderId = AgentProviderId("pinned"))
        val result = selectCollectiveSpecialists("Architecture", listOf(role), model("other").uses,
            { model("other") }, emptyMap())
        assertTrue(result.isEmpty())
    }
}
