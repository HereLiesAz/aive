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
    fun explicitPinNeverFallsBackToAnotherSource() {
        val role = BuiltInRoles.Architect.copy(preferredProviderId = AgentProviderId("pinned"))
        val result = selectCollectiveSpecialists("Architecture", listOf(role), model("other").uses,
            { model("other") }, emptyMap())
        assertTrue(result.isEmpty())
    }
}
